package com.rokid.glassesbaredevsample.sensor

import kotlin.math.sqrt

/**
 * Pauses hand-driven pointer output briefly after a large head rotation (gyro spike).
 * Used as a safety filter so head turns do not move the cursor in image space.
 */
class HeadJerkGate(
    private val jerkThresholdRadPerSec: Float = DEFAULT_JERK_THRESHOLD_RAD,
    private val pauseMs: Long = DEFAULT_PAUSE_MS,
) {
    private var pausedUntilMs: Long = 0L

    fun onGyroSample(gxRad: Float, gyRad: Float, gzRad: Float, nowMs: Long) {
        val magnitude = sqrt(gxRad * gxRad + gyRad * gyRad + gzRad * gzRad)
        if (magnitude >= jerkThresholdRadPerSec) {
            pausedUntilMs = nowMs + pauseMs
        }
    }

    fun isPaused(nowMs: Long): Boolean = nowMs < pausedUntilMs

    fun reset() {
        pausedUntilMs = 0L
    }

    companion object {
        /** ~120°/s total gyro magnitude. */
        const val DEFAULT_JERK_THRESHOLD_RAD: Float = 2.1f
        const val DEFAULT_PAUSE_MS: Long = 300L
    }
}
