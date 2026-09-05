package com.rokid.glassesbaredevsample.activities.pland

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rokid.glassesbaredevsample.camera.CameraFrameTransform
import com.rokid.glassesbaredevsample.camera.FrameJpegEncoder
import com.rokid.glassesbaredevsample.camera.createAnalysisUseCase
import com.rokid.glassesbaredevsample.camera.rememberCameraBound
import com.rokid.glassesbaredevsample.hand.PointerGesture
import com.rokid.glassesbaredevsample.input.BareKeyEvent
import com.rokid.glassesbaredevsample.input.RegisterBareKeyHandler
import com.rokid.glassesbaredevsample.input.rememberSubPageEnterDebounce
import com.rokid.glassesbaredevsample.ui.design.BareInfoBlock
import com.rokid.glassesbaredevsample.ui.design.BareKeyGuide
import com.rokid.glassesbaredevsample.ui.design.BareScreenLayout
import com.rokid.glassesbaredevsample.ui.handmouse.HandLandmarkOverlay
import com.rokid.glassesbaredevsample.ui.handmouse.LocalPointerCursor
import com.rokid.glassesbaredevsample.utils.BarePermissions
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun PlanDMouseScreen(onExit: () -> Unit, viewModel: PlanDMouseViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    var hasCamera by remember { mutableStateOf(BarePermissions.hasCamera(context)) }
    val ignoreDoubleClick = rememberSubPageEnterDebounce()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCamera = granted
        if (!granted) viewModel.onCameraError("未授予相机权限")
    }

    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PlanDFrameCapture")
        }
    }
    val frameEncoder = remember { FrameJpegEncoder() }
    val processEveryN = viewModel.cameraProcessEveryNFrames()
    val frameCounter = remember { AtomicInteger(0) }
    val analyzer = remember(viewModel, frameEncoder, processEveryN) {
        ImageAnalysis.Analyzer { image ->
            try {
                if (frameCounter.getAndIncrement() % processEveryN != 0) return@Analyzer

                val arrivedNs = System.nanoTime()
                val encodeStartedNs = System.nanoTime()
                viewModel.onFrameArrived(
                    nowNs = arrivedNs,
                    sourceTimestampNs = image.imageInfo.timestamp,
                    width = image.width,
                    height = image.height,
                    imageFormat = image.format,
                    rotationDegrees = CameraFrameTransform.normalizeRotationDegrees(
                        image.imageInfo.rotationDegrees,
                    ),
                )
                val encoded = frameEncoder.encode(image)
                val finishedNs = System.nanoTime()
                if (encoded != null) {
                    viewModel.onCameraFrame(
                        nowNs = finishedNs,
                        encodeDurationNs = finishedNs - encodeStartedNs,
                        jpeg = encoded.jpeg,
                        rotationDeg = encoded.rotationDegrees,
                        width = encoded.width,
                        height = encoded.height,
                        tMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                            image.imageInfo.timestamp,
                        ),
                    )
                }
            } catch (error: Exception) {
                viewModel.onAnalyzerError(error)
            } finally {
                image.close()
            }
        }
    }
    val analysisUseCase = remember(analysisExecutor, analyzer) {
        createAnalysisUseCase(analysisExecutor, analyzer)
    }

    DisposableEffect(Unit) {
        viewModel.onSceneEntered()
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
        onDispose {
            analysisUseCase.clearAnalyzer()
            analysisExecutor.shutdownNow()
            frameEncoder.close()
            viewModel.onSceneExited()
        }
    }

    rememberCameraBound(
        context = context,
        lifecycleOwner = lifecycleOwner,
        enabled = hasCamera && viewModel.shouldBindCamera(),
        onReady = viewModel::onCameraReady,
        onError = viewModel::onCameraError,
        onUnbind = viewModel::onCameraUnbound,
        useCases = { arrayOf(analysisUseCase) },
    )

    RegisterBareKeyHandler { event ->
        when (event) {
            BareKeyEvent.SwipeForward -> {
                viewModel.onTouchPadSwipe(forward = true)
                true
            }
            BareKeyEvent.SwipeBack -> {
                viewModel.onTouchPadSwipe(forward = false)
                true
            }
            BareKeyEvent.Click -> {
                viewModel.onTouchPadTap()
                true
            }
            BareKeyEvent.DoubleClick -> {
                if (ignoreDoubleClick()) return@RegisterBareKeyHandler true
                viewModel.onSceneExited()
                onExit()
                true
            }
            BareKeyEvent.LongPress -> {
                viewModel.onTouchPadLongPress()
                true
            }
            BareKeyEvent.LongPressRelease -> {
                viewModel.onTouchPadLongPressRelease()
                true
            }
            BareKeyEvent.TwoFingerSingleTap -> false
        }
    }

    val metrics = uiState.metrics
    val pointer = uiState.pointer
    val outputLabel = when {
        uiState.postureFreezeActive -> "手姿调整(按住)"
        uiState.outputEnabled -> "已启用"
        else -> "已暂停（离合）"
    }
    val gestureLabel = when (pointer.gesture) {
        PointerGesture.IDLE -> "IDLE"
        PointerGesture.TRACKING -> "MOVE"
        PointerGesture.PAUSED -> "PAUSED"
        PointerGesture.LOST -> "LOST"
        else -> pointer.gesture.name
    }
    val linkLabel = when {
        !uiState.linkConfigured -> "未配置 host（长按保存 / adb）"
        uiState.linkError != null ->
            "发送失败 ${uiState.linkEndpoint} seq=${uiState.linkSequence}"
        uiState.linkSequence > 0L ->
            "已发送 ${uiState.linkEndpoint} seq=${uiState.linkSequence}"
        else -> "已配置 ${uiState.linkEndpoint}"
    }
    val lines = buildList {
        add("Plan D · 电脑端 MediaPipe @30Hz")
        add("链路: $linkLabel")
        add("相机: ${uiState.cameraStatus}")
        add("帧流: ${uiState.detectorStatus}")
        add("输出: $outputLabel")
        add("手势: $gestureLabel")
        add(
            "左键=${if (pointer.leftPressed) "按下" else "抬起"} · " +
                "点击 ↓${uiState.pinchDownCount} ↑${uiState.pinchUpCount}",
        )
        add("灵敏度: ${formatMetric(uiState.linkGain.toDouble())} (固定满档)")
        if (uiState.precisionModeActive) add("模式: 精细 · 前滑切回")
        if (uiState.outputEnabled) {
            add(
                if (uiState.laptopHandPresent) "电脑识别: 手可见 (${uiState.landmarkCount}点)"
                else "电脑识别: 未检测到手",
            )
        }
        if (metrics.width > 0 && metrics.height > 0) {
            add("采集: ${metrics.width}×${metrics.height} · FPS ${formatMetric(metrics.analyzedFps)}")
        }
        uiState.linkPersistHint?.let { add("配置: $it") }
        uiState.linkError?.let { add("链路错误: $it") }
        uiState.lastDetectorError?.let { add("错误(${uiState.detectorErrors}): $it") }
            ?: metrics.lastError?.let { add("分析错误(${metrics.analyzerErrors}): $it") }
    }

    BareScreenLayout(
        title = "mouse_control_glasses",
        subtitle = when {
            !hasCamera -> "需要相机权限"
            !uiState.linkConfigured -> "无保存链路 · adb 或长按保存"
            uiState.precisionModeActive -> "精细模式 · 前滑切回"
            uiState.postureFreezeActive -> "调整手姿 · 光标居中 · 松手继续"
            !uiState.outputEnabled -> "离合关闭 · 单击启用"
            pointer.leftPressed && uiState.leftHoldActive -> "左键按住 · 单击释放"
            else -> "手→电脑 · 满灵敏度 2.0"
        },
        keyGuide = BareKeyGuide(
            click = when {
                !uiState.outputEnabled -> "启用离合"
                uiState.leftHoldActive -> "释放左键"
                uiState.outputEnabled -> "左键单击"
                else -> "启用离合"
            },
            doubleClick = "退出",
            longPress = when {
                uiState.postureFreezeActive -> "松手继续"
                uiState.outputEnabled -> "按住·调手姿"
                else -> "保存链路"
            },
            swipeForward = if (uiState.outputEnabled) "精细↔正常" else null,
            swipeBack = if (uiState.outputEnabled) "按住左键" else null,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.landmarks.isNotEmpty()) {
                HandLandmarkOverlay(
                    landmarks = uiState.landmarks,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            LocalPointerCursor(
                normX = uiState.cursorNormX,
                normY = uiState.cursorNormY,
                pressed = pointer.leftPressed,
                visible = true,
                modifier = Modifier.fillMaxSize(),
            )
            BareInfoBlock(
                label = "Plan D 鼠标",
                lines = lines,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart),
            )
        }
    }
}

private fun formatMetric(value: Double): String {
    if (!value.isFinite()) return "-"
    return String.format(Locale.US, "%.1f", value)
}
