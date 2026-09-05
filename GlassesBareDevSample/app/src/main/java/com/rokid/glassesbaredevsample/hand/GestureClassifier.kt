package com.rokid.glassesbaredevsample.hand

import kotlin.math.hypot

/**
 * Thumb–index pinch with enter/exit hysteresis, minimum hold, cooldown, and hand-loss reset.
 * Operates on **display-space** landmarks (21 points).
 */
class GestureClassifier(
    private val config: HandMouseConfig = HandMouseConfig.Default,
) {
    private var pinched = false
    private var pinchCandidateSinceMs: Long? = null
    private var lastReleaseMs = -config.pinchCooldownMs
    private var lastPinchNormalized = Float.POSITIVE_INFINITY

    fun reset() {
        pinched = false
        pinchCandidateSinceMs = null
        lastReleaseMs = -config.pinchCooldownMs
        lastPinchNormalized = Float.POSITIVE_INFINITY
    }

    /**
     * @return whether left button should be considered pressed after this sample.
     */
    fun update(landmarks: List<HandLandmark>, nowMs: Long, handPresent: Boolean): Boolean {
        if (!handPresent || landmarks.size < HandSkeleton.LANDMARK_COUNT) {
            reset()
            return false
        }

        val pinch = normalizedPinch(landmarks)
        lastPinchNormalized = pinch

        if (pinched) {
            if (pinch >= config.pinchOffNormalized) {
                pinched = false
                pinchCandidateSinceMs = null
                lastReleaseMs = nowMs
            }
            return pinched
        }

        val inCooldown = nowMs - lastReleaseMs < config.pinchCooldownMs
        if (inCooldown || pinch > config.pinchOnNormalized) {
            pinchCandidateSinceMs = null
            return false
        }

        val since = pinchCandidateSinceMs
        if (since == null) {
            if (config.pinchMinHoldMs <= 0L) {
                pinched = true
                return true
            }
            pinchCandidateSinceMs = nowMs
            return false
        }
        if (nowMs - since >= config.pinchMinHoldMs) {
            pinched = true
            pinchCandidateSinceMs = null
            return true
        }
        return false
    }

    fun lastPinchNormalized(): Float = lastPinchNormalized

    fun isPinched(): Boolean = pinched

    companion object {
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9

        fun handScale(landmarks: List<HandLandmark>): Float {
            val wrist = landmarks[WRIST]
            val middle = landmarks[MIDDLE_MCP]
            val scale = hypot(
                (middle.x - wrist.x).toDouble(),
                (middle.y - wrist.y).toDouble(),
            ).toFloat()
            return scale.coerceAtLeast(1e-3f)
        }

        fun normalizedPinch(landmarks: List<HandLandmark>): Float {
            val thumb = landmarks[THUMB_TIP]
            val index = landmarks[INDEX_TIP]
            val distance = hypot(
                (thumb.x - index.x).toDouble(),
                (thumb.y - index.y).toDouble(),
            ).toFloat()
            return distance / handScale(landmarks)
        }

        fun palmCenter(landmarks: List<HandLandmark>): Pair<Float, Float> {
            // Wrist + finger MCPs — stable for relative tracking.
            val indices = intArrayOf(0, 5, 9, 13, 17)
            var sx = 0f
            var sy = 0f
            for (i in indices) {
                sx += landmarks[i].x
                sy += landmarks[i].y
            }
            val n = indices.size.toFloat()
            return sx / n to sy / n
        }

        fun indexTip(landmarks: List<HandLandmark>): Pair<Float, Float> {
            val tip = landmarks[INDEX_TIP]
            return tip.x to tip.y
        }
    }
}
