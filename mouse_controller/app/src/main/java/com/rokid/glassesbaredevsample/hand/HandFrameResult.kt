package com.rokid.glassesbaredevsample.hand

/**
 * Immutable per-frame Hand Landmarker output for Stage 2 benchmarking.
 * Stage 3 will consume this without depending on MediaPipe types.
 */
data class HandFrameResult(
    val timestampMs: Long,
    val handPresent: Boolean,
    val landmarks: List<HandLandmark>,
    val inferenceMs: Double,
)
