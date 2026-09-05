package com.rokid.glassesbaredevsample.activities.handmouse

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.rokid.glassesbaredevsample.camera.AnalysisMetrics
import com.rokid.glassesbaredevsample.camera.AnalysisMetricsSnapshot
import com.rokid.glassesbaredevsample.hand.ControlScheme
import com.rokid.glassesbaredevsample.hand.HandDisplayTransform
import com.rokid.glassesbaredevsample.hand.HandFrameResult
import com.rokid.glassesbaredevsample.hand.HandLandmark
import com.rokid.glassesbaredevsample.hand.HandMouseConfig
import com.rokid.glassesbaredevsample.hand.InferenceLatencySnapshot
import com.rokid.glassesbaredevsample.hand.InferenceLatencyTracker
import com.rokid.glassesbaredevsample.hand.PointerCommand
import com.rokid.glassesbaredevsample.hand.PointerGesture
import com.rokid.glassesbaredevsample.hand.PointerMapper
import com.rokid.glassesbaredevsample.link.LinkGain
import com.rokid.glassesbaredevsample.link.MouseLinkConfig
import com.rokid.glassesbaredevsample.link.MouseLinkClient
import com.rokid.glassesbaredevsample.link.MouseLinkStore
import com.rokid.glassesbaredevsample.link.UdpMouseLinkClient
import com.rokid.glassesbaredevsample.sensor.HeadJerkMonitor
import com.rokid.glassesbaredevsample.sensor.HeadOrientationTracker
import com.rokid.glassesbaredevsample.sensor.HybridPointerBlender
import com.rokid.glassesbaredevsample.sensor.ImuPointerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class HandMouseUiState(
    val cameraStatus: String = "相机准备中",
    val detectorStatus: String = "MediaPipe 未初始化",
    val cameraReady: Boolean = false,
    val detectorReady: Boolean = false,
    val usesCameraForPointer: Boolean = true,
    val imuReady: Boolean = false,
    val imuCalibrated: Boolean = false,
    val headYawDeg: Float = 0f,
    val headPitchDeg: Float = 0f,
    val outputEnabled: Boolean = false,
    val handPresent: Boolean = false,
    val landmarkCount: Int = 0,
    val landmarks: List<HandLandmark> = emptyList(),
    val metrics: AnalysisMetricsSnapshot = AnalysisMetricsSnapshot(),
    val inference: InferenceLatencySnapshot = InferenceLatencySnapshot(),
    val detectorErrors: Long = 0,
    val lastDetectorError: String? = null,
    val pointer: PointerCommand = PointerCommand(
        outputEnabled = false,
        handOk = false,
        dx = 0f,
        dy = 0f,
        leftPressed = false,
        gesture = PointerGesture.IDLE,
    ),
    val cursorNormX: Float = 0.5f,
    val cursorNormY: Float = 0.5f,
    val pinchDownCount: Long = 0,
    val pinchUpCount: Long = 0,
    val linkEndpoint: String = "(no host)",
    val linkConfigured: Boolean = false,
    val linkSequence: Long = 0,
    val linkError: String? = null,
    val linkPersistHint: String? = null,
    val linkGain: Float = LinkGain.DEFAULT,
)

private data class HandFineSample(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val handOk: Boolean = false,
)

class HandMouseViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val mouseConfig: HandMouseConfig = HandMouseConfig.Default
    private val linkClient: MouseLinkClient = UdpMouseLinkClient()
    private val linkStore = MouseLinkStore(application)
    private val metrics = AnalysisMetrics()
    private val inferenceLatency = InferenceLatencyTracker()
    private val pointerMapper = PointerMapper(mouseConfig)
    private val headOrientationTracker = HeadOrientationTracker(application)
    private val imuPointerController = ImuPointerController(mouseConfig)
    private val headJerkMonitor = HeadJerkMonitor(application)
    private val _uiState = MutableStateFlow(HandMouseUiState())
    val uiState: StateFlow<HandMouseUiState> = _uiState.asStateFlow()

    private var lastPublishNs = 0L
    private var lastErrorPublishNs = 0L
    private var detectorErrors = 0L
    private var lastDetectorError: String? = null
    private var cursorNormX = 0.5f
    private var cursorNormY = 0.5f
    private var pinchDownCount = 0L
    private var pinchUpCount = 0L
    private var wasLeftPressed = false
    private var touchLeftPressed = false
    private var touchClickReleaseAtMs = 0L
    private var pendingWheelDelta = 0
    private var outputEnabled = false
    private var linkConfig = MouseLinkConfig()
    private val latestCommand = AtomicReference(idlePointer(false))
    private val latestHandFine = AtomicReference(HandFineSample())
    private var linkOutputArmed = false
    private val motionExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "MouseMotionLoop").apply { isDaemon = true }
    }
    private var imuTickFuture: ScheduledFuture<*>? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null

    init {
        linkClient.onRemoteGainChanged = { gain -> applyRemoteLinkGain(gain) }
    }

    fun usesCameraForPointer(): Boolean = mouseConfig.usesCameraForPointer()

    fun shouldBindCamera(): Boolean =
        usesCameraForPointer() &&
            (!mouseConfig.cameraOnlyWhenClutchOn || outputEnabled)

    fun cameraProcessEveryNFrames(): Int = mouseConfig.cameraProcessEveryNFrames

    fun isHybridMode(): Boolean =
        mouseConfig.controlScheme == ControlScheme.HYBRID_IMU_HAND

    fun usesImuMotion(): Boolean = mouseConfig.usesImuMotion()

    fun applyLinkConfig(config: MouseLinkConfig) {
        linkConfig = config
        linkClient.updateConfig(config)
        _uiState.update {
            it.copy(
                linkEndpoint = config.endpointLabel,
                linkConfigured = config.host.isNotBlank(),
                linkGain = config.linkGain,
                linkError = linkClient.lastError,
                linkPersistHint = null,
            )
        }
    }

    fun onLinkConfigPersisted() {
        _uiState.update { it.copy(linkPersistHint = "链路已保存") }
    }

    fun persistLinkConfig() {
        if (linkConfig.host.isBlank()) {
            _uiState.update { it.copy(linkPersistHint = "无 host 可保存") }
            return
        }
        linkStore.save(linkConfig)
        _uiState.update { it.copy(linkPersistHint = "链路已保存") }
        Log.i(TAG, "Saved link config → ${linkConfig.endpointLabel}")
    }

    fun onSceneEntered() {
        metrics.reset()
        inferenceLatency.reset()
        pointerMapper.reset()
        pointerMapper.setOutputEnabled(false)
        outputEnabled = false
        stopMotionLoop()
        lastPublishNs = 0L
        lastErrorPublishNs = 0L
        detectorErrors = 0L
        lastDetectorError = null
        cursorNormX = 0.5f
        cursorNormY = 0.5f
        pinchDownCount = 0L
        pinchUpCount = 0L
        wasLeftPressed = false
        touchLeftPressed = false
        touchClickReleaseAtMs = 0L
        pendingWheelDelta = 0
        linkOutputArmed = false
        latestCommand.set(idlePointer(false))

        latestHandFine.set(HandFineSample())

        imuPointerController.resetAnchor()

        if (usesImuMotion()) {
            headOrientationTracker.start()
            imuPointerController.resetAnchor()
        } else {
            headJerkMonitor.start()
        }

        _uiState.value = HandMouseUiState(
            usesCameraForPointer = usesCameraForPointer(),
            imuReady = usesImuMotion() && headOrientationTracker.available.value,
            cameraStatus = when {
                isHybridMode() -> "方案C · 相机+IMU"
                usesCameraForPointer() -> "相机准备中"
                else -> "方案B · 无需相机"
            },
            detectorStatus = when {
                isHybridMode() -> "MediaPipe 精细层 + IMU"
                usesCameraForPointer() -> "MediaPipe 未初始化"
                else -> "IMU 头指针"
            },
            linkEndpoint = linkConfig.endpointLabel,
            linkConfigured = linkConfig.host.isNotBlank(),
            linkGain = linkConfig.linkGain,
        )
    }

    fun onDetectorReady() {
        _uiState.update {
            it.copy(
                detectorStatus = "MediaPipe 已就绪 (VIDEO/CPU)",
                detectorReady = true,
                lastDetectorError = null,
            )
        }
    }

    fun onDetectorInitFailed(message: String) {
        Log.e(TAG, "Detector init failed: $message")
        detectorErrors++
        lastDetectorError = message
        _uiState.update {
            it.copy(
                detectorStatus = "MediaPipe 初始化失败",
                detectorReady = false,
                detectorErrors = detectorErrors,
                lastDetectorError = message,
            )
        }
    }

    fun onCameraReady() {
        _uiState.update { it.copy(cameraStatus = "CameraX 已就绪", cameraReady = true) }
    }

    fun onCameraUnbound() {
        if (usesCameraForPointer() && !usesImuMotion()) {
            disableOutputAndRelease(sendRelease = true)
        } else if (isHybridMode()) {
            latestHandFine.set(HandFineSample())
        }
        _uiState.update {
            it.copy(
                cameraStatus = when {
                    isHybridMode() && !outputEnabled -> "相机休眠(离合关)"
                    else -> "CameraX 已解绑"
                },
                cameraReady = false,
                handPresent = false,
                landmarkCount = 0,
                landmarks = emptyList(),
            )
        }
    }

    fun onCameraError(message: String) {
        if (usesImuMotion() && !isHybridMode()) return
        Log.e(TAG, "Camera error: $message")
        disableOutputAndRelease(sendRelease = true)
        _uiState.update {
            it.copy(
                cameraStatus = message,
                cameraReady = false,
                outputEnabled = false,
                pointer = idlePointer(outputEnabled = false),
            )
        }
    }

    fun onFrameArrived(
        nowNs: Long,
        sourceTimestampNs: Long,
        width: Int,
        height: Int,
        imageFormat: Int,
        rotationDegrees: Int,
    ) {
        metrics.recordArrival(
            nowNs = nowNs,
            sourceTimestampNs = sourceTimestampNs,
            width = width,
            height = height,
            imageFormat = imageFormat,
            rotationDegrees = rotationDegrees,
        )
    }

    fun onHandFrame(nowNs: Long, analysisDurationNs: Long, result: HandFrameResult) {
        val metricsSnapshot = metrics.recordAnalyzed(nowNs, analysisDurationNs)
        val inferenceSnapshot = inferenceLatency.record(result.inferenceMs)
        val displayLandmarks = HandDisplayTransform.toDisplay(result.landmarks)
        val nowMs = TimeUnit.NANOSECONDS.toMillis(nowNs)

        if (isHybridMode()) {
            val mapped = pointerMapper.update(
                landmarks = displayLandmarks,
                handPresent = result.handPresent,
                nowMs = nowMs,
                motionPaused = false,
            )
            latestHandFine.set(
                HandFineSample(
                    dx = mapped.dx,
                    dy = mapped.dy,
                    handOk = mapped.handOk,
                ),
            )
            val publishUi =
                lastPublishNs == 0L || nowNs - lastPublishNs >= UI_PUBLISH_INTERVAL_NS
            if (publishUi) {
                lastPublishNs = nowNs
            }
            lastDetectorError = null
            _uiState.update {
                it.copy(
                    handPresent = result.handPresent,
                    landmarkCount = result.landmarks.size,
                    landmarks = displayLandmarks,
                    metrics = if (publishUi) metricsSnapshot else it.metrics,
                    inference = if (publishUi) inferenceSnapshot else it.inference,
                    detectorErrors = detectorErrors,
                    lastDetectorError = null,
                )
            }
            return
        }

        if (usesImuMotion()) return
        val motionPaused = headJerkMonitor.isPaused(nowMs)
        val mapped = pointerMapper.update(
            landmarks = displayLandmarks,
            handPresent = result.handPresent,
            nowMs = nowMs,
            motionPaused = motionPaused,
        )
        publishHandCommand(
            command = applyTouchOverlay(mapped, nowMs),
            nowNs = nowNs,
            metricsSnapshot = metricsSnapshot,
            inferenceSnapshot = inferenceSnapshot,
            handPresent = result.handPresent,
            landmarkCount = result.landmarks.size,
            landmarks = displayLandmarks,
        )
    }

    fun onAnalyzerError(error: Exception) {
        if (usesImuMotion() && !isHybridMode()) return

        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "Analyzer error", error)
        detectorErrors++
        lastDetectorError = message
        if (wasLeftPressed) {
            pinchUpCount++
            wasLeftPressed = false
        }
        val frozen = pointerMapper.update(
            landmarks = emptyList(),
            handPresent = false,
            nowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),
            motionPaused = false,
        )
        if (isHybridMode()) {
            latestHandFine.set(HandFineSample())
        }
        val command = applyTouchOverlay(frozen, System.nanoTime() / 1_000_000L)
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        val nowNs = System.nanoTime()
        val publishError =
            lastErrorPublishNs == 0L || nowNs - lastErrorPublishNs >= UI_PUBLISH_INTERVAL_NS
        if (publishError) {
            lastErrorPublishNs = nowNs
            _uiState.update {
                it.copy(
                    metrics = metrics.recordError(message),
                    detectorErrors = detectorErrors,
                    lastDetectorError = message,
                    handPresent = false,
                    landmarkCount = 0,
                    landmarks = emptyList(),
                    pointer = command,
                    pinchUpCount = pinchUpCount,
                    linkSequence = linkClient.lastSequence,
                    linkError = linkClient.lastError,
                )
            }
        } else {
            metrics.recordError(message)
        }
    }

    fun onTouchPadTap() {
        if (!outputEnabled) {
            enableOutput()
            return
        }
        val nowMs = System.nanoTime() / 1_000_000L
        touchLeftPressed = true
        touchClickReleaseAtMs = nowMs + TOUCH_CLICK_HOLD_MS
        val command = applyTouchOverlay(latestCommand.get(), nowMs)
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        updateClickCounters(command.leftPressed)
        _uiState.update { it.copy(pointer = command, pinchDownCount = pinchDownCount) }
    }

    fun onTouchPadLongPress() {
        if (outputEnabled) {
            if (usesImuMotion()) {
                headOrientationTracker.calibrate()
                imuPointerController.resetAnchor()
            }
            if (isHybridMode() || !usesImuMotion()) {
                pointerMapper.recenterAnchor()
            }
            if (!usesImuMotion()) {
                headJerkMonitor.reset()
            }
            _uiState.update {
                it.copy(
                    linkPersistHint = if (isHybridMode()) "IMU+手已重锚" else "头姿已重锚",
                    imuCalibrated = usesImuMotion(),
                    headYawDeg = 0f,
                    headPitchDeg = 0f,
                )
            }
        } else {
            persistLinkConfig()
        }
    }

    fun onTwoFingerSingleTap() {
        if (outputEnabled) {
            disableOutput()
        }
    }

    fun onTouchPadSwipe(forward: Boolean) {
        adjustLinkGain(increase = forward)
    }

    private fun applyRemoteLinkGain(gain: Float) {
        val clamped = LinkGain.clamp(gain)
        if (kotlin.math.abs(clamped - linkConfig.linkGain) < 0.001f) return
        linkConfig = linkConfig.copy(linkGain = clamped)
        linkClient.updateConfig(linkConfig)
        linkStore.saveLinkGain(clamped)
        _uiState.update {
            it.copy(
                linkGain = clamped,
                linkPersistHint = "灵敏度已同步(电脑): ${formatGain(clamped)}",
            )
        }
    }

    private fun adjustLinkGain(increase: Boolean) {
        val step = if (increase) LinkGain.STEP else -LinkGain.STEP
        val next = LinkGain.clamp(linkConfig.linkGain + step)
        linkConfig = linkConfig.copy(linkGain = next)
        linkClient.updateConfig(linkConfig)
        linkStore.saveLinkGain(next)
        linkClient.sendGainSync(next)
        _uiState.update {
            it.copy(
                linkGain = next,
                linkPersistHint = "灵敏度: ${formatGain(next)}",
            )
        }
    }

    private fun formatGain(gain: Float): String =
        String.format(Locale.US, "%.2f", gain)

    fun onSceneExited() {
        disableOutputAndRelease(sendRelease = true)
        if (usesImuMotion()) {
            headOrientationTracker.stop()
        } else {
            headJerkMonitor.stop()
        }
        pointerMapper.reset()
        wasLeftPressed = false
        touchLeftPressed = false
        _uiState.update {
            it.copy(
                outputEnabled = false,
                cameraReady = false,
                handPresent = false,
                landmarkCount = 0,
                landmarks = emptyList(),
                pointer = idlePointer(outputEnabled = false),
                linkSequence = linkClient.lastSequence,
                linkError = linkClient.lastError,
            )
        }
    }

    override fun onCleared() {
        disableOutputAndRelease(sendRelease = true)
        headOrientationTracker.stop()
        headJerkMonitor.stop()
        linkClient.close()
        motionExecutor.shutdownNow()
        super.onCleared()
    }

    private fun enableOutput() {
        outputEnabled = true
        if (usesImuMotion()) {
            headOrientationTracker.calibrate()
            imuPointerController.resetAnchor()
        }
        if (isHybridMode() || !usesImuMotion()) {
            pointerMapper.setOutputEnabled(true)
            if (isHybridMode()) {
                pointerMapper.recenterAnchor()
            }
        } else {
            pointerMapper.setOutputEnabled(false)
        }

        val gesture = when {
            usesImuMotion() -> PointerGesture.TRACKING
            _uiState.value.handPresent -> PointerGesture.TRACKING
            else -> PointerGesture.LOST
        }
        val handOk = (!isHybridMode() && usesImuMotion()) ||
            (isHybridMode() && _uiState.value.handPresent)
        val command = _uiState.value.pointer.copy(
            outputEnabled = true,
            dx = 0f,
            dy = 0f,
            leftPressed = false,
            gesture = gesture,
            handOk = handOk,
        )
        latestCommand.set(command)
        latestHandFine.set(HandFineSample())
        startMotionLoop()
        publishPointerToLink(command, heartbeat = false)
        if (linkConfig.host.isNotBlank()) {
            linkClient.sendGainSync(linkConfig.linkGain)
        }
        _uiState.update {
            it.copy(
                outputEnabled = true,
                imuCalibrated = usesImuMotion(),
                headYawDeg = 0f,
                headPitchDeg = 0f,
                pointer = command,
                linkSequence = linkClient.lastSequence,
                linkError = linkClient.lastError,
            )
        }
    }

    private fun disableOutput() {
        outputEnabled = false
        pointerMapper.setOutputEnabled(false)
        if (wasLeftPressed || touchLeftPressed) {
            pinchUpCount++
            wasLeftPressed = false
            touchLeftPressed = false
        }
        stopMotionLoop()
        latestHandFine.set(HandFineSample())
        val command = _uiState.value.pointer.copy(
            outputEnabled = false,
            dx = 0f,
            dy = 0f,
            leftPressed = false,
            wheelDelta = 0,
            gesture = PointerGesture.IDLE,
        )
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        _uiState.update {
            it.copy(
                outputEnabled = false,
                handPresent = false,
                landmarkCount = 0,
                landmarks = emptyList(),
                cameraStatus = if (isHybridMode() && usesCameraForPointer()) {
                    "相机休眠(离合关)"
                } else {
                    it.cameraStatus
                },
                pointer = command,
                pinchUpCount = pinchUpCount,
                linkSequence = linkClient.lastSequence,
                linkError = linkClient.lastError,
            )
        }
    }

    private fun imuTick() {
        if (!outputEnabled || !usesImuMotion()) return

        val pose = headOrientationTracker.snapshotPointerPose()
        val (imuDx, imuDy) = imuPointerController.update(pose)
        val hand = latestHandFine.get()
        val (dx, dy) = if (isHybridMode()) {
            HybridPointerBlender.blend(
                imuDx = imuDx,
                imuDy = imuDy,
                handDx = hand.dx,
                handDy = hand.dy,
                handOk = hand.handOk,
            )
        } else {
            imuDx to imuDy
        }
        val handOk = if (isHybridMode()) hand.handOk else true
        val nowMs = System.nanoTime() / 1_000_000L
        val base = PointerCommand(
            outputEnabled = true,
            handOk = handOk,
            dx = dx,
            dy = dy,
            leftPressed = false,
            gesture = PointerGesture.TRACKING,
            poseValid = pose.isCalibrated,
        )
        val command = applyTouchOverlay(base, nowMs)
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        updateClickCounters(command.leftPressed)

        cursorNormX = (cursorNormX + command.dx / CURSOR_REF_WIDTH_PX).coerceIn(0f, 1f)
        cursorNormY = (cursorNormY + command.dy / CURSOR_REF_HEIGHT_PX).coerceIn(0f, 1f)

        val nowNs = System.nanoTime()
        val publishUi = lastPublishNs == 0L || nowNs - lastPublishNs >= UI_PUBLISH_INTERVAL_NS
        if (publishUi) {
            lastPublishNs = nowNs
            _uiState.update {
                it.copy(
                    outputEnabled = true,
                    imuCalibrated = pose.isCalibrated,
                    headYawDeg = pose.deltaYawDeg,
                    headPitchDeg = pose.deltaPitchDeg,
                    handPresent = if (isHybridMode()) hand.handOk else it.handPresent,
                    pointer = command,
                    cursorNormX = cursorNormX,
                    cursorNormY = cursorNormY,
                    pinchDownCount = pinchDownCount,
                    pinchUpCount = pinchUpCount,
                    linkSequence = linkClient.lastSequence,
                    linkError = linkClient.lastError,
                )
            }
        }
    }

    private fun publishHandCommand(
        command: PointerCommand,
        nowNs: Long,
        metricsSnapshot: AnalysisMetricsSnapshot,
        inferenceSnapshot: InferenceLatencySnapshot,
        handPresent: Boolean,
        landmarkCount: Int,
        landmarks: List<HandLandmark>,
    ) {
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        updateClickCounters(command.leftPressed)

        if (command.outputEnabled && command.handOk) {
            cursorNormX = (cursorNormX + command.dx / CURSOR_REF_WIDTH_PX).coerceIn(0f, 1f)
            cursorNormY = (cursorNormY + command.dy / CURSOR_REF_HEIGHT_PX).coerceIn(0f, 1f)
        }

        val publishMetrics =
            lastPublishNs == 0L || nowNs - lastPublishNs >= UI_PUBLISH_INTERVAL_NS
        if (publishMetrics) {
            lastPublishNs = nowNs
        }
        lastDetectorError = null
        _uiState.update {
            it.copy(
                handPresent = handPresent,
                landmarkCount = landmarkCount,
                landmarks = landmarks,
                metrics = if (publishMetrics) metricsSnapshot else it.metrics,
                inference = if (publishMetrics) inferenceSnapshot else it.inference,
                detectorErrors = detectorErrors,
                lastDetectorError = null,
                outputEnabled = command.outputEnabled,
                pointer = command,
                cursorNormX = cursorNormX,
                cursorNormY = cursorNormY,
                pinchDownCount = pinchDownCount,
                pinchUpCount = pinchUpCount,
                linkSequence = linkClient.lastSequence,
                linkError = linkClient.lastError,
            )
        }
    }

    private fun applyTouchOverlay(base: PointerCommand, nowMs: Long): PointerCommand {
        if (touchLeftPressed && nowMs >= touchClickReleaseAtMs) {
            touchLeftPressed = false
        }
        val leftPressed = base.leftPressed || touchLeftPressed
        val wheelDelta = pendingWheelDelta
        return base.copy(leftPressed = leftPressed, wheelDelta = wheelDelta)
    }

    private fun updateClickCounters(leftPressed: Boolean) {
        if (leftPressed && !wasLeftPressed) {
            pinchDownCount++
        } else if (!leftPressed && wasLeftPressed) {
            pinchUpCount++
        }
        wasLeftPressed = leftPressed
    }

    private fun disableOutputAndRelease(sendRelease: Boolean) {
        outputEnabled = false
        pointerMapper.setOutputEnabled(false)
        stopMotionLoop()
        val release = idlePointer(outputEnabled = false)
        latestCommand.set(release)
        if (sendRelease) {
            forceLinkRelease()
        }
    }

    private fun publishPointerToLink(command: PointerCommand, heartbeat: Boolean) {
        if (linkConfig.host.isBlank()) return
        if (command.outputEnabled) {
            linkOutputArmed = true
            linkClient.send(command, heartbeat = heartbeat)
            return
        }
        if (linkOutputArmed || heartbeat) {
            linkOutputArmed = false
            linkClient.send(
                command.copy(dx = 0f, dy = 0f, leftPressed = false, outputEnabled = false),
                heartbeat = false,
            )
        }
    }

    private fun forceLinkRelease() {
        if (linkConfig.host.isBlank()) return
        linkOutputArmed = false
        linkClient.send(
            idlePointer(outputEnabled = false),
            heartbeat = false,
        )
    }

    private fun startMotionLoop() {
        stopMotionLoop()
        if (usesImuMotion()) {
            imuTickFuture = motionExecutor.scheduleAtFixedRate(
                { imuTick() },
                0L,
                IMU_TICK_PERIOD_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        heartbeatFuture = motionExecutor.scheduleAtFixedRate(
            {
                val command = latestCommand.get()
                if (command.outputEnabled) {
                    linkClient.send(
                        command.copy(dx = 0f, dy = 0f, wheelDelta = 0),
                        heartbeat = true,
                    )
                }
            },
            HEARTBEAT_PERIOD_MS,
            HEARTBEAT_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopMotionLoop() {
        imuTickFuture?.cancel(false)
        imuTickFuture = null
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    private fun idlePointer(outputEnabled: Boolean) = PointerCommand(
        outputEnabled = outputEnabled,
        handOk = usesImuMotion() && outputEnabled && !isHybridMode(),
        dx = 0f,
        dy = 0f,
        leftPressed = false,
        gesture = if (outputEnabled) {
            if (usesImuMotion()) PointerGesture.TRACKING else PointerGesture.LOST
        } else {
            PointerGesture.IDLE
        },
    )

    companion object {
        private const val TAG = "HandMouseAnalysis"
        private const val UI_PUBLISH_INTERVAL_NS = 250_000_000L
        private const val HEARTBEAT_PERIOD_MS = 50L
        private const val IMU_TICK_PERIOD_MS = 16L
        private const val TOUCH_CLICK_HOLD_MS = 80L
        private const val CURSOR_REF_WIDTH_PX = 480f
        private const val CURSOR_REF_HEIGHT_PX = 640f
    }
}
