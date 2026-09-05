package com.rokid.glassesbaredevsample.hand

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Dual-axis static posture control:
 * - **Horizontal (dx):** four-finger aim angle vs calibrated neutral (left / right + diagonals).
 * - **Vertical (dy):** thumb spread vs neutral — thumb out = up, thumb toward palm = down.
 */
class PointingController(
    private val config: HandMouseConfig = HandMouseConfig.Default,
) {
    private var neutralFingerAngleRad: Float? = null
    private var neutralThumbSpread: Float? = null
    private var filteredFingerAngleRad: Float? = null
    private var filteredThumbSpread: Float? = null
    private var calibrated = false

    fun reset() {
        neutralFingerAngleRad = null
        neutralThumbSpread = null
        filteredFingerAngleRad = null
        filteredThumbSpread = null
        calibrated = false
    }

    /** Call when clutch turns on or when re-centering. */
    fun calibrateNeutral(landmarks: List<HandLandmark>) {
        if (!HandPoseMath.isPointingPoseValid(landmarks, config.pointingMinExtension)) return
        val angle = HandPoseMath.fingerAimAngleRad(landmarks)
        val spread = HandPoseMath.thumbSpread(landmarks, HandPoseMath.handScale(landmarks))
        neutralFingerAngleRad = angle
        neutralThumbSpread = spread
        filteredFingerAngleRad = angle
        filteredThumbSpread = spread
        calibrated = true
    }

    fun isCalibrated(): Boolean = calibrated

    fun neutralAngleRad(): Float? = neutralFingerAngleRad

    fun filteredAngleRad(): Float? = filteredFingerAngleRad

    fun neutralThumbSpread(): Float? = neutralThumbSpread

    fun filteredThumbSpread(): Float? = filteredThumbSpread

    /**
     * @return dx/dy for this frame; axes combine for diagonal motion.
     */
    fun update(landmarks: List<HandLandmark>): Pair<Float, Float> {
        if (!HandPoseMath.isPointingPoseValid(landmarks, config.pointingMinExtension)) {
            return 0f to 0f
        }

        val scale = HandPoseMath.handScale(landmarks)
        val rawFingerAngle = HandPoseMath.fingerAimAngleRad(landmarks)
        val rawThumbSpread = HandPoseMath.thumbSpread(landmarks, scale)

        filteredFingerAngleRad = filteredFingerAngleRad?.let { prev ->
            lerpAngle(prev, rawFingerAngle, config.pointingSmoothing)
        } ?: rawFingerAngle

        filteredThumbSpread = filteredThumbSpread?.let { prev ->
            lerp(prev, rawThumbSpread, config.thumbSmoothing)
        } ?: rawThumbSpread

        val neutralAngle = neutralFingerAngleRad ?: run {
            calibrateNeutral(landmarks)
            return 0f to 0f
        }
        val neutralThumb = neutralThumbSpread ?: run {
            calibrateNeutral(landmarks)
            return 0f to 0f
        }

        val fingerAngle = filteredFingerAngleRad ?: return 0f to 0f
        val thumbSpread = filteredThumbSpread ?: return 0f to 0f

        var dx = horizontalDelta(fingerAngle, neutralAngle)
        var dy = verticalDelta(thumbSpread, neutralThumb)

        if (config.pointingFlipX) dx = -dx
        if (config.pointingFlipY) dy = -dy

        dx = dx.coerceIn(-config.maxDeltaPerFrame, config.maxDeltaPerFrame)
        dy = dy.coerceIn(-config.maxDeltaPerFrame, config.maxDeltaPerFrame)
        return dx to dy
    }

    private fun horizontalDelta(angle: Float, neutral: Float): Float {
        val offset = HandPoseMath.wrapPi(angle - neutral)
        val deadRad = HandPoseMath.degreesToRadians(config.pointingDeadzoneDeg)
        val maxRad = HandPoseMath.degreesToRadians(config.pointingMaxOffsetDeg)
        if (abs(offset) <= deadRad || maxRad <= deadRad) return 0f

        val magnitude = ((abs(offset) - deadRad) / (maxRad - deadRad)).coerceIn(0f, 1f)
            .toDouble()
            .pow(config.accelerationExponent.toDouble())
            .toFloat()

        return sin(offset) * magnitude * config.pointingSensitivity
    }

    private fun verticalDelta(spread: Float, neutral: Float): Float {
        val delta = spread - neutral
        val dead = config.thumbVerticalDeadzone
        val maxDelta = config.thumbVerticalMaxDelta
        if (maxDelta <= dead) return 0f

        return when {
            delta > dead -> {
                val magnitude = ((delta - dead) / (maxDelta - dead)).coerceIn(0f, 1f)
                    .toDouble()
                    .pow(config.accelerationExponent.toDouble())
                    .toFloat()
                -magnitude * config.pointingSensitivity
            }
            delta < -dead -> {
                val magnitude = ((-delta) - dead) / (maxDelta - dead)
                if (magnitude <= 0f) 0f else {
                    magnitude.coerceIn(0f, 1f)
                        .toDouble()
                        .pow(config.accelerationExponent.toDouble())
                        .toFloat() * config.pointingSensitivity
                }
            }
            else -> 0f
        }
    }

    private fun lerpAngle(from: Float, to: Float, alpha: Float): Float {
        val delta = HandPoseMath.wrapPi(to - from)
        return from + delta * alpha.coerceIn(0f, 1f)
    }

    private fun lerp(from: Float, to: Float, alpha: Float): Float =
        from + (to - from) * alpha.coerceIn(0f, 1f)
}
