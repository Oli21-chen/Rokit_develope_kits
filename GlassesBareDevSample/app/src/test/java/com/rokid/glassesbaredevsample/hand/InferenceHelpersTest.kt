package com.rokid.glassesbaredevsample.hand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicTimestampMsTest {
    @Test
    fun advancesWhenCandidateStallsOrGoesBackward() {
        val timestamps = MonotonicTimestampMs()
        assertEquals(100L, timestamps.next(100L))
        assertEquals(101L, timestamps.next(100L))
        assertEquals(102L, timestamps.next(50L))
        assertEquals(200L, timestamps.next(200L))
    }

    @Test
    fun resetAllowsReuseOfEarlierCandidates() {
        val timestamps = MonotonicTimestampMs()
        timestamps.next(500L)
        timestamps.reset()
        assertEquals(10L, timestamps.next(10L))
    }
}

class InferenceLatencyTrackerTest {
    @Test
    fun computesAvgP50P95AndMax() {
        val tracker = InferenceLatencyTracker(capacity = 5)
        listOf(10.0, 20.0, 30.0, 40.0, 100.0).forEach { tracker.record(it) }

        val snapshot = tracker.snapshot()
        assertEquals(5, snapshot.sampleCount)
        assertEquals(100.0, snapshot.lastMs, 0.001)
        assertEquals(40.0, snapshot.avgMs, 0.001)
        assertEquals(30.0, snapshot.p50Ms, 0.001)
        assertEquals(88.0, snapshot.p95Ms, 0.001)
        assertEquals(100.0, snapshot.maxMs, 0.001)
    }

    @Test
    fun respectsRollingCapacity() {
        val tracker = InferenceLatencyTracker(capacity = 3)
        tracker.record(1.0)
        tracker.record(2.0)
        tracker.record(3.0)
        tracker.record(4.0)

        val snapshot = tracker.snapshot()
        assertEquals(3, snapshot.sampleCount)
        assertEquals(3.0, snapshot.avgMs, 0.001)
        assertEquals(4.0, snapshot.lastMs, 0.001)
    }

    @Test
    fun percentileHelperInterpolates() {
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        assertEquals(10.0, InferenceLatencyTracker.percentile(values, 0.0), 0.001)
        assertEquals(30.0, InferenceLatencyTracker.percentile(values, 0.5), 0.001)
        assertEquals(50.0, InferenceLatencyTracker.percentile(values, 1.0), 0.001)
        assertTrue(InferenceLatencyTracker.percentile(values, 0.95) > 46.0)
    }
}

class HandSkeletonTest {
    @Test
    fun connectionsStayWithinLandmarkIndices() {
        assertEquals(21, HandSkeleton.LANDMARK_COUNT)
        assertTrue(HandSkeleton.CONNECTIONS.isNotEmpty())
        for ((from, to) in HandSkeleton.CONNECTIONS) {
            assertTrue(from in 0 until HandSkeleton.LANDMARK_COUNT)
            assertTrue(to in 0 until HandSkeleton.LANDMARK_COUNT)
            assertTrue(from != to)
        }
    }
}

class HandDisplayTransformTest {
    @Test
    fun rotatesOverlayNinetyDegreesCounterClockwise() {
        // Physical up currently drawn at right-center (0.9, 0.5) → should become top (0.5, 0.1).
        val tip = HandLandmark(0.9f, 0.5f, 0f)
        val mapped = HandDisplayTransform.toDisplay(tip)
        assertEquals(0.5f, mapped.x, 0.0001f)
        assertEquals(0.1f, mapped.y, 0.0001f)
        assertEquals(270, HandDisplayTransform.EXTRA_ROTATION_CW_DEGREES)
    }
}
