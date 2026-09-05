package com.rokid.glassesbaredevsample.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraFrameTransformTest {
    private val point = NormalizedCameraPoint(x = 0.2f, y = 0.3f)

    @Test
    fun appliesClockwiseRightAngleRotations() {
        assertPoint(0.2f, 0.3f, CameraFrameTransform.transform(point, 0))
        assertPoint(0.7f, 0.2f, CameraFrameTransform.transform(point, 90))
        assertPoint(0.8f, 0.7f, CameraFrameTransform.transform(point, 180))
        assertPoint(0.3f, 0.8f, CameraFrameTransform.transform(point, 270))
    }

    @Test
    fun normalizesEquivalentRotationAndMirrorsAfterRotation() {
        assertEquals(270, CameraFrameTransform.normalizeRotationDegrees(-90))
        assertEquals(90, CameraFrameTransform.normalizeRotationDegrees(450))
        assertPoint(
            expectedX = 0.3f,
            expectedY = 0.2f,
            actual = CameraFrameTransform.transform(point, 90, mirrorHorizontal = true),
        )
    }

    @Test
    fun rejectsNonRightAngleRotation() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraFrameTransform.normalizeRotationDegrees(45)
        }
    }

    private fun assertPoint(
        expectedX: Float,
        expectedY: Float,
        actual: NormalizedCameraPoint,
    ) {
        assertEquals(expectedX, actual.x, 0.0001f)
        assertEquals(expectedY, actual.y, 0.0001f)
    }
}
