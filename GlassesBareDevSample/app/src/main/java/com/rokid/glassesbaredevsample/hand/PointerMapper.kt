package com.rokid.glassesbaredevsample.hand

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Converts display-space landmarks into relative [PointerCommand] samples.
 * Call [setOutputEnabled] from the TouchPad clutch; output starts disabled.
 */
class PointerMapper(
    private val config: HandMouseConfig = HandMouseConfig.Default,
) {
    private val pinchGesture = GestureClassifier(config)
    private val fistClassifier = FistClassifier(config)
    private val pointingController = PointingController(config)
    private var outputEnabled = false
    private var hasAnchor = false
    private var lastRawX: Float? = null
    private var lastRawY: Float? = null

    fun setOutputEnabled(enabled: Boolean) {
        if (outputEnabled == enabled) return
        outputEnabled = enabled
        resetMotionAnchor()
        if (enabled && config.controlScheme == ControlScheme.POINTING_FIST) {
            pointingController.reset()
        }
        if (!enabled) {
            pinchGesture.reset()
            fistClassifier.reset()
            pointingController.reset()
        }
    }

    fun isOutputEnabled(): Boolean = outputEnabled

    /** Re-anchor palm/index position (Solution A long-press recenter). */
    fun recenterAnchor() {
        hasAnchor = false
        lastRawX = null
        lastRawY = null
    }

    private fun resetMotionAnchor() {
        hasAnchor = false
        lastRawX = null
        lastRawY = null
    }

    fun reset() {
        pinchGesture.reset()
        fistClassifier.reset()
        pointingController.reset()
        resetMotionAnchor()
    }

    fun update(
        landmarks: List<HandLandmark>,
        handPresent: Boolean,
        nowMs: Long,
        motionPaused: Boolean = false,
    ): PointerCommand {
        if (!handPresent || landmarks.size < HandSkeleton.LANDMARK_COUNT) {
            pinchGesture.reset()
            fistClassifier.reset()
            resetMotionAnchor()
            return PointerCommand(
                outputEnabled = outputEnabled,
                handOk = false,
                dx = 0f,
                dy = 0f,
                leftPressed = false,
                gesture = if (outputEnabled) PointerGesture.LOST else PointerGesture.IDLE,
                pinchNormalized = Float.POSITIVE_INFINITY,
                poseValid = false,
            )
        }

        return when (config.controlScheme) {
            ControlScheme.POINTING_FIST -> updatePointingFist(landmarks, nowMs)
            ControlScheme.TRANSLATION_PINCH -> updateTranslationPinch(landmarks, nowMs)
            ControlScheme.TOUCH_GATED_TRACKPAD,
                ControlScheme.HYBRID_IMU_HAND ->
                updateTouchGatedTrackpad(landmarks, nowMs, motionPaused)
            ControlScheme.IMU_HEAD_POINTER -> imuIdleCommand(handPresent)
        }
    }

    private fun imuIdleCommand(handPresent: Boolean): PointerCommand =
        PointerCommand(
            outputEnabled = outputEnabled,
            handOk = handPresent,
            dx = 0f,
            dy = 0f,
            leftPressed = false,
            gesture = if (outputEnabled) PointerGesture.TRACKING else PointerGesture.IDLE,
            poseValid = false,
        )

    private fun updatePointingFist(
        landmarks: List<HandLandmark>,
        nowMs: Long,
    ): PointerCommand {
        val poseValid = HandPoseMath.isPointingPoseValid(landmarks, config.pointingMinExtension)

        val leftPressed = if (outputEnabled) {
            fistClassifier.update(landmarks, nowMs, handPresent = true)
        } else {
            fistClassifier.reset()
            false
        }

        var dx = 0f
        var dy = 0f
        var aimOffsetDeg = Float.NaN

        if (outputEnabled && !leftPressed && poseValid) {
            if (!pointingController.isCalibrated()) {
                pointingController.calibrateNeutral(landmarks)
            } else {
                val motion = pointingController.update(landmarks)
                dx = motion.first
                dy = motion.second
                val neutral = pointingController.neutralAngleRad()
                val filtered = pointingController.filteredAngleRad()
                if (neutral != null && filtered != null) {
                    aimOffsetDeg = Math.toDegrees(
                        HandPoseMath.wrapPi(filtered - neutral).toDouble(),
                    ).toFloat()
                }
            }
        }

        val gestureLabel = when {
            !outputEnabled -> PointerGesture.IDLE
            leftPressed -> PointerGesture.FIST
            !poseValid -> PointerGesture.LOST
            dx != 0f || dy != 0f -> PointerGesture.TRACKING
            else -> PointerGesture.TRACKING
        }

        return PointerCommand(
            outputEnabled = outputEnabled,
            handOk = true,
            dx = dx,
            dy = dy,
            leftPressed = leftPressed,
            gesture = gestureLabel,
            pinchNormalized = fistClassifier.lastCurlScore(),
            poseValid = poseValid,
            aimOffsetDeg = aimOffsetDeg,
        )
    }

    private fun updateTouchGatedTrackpad(
        landmarks: List<HandLandmark>,
        nowMs: Long,
        motionPaused: Boolean,
    ): PointerCommand {
        val (rawX, rawY) = if (config.usePalmCenter) {
            GestureClassifier.palmCenter(landmarks)
        } else {
            GestureClassifier.indexTip(landmarks)
        }

        var dx = 0f
        var dy = 0f
        if (outputEnabled && !motionPaused) {
            val prevRawX = lastRawX
            val prevRawY = lastRawY
            if (hasAnchor && prevRawX != null && prevRawY != null) {
                dx = mapAxis(rawX - prevRawX)
                dy = mapAxis(rawY - prevRawY)
            } else {
                hasAnchor = true
            }
        } else if (motionPaused) {
            resetMotionAnchor()
        }
        lastRawX = rawX
        lastRawY = rawY

        val gestureLabel = when {
            !outputEnabled -> PointerGesture.IDLE
            motionPaused -> PointerGesture.PAUSED
            else -> PointerGesture.TRACKING
        }

        return PointerCommand(
            outputEnabled = outputEnabled,
            handOk = true,
            dx = dx,
            dy = dy,
            leftPressed = false,
            gesture = gestureLabel,
            poseValid = true,
        )
    }

    private fun updateTranslationPinch(
        landmarks: List<HandLandmark>,
        nowMs: Long,
    ): PointerCommand {
        val leftPressed = if (outputEnabled) {
            pinchGesture.update(landmarks, nowMs, handPresent = true)
        } else {
            pinchGesture.reset()
            false
        }

        val (rawX, rawY) = if (config.usePalmCenter) {
            GestureClassifier.palmCenter(landmarks)
        } else {
            GestureClassifier.indexTip(landmarks)
        }

        var dx = 0f
        var dy = 0f
        if (outputEnabled) {
            val prevRawX = lastRawX
            val prevRawY = lastRawY
            if (hasAnchor && prevRawX != null && prevRawY != null) {
                dx = mapAxis(rawX - prevRawX)
                dy = mapAxis(rawY - prevRawY)
            } else {
                hasAnchor = true
            }
        }
        lastRawX = rawX
        lastRawY = rawY

        val gestureLabel = when {
            !outputEnabled -> PointerGesture.IDLE
            leftPressed -> PointerGesture.PINCH
            else -> PointerGesture.TRACKING
        }

        return PointerCommand(
            outputEnabled = outputEnabled,
            handOk = true,
            dx = dx,
            dy = dy,
            leftPressed = leftPressed,
            gesture = gestureLabel,
            pinchNormalized = pinchGesture.lastPinchNormalized(),
            poseValid = true,
        )
    }

    private fun mapAxis(deltaNorm: Float): Float {
        val mag = abs(deltaNorm)
        if (mag < config.moveDeadzone) return 0f
        val shaped = (mag - config.moveDeadzone)
            .toDouble()
            .pow(config.accelerationExponent.toDouble())
            .toFloat()
        val scaled = shaped * config.sensitivity * sign(deltaNorm)
        return scaled.coerceIn(-config.maxDeltaPerFrame, config.maxDeltaPerFrame)
    }
}
