package com.rokid.glassesbaredevsample.ui.design

import androidx.compose.runtime.Composable

@Composable
fun BareSavedPathBlock(path: String?) {
    BareInfoBlock(
        label = "落盘路径",
        lines = listOf(path?.takeIf { it.isNotBlank() } ?: "（录制/拍照成功后显示 .jpg / .mp4 路径）"),
    )
}
