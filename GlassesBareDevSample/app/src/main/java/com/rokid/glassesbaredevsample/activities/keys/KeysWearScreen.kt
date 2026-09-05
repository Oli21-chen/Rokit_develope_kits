package com.rokid.glassesbaredevsample.activities.keys

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rokid.glassesbaredevsample.input.BareKeyEvent
import com.rokid.glassesbaredevsample.input.LocalBareGlassesInputDispatcher
import com.rokid.glassesbaredevsample.input.RegisterBareKeyHandler
import com.rokid.glassesbaredevsample.input.cycleIndex
import com.rokid.glassesbaredevsample.ui.design.BareInfoBlock
import com.rokid.glassesbaredevsample.ui.design.BareKeyGuide
import com.rokid.glassesbaredevsample.ui.design.BarePagedViewport
import com.rokid.glassesbaredevsample.ui.design.BareScreenLayout

@Composable
fun KeysWearScreen(onBack: () -> Unit, viewModel: KeysWearViewModel) {
    val logs by viewModel.logLines.collectAsState()
    val take by viewModel.takeState.collectAsState()
    val leg by viewModel.legState.collectAsState()
    val dispatcher = LocalBareGlassesInputDispatcher.current
    var pageIndex by remember { mutableIntStateOf(0) }
    val pageCount = 2

    DisposableEffect(Unit) {
        viewModel.register()
        onDispose { viewModel.unregister() }
    }

    DisposableEffect(dispatcher) {
        dispatcher?.setInterceptListener { line -> viewModel.appendLog(line) }
        onDispose { dispatcher?.setInterceptListener(null) }
    }

    RegisterBareKeyHandler { event ->
        when (event) {
            BareKeyEvent.SwipeForward -> {
                pageIndex = cycleIndex(pageIndex, pageCount, 1)
                true
            }
            BareKeyEvent.SwipeBack -> {
                pageIndex = cycleIndex(pageIndex, pageCount, -1)
                true
            }
            BareKeyEvent.Click -> false
            BareKeyEvent.DoubleClick -> {
                onBack()
                true
            }
            BareKeyEvent.LongPress -> false
            BareKeyEvent.TwoFingerSingleTap -> false
        }
    }

    BareScreenLayout(
        title = "按键与佩戴",
        subtitle = if (pageIndex == 0) "佩戴 / 镜腿" else "按键日志",
        pageIndex = pageIndex,
        pageCount = pageCount,
        keyGuide = BareKeyGuide(
            swipeForward = "下一屏",
            swipeBack = "上一屏",
            doubleClick = "返回",
        ),
    ) {
        BarePagedViewport(pageIndex = pageIndex, pageCount = pageCount) { page ->
            when (page) {
                0 -> BareInfoBlock(
                    label = "佩戴 / 镜腿",
                    lines = listOf(
                        "take: $take  (1=佩戴)",
                        "leg:  $leg  (1=展开)",
                    ),
                )
                else -> BareInfoBlock(
                    label = "最近事件",
                    lines = if (logs.isEmpty()) {
                        listOf(
                            "（等待 Key / 广播）",
                            "TouchPad：单击·双击·长按",
                            "镜腿：单击·双击·长按",
                        )
                    } else {
                        logs.takeLast(6)
                    },
                )
            }
        }
    }
}
