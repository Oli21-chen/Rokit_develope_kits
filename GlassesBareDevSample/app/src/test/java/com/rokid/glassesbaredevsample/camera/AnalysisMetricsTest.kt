package com.rokid.glassesbaredevsample.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisMetricsTest {
    @Test
    fun recordsDimensionsRatesAndAnalysisDuration() {
        val metrics = AnalysisMetrics(nominalFps = 30.0)
        val framePeriodNs = 33_333_333L

        repeat(4) { index ->
            val timestamp = index * framePeriodNs
            metrics.recordArrival(
                nowNs = timestamp,
                sourceTimestampNs = timestamp,
                width = 640,
                height = 480,
                imageFormat = 35,
                rotationDegrees = 90,
            )
            metrics.recordAnalyzed(
                nowNs = timestamp,
                analysisDurationNs = 2_500_000L,
            )
        }

        val snapshot = metrics.snapshot()
        assertEquals(640, snapshot.width)
        assertEquals(480, snapshot.height)
        assertEquals(35, snapshot.imageFormat)
        assertEquals(90, snapshot.rotationDegrees)
        assertEquals(4L, snapshot.arrivalFrames)
        assertEquals(4L, snapshot.analyzedFrames)
        assertEquals(30.0, snapshot.arrivalFps, 0.01)
        assertEquals(30.0, snapshot.analyzedFps, 0.01)
        assertEquals(0L, snapshot.estimatedDroppedFrames)
        assertEquals(2.5, snapshot.lastAnalysisMs, 0.001)
    }

    @Test
    fun estimatesDropsFromSourceTimestampGapAtNominalRate() {
        val metrics = AnalysisMetrics(nominalFps = 30.0)

        metrics.recordArrival(0L, 0L, 640, 480, 35, 0)
        metrics.recordArrival(100_000_000L, 100_000_000L, 640, 480, 35, 0)

        assertEquals(2L, metrics.snapshot().estimatedDroppedFrames)
    }

    @Test
    fun resetClearsCountersAndErrors() {
        val metrics = AnalysisMetrics()
        metrics.recordArrival(1L, 1L, 640, 480, 35, 0)
        metrics.recordAnalyzed(2L, 1L)
        metrics.recordError("test")

        metrics.reset()

        assertEquals(AnalysisMetricsSnapshot(), metrics.snapshot())
    }
}
