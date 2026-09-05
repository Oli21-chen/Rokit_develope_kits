package com.rokid.glassesbaredevsample.activities.main

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rokid.glassesbaredevsample.activities.pland.PlanDMouseScreen
import com.rokid.glassesbaredevsample.activities.pland.PlanDMouseViewModel
import com.rokid.glassesbaredevsample.input.BareGlassesInputDispatcher
import com.rokid.glassesbaredevsample.input.LocalBareGlassesInputDispatcher
import com.rokid.glassesbaredevsample.input.TouchPadSwipeDetector
import com.rokid.glassesbaredevsample.input.rememberBareGlassesInputDispatcher
import com.rokid.glassesbaredevsample.link.MouseLinkConfig
import com.rokid.glassesbaredevsample.link.MouseLinkStore
import com.rokid.glassesbaredevsample.ui.design.GlassesDisplayFrame
import com.rokid.glassesbaredevsample.ui.theme.MouseControlGlassesTheme
import com.rokid.glassesbaredevsample.ui.theme.PitchBlack

class MainActivity : ComponentActivity() {
    private val planDViewModel by viewModels<PlanDMouseViewModel>()
    private lateinit var mouseLinkStore: MouseLinkStore

    private var keyDispatcher: BareGlassesInputDispatcher? = null
    private val swipeDetector = TouchPadSwipeDetector()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        mouseLinkStore = MouseLinkStore(this)
        applyMouseLinkConfig(intent)
        setContent {
            val context = LocalContext.current
            val dispatcher = rememberBareGlassesInputDispatcher(context)
            remember { keyDispatcher = dispatcher }
            MouseControlGlassesTheme {
                CompositionLocalProvider(LocalBareGlassesInputDispatcher provides dispatcher) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PitchBlack),
                    ) {
                        GlassesDisplayFrame {
                            PlanDMouseScreen(
                                viewModel = planDViewModel,
                                onExit = { finish() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyMouseLinkConfig(intent)
    }

    private fun applyMouseLinkConfig(intent: android.content.Intent?) {
        val saved = mouseLinkStore.load()
        val config = MouseLinkConfig.mergeSavedWithIntent(saved, intent)
        planDViewModel.applyLinkConfig(config)
        if (MouseLinkConfig.shouldPersistFromIntent(intent)) {
            mouseLinkStore.save(config)
            planDViewModel.onLinkConfigPersisted()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> return true
            KeyEvent.KEYCODE_BACK -> {
                keyDispatcher?.dispatchBackKey()
                return true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                keyDispatcher?.dispatchLongKey()
                return true
            }
            KeyEvent.KEYCODE_SETTINGS -> {
                keyDispatcher?.consumeSystemKey("Key·SETTINGS")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                event?.let(swipeDetector::onKey)?.let { swipe ->
                    keyDispatcher?.dispatchSwipe(swipe)
                }
                return true
            }
            KeyEvent.KEYCODE_NOTIFICATION -> {
                if (event != null && event.repeatCount == 0) {
                    keyDispatcher?.dispatchTwoFingerTap()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (event != null && event.repeatCount == 0) {
                    keyDispatcher?.dispatchEnterKey()
                    return true
                }
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                keyDispatcher?.dispatchLongKeyRelease()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        keyDispatcher?.unregister(applicationContext)
        keyDispatcher = null
        super.onDestroy()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
