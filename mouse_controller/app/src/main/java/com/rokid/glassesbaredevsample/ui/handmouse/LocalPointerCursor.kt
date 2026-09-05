package com.rokid.glassesbaredevsample.ui.handmouse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.rokid.glassesbaredevsample.ui.theme.NeonGreen

/**
 * Local Stage 3 cursor using normalized panel coordinates [0, 1].
 */
@Composable
fun LocalPointerCursor(
    normX: Float,
    normY: Float,
    pressed: Boolean,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(
            normX.coerceIn(0f, 1f) * size.width,
            normY.coerceIn(0f, 1f) * size.height,
        )
        val arm = if (pressed) 14f else 10f
        val stroke = if (pressed) 3.5f else 2.5f
        drawLine(
            color = NeonGreen,
            start = Offset(center.x - arm, center.y),
            end = Offset(center.x + arm, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = NeonGreen,
            start = Offset(center.x, center.y - arm),
            end = Offset(center.x, center.y + arm),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = NeonGreen,
            radius = if (pressed) 6f else 4f,
            center = center,
        )
    }
}
