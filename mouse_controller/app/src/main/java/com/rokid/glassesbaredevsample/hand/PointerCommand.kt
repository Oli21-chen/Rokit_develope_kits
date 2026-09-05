package com.rokid.glassesbaredevsample.hand

enum class PointerGesture {
    IDLE,
    TRACKING,
    PINCH,
    FIST,
    LOST,
    /** Solution A: motion paused by head-jerk gate. */
    PAUSED,
}

/**
 * Shared UI / link contract for one control frame.
 * When [outputEnabled] is false or [handOk] is false: dx/dy are 0 and leftPressed is false.
 */
data class PointerCommand(
    val outputEnabled: Boolean,
    val handOk: Boolean,
    val dx: Float,
    val dy: Float,
    val leftPressed: Boolean,
    val gesture: PointerGesture,
    val pinchNormalized: Float = Float.POSITIVE_INFINITY,
    val poseValid: Boolean = false,
    val aimOffsetDeg: Float = Float.NaN,
    /** One-shot scroll tick: +1 up, -1 down, 0 none. */
    val wheelDelta: Int = 0,
    /** One-shot recenter for laptop-side pointer anchor. */
    val recenter: Boolean = false,
    /** One-shot move laptop cursor to screen center (posture freeze). */
    val centerCursor: Boolean = false,
    val motionPaused: Boolean = false,
    /** Manual precision mode for small UI targets (TouchPad two-finger). */
    val precisionMode: Boolean = false,
)
