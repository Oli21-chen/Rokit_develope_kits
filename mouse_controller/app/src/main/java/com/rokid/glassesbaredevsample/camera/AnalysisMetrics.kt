package com.rokid.glassesbaredevsample.camera

import java.util.ArrayDeque
import kotlin.math.roundToLong

data class AnalysisMetricsSnapshot(
    val width: Int = 0,
    val height: Int = 0,
    val imageFormat: Int = 0,
    val rotationDegrees: Int = 0,
    val arrivalFrames: Long = 0,
    val analyzedFrames: Long = 0,
    val arrivalFps: Double = 0.0,
    val analyzedFps: Double = 0.0,
    val estimatedDroppedFrames: Long = 0,
    val lastAnalysisMs: Double = 0.0,
    val analyzerErrors: Long = 0,
    val lastError: String? = null,
)

/**
 * Thread-safe CameraX analysis telemetry.
 *
 * Dropped frames are an estimate relative to [nominalFps]. CameraX does not expose frames
 * discarded by STRATEGY_KEEP_ONLY_LATEST, so source timestamp gaps are the best available signal.
 */
class AnalysisMetrics(
    nominalFps: Double = 30.0,
    private val rateWindowNs: Long = 2_000_000_000L,
) {
    private val nominalFramePeriodNs: Long
    private val arrivalTimes = ArrayDeque<Long>()
    private val analyzedTimes = ArrayDeque<Long>()

    private var width = 0
    private var height = 0
    private var imageFormat = 0
    private var rotationDegrees = 0
    private var arrivalFrames = 0L
    private var analyzedFrames = 0L
    private var estimatedDroppedFrames = 0L
    private var lastSourceTimestampNs: Long? = null
    private var lastAnalysisMs = 0.0
    private var analyzerErrors = 0L
    private var lastError: String? = null

    init {
        require(nominalFps.isFinite() && nominalFps > 0.0)
        require(rateWindowNs > 0L)
        nominalFramePeriodNs = (1_000_000_000.0 / nominalFps).roundToLong()
    }

    @Synchronized
    fun recordArrival(
        nowNs: Long,
        sourceTimestampNs: Long,
        width: Int,
        height: Int,
        imageFormat: Int,
        rotationDegrees: Int,
    ) {
        this.width = width
        this.height = height
        this.imageFormat = imageFormat
        this.rotationDegrees = rotationDegrees
        arrivalFrames++
        addRateSample(arrivalTimes, nowNs)

        lastSourceTimestampNs?.let { previous ->
            val deltaNs = sourceTimestampNs - previous
            if (deltaNs > 0L) {
                val representedFrames =
                    (deltaNs.toDouble() / nominalFramePeriodNs).roundToLong().coerceAtLeast(1L)
                estimatedDroppedFrames += (representedFrames - 1L).coerceAtLeast(0L)
            }
        }
        lastSourceTimestampNs = sourceTimestampNs
    }

    @Synchronized
    fun recordAnalyzed(nowNs: Long, analysisDurationNs: Long): AnalysisMetricsSnapshot {
        analyzedFrames++
        lastAnalysisMs = analysisDurationNs.coerceAtLeast(0L) / 1_000_000.0
        addRateSample(analyzedTimes, nowNs)
        return snapshot()
    }

    @Synchronized
    fun recordError(message: String): AnalysisMetricsSnapshot {
        analyzerErrors++
        lastError = message
        return snapshot()
    }

    @Synchronized
    fun reset() {
        arrivalTimes.clear()
        analyzedTimes.clear()
        width = 0
        height = 0
        imageFormat = 0
        rotationDegrees = 0
        arrivalFrames = 0L
        analyzedFrames = 0L
        estimatedDroppedFrames = 0L
        lastSourceTimestampNs = null
        lastAnalysisMs = 0.0
        analyzerErrors = 0L
        lastError = null
    }

    @Synchronized
    fun snapshot(): AnalysisMetricsSnapshot = AnalysisMetricsSnapshot(
        width = width,
        height = height,
        imageFormat = imageFormat,
        rotationDegrees = rotationDegrees,
        arrivalFrames = arrivalFrames,
        analyzedFrames = analyzedFrames,
        arrivalFps = calculateRate(arrivalTimes),
        analyzedFps = calculateRate(analyzedTimes),
        estimatedDroppedFrames = estimatedDroppedFrames,
        lastAnalysisMs = lastAnalysisMs,
        analyzerErrors = analyzerErrors,
        lastError = lastError,
    )

    private fun addRateSample(samples: ArrayDeque<Long>, nowNs: Long) {
        samples.addLast(nowNs)
        val cutoff = nowNs - rateWindowNs
        while (samples.size > 2 && samples.first() < cutoff) {
            samples.removeFirst()
        }
    }

    private fun calculateRate(samples: ArrayDeque<Long>): Double {
        if (samples.size < 2) return 0.0
        val elapsedNs = samples.last() - samples.first()
        if (elapsedNs <= 0L) return 0.0
        return (samples.size - 1) * 1_000_000_000.0 / elapsedNs
    }
}
