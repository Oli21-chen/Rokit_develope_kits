package com.rokid.glassesbaredevsample.activities.main

import android.Manifest
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rokid.glassesbaredevsample.activities.audio.AudioScreen
import com.rokid.glassesbaredevsample.activities.audio.AudioViewModel
import com.rokid.glassesbaredevsample.activities.handmouse.HandMouseScreen
import com.rokid.glassesbaredevsample.activities.handmouse.HandMouseViewModel
import com.rokid.glassesbaredevsample.activities.imu.ImuScreen
import com.rokid.glassesbaredevsample.activities.keys.KeysWearScreen
import com.rokid.glassesbaredevsample.activities.keys.KeysWearViewModel
import com.rokid.glassesbaredevsample.activities.photo.PhotoScreen
import com.rokid.glassesbaredevsample.activities.photo.PhotoViewModel
import com.rokid.glassesbaredevsample.activities.video.VideoScreen
import com.rokid.glassesbaredevsample.activities.video.VideoViewModel
import com.rokid.glassesbaredevsample.input.BareGlassesInputDispatcher
import com.rokid.glassesbaredevsample.input.LocalBareGlassesInputDispatcher
import com.rokid.glassesbaredevsample.input.TouchPadSwipeDetector
import com.rokid.glassesbaredevsample.input.rememberBareGlassesInputDispatcher
import com.rokid.glassesbaredevsample.link.MouseLinkConfig
import com.rokid.glassesbaredevsample.link.MouseLinkStore
import com.rokid.glassesbaredevsample.navigation.BareSceneRoutes
import com.rokid.glassesbaredevsample.ui.design.GlassesDisplayFrame
import com.rokid.glassesbaredevsample.ui.theme.GlassesBareDevSampleTheme
import com.rokid.glassesbaredevsample.ui.theme.PitchBlack

class MainActivity : ComponentActivity() {
    private val keysWearViewModel by viewModels<KeysWearViewModel>()
    private val audioViewModel by viewModels<AudioViewModel>()
    private val photoViewModel by viewModels<PhotoViewModel>()
    private val videoViewModel by viewModels<VideoViewModel>()
    private val handMouseViewModel by viewModels<HandMouseViewModel>()
    private lateinit var mouseLinkStore: MouseLinkStore

    private var keyDispatcher: BareGlassesInputDispatcher? = null
    private val swipeDetector = TouchPadSwipeDetector()

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            audioViewModel.onRecordAudioPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        mouseLinkStore = MouseLinkStore(this)
        audioViewModel.refreshPermission()
        applyMouseLinkConfig(intent)
        setContent {
            val context = LocalContext.current
            val dispatcher = rememberBareGlassesInputDispatcher(context)
            remember { keyDispatcher = dispatcher }
            GlassesBareDevSampleTheme {
                CompositionLocalProvider(LocalBareGlassesInputDispatcher provides dispatcher) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PitchBlack),
                    ) {
                        GlassesDisplayFrame {
                            BareNavApp(
                                keysWearViewModel = keysWearViewModel,
                                audioViewModel = audioViewModel,
                                photoViewModel = photoViewModel,
                                videoViewModel = videoViewModel,
                                handMouseViewModel = handMouseViewModel,
                                onRequestRecordAudio = {
                                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
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
        handMouseViewModel.applyLinkConfig(config)
        if (MouseLinkConfig.shouldPersistFromIntent(intent)) {
            mouseLinkStore.save(config)
            handMouseViewModel.onLinkConfigPersisted()
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
            KeyEvent.KEYCODE_NOTIFICATION -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && event != null && event.repeatCount == 0) {
            keyDispatcher?.dispatchEnterKey()
            return true
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

@Composable
private fun BareNavApp(
    keysWearViewModel: KeysWearViewModel,
    audioViewModel: AudioViewModel,
    photoViewModel: PhotoViewModel,
    videoViewModel: VideoViewModel,
    handMouseViewModel: HandMouseViewModel,
    onRequestRecordAudio: () -> Unit,
) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = BareSceneRoutes.HUB,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(BareSceneRoutes.HUB) {
            val activity = LocalContext.current as ComponentActivity
            HubScreen(
                onNavigate = { route -> nav.navigate(route) },
                onExit = { activity.finish() },
            )
        }
        composable(BareSceneRoutes.KEYS_WEAR) {
            KeysWearScreen(onBack = { nav.popBackStack() }, viewModel = keysWearViewModel)
        }
        composable(BareSceneRoutes.AUDIO) {
            AudioScreen(
                onBack = { nav.popBackStack() },
                viewModel = audioViewModel,
                onRequestRecordAudio = onRequestRecordAudio,
            )
        }
        composable(BareSceneRoutes.PHOTO) {
            PhotoScreen(onBack = { nav.popBackStack() }, viewModel = photoViewModel)
        }
        composable(BareSceneRoutes.VIDEO) {
            VideoScreen(onBack = { nav.popBackStack() }, viewModel = videoViewModel)
        }
        composable(BareSceneRoutes.IMU) {
            ImuScreen(onBack = { nav.popBackStack() })
        }
        composable(BareSceneRoutes.HAND_MOUSE) {
            HandMouseScreen(
                onBack = { nav.popBackStack() },
                viewModel = handMouseViewModel,
            )
        }
    }
}
