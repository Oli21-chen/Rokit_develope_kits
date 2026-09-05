package com.rokid.glassesbaredevsample.sensor

import com.rokid.glassesbaredevsample.hand.HandMouseConfig
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Maps head pose offset from calibrated neutral to pointer dx/dy per tick.
 *
 * Pose-and-hold: turn left/right → dx; nod up/down → dy; return to neutral → stop.
 * Laptop [com.rokid.glassesbaredevsample.link.LinkGain] scales motion on the PC side.
 */
class ImuPointerController(
    private val config: HandMouseConfig,
) {
    fun resetAnchor() = Unit

    fun update(pose: HeadPose): Pair<Float, Float> {
        if (!pose.isCalibrated) {
            return 0f to 0f
        }

        var dx = mapAxis(pose.deltaYawDeg)
        var dy = mapAxis(pose.deltaPitchDeg)
        if (config.imuFlipX) dx = -dx
        if (config.imuFlipY) dy = -dy

        dx = dx.coerceIn(-config.maxDeltaPerFrame, config.maxDeltaPerFrame)
        dy = dy.coerceIn(-config.maxDeltaPerFrame, config.maxDeltaPerFrame)
        return dx to dy
    }

    private fun mapAxis(offsetDeg: Float): Float {
        val mag = abs(offsetDeg)
        if (mag < config.imuDeadzoneDeg) return 0f

        val maxBeyond = (config.imuMaxTiltDeg - config.imuDeadzoneDeg).coerceAtLeast(0.001f)
        val beyond = (mag - config.imuDeadzoneDeg).coerceAtMost(maxBeyond)
        val shaped = beyond.toDouble().pow(config.accelerationExponent.toDouble()).toFloat()
        return shaped * config.imuSensitivity * sign(offsetDeg)
    }
}
