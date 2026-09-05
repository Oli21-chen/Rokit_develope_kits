package com.rokid.glassesbaredevsample.camera

data class NormalizedCameraPoint(val x: Float, val y: Float)

object CameraFrameTransform {
    fun normalizeRotationDegrees(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized % 90 == 0) {
            "Camera rotation must be a multiple of 90 degrees: $rotationDegrees"
        }
        return normalized
    }

    /**
     * Applies the clockwise CameraX target rotation, then optional horizontal mirroring.
     */
    fun transform(
        point: NormalizedCameraPoint,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean = false,
    ): NormalizedCameraPoint {
        val rotated = when (normalizeRotationDegrees(rotationDegrees)) {
            0 -> point
            90 -> NormalizedCameraPoint(1f - point.y, point.x)
            180 -> NormalizedCameraPoint(1f - point.x, 1f - point.y)
            270 -> NormalizedCameraPoint(point.y, 1f - point.x)
            else -> error("Unreachable rotation")
        }
        return if (mirrorHorizontal) {
            NormalizedCameraPoint(1f - rotated.x, rotated.y)
        } else {
            rotated
        }
    }
}
