package com.rokid.glassesbaredevsample.activities.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rokid.glassesbaredevsample.input.BareKeyEvent
import com.rokid.glassesbaredevsample.input.RegisterBareKeyHandler
import com.rokid.glassesbaredevsample.ui.design.BareInfoBlock
import com.rokid.glassesbaredevsample.ui.design.BareKeyGuide
import com.rokid.glassesbaredevsample.ui.design.BareSavedPathBlock
import com.rokid.glassesbaredevsample.ui.design.BareScreenLayout

@Composable
fun AudioScreen(
    onBack: () -> Unit,
    viewModel: AudioViewModel,
    onRequestRecordAudio: () -> Unit,
) {
    val status by viewModel.status.collectAsState()
    val lastSavedPath by viewModel.lastSavedPath.collectAsState()
    val permissionGranted by viewModel.permissionGranted.collectAsState()

    DisposableEffect(Unit) {
        viewModel.refreshPermission()
        onDispose { viewModel.stopIfRecording() }
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) onRequestRecordAudio()
    }

    RegisterBareKeyHandler { event ->
        when (event) {
            BareKeyEvent.SwipeForward, BareKeyEvent.SwipeBack -> false
            BareKeyEvent.Click -> {
                viewModel.onSpriteClick()
                true
            }
            BareKeyEvent.DoubleClick -> {
                onBack()
                true
            }
            BareKeyEvent.LongPress -> false
            BareKeyEvent.TwoFingerSingleTap -> false
        }
    }

    BareScreenLayout(
        title = "原始音频",
        subtitle = "16kHz · 8ch · 0x6000FC",
        keyGuide = BareKeyGuide(
            click = "开始/停止录音",
            doubleClick = "返回",
        ),
    ) {
        BareInfoBlock(
            label = "权限",
            lines = listOf(
                if (permissionGranted) "已授予" else "未授予 · 请在手机端确认",
            ),
        )
        BareInfoBlock(label = "状态", lines = listOf(status))
        BareSavedPathBlock(lastSavedPath)
    }
}
