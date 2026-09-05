package com.rokid.glassesbaredevsample.ui.imu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rokid.glassesbaredevsample.app.CONSTANT
import com.rokid.glassesbaredevsample.sensor.HeadPose
import com.rokid.glassesbaredevsample.sensor.SixAxisReading
import com.rokid.glassesbaredevsample.ui.design.BareTokens
import com.rokid.glassesbaredevsample.ui.theme.NeonGreen
import com.rokid.glassesbaredevsample.ui.theme.PitchBlack

private const val BALL_RADIUS = 14f
private const val GAIN = 10f
private const val LOW_PASS = 0.85f

@Composable
fun ImuBallScreen(
    pose: HeadPose,
    modifier: Modifier = Modifier,
    drawSafeBorder: Boolean = true,
    readings: SixAxisReading? = null,
) {
    var smoothedYaw by remember { mutableFloatStateOf(0f) }
    var smoothedPitch by remember { mutableFloatStateOf(0f) }

    SideEffect {
        if (pose.isCalibrated) {
            smoothedYaw = LOW_PASS * smoothedYaw + (1f - LOW_PASS) * pose.deltaYawDeg
            smoothedPitch = LOW_PASS * smoothedPitch + (1f - LOW_PASS) * pose.deltaPitchDeg
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PitchBlack),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val top = if (drawSafeBorder) CONSTANT.SAFE_AREA_TOP_PX.toFloat() else 0f
                val bottom = if (drawSafeBorder) CONSTANT.SAFE_AREA_BOTTOM_PX.toFloat() else size.height
                val safeHeight = bottom - top
                val centerX = size.width / 2f
                val centerY = top + safeHeight / 2f
                val margin = BALL_RADIUS + 8f
                val minX = margin
                val maxX = size.width - margin
                val minY = top + margin
                val maxY = bottom - margin

                if (drawSafeBorder) {
                    drawRect(
                        color = NeonGreen,
                        style = Stroke(BareTokens.STROKE_NORMAL),
                        topLeft = Offset(1f, top),
                        size = androidx.compose.ui.geometry.Size(size.width - 2f, safeHeight),
                    )
                }
                drawLine(NeonGreen, Offset(centerX, top), Offset(centerX, bottom), BareTokens.STROKE_THIN)
                drawLine(NeonGreen, Offset(0f, centerY), Offset(size.width, centerY), BareTokens.STROKE_THIN)

                val (bx, by) = if (pose.isCalibrated) {
                    (centerX + smoothedYaw * GAIN).coerceIn(minX, maxX) to
                        (centerY - smoothedPitch * GAIN).coerceIn(minY, maxY)
                } else {
                    centerX to centerY
                }
                drawCircle(NeonGreen, BALL_RADIUS, center = Offset(bx, by), style = Stroke(BareTokens.STROKE_NORMAL))
                drawCircle(NeonGreen, 4f, center = Offset(bx, by))
            }
        }
        readings?.let {
            ImuGyroMeterStrip(
                readings = it,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}
