package com.rokid.glassesbaredevsample.ui.handmouse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.rokid.glassesbaredevsample.hand.HandLandmark
import com.rokid.glassesbaredevsample.hand.HandSkeleton
import com.rokid.glassesbaredevsample.ui.theme.NeonGreen

/**
 * Debug overlay: draws MediaPipe landmarks in normalized image space onto the glasses panel.
 * No camera Preview — points only on the black HUD.
 */
@Composable
fun HandLandmarkOverlay(
    landmarks: List<HandLandmark>,
    modifier: Modifier = Modifier,
) {
    if (landmarks.size < HandSkeleton.LANDMARK_COUNT) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun point(index: Int): Offset {
            val lm = landmarks[index]
            return Offset(lm.x * w, lm.y * h)
        }

        for ((from, to) in HandSkeleton.CONNECTIONS) {
            drawLine(
                color = NeonGreen.copy(alpha = 0.85f),
                start = point(from),
                end = point(to),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round,
            )
        }
        for (index in landmarks.indices) {
            val center = point(index)
            val radius = if (index == 0 || index == 4 || index == 8 || index == 12 ||
                index == 16 || index == 20
            ) {
                5f
            } else {
                3.5f
            }
            drawCircle(color = NeonGreen, radius = radius, center = center)
        }
    }
}
