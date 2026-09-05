package com.rokid.glassesbaredevsample.hand

/**
 * Detects a clenched fist via normalized finger curl with enter/exit hysteresis.
 */
class FistClassifier(
    private val config: HandMouseConfig = HandMouseConfig.Default,
) {
    private var fisted = false
    private var fistCandidateSinceMs: Long? = null
    private var lastReleaseMs = -config.fistCooldownMs
    private var lastCurlScore = Float.POSITIVE_INFINITY

    fun reset() {
        fisted = false
        fistCandidateSinceMs = null
        lastReleaseMs = -config.fistCooldownMs
        lastCurlScore = Float.POSITIVE_INFINITY
    }

    fun isFisted(): Boolean = fisted

    fun lastCurlScore(): Float = lastCurlScore

    /**
     * @return whether left button should be pressed after this sample.
     */
    fun update(landmarks: List<HandLandmark>, nowMs: Long, handPresent: Boolean): Boolean {
        if (!handPresent || landmarks.size < HandSkeleton.LANDMARK_COUNT) {
            reset()
            return false
        }

        val scale = HandPoseMath.handScale(landmarks)
        val curl = maxFingerCurl(landmarks, scale)
        lastCurlScore = curl

        if (fisted) {
            if (curl >= config.fistOffCurl) {
                fisted = false
                fistCandidateSinceMs = null
                lastReleaseMs = nowMs
            }
            return fisted
        }

        if (nowMs - lastReleaseMs < config.fistCooldownMs) {
            fistCandidateSinceMs = null
            return false
        }

        if (!isFistShape(landmarks, scale) || curl > config.fistOnCurl) {
            fistCandidateSinceMs = null
            return false
        }

        val since = fistCandidateSinceMs
        if (since == null) {
            if (config.fistMinHoldMs <= 0L) {
                fisted = true
                return true
            }
            fistCandidateSinceMs = nowMs
            return false
        }
        if (nowMs - since >= config.fistMinHoldMs) {
            fisted = true
            fistCandidateSinceMs = null
            return true
        }
        return false
    }

    private fun isFistShape(landmarks: List<HandLandmark>, scale: Float): Boolean {
        val idx = HandLandmarkIndex
        val indexC = HandPoseMath.fingerCurl(landmarks, idx.INDEX_MCP, idx.INDEX_TIP, scale)
        val middleC = HandPoseMath.fingerCurl(landmarks, idx.MIDDLE_MCP, idx.MIDDLE_TIP, scale)
        val ringC = HandPoseMath.fingerCurl(landmarks, idx.RING_MCP, idx.RING_TIP, scale)
        val pinkyC = HandPoseMath.fingerCurl(landmarks, idx.PINKY_MCP, idx.PINKY_TIP, scale)
        val thumbC = HandPoseMath.fingerCurl(landmarks, idx.THUMB_IP, idx.THUMB_TIP, scale)
        return indexC < config.fistOnCurl &&
            middleC < config.fistOnCurl &&
            ringC < config.fistOnCurl &&
            pinkyC < config.fistOnCurl &&
            thumbC < config.fistThumbCurl
    }

    private fun maxFingerCurl(landmarks: List<HandLandmark>, scale: Float): Float {
        val idx = HandLandmarkIndex
        return maxOf(
            HandPoseMath.fingerCurl(landmarks, idx.INDEX_MCP, idx.INDEX_TIP, scale),
            HandPoseMath.fingerCurl(landmarks, idx.MIDDLE_MCP, idx.MIDDLE_TIP, scale),
            HandPoseMath.fingerCurl(landmarks, idx.RING_MCP, idx.RING_TIP, scale),
            HandPoseMath.fingerCurl(landmarks, idx.PINKY_MCP, idx.PINKY_TIP, scale),
        )
    }
}
