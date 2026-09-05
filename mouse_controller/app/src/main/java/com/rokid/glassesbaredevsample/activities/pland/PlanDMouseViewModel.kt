package com.rokid.glassesbaredevsample.activities.pland

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.rokid.glassesbaredevsample.camera.AnalysisMetrics
import com.rokid.glassesbaredevsample.camera.AnalysisMetricsSnapshot
import com.rokid.glassesbaredevsample.hand.ControlScheme
import com.rokid.glassesbaredevsample.hand.HandDisplayTransform
import com.rokid.glassesbaredevsample.hand.HandFrameResult
import com.rokid.glassesbaredevsample.hand.HandLandmark
import com.rokid.glassesbaredevsample.hand.PlanDMouseConfig
import com.rokid.glassesbaredevsample.hand.InferenceLatencySnapshot
import com.rokid.glassesbaredevsample.hand.InferenceLatencyTracker
import com.rokid.glassesbaredevsample.hand.PointerCommand
import com.rokid.glassesbaredevsample.hand.PointerGesture
import com.rokid.glassesbaredevsample.hand.PointerMapper
import com.rokid.glassesbaredevsample.link.LinkGain
import com.rokid.glassesbaredevsample.link.MouseLinkConfig
import com.rokid.glassesbaredevsample.link.MouseLinkClient
import com.rokid.glassesbaredevsample.link.MouseLinkStore
import com.rokid.glassesbaredevsample.link.UdpFrameLinkClient
import com.rokid.glassesbaredevsample.link.UdpLandmarkFeedbackReceiver
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class PlanDMouseUiState(
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
    val postureFreezeActive: Boolean = false,
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
    val linkGain: Float = LinkGain.PLAN_D,
    val precisionModeActive: Boolean = false,
    val laptopHandPresent: Boolean = false,
    val leftHoldActive: Boolean = false,
)

private data class HandFineSample(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val handOk: Boolean = false,
)

private data class LatestCameraFrame(
    val jpeg: ByteArray,
    val rotationDeg: Int,
    val width: Int,
    val height: Int,
    val tMs: Long,
    val captureSeq: Long,
)

/**
 * Plan D view-model: glasses stream JPEG + control; laptop runs MediaPipe.
 *
 * TouchPad controls (RG glasses · KeyEvent path):
 * - **单击** — clutch off → on; clutch on + 后滑按住中 → 释放左键; 否则 → 左键单击
 * - **前滑** — 切换精细/正常模式（需 clutch on）
 * - **后滑** — 左键按住（需 clutch on）
 * - **长按** — clutch off → 保存链路; clutch on → 手姿调整(光标居中)
 * - **松手** — 结束手姿调整
 * - **双击** — 退出应用
 *
 * 灵敏度固定 **2.0**（满档），每次启用 clutch 时同步到电脑。
 */
class PlanDMouseViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val mouseConfig: PlanDMouseConfig = PlanDMouseConfig.Default
    private val linkClient: MouseLinkClient = UdpMouseLinkClient()
    private val frameLinkClient = UdpFrameLinkClient()
    private val linkStore = MouseLinkStore(application)
    private val metrics = AnalysisMetrics()
    private val inferenceLatency = InferenceLatencyTracker()
    private val pointerMapper = PointerMapper(mouseConfig)
    private val headOrientationTracker = HeadOrientationTracker(application)
    private val imuPointerController = ImuPointerController(mouseConfig)
    private val headJerkMonitor = HeadJerkMonitor(application)
    private val _uiState = MutableStateFlow(PlanDMouseUiState())
    val uiState: StateFlow<PlanDMouseUiState> = _uiState.asStateFlow()

    private var lastPublishNs = 0L
    private var lastErrorPublishNs = 0L
    private var detectorErrors = 0L
    private var lastDetectorError: String? = null
    private var cursorNormX = 0.5f
    private var cursorNormY = 0.5f
    private var pinchDownCount = 0L
    private var pinchUpCount = 0L
    private var wasLeftPressed = false
    private val touchLock = Any()
    private var touchLeftPressed = false
    private var leftHoldLatched = false
    private var touchClickReleaseAtMs = 0L
    private var ignoreTapUntilMs = 0L
    private var pendingWheelDelta = 0
    @Volatile private var outputEnabled = false
    @Volatile private var postureFreezeActive = false
    private var linkConfig = MouseLinkConfig()
    private val latestCommand = AtomicReference(idlePointer(false))
    private val latestHandFine = AtomicReference(HandFineSample())
    private val latestCameraFrame = AtomicReference<LatestCameraFrame?>(null)
    private val cameraCaptureSeq = AtomicLong(0L)
    private var lastSentCaptureSeq = -1L
    @Volatile private var pendingRecenter = false
    @Volatile private var pendingCenterCursor = false
    @Volatile private var precisionModeActive = false
    private var framesSent = 0L
    private var feedbackSequence = 0L
    private var landmarkFeedbackReceiver: UdpLandmarkFeedbackReceiver? = null
    private var linkOutputArmed = false
    private val motionExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "MouseMotionLoop").apply { isDaemon = true }
    }
    private var imuTickFuture: ScheduledFuture<*>? = null
    private var laptopTickFuture: ScheduledFuture<*>? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null

    init {
        linkClient.onRemoteGainChanged = { gain ->
            if (!usesLaptopInference()) {
                applyRemoteLinkGain(gain)
            }
        }
    }

    fun usesCameraForPointer(): Boolean = mouseConfig.usesCameraForPointer()

    fun shouldBindCamera(): Boolean =
        usesCameraForPointer() &&
            (!mouseConfig.cameraOnlyWhenClutchOn || outputEnabled) &&
            !postureFreezeActive

    fun cameraProcessEveryNFrames(): Int = mouseConfig.cameraProcessEveryNFrames

    fun isHybridMode(): Boolean =
        mouseConfig.controlScheme == ControlScheme.HYBRID_IMU_HAND

    fun usesImuMotion(): Boolean = mouseConfig.usesImuMotion()

    fun usesLaptopInference(): Boolean = mouseConfig.usesLaptopInference()

    fun usesOnDeviceMediaPipe(): Boolean = mouseConfig.usesOnDeviceMediaPipe()

    fun applyLinkConfig(config: MouseLinkConfig) {
        linkConfig = config
        linkClient.updateConfig(config)
        frameLinkClient.updateConfig(config)
        restartLandmarkFeedbackReceiver()
        if (usesLaptopInference()) {
            applyPlanDGain()
        }
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
        postureFreezeActive = false
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
        synchronized(touchLock) {
            touchLeftPressed = false
            leftHoldLatched = false
            touchClickReleaseAtMs = 0L
            ignoreTapUntilMs = 0L
        }
        pendingWheelDelta = 0
        linkOutputArmed = false
        latestCommand.set(idlePointer(false))
        latestHandFine.set(HandFineSample())
        latestCameraFrame.set(null)
        pendingRecenter = false
        pendingCenterCursor = false
        precisionModeActive = false
        framesSent = 0L
        feedbackSequence = 0L
        cameraCaptureSeq.set(0L)

        if (usesLaptopInference()) {
            restartLandmarkFeedbackReceiver()
            applyPlanDGain()
        }

        lastSentCaptureSeq = -1L

        imuPointerController.resetAnchor()

        if (usesImuMotion()) {
            headOrientationTracker.start()
            imuPointerController.resetAnchor()
        } else if (!usesLaptopInference()) {
            headJerkMonitor.start()
        }

        _uiState.value = PlanDMouseUiState(
            usesCameraForPointer = usesCameraForPointer(),
            imuReady = usesImuMotion() && headOrientationTracker.available.value,
            cameraStatus = when {
                usesLaptopInference() -> "方案D · 相机流→电脑"
                isHybridMode() -> "方案C · 相机+IMU"
                usesCameraForPointer() -> "相机准备中"
                else -> "方案B · 无需相机"
            },
            detectorStatus = when {
                usesLaptopInference() -> "电脑端 MediaPipe (30Hz)"
                isHybridMode() -> "MediaPipe 精细层 + IMU"
                usesOnDeviceMediaPipe() -> "MediaPipe 未初始化"
                else -> "IMU 头指针"
            },
            detectorReady = usesLaptopInference(),
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
        if (usesLaptopInference()) {
            latestCameraFrame.set(null)
            if (!outputEnabled) {
                // keep clutch state
            } else if (!postureFreezeActive) {
                disableOutputAndRelease(sendRelease = true)
            }
        } else if (usesCameraForPointer() && !usesImuMotion()) {
            disableOutputAndRelease(sendRelease = true)
        } else if (isHybridMode()) {
            latestHandFine.set(HandFineSample())
        }
        _uiState.update {
            it.copy(
                cameraStatus = when {
                    postureFreezeActive -> "手姿调整 · 相机休眠"
                    (usesLaptopInference() || isHybridMode()) && !outputEnabled ->
                        "相机休眠(离合关)"
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

    fun onCameraFrame(
        nowNs: Long,
        encodeDurationNs: Long,
        jpeg: ByteArray,
        rotationDeg: Int,
        width: Int,
        height: Int,
        tMs: Long,
    ) {
        if (!usesLaptopInference() || postureFreezeActive) return
        val metricsSnapshot = metrics.recordAnalyzed(nowNs, encodeDurationNs)
        val captureSeq = cameraCaptureSeq.incrementAndGet()
        latestCameraFrame.set(
            LatestCameraFrame(
                jpeg = jpeg,
                rotationDeg = rotationDeg,
                width = width,
                height = height,
                tMs = tMs,
                captureSeq = captureSeq,
            ),
        )
        val publishUi = lastPublishNs == 0L || nowNs - lastPublishNs >= UI_PUBLISH_INTERVAL_NS
        if (publishUi) {
            lastPublishNs = nowNs
            _uiState.update {
                it.copy(
                    metrics = metricsSnapshot,
                    detectorStatus = "电脑端 MediaPipe (30Hz) · 帧发送 $framesSent",
                )
            }
        }
    }

    fun onHandFrame(nowNs: Long, analysisDurationNs: Long, result: HandFrameResult) {
        if (usesLaptopInference()) return
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
        if (usesImuMotion() && !isHybridMode() && !usesLaptopInference()) return

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
        if (usesLaptopInference()) {
            latestCameraFrame.set(null)
        }
        val nowMs = System.nanoTime() / 1_000_000L
        val command = if (usesLaptopInference()) {
            applyTouchOverlay(buildLaptopControlCommand(nowMs), nowMs)
        } else {
            applyTouchOverlay(frozen, nowMs)
        }
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

    private fun buildLaptopControlCommand(nowMs: Long): PointerCommand {
        val motionPaused = if (usesLaptopInference()) {
            postureFreezeActive
        } else {
            postureFreezeActive || headJerkMonitor.isPaused(nowMs)
        }
        val recenter = pendingRecenter
        if (pendingRecenter) {
            pendingRecenter = false
        }
        val centerCursor = pendingCenterCursor
        if (pendingCenterCursor) {
            pendingCenterCursor = false
        }
        return PointerCommand(
            outputEnabled = outputEnabled,
            handOk = false,
            dx = 0f,
            dy = 0f,
            leftPressed = false,
            gesture = when {
                !outputEnabled -> PointerGesture.IDLE
                postureFreezeActive -> PointerGesture.PAUSED
                motionPaused -> PointerGesture.PAUSED
                else -> PointerGesture.TRACKING
            },
            recenter = recenter,
            centerCursor = centerCursor,
            motionPaused = motionPaused,
            precisionMode = precisionModeActive,
        )
    }

    private fun restartLandmarkFeedbackReceiver() {
        landmarkFeedbackReceiver?.close()
        landmarkFeedbackReceiver = null
        if (!usesLaptopInference()) return
        landmarkFeedbackReceiver = UdpLandmarkFeedbackReceiver(
            port = linkConfig.feedbackPort,
            token = linkConfig.token,
            onFeedback = { feedback -> onLandmarkFeedback(feedback) },
        ).also { it.start() }
    }

    private fun onLandmarkFeedback(feedback: com.rokid.glassesbaredevsample.link.LandmarkFeedbackPacket.Decoded) {
        if (feedback.sequence == feedbackSequence) return
        feedbackSequence = feedback.sequence
        val nowNs = System.nanoTime()
        if (lastPublishNs != 0L && nowNs - lastPublishNs < UI_PUBLISH_INTERVAL_NS / 2) {
            return
        }
        lastPublishNs = nowNs
        _uiState.update {
            it.copy(
                handPresent = feedback.handPresent,
                laptopHandPresent = feedback.handPresent,
                landmarkCount = if (feedback.handPresent) feedback.landmarks.size else 0,
                landmarks = if (feedback.handPresent) feedback.landmarks else emptyList(),
                precisionModeActive = feedback.precisionActive || precisionModeActive,
                detectorStatus = when {
                    feedback.handPresent && feedback.precisionActive -> "电脑端 · 手已识别 · 精细"
                    feedback.handPresent -> "电脑端 · 手已识别"
                    outputEnabled -> "电脑端 · 未检测到手"
                    else -> it.detectorStatus
                },
            )
        }
    }

    private fun laptopTick() {
        if (!usesLaptopInference()) return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val frame = latestCameraFrame.get()
        if (outputEnabled && frame != null && linkConfig.host.isNotBlank()) {
            if (frame.captureSeq != lastSentCaptureSeq) {
                frameLinkClient.sendFrame(
                    jpeg = frame.jpeg,
                    tMs = frame.tMs,
                    rotationDeg = frame.rotationDeg,
                    width = frame.width,
                    height = frame.height,
                    outputEnabled = true,
                )
                lastSentCaptureSeq = frame.captureSeq
                framesSent++
            }
        }
        val command = synchronized(touchLock) {
            applyTouchOverlayLocked(buildLaptopControlCommand(nowMs), nowMs)
        }
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        updateClickCounters(command.leftPressed)
        val nowNs = System.nanoTime()
        val publishUi = lastPublishNs == 0L || nowNs - lastPublishNs >= UI_PUBLISH_INTERVAL_NS
        if (publishUi) {
            lastPublishNs = nowNs
            _uiState.update {
                it.copy(
                    outputEnabled = outputEnabled,
                    pointer = command,
                    pinchDownCount = pinchDownCount,
                    pinchUpCount = pinchUpCount,
                    linkSequence = linkClient.lastSequence,
                    linkError = linkClient.lastError ?: frameLinkClient.lastError,
                    detectorStatus = "电脑端 MediaPipe (30Hz) · 帧发送 $framesSent",
                )
            }
        }
    }

    fun onTouchPadTap() {
        if (!outputEnabled) {
            enableOutput()
            return
        }
        val nowMs = android.os.SystemClock.elapsedRealtime()
        synchronized(touchLock) {
            if (nowMs < ignoreTapUntilMs) {
                return
            }
            if (leftHoldLatched) {
                releaseLeftHoldLocked(nowMs)
                return
            }
            touchLeftPressed = true
            leftHoldLatched = false
            touchClickReleaseAtMs = nowMs + TOUCH_CLICK_HOLD_MS
            val command = applyTouchOverlayLocked(latestCommand.get(), nowMs)
            latestCommand.set(command)
            publishPointerToLink(command, heartbeat = false)
            updateClickCounters(command.leftPressed)
            _uiState.update { it.copy(pointer = command, pinchDownCount = pinchDownCount) }
        }
    }

    fun onTouchPadLongPress() {
        if (postureFreezeActive) {
            exitPostureFreeze()
            return
        }
        if (outputEnabled) {
            if (usesLaptopInference()) {
                enterPostureFreeze()
                return
            }
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
                    linkPersistHint = when {
                        isHybridMode() -> "IMU+手已重锚"
                        else -> "头姿已重锚"
                    },
                    imuCalibrated = usesImuMotion(),
                    headYawDeg = 0f,
                    headPitchDeg = 0f,
                )
            }
        } else {
            persistLinkConfig()
        }
    }

    fun onTouchPadLongPressRelease() {
        if (postureFreezeActive) {
            exitPostureFreeze()
        }
    }

    private fun enterPostureFreeze() {
        if (!outputEnabled || !usesLaptopInference() || postureFreezeActive) return
        if (leftHoldLatched) {
            releaseLeftHold()
        }
        postureFreezeActive = true
        pendingCenterCursor = true
        latestCameraFrame.set(null)
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val command = applyTouchOverlay(buildLaptopControlCommand(nowMs), nowMs)
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        _uiState.update {
            it.copy(
                postureFreezeActive = true,
                cameraStatus = "手姿调整 · 相机休眠",
                linkPersistHint = "光标已居中 · 调整手姿 · 松手继续",
                pointer = command,
                linkSequence = linkClient.lastSequence,
                linkError = linkClient.lastError,
            )
        }
    }

    private fun exitPostureFreeze() {
        if (!postureFreezeActive) return
        postureFreezeActive = false
        pendingRecenter = true
        latestCameraFrame.set(null)
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val command = applyTouchOverlay(buildLaptopControlCommand(nowMs), nowMs)
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        _uiState.update {
            it.copy(
                postureFreezeActive = false,
                cameraStatus = if (outputEnabled) "方案D · 相机流→电脑" else it.cameraStatus,
                linkPersistHint = "手姿已更新 · 继续控制",
                pointer = command,
                linkSequence = linkClient.lastSequence,
                linkError = linkClient.lastError,
            )
        }
    }

    fun onTouchPadSwipe(forward: Boolean) {
        if (!outputEnabled || postureFreezeActive) return
        if (forward) {
            togglePrecisionMode()
        } else {
            startLeftHold()
        }
    }

    private fun togglePrecisionMode() {
        precisionModeActive = !precisionModeActive
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val command = applyTouchOverlay(buildLaptopControlCommand(nowMs), nowMs)
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        _uiState.update {
            it.copy(
                precisionModeActive = precisionModeActive,
                linkPersistHint = if (precisionModeActive) {
                    "精细模式 · 前滑切回正常"
                } else {
                    "正常模式 · 前滑切精细"
                },
                pointer = command,
                linkSequence = linkClient.lastSequence,
            )
        }
    }

    private fun startLeftHold() {
        synchronized(touchLock) {
            if (leftHoldLatched) return
            val nowMs = android.os.SystemClock.elapsedRealtime()
            leftHoldLatched = true
            touchLeftPressed = true
            ignoreTapUntilMs = nowMs + SWIPE_HOLD_TAP_IGNORE_MS
            val command = applyTouchOverlayLocked(buildLaptopControlCommand(nowMs), nowMs)
            latestCommand.set(command)
            publishPointerToLink(command, heartbeat = false)
            updateClickCounters(command.leftPressed)
            _uiState.update {
                it.copy(
                    pointer = command,
                    pinchDownCount = pinchDownCount,
                    leftHoldActive = true,
                    linkPersistHint = "左键按住 · 单击释放",
                )
            }
        }
    }

    private fun releaseLeftHold() {
        synchronized(touchLock) {
            releaseLeftHoldLocked(android.os.SystemClock.elapsedRealtime())
        }
    }

    private fun releaseLeftHoldLocked(nowMs: Long) {
        if (!leftHoldLatched && !touchLeftPressed) return
        leftHoldLatched = false
        touchLeftPressed = false
        val command = applyTouchOverlayLocked(buildLaptopControlCommand(nowMs), nowMs)
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        updateClickCounters(command.leftPressed)
        _uiState.update {
            it.copy(
                pointer = command,
                pinchUpCount = pinchUpCount,
                leftHoldActive = false,
                linkPersistHint = "左键已释放",
            )
        }
    }

    private fun applyPlanDGain() {
        val gain = LinkGain.PLAN_D
        linkConfig = linkConfig.copy(linkGain = gain)
        linkClient.updateConfig(linkConfig)
        linkStore.saveLinkGain(gain)
        if (linkConfig.host.isNotBlank()) {
            linkClient.sendGainSync(gain)
        }
        _uiState.update { it.copy(linkGain = gain) }
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

    fun onTwoFingerSingleTap() {
        // Reserved — Plan D uses swipe-forward for precision; two-finger not used on RG TouchPad.
    }

    private fun formatGain(gain: Float): String =
        String.format(Locale.US, "%.2f", gain)

    fun onSceneExited() {
        disableOutputAndRelease(sendRelease = true)
        if (usesImuMotion()) {
            headOrientationTracker.stop()
        } else if (!usesLaptopInference()) {
            headJerkMonitor.stop()
        }
        pointerMapper.reset()
        wasLeftPressed = false
        synchronized(touchLock) {
            touchLeftPressed = false
            leftHoldLatched = false
        }
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
        frameLinkClient.close()
        landmarkFeedbackReceiver?.close()
        landmarkFeedbackReceiver = null
        motionExecutor.shutdownNow()
        super.onCleared()
    }

    private fun enableOutput() {
        outputEnabled = true
        if (usesImuMotion()) {
            headOrientationTracker.calibrate()
            imuPointerController.resetAnchor()
        }
        if (isHybridMode() || (!usesImuMotion() && !usesLaptopInference())) {
            pointerMapper.setOutputEnabled(true)
            if (isHybridMode()) {
                pointerMapper.recenterAnchor()
            }
        } else if (!usesLaptopInference()) {
            pointerMapper.setOutputEnabled(false)
        }

        val gesture = when {
            usesImuMotion() -> PointerGesture.TRACKING
            usesLaptopInference() -> PointerGesture.TRACKING
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
        latestCameraFrame.set(null)
        framesSent = 0L
        lastSentCaptureSeq = -1L
        pendingRecenter = true
        startMotionLoop()
        publishPointerToLink(command, heartbeat = false)
        applyPlanDGain()
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
        postureFreezeActive = false
        precisionModeActive = false
        synchronized(touchLock) {
            leftHoldLatched = false
            if (wasLeftPressed || touchLeftPressed) {
                pinchUpCount++
                wasLeftPressed = false
                touchLeftPressed = false
            }
        }
        pointerMapper.setOutputEnabled(false)
        stopMotionLoop()
        latestHandFine.set(HandFineSample())
        latestCameraFrame.set(null)
        val command = _uiState.value.pointer.copy(
            outputEnabled = false,
            dx = 0f,
            dy = 0f,
            leftPressed = false,
            wheelDelta = 0,
            gesture = PointerGesture.IDLE,
            recenter = false,
            motionPaused = false,
            precisionMode = false,
        )
        latestCommand.set(command)
        publishPointerToLink(command, heartbeat = false)
        _uiState.update {
            it.copy(
                outputEnabled = false,
                postureFreezeActive = false,
                precisionModeActive = false,
                leftHoldActive = false,
                handPresent = false,
                laptopHandPresent = false,
                landmarkCount = 0,
                landmarks = emptyList(),
                cameraStatus = if ((usesLaptopInference() || isHybridMode()) && usesCameraForPointer()) {
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

    private fun applyTouchOverlay(base: PointerCommand, nowMs: Long): PointerCommand =
        synchronized(touchLock) {
            applyTouchOverlayLocked(base, nowMs)
        }

    private fun applyTouchOverlayLocked(base: PointerCommand, nowMs: Long): PointerCommand {
        if (touchLeftPressed && !leftHoldLatched && nowMs >= touchClickReleaseAtMs) {
            touchLeftPressed = false
        }
        val leftPressed = base.leftPressed || touchLeftPressed || leftHoldLatched
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
        postureFreezeActive = false
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
        if (usesLaptopInference()) {
            laptopTickFuture = motionExecutor.scheduleAtFixedRate(
                { laptopTick() },
                0L,
                LAPTOP_TICK_PERIOD_MS,
                TimeUnit.MILLISECONDS,
            )
        } else if (usesImuMotion()) {
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
                if (!command.outputEnabled) return@scheduleAtFixedRate
                val holdLeft = synchronized(touchLock) { leftHoldLatched }
                val withHold = if (holdLeft) command.copy(leftPressed = true) else command
                linkClient.send(
                    withHold.copy(dx = 0f, dy = 0f, wheelDelta = 0, recenter = false),
                    heartbeat = true,
                )
            },
            HEARTBEAT_PERIOD_MS,
            HEARTBEAT_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopMotionLoop() {
        imuTickFuture?.cancel(false)
        imuTickFuture = null
        laptopTickFuture?.cancel(false)
        laptopTickFuture = null
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
        private const val TAG = "PlanDMouse"
        private const val UI_PUBLISH_INTERVAL_NS = 250_000_000L
        private const val HEARTBEAT_PERIOD_MS = 50L
        private const val IMU_TICK_PERIOD_MS = 16L
        private const val LAPTOP_TICK_PERIOD_MS = 33L
        private const val TOUCH_CLICK_HOLD_MS = 80L
        private const val SWIPE_HOLD_TAP_IGNORE_MS = 350L
        private const val CURSOR_REF_WIDTH_PX = 480f
        private const val CURSOR_REF_HEIGHT_PX = 640f
    }
}
