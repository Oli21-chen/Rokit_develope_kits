package com.rokid.glassesbaredevsample.activities.imu

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rokid.glassesbaredevsample.input.BareKeyEvent
import com.rokid.glassesbaredevsample.input.RegisterBareKeyHandler
import com.rokid.glassesbaredevsample.input.cycleIndex
import com.rokid.glassesbaredevsample.sensor.GlassesAxisRemapper
import com.rokid.glassesbaredevsample.sensor.HeadOrientationTracker
import com.rokid.glassesbaredevsample.sensor.HeadPose
import com.rokid.glassesbaredevsample.sensor.ImuAxisVerificationEngine
import com.rokid.glassesbaredevsample.sensor.ImuVerificationUiState
import com.rokid.glassesbaredevsample.sensor.PhonePortraitAxes
import com.rokid.glassesbaredevsample.sensor.SixAxisReading
import com.rokid.glassesbaredevsample.sensor.VerificationPhase
import com.rokid.glassesbaredevsample.ui.design.BareInfoBlock
import com.rokid.glassesbaredevsample.ui.design.BareKeyGuide
import com.rokid.glassesbaredevsample.ui.design.BarePagedViewport
import com.rokid.glassesbaredevsample.ui.design.BareScreenLayout
import com.rokid.glassesbaredevsample.ui.imu.ImuAccelLevelScreen
import com.rokid.glassesbaredevsample.ui.imu.ImuBallScreen
import com.rokid.glassesbaredevsample.ui.imu.ImuSixAxisMeterScreen

private enum class ImuUiMode { Verification, Display }

private const val DISPLAY_PAGE_COUNT = 5

@Composable
fun ImuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tracker = remember { HeadOrientationTracker(context) }
    val verificationEngine = remember { ImuAxisVerificationEngine() }
    val readings by tracker.readings.collectAsState()
    val pose by tracker.pose.collectAsState()
    val available by tracker.available.collectAsState()
    val verificationState by verificationEngine.uiState.collectAsState()

    var uiMode by remember { mutableStateOf(ImuUiMode.Verification) }
    var displayPage by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        tracker.start()
        onDispose {
            verificationEngine.cancel()
            tracker.stop()
        }
    }

    LaunchedEffect(available, uiMode, verificationState.phase) {
        if (available &&
            uiMode == ImuUiMode.Verification &&
            verificationState.phase == VerificationPhase.Intro
        ) {
            verificationEngine.start()
        }
    }

    LaunchedEffect(verificationState.phase) {
        if (verificationState.phase == VerificationPhase.Running) {
            snapshotFlow { readings }.collect { r ->
                verificationEngine.onGyroSample(
                    r.gxRad, r.gyRad, r.gzRad, System.nanoTime(),
                )
            }
        }
    }

    LaunchedEffect(uiMode) {
        if (uiMode == ImuUiMode.Display && available) {
            tracker.calibrate()
            displayPage = 0
        }
    }

    val keyGuide = when (uiMode) {
        ImuUiMode.Verification -> when (verificationState.phase) {
            VerificationPhase.Intro -> BareKeyGuide(click = "准备中…", doubleClick = "返回")
            VerificationPhase.Running -> BareKeyGuide(
                doubleClick = "取消",
                longPress = if (verificationState.canRetry) "重试本步" else null,
            )
            VerificationPhase.Complete -> BareKeyGuide(click = "进入演示", doubleClick = "返回")
        }
        ImuUiMode.Display -> when (displayPage) {
            DISPLAY_PAGE_COUNT - 1 -> BareKeyGuide(
                swipeBack = "上一屏",
                swipeForward = "回球视图",
                doubleClick = "返回",
                longPress = "重新验证",
            )
            else -> BareKeyGuide(
                swipeForward = "下一屏",
                swipeBack = "上一屏",
                doubleClick = "返回",
                longPress = "校准姿态",
            )
        }
    }

    RegisterBareKeyHandler { event ->
        when (uiMode) {
            ImuUiMode.Verification -> handleVerificationKey(
                event, verificationState, verificationEngine, onBack,
            ) { uiMode = ImuUiMode.Display }
            ImuUiMode.Display -> handleDisplayKey(
                event, displayPage,
                onPageChange = { displayPage = it },
                onRecalibrate = { tracker.calibrate() },
                onRerunVerification = {
                    verificationEngine.cancel()
                    uiMode = ImuUiMode.Verification
                    displayPage = 0
                    verificationEngine.start()
                },
                onBack = onBack,
            )
        }
    }

    val title = when (uiMode) {
        ImuUiMode.Verification -> "IMU 验证"
        ImuUiMode.Display -> "IMU 演示"
    }
    val subtitle = when (uiMode) {
        ImuUiMode.Verification -> when (verificationState.phase) {
            VerificationPhase.Intro -> "自动开始"
            VerificationPhase.Running -> verificationState.instruction
            VerificationPhase.Complete -> "验证完成"
        }
        ImuUiMode.Display -> when (displayPage) {
            0 -> "头姿球"
            1 -> "六轴仪表"
            2 -> "加速度水平仪"
            3 -> "轴向映射"
            else -> "坐标系"
        }
    }
    val pageIndex = when (uiMode) {
        ImuUiMode.Verification -> null
        ImuUiMode.Display -> displayPage
    }
    val pageCount = when (uiMode) {
        ImuUiMode.Verification -> null
        ImuUiMode.Display -> DISPLAY_PAGE_COUNT
    }

    BareScreenLayout(
        title = title,
        subtitle = subtitle,
        pageIndex = pageIndex,
        pageCount = pageCount,
        keyGuide = keyGuide,
        drawSafeAreaFrame = uiMode == ImuUiMode.Display && displayPage == 0,
    ) {
        if (!available) {
            BareInfoBlock(
                label = "不可用",
                lines = listOf("无陀螺仪或加速度计"),
            )
            return@BareScreenLayout
        }
        when (uiMode) {
            ImuUiMode.Verification -> ImuVerificationSection(verificationState, readings)
            ImuUiMode.Display -> ImuDisplayPaged(displayPage, readings, pose)
        }
    }
}

