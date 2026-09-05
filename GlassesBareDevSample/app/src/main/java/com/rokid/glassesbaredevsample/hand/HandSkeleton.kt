package com.rokid.glassesbaredevsample.hand

/**
 * MediaPipe Hand Landmarker topology (21 points).
 * Indices match the official landmark map.
 */
object HandSkeleton {
    val CONNECTIONS: List<Pair<Int, Int>> = listOf(
        // Thumb
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        // Index
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        // Middle
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        // Ring
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        // Pinky
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        // Palm
        5 to 9, 9 to 13, 13 to 17,
    )

    const val LANDMARK_COUNT = 21
}
