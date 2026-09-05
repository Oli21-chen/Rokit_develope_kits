package com.rokid.glassesbaredevsample.ui.imu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rokid.glassesbaredevsample.sensor.SixAxisReading
import com.rokid.glassesbaredevsample.ui.design.BareTokens
import com.rokid.glassesbaredevsample.ui.theme.NeonGreen
import com.rokid.glassesbaredevsample.ui.theme.PitchBlack
private const val ACC_LEVEL_RANGE = 9.8f
private const val LOW_PASS = 0.85f

@Composable
fun ImuAccelLevelScreen(
    readings: SixAxisReading,
    modifier: Modifier = Modifier,
) {
    var ax by remember { mutableFloatStateOf(readings.ax) }
    var ay by remember { mutableFloatStateOf(readings.ay) }
    var az by remember { mutableFloatStateOf(readings.az) }

    SideEffect {
        ax = LOW_PASS * ax + (1f - LOW_PASS) * readings.ax
        ay = LOW_PASS * ay + (1f - LOW_PASS) * readings.ay
        az = LOW_PASS * az + (1f - LOW_PASS) * readings.az
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PitchBlack),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val sizeMin = size.minDimension
                val radius = sizeMin * 0.38f
                val cx = size.width / 2f
                val cy = size.height / 2f

                drawCircle(
                    color = NeonGreen.copy(alpha = 0.55f),
                    radius = radius,
                    center = Offset(cx, cy),
                    style = Stroke(BareTokens.STROKE_NORMAL),
                )
                drawLine(
                    NeonGreen.copy(alpha = 0.45f),
                    Offset(cx - radius, cy),
                    Offset(cx + radius, cy),
                    BareTokens.STROKE_THIN,
                )
                drawLine(
                    NeonGreen.copy(alpha = 0.45f),
                    Offset(cx, cy - radius),
                    Offset(cx, cy + radius),
                    BareTokens.STROKE_THIN,
                )

                val normX = (ax / ACC_LEVEL_RANGE).coerceIn(-1f, 1f)
                val normY = (-ay / ACC_LEVEL_RANGE).coerceIn(-1f, 1f)
                val maxOffset = radius - 10f
                val dotX = cx + normX * maxOffset
                val dotY = cy + normY * maxOffset

                drawCircle(
                    color = NeonGreen,
                    radius = 5f,
                    center = Offset(dotX, dotY),
                    style = Stroke(BareTokens.STROKE_NORMAL),
                )
                drawCircle(color = NeonGreen, radius = 2.5f, center = Offset(dotX, dotY))
            }
        }
        Text(
            text = "acc Z ${"%+.2f".format(az)} m/s²",
            color = NeonGreen.copy(alpha = 0.85f),
            fontSize = BareTokens.BodySp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "倾斜：X/Y 驱动圆点 · 静止时居中",
            color = NeonGreen.copy(alpha = 0.55f),
            fontSize = BareTokens.CaptionSp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}
