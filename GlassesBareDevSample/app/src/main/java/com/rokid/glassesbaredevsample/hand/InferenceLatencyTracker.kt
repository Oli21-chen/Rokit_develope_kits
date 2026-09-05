package com.rokid.glassesbaredevsample.hand

import java.util.ArrayDeque

data class InferenceLatencySnapshot(
    val sampleCount: Int = 0,
    val lastMs: Double = 0.0,
    val avgMs: Double = 0.0,
    val p50Ms: Double = 0.0,
    val p95Ms: Double = 0.0,
    val maxMs: Double = 0.0,
)

/**
 * Rolling window of inference durations for HUD / Stage 2 exit-gate metrics.
 */
class InferenceLatencyTracker(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val samples = ArrayDeque<Double>(capacity)

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun record(inferenceMs: Double): InferenceLatencySnapshot {
        require(inferenceMs.isFinite() && inferenceMs >= 0.0)
        if (samples.size >= capacity) {
            samples.removeFirst()
        }
        samples.addLast(inferenceMs)
        return snapshotLocked()
    }

    @Synchronized
    fun reset() {
        samples.clear()
    }

    @Synchronized
    fun snapshot(): InferenceLatencySnapshot = snapshotLocked()

    private fun snapshotLocked(): InferenceLatencySnapshot {
        if (samples.isEmpty()) return InferenceLatencySnapshot()
        val values = samples.toDoubleArray()
        val sorted = values.sorted()
        val sum = values.sum()
        return InferenceLatencySnapshot(
            sampleCount = values.size,
            lastMs = values.last(),
            avgMs = sum / values.size,
            p50Ms = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            maxMs = sorted.last(),
        )
    }

    companion object {
        const val DEFAULT_CAPACITY = 120

        fun percentile(sortedAscending: List<Double>, percentile: Double): Double {
            require(sortedAscending.isNotEmpty())
            require(percentile in 0.0..1.0)
            if (sortedAscending.size == 1) return sortedAscending[0]
            val rank = percentile * (sortedAscending.size - 1)
            val lower = rank.toInt()
            val upper = (lower + 1).coerceAtMost(sortedAscending.lastIndex)
            val weight = rank - lower
            return sortedAscending[lower] * (1.0 - weight) + sortedAscending[upper] * weight
        }
    }
}
