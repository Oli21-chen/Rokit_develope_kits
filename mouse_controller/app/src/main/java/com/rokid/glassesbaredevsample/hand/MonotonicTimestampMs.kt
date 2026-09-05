package com.rokid.glassesbaredevsample.hand

/**
 * Ensures VIDEO-mode timestamps are strictly increasing.
 * Camera frame timestamps can stall or repeat across rebinds.
 */
class MonotonicTimestampMs {
    private var lastMs = -1L

    @Synchronized
    fun next(candidateMs: Long): Long {
        val safeCandidate = candidateMs.coerceAtLeast(0L)
        val next = if (safeCandidate <= lastMs) lastMs + 1L else safeCandidate
        lastMs = next
        return next
    }

    @Synchronized
    fun reset() {
        lastMs = -1L
    }
}
