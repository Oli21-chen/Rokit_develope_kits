package com.rokid.glassesbaredevsample.hand

import com.rokid.glassesbaredevsample.camera.CameraFrameTransform
import com.rokid.glassesbaredevsample.camera.NormalizedCameraPoint

/**
 * Maps MediaPipe landmark space (after CameraX [ImageProcessingOptions] rotation)
 * into the glasses HUD / control space.
 *
 * On RG-glasses, CameraX reports 270° and MediaPipe upright still appears **90° CW**
 * relative to the wearer’s view (hand up → overlay points right). Apply **270° CW**
 * (= 90° CCW) so fingers-up matches the HUD.
 */
object HandDisplayTransform {
    /** Extra clockwise rotation after MediaPipe upright coordinates. */
    const val EXTRA_ROTATION_CW_DEGREES = 270

    fun toDisplay(landmark: HandLandmark): HandLandmark {
        val mapped = CameraFrameTransform.transform(
            NormalizedCameraPoint(landmark.x, landmark.y),
            EXTRA_ROTATION_CW_DEGREES,
        )
        return HandLandmark(x = mapped.x, y = mapped.y, z = landmark.z)
    }

    fun toDisplay(landmarks: List<HandLandmark>): List<HandLandmark> =
        landmarks.map(::toDisplay)
}
