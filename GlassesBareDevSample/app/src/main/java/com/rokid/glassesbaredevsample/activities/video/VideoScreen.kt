package com.rokid.glassesbaredevsample.activities.video

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rokid.glassesbaredevsample.camera.rememberCameraBound
import com.rokid.glassesbaredevsample.input.BareKeyEvent
import com.rokid.glassesbaredevsample.input.RegisterBareKeyHandler
import com.rokid.glassesbaredevsample.input.rememberSubPageEnterDebounce
import com.rokid.glassesbaredevsample.ui.design.BareHeroText
import com.rokid.glassesbaredevsample.ui.design.BareInfoBlock
import com.rokid.glassesbaredevsample.ui.design.BareKeyGuide
import com.rokid.glassesbaredevsample.ui.design.BareSavedPathBlock
import com.rokid.glassesbaredevsample.ui.design.BareScreenLayout
import com.rokid.glassesbaredevsample.utils.BarePermissions
import java.util.concurrent.Executors

@Composable
fun VideoScreen(onBack: () -> Unit, viewModel: VideoViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val status by viewModel.status.collectAsState()
    val lastSavedPath by viewModel.lastSavedPath.collectAsState()
    var hasCamera by remember { mutableStateOf(BarePermissions.hasCamera(context)) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    val ignoreDoubleClick = rememberSubPageEnterDebounce()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { map ->
        hasCamera = map[Manifest.permission.CAMERA] == true
        if (!hasCamera) viewModel.setStatus("未授予相机权限")
    }

    DisposableEffect(Unit) {
        if (!hasCamera) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA))
        }
        onDispose {
            recording?.stop()
            cameraExecutor.shutdown()
        }
    }

    val cameraReady = rememberCameraBound(
        context = context,
        lifecycleOwner = lifecycleOwner,
        enabled = hasCamera,
        onReady = { viewModel.setStatus("待机 · 单击开始") },
        onError = { viewModel.setStatus(it) },
        onUnbind = {
            recording?.stop()
            recording = null
            videoCapture = null
        },
        onBound = { cases ->
            @Suppress("UNCHECKED_CAST")
            val cap = cases.filterIsInstance<VideoCapture<*>>().firstOrNull() as? VideoCapture<Recorder>
            videoCapture = cap
        },
        useCases = {
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            arrayOf(VideoCapture.Builder(recorder).build())
        },
    )

    RegisterBareKeyHandler { event ->
        when (event) {
            BareKeyEvent.SwipeForward, BareKeyEvent.SwipeBack -> false
            BareKeyEvent.Click -> {
                if (!hasCamera || !cameraReady) {
                    viewModel.setStatus(if (!hasCamera) "未授予相机权限" else "相机准备中")
                    return@RegisterBareKeyHandler true
                }
                val cap = videoCapture ?: run {
                    viewModel.setStatus("相机未就绪")
                    return@RegisterBareKeyHandler true
                }
                val active = recording
                if (active != null) {
                    viewModel.setStatus("停止中…")
                    active.stop()
                } else {
                    val file = viewModel.nextOutputFile()
                    viewModel.onRecordingStart(file.absolutePath)
                    val opts = FileOutputOptions.Builder(file).build()
                    recording = cap.output
                        .prepareRecording(context, opts)
                        .start(cameraExecutor) { ev ->
                            when (ev) {
                                is VideoRecordEvent.Start -> Unit
                                is VideoRecordEvent.Finalize -> {
                                    recording = null
                                    viewModel.onRecordingFinalize(
                                        hasError = ev.hasError(),
                                        errorCode = ev.error,
                                        cause = ev.cause?.message,
                                    )
                                }
                                else -> Unit
                            }
                        }
                }
                true
            }
            BareKeyEvent.DoubleClick -> {
                if (ignoreDoubleClick()) return@RegisterBareKeyHandler true
                recording?.stop()
                onBack()
                true
            }
            BareKeyEvent.LongPress -> false
            BareKeyEvent.TwoFingerSingleTap -> false
        }
    }

    val isRecording = recording != null
    val ready = hasCamera && cameraReady && videoCapture != null
    val heroText = when {
        !hasCamera -> "需要相机权限"
        !ready -> "准备中…"
        isRecording -> "录制中"
        status.startsWith("已保存") -> "已保存"
        status.startsWith("保存失败") -> "保存失败"
        status.startsWith("停止中") -> "停止中"
        else -> "待机"
    }
    val heroHint = when {
        !hasCamera -> "请在手机端授权"
        isRecording -> "单击停止"
        status.startsWith("已保存") -> lastSavedPath ?: status
        status.startsWith("保存失败") -> status
        ready -> "单击开始"
        else -> "绑定 CameraX"
    }

    BareScreenLayout(
        title = "录像",
        subtitle = status,
        keyGuide = BareKeyGuide(
            click = when {
                !ready -> "等待就绪"
                isRecording -> "停止录制"
                else -> "开始录制"
            },
            doubleClick = "返回",
        ),
    ) {
        BareHeroText(text = heroText, hint = heroHint)
        BareInfoBlock(label = "状态", lines = listOf(status))
        BareSavedPathBlock(lastSavedPath)
    }
}