private fun handleVerificationKey(
    event: BareKeyEvent,
    state: ImuVerificationUiState,
    engine: ImuAxisVerificationEngine,
    onBack: () -> Unit,
    onEnterDisplay: () -> Unit,
): Boolean = when (event) {
    BareKeyEvent.SwipeForward, BareKeyEvent.SwipeBack -> false
    BareKeyEvent.Click -> when (state.phase) {
        VerificationPhase.Intro -> { engine.start(); true }
        VerificationPhase.Complete -> { onEnterDisplay(); true }
        VerificationPhase.Running -> false
    }
    BareKeyEvent.DoubleClick -> { engine.cancel(); onBack(); true }
    BareKeyEvent.LongPress -> {
        if (state.phase == VerificationPhase.Running && state.canRetry) {
            engine.retryCurrentStep()
            true
        } else {
            false
        }
    }
    BareKeyEvent.TwoFingerSingleTap -> false
}

private fun handleDisplayKey(
    event: BareKeyEvent,
    displayPage: Int,
    onPageChange: (Int) -> Unit,
    onRecalibrate: () -> Unit,
    onRerunVerification: () -> Unit,
    onBack: () -> Unit,
): Boolean = when (event) {
    BareKeyEvent.SwipeForward -> {
        onPageChange(cycleIndex(displayPage, DISPLAY_PAGE_COUNT, 1))
        true
    }
    BareKeyEvent.SwipeBack -> {
        onPageChange(cycleIndex(displayPage, DISPLAY_PAGE_COUNT, -1))
        true
    }
    BareKeyEvent.Click -> false
    BareKeyEvent.DoubleClick -> { onBack(); true }
    BareKeyEvent.LongPress -> {
        if (displayPage == DISPLAY_PAGE_COUNT - 1) onRerunVerification() else onRecalibrate()
        true
    }
    BareKeyEvent.TwoFingerSingleTap -> false
}

@Composable
private fun ImuDisplayPaged(
    pageIndex: Int,
    readings: SixAxisReading,
    pose: HeadPose,
) {
    val calibration = GlassesAxisRemapper.lastCalibration
    BarePagedViewport(pageIndex = pageIndex, pageCount = DISPLAY_PAGE_COUNT) { page ->
        when (page) {
            0 -> ImuBallScreen(
                pose = pose,
                readings = readings,
                drawSafeBorder = false,
                modifier = Modifier.fillMaxSize(),
            )
            1 -> ImuSixAxisMeterScreen(
                readings = readings,
                modifier = Modifier.fillMaxSize(),
            )
            2 -> ImuAccelLevelScreen(
                readings = readings,
                modifier = Modifier.fillMaxSize(),
            )
            3 -> BareInfoBlock(
                label = "映射",
                lines = buildList {
                    calibration?.summaryLines()?.let { addAll(it) }
                    addAll(PhonePortraitAxes.remapperTuningLines())
                },
            )
            else -> BareInfoBlock(
                label = "标准坐标",
                lines = buildList {
                    add(PhonePortraitAxes.AXIS_X_LABEL)
                    add(PhonePortraitAxes.AXIS_Y_LABEL)
                    add(PhonePortraitAxes.AXIS_Z_LABEL)
                    addAll(PhonePortraitAxes.expectedAxisLines())
                },
            )
        }
    }
}
