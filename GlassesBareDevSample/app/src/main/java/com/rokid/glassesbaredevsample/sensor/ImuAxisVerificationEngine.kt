package com.rokid.glassesbaredevsample.sensor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 引导用户依次完成转头 / 点头 / 歪头，用陀螺仪能量判定各动作对应物理轴。
 */
class ImuAxisVerificationEngine {

    private val _uiState = MutableStateFlow(ImuVerificationUiState())
    val uiState: StateFlow<ImuVerificationUiState> = _uiState.asStateFlow()

    private var running = false
    private var stepIndex = 0
    private var stepStartedAtMs = 0L
    private var lastSampleNs = 0L

    private var motionSeen = false
    private var idleSinceMs = 0L

    private val energy = FloatArray(3)
    private val peakSigned = FloatArray(3)
    private val completedBindings = mutableListOf<MotionAxisBinding>()

    fun start() {
        running = true
        stepIndex = 0
        completedBindings.clear()
        resetStepAccumulators()
        stepStartedAtMs = System.currentTimeMillis()
        publishStep(waiting = true)
    }

    fun cancel() {
        running = false
        _uiState.value = ImuVerificationUiState()
    }

    fun retryCurrentStep() {
        if (!running) return
        resetStepAccumulators()
        stepStartedAtMs = System.currentTimeMillis()
        publishStep(waiting = true)
    }

    fun onGyroSample(gx: Float, gy: Float, gz: Float, timestampNs: Long) {
        if (!running || stepIndex >= ImuMotionKind.entries.size) return

        val dt = if (lastSampleNs == 0L) 0f
        else ((timestampNs - lastSampleNs) * 1e-9f).coerceIn(0f, 0.1f)
        lastSampleNs = timestampNs

        val samples = floatArrayOf(gx, gy, gz)
        val magnitude = samples.maxOf { abs(it) }
        val now = System.currentTimeMillis()

        if (magnitude >= MOTION_START_RAD_S) {
            motionSeen = true
            idleSinceMs = 0L
            _uiState.value = _uiState.value.copy(statusMessage = "检测中…")
        } else if (motionSeen && magnitude < MOTION_END_RAD_S) {
            if (idleSinceMs == 0L) idleSinceMs = now
        } else if (!motionSeen) {
            _uiState.value = _uiState.value.copy(statusMessage = "等待动作…")
        }

        if (dt > 0f && motionSeen) {
            for (i in 0..2) {
                val v = samples[i]
                energy[i] += abs(v) * dt
                if (abs(v) > abs(peakSigned[i])) peakSigned[i] = v
            }
        }

        val timedOut = now - stepStartedAtMs > STEP_TIMEOUT_MS
        val stepComplete = motionSeen && idleSinceMs > 0L &&
            now - idleSinceMs >= IDLE_COMPLETE_MS

        if (stepComplete || timedOut) {
            if (motionSeen && energy.max() > MIN_ENERGY) {
                finishCurrentStep()
            } else if (timedOut) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "未检测到动作，请点击重试",
                    canRetry = true,
                )
            }
        }
    }

    private fun finishCurrentStep() {
        val motion = ImuMotionKind.entries[stepIndex]
        val dominant = energy.indices.maxByOrNull { energy[it] } ?: 2
        val sign = when {
            peakSigned[dominant] > 0f -> 1
            peakSigned[dominant] < 0f -> -1
            else -> 1
        }
        completedBindings.add(
            MotionAxisBinding(
                motion = motion,
                axis = GyroAxis.fromIndex(dominant),
                sign = sign,
                peakRadPerSec = abs(peakSigned[dominant]),
            ),
        )
        stepIndex++
        if (stepIndex >= ImuMotionKind.entries.size) {
            completeAll()
        } else {
            resetStepAccumulators()
            stepStartedAtMs = System.currentTimeMillis()
            publishStep(waiting = true)
        }
    }

    private fun completeAll() {
        running = false
        val result = ImuAxisCalibrationResult(completedBindings.toList())
        GlassesAxisRemapper.applyCalibration(result)
        _uiState.value = ImuVerificationUiState(
            phase = VerificationPhase.Complete,
            stepIndex = ImuMotionKind.entries.size,
            statusMessage = "验证完成",
            result = result,
        )
    }

    private fun resetStepAccumulators() {
        motionSeen = false
        idleSinceMs = 0L
        lastSampleNs = 0L
        energy.fill(0f)
        peakSigned.fill(0f)
    }

    private fun publishStep(waiting: Boolean) {
        val motion = ImuMotionKind.entries[stepIndex]
        _uiState.value = ImuVerificationUiState(
            phase = VerificationPhase.Running,
            stepIndex = stepIndex,
            stepCount = ImuMotionKind.entries.size,
            stepTitle = motion.title,
            instruction = motion.instruction,
            statusMessage = if (waiting) "等待动作…" else "检测中…",
            canRetry = false,
        )
    }

    companion object {
        private const val MOTION_START_RAD_S = 0.35f
        private const val MOTION_END_RAD_S = 0.22f
        private const val IDLE_COMPLETE_MS = 700L
        private const val STEP_TIMEOUT_MS = 18_000L
        private const val MIN_ENERGY = 0.08f
    }
}

enum class VerificationPhase {
    Intro,
    Running,
    Complete,
}

data class ImuVerificationUiState(
    val phase: VerificationPhase = VerificationPhase.Intro,
    val stepIndex: Int = 0,
    val stepCount: Int = ImuMotionKind.entries.size,
    val stepTitle: String = "",
    val instruction: String = "",
    val statusMessage: String = "",
    val canRetry: Boolean = false,
    val result: ImuAxisCalibrationResult? = null,
) {
    val progressLabel: String
        get() = when (phase) {
            VerificationPhase.Intro -> ""
            VerificationPhase.Complete -> "完成"
            VerificationPhase.Running -> "${stepIndex + 1}/$stepCount"
        }
}
