package com.rokid.glassesbaredevsample.activities.handmouse

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
import com.rokid.glassesbaredevsample.camera.createAnalysisUseCase
import com.rokid.glassesbaredevsample.camera.rememberCameraBound
import com.rokid.glassesbaredevsample.hand.HandLandmarkerEngine
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
fun HandMouseScreen(onBack: () -> Unit, viewModel: HandMouseViewModel) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val usesCamera = viewModel.usesCameraForPointer()
    val isHybrid = viewModel.isHybridMode()
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
            Thread(runnable, "HandMouseAnalysis")
        }
    }
    val landmarkerEngine = remember { HandLandmarkerEngine() }
    val processEveryN = viewModel.cameraProcessEveryNFrames()
    val frameCounter = remember { AtomicInteger(0) }
    val analyzer = remember(viewModel, landmarkerEngine, processEveryN) {
        ImageAnalysis.Analyzer { image ->
            try {
                val processFrame = frameCounter.getAndIncrement() % processEveryN == 0
                if (!processFrame) return@Analyzer

                val arrivedNs = System.nanoTime()
                val analysisStartedNs = System.nanoTime()
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
                val result = landmarkerEngine.detect(image)
                val finishedNs = System.nanoTime()
                viewModel.onHandFrame(
                    nowNs = finishedNs,
                    analysisDurationNs = finishedNs - analysisStartedNs,
                    result = result,
                )
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

    DisposableEffect(usesCamera) {
        viewModel.onSceneEntered()
        val initFuture = if (usesCamera) {
            analysisExecutor.submit {
                val initError = landmarkerEngine.initialize(appContext)
                if (initError == null) {
                    viewModel.onDetectorReady()
                } else {
                    viewModel.onDetectorInitFailed(initError)
                }
            }
        } else {
            null
        }
        if (usesCamera && !hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
        onDispose {
            if (usesCamera) {
                analysisUseCase.clearAnalyzer()
                initFuture?.cancel(true)
                analysisExecutor.shutdownNow()
                landmarkerEngine.close()
            }
            viewModel.onSceneExited()
        }
    }

    if (usesCamera) {
        rememberCameraBound(
            context = context,
            lifecycleOwner = lifecycleOwner,
            enabled = hasCamera && uiState.detectorReady && viewModel.shouldBindCamera(),
            onReady = viewModel::onCameraReady,
            onError = viewModel::onCameraError,
            onUnbind = viewModel::onCameraUnbound,
            useCases = { arrayOf(analysisUseCase) },
        )
    }

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
                onBack()
                true
            }
            BareKeyEvent.LongPress -> {
                viewModel.onTouchPadLongPress()
                true
            }
            BareKeyEvent.TwoFingerSingleTap -> {
                viewModel.onTwoFingerSingleTap()
                true
            }
        }
    }

    val metrics = uiState.metrics
    val inference = uiState.inference
    val pointer = uiState.pointer
    val outputLabel = if (uiState.outputEnabled) "已启用（UDP+本地）" else "已暂停（离合）"
    val gestureLabel = when (pointer.gesture) {
        PointerGesture.IDLE -> "IDLE"
        PointerGesture.TRACKING -> "MOVE"
        PointerGesture.PINCH -> "PINCH"
        PointerGesture.FIST -> "FIST"
        PointerGesture.LOST -> "LOST"
        PointerGesture.PAUSED -> "PAUSED"
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
        add("链路: $linkLabel")
        when {
            isHybrid -> {
                add("方案C: 有手=食指精调 · 无手=转头控制 · 相机仅离合开")
                add("IMU: ${if (uiState.imuReady) "就绪" else "不可用"}")
                add(
                    "头姿: 转=${formatMetric(uiState.headYawDeg.toDouble())}° " +
                        "点=${formatMetric(uiState.headPitchDeg.toDouble())}° · " +
                        "灵敏度=${formatMetric(uiState.linkGain.toDouble())}",
                )
                add("相机: ${uiState.cameraStatus}")
                add("检测: ${uiState.detectorStatus}")
                add("手: ${if (uiState.handPresent) "有 landmarks=${uiState.landmarkCount}" else "无(仅IMU)"}")
            }
            usesCamera -> {
                add("方案A: 移掌=指针 · 单击=左键 · 长按=重锚 · 双指=关闭 · 滑=滚轮")
                add("相机: ${uiState.cameraStatus}")
                add("检测: ${uiState.detectorStatus}")
                add("手: ${if (uiState.handPresent) "有 landmarks=${uiState.landmarkCount}" else "无"}")
            }
            else -> {
                add("方案B: 转头=左右 · 点头=上下 · 单击=左键 · 长按=重锚 · 双指=关闭")
                add("IMU: ${if (uiState.imuReady) "就绪" else "不可用"}")
                add(
                    "头姿: yaw=${formatMetric(uiState.headYawDeg.toDouble())}° " +
                        "pitch=${formatMetric(uiState.headPitchDeg.toDouble())}° " +
                        "灵敏度=${formatMetric(uiState.linkGain.toDouble())} " +
                        "锚=${if (uiState.imuCalibrated) "OK" else "—"}",
                )
            }
        }
        add("输出: $outputLabel")
        add("手势: $gestureLabel")
        add(
            "指针: dx=${formatMetric(pointer.dx.toDouble())} " +
                "dy=${formatMetric(pointer.dy.toDouble())} " +
                "左键=${if (pointer.leftPressed) "按下" else "抬起"}",
        )
        add("点击计数: ↓${uiState.pinchDownCount} ↑${uiState.pinchUpCount}")
        if (usesCamera) {
            val dimensions = if (metrics.width > 0 && metrics.height > 0) {
                "${metrics.width}×${metrics.height}"
            } else {
                "等待首帧"
            }
            add("帧: $dimensions  格式=${metrics.imageFormat}")
            add(
                "FPS: 到达 ${formatMetric(metrics.arrivalFps)} / " +
                    "分析 ${formatMetric(metrics.analyzedFps)}",
            )
            add(
                "推理: 末 ${formatMetric(inference.lastMs)} / " +
                    "p95 ${formatMetric(inference.p95Ms)} ms",
            )
        }
        uiState.linkPersistHint?.let { add("配置: $it") }
        uiState.linkError?.let { add("链路错误: $it") }
        uiState.lastDetectorError?.let { add("检测错误(${uiState.detectorErrors}): $it") }
            ?: metrics.lastError?.let { add("分析错误(${metrics.analyzerErrors}): $it") }
    }

    BareScreenLayout(
        title = when {
            isHybrid -> "混合鼠标 · 方案C"
            usesCamera -> "手势鼠标 · 方案A"
            else -> "头控鼠标 · 方案B"
        },
        subtitle = when {
            isHybrid && !hasCamera -> "需要相机权限(精细层)"
            isHybrid && !uiState.detectorReady -> uiState.detectorStatus
            isHybrid && !uiState.imuReady -> "IMU 不可用"
            usesCamera && !hasCamera -> "需要相机权限"
            usesCamera && !uiState.detectorReady -> uiState.detectorStatus
            !usesCamera && !uiState.imuReady -> "IMU 不可用"
            !uiState.linkConfigured -> "无保存链路 · adb 或长按保存"
            !uiState.outputEnabled -> "离合关闭 · 相机休眠 · 单击启用"
            pointer.leftPressed -> "TouchPad → 左键"
            isHybrid && uiState.handPresent -> "手精调 (IMU已关)"
            isHybrid -> "转头控制 (举手切手精调)"
            else -> "头动 → UDP 指针（60Hz）"
        },
        keyGuide = BareKeyGuide(
            click = if (uiState.outputEnabled) "左键" else "启用离合",
            doubleClick = "返回",
            longPress = if (uiState.outputEnabled) {
                if (isHybrid) "重锚IMU+手" else "重锚头姿"
            } else {
                "保存链路"
            },
            swipeForward = "灵敏度+",
            swipeBack = "灵敏度−",
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (usesCamera && uiState.outputEnabled) {
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
                label = "UDP 鼠标链路",
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
