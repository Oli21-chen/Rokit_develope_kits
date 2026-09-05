package com.rokid.glassesbaredevsample.activities.photo

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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

@Composable
fun PhotoScreen(onBack: () -> Unit, viewModel: PhotoViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val status by viewModel.status.collectAsState()
    val lastSavedPath by viewModel.lastSavedPath.collectAsState()
    var hasCamera by remember { mutableStateOf(BarePermissions.hasCamera(context)) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val ignoreDoubleClick = rememberSubPageEnterDebounce()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCamera = granted
        if (!granted) viewModel.onError("未授予相机权限")
    }

    DisposableEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    val cameraReady = rememberCameraBound(
        context = context,
        lifecycleOwner = lifecycleOwner,
        enabled = hasCamera,
        onReady = { viewModel.setReady() },
        onError = viewModel::onError,
        onUnbind = {
            imageCapture = null
            viewModel.clearCapture()
        },
        onBound = { cases ->
            val cap = cases.filterIsInstance<ImageCapture>().firstOrNull()
            imageCapture = cap
            viewModel.boundCapture = cap
        },
        useCases = {
            arrayOf(
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .build(),
            )
        },
    )

    RegisterBareKeyHandler { event ->
        when (event) {
            BareKeyEvent.SwipeForward, BareKeyEvent.SwipeBack -> false
            BareKeyEvent.Click -> {
                val cap = imageCapture ?: viewModel.boundCapture
                if (!hasCamera || !cameraReady || cap == null) {
                    viewModel.onError(
                        when {
                            !hasCamera -> "未授予相机权限"
                            !cameraReady -> "相机准备中"
                            else -> "相机未就绪"
                        },
                    )
                    return@RegisterBareKeyHandler true
                }
                if (!viewModel.canCapture()) return@RegisterBareKeyHandler true
                val file = viewModel.nextOutputFile()
                viewModel.onCaptureStarted()
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                cap.takePicture(
                    options,
                    mainExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            viewModel.onPhotoSaved(file.absolutePath)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            viewModel.onError(
                                exception.message ?: "拍照失败(${exception.imageCaptureError})",
                            )
                        }
                    },
                )
                true
            }
            BareKeyEvent.DoubleClick -> {
                if (ignoreDoubleClick()) return@RegisterBareKeyHandler true
                onBack()
                true
            }
            BareKeyEvent.LongPress -> false
            BareKeyEvent.TwoFingerSingleTap -> false
        }
    }

    val ready = hasCamera && cameraReady && imageCapture != null
    val heroText = when {
        !hasCamera -> "需要相机权限"
        !ready -> "准备中…"
        status.startsWith("拍摄") -> "拍摄中"
        status.startsWith("已保存") -> "已保存"
        status.contains("失败") -> "拍照失败"
        else -> "相机就绪"
    }
    val heroHint = when {
        !hasCamera -> "请在手机端授权"
        !ready -> "绑定 CameraX"
        status.startsWith("已保存") -> lastSavedPath ?: status
        status.contains("失败") -> status
        else -> "单击拍摄"
    }

    BareScreenLayout(
        title = "拍照",
        subtitle = status,
        keyGuide = BareKeyGuide(
            click = if (ready) "拍照" else "等待就绪",
            doubleClick = "返回",
        ),
    ) {
        BareHeroText(text = heroText, hint = heroHint)
        BareInfoBlock(label = "状态", lines = listOf(status))
        BareSavedPathBlock(lastSavedPath)
    }
}
