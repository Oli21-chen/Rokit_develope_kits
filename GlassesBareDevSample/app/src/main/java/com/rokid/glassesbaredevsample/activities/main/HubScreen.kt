package com.rokid.glassesbaredevsample.activities.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rokid.glassesbaredevsample.input.BareKeyEvent
import com.rokid.glassesbaredevsample.input.RegisterBareKeyHandler
import com.rokid.glassesbaredevsample.input.cycleIndex
import com.rokid.glassesbaredevsample.navigation.BareSceneRoutes
import com.rokid.glassesbaredevsample.ui.design.BareHeroText
import com.rokid.glassesbaredevsample.ui.design.BareKeyGuide
import com.rokid.glassesbaredevsample.ui.design.BareScreenLayout

private data class HubEntry(val label: String, val route: String)

private val HUB_ENTRIES = listOf(
    HubEntry("按键与佩戴/折叠", BareSceneRoutes.KEYS_WEAR),
    HubEntry("原始音频", BareSceneRoutes.AUDIO),
    HubEntry("拍照", BareSceneRoutes.PHOTO),
    HubEntry("录像", BareSceneRoutes.VIDEO),
    HubEntry("IMU 传感器", BareSceneRoutes.IMU),
    HubEntry("混合鼠标 · 方案C", BareSceneRoutes.HAND_MOUSE),
)

@Composable
fun HubScreen(
    onNavigate: (String) -> Unit,
    onExit: () -> Unit,
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val entry = HUB_ENTRIES[selectedIndex]
    val count = HUB_ENTRIES.size

    RegisterBareKeyHandler { event ->
        when (event) {
            BareKeyEvent.SwipeForward -> {
                selectedIndex = cycleIndex(selectedIndex, count, 1)
                true
            }
            BareKeyEvent.SwipeBack -> {
                selectedIndex = cycleIndex(selectedIndex, count, -1)
                true
            }
            BareKeyEvent.Click -> {
                onNavigate(entry.route)
                true
            }
            BareKeyEvent.DoubleClick -> {
                onExit()
                true
            }
            BareKeyEvent.LongPress -> false
            BareKeyEvent.TwoFingerSingleTap -> false
        }
    }

    BareScreenLayout(
        title = "裸机能力",
        subtitle = "GlassesBareDevSample",
        pageIndex = selectedIndex,
        pageCount = count,
        keyGuide = BareKeyGuide.Hub,
        drawSafeAreaFrame = true,
    ) {
        BareHeroText(
            text = entry.label,
            hint = "单击进入",
        )
    }
}
