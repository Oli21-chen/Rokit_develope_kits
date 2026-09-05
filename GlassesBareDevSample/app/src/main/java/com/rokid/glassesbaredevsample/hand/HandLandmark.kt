package com.rokid.glassesbaredevsample.hand

/**
 * One MediaPipe hand landmark in normalized image coordinates.
 *
 * x/y are in [0, 1] relative to the (rotation-corrected) image width/height.
 * z is relative depth with roughly the same scale as x (MediaPipe convention).
 */
data class HandLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
)
