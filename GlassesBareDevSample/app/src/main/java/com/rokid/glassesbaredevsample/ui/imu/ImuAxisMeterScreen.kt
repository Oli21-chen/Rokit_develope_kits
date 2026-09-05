package com.rokid.glassesbaredevsample.ui.imu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rokid.glassesbaredevsample.sensor.GyroAxis
import com.rokid.glassesbaredevsample.sensor.ImuMotionKind
import com.rokid.glassesbaredevsample.sensor.SixAxisReading
import com.rokid.glassesbaredevsample.ui.design.BareTokens
import com.rokid.glassesbaredevsample.ui.theme.NeonGreen
import com.rokid.glassesbaredevsample.ui.theme.PitchBlack
import kotlin.math.abs

enum class AxisHighlight {
    None,
    Expected,
    Dominant,
}

private const val ACC_RANGE = 12f
private const val GYRO_RANGE_DEG = 180f
private const val LOW_PASS = 0.85f
private const val DOMINANT_GYRO_RAD_S = 0.35f

private val AXIS_LABELS = arrayOf("X", "Y", "Z")

fun ImuMotionKind.expectedGyroAxisIndex(): Int = when (this) {
    ImuMotionKind.YAW -> 2
    ImuMotionKind.PITCH -> 0
    ImuMotionKind.ROLL -> 1
}

@Composable
fun ImuSixAxisMeterScreen(
    readings: SixAxisReading,
    modifier: Modifier = Modifier,
) {
    val smoothed = rememberSmoothedReadings(readings)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PitchBlack)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "加速度 m/s²",
            color = NeonGreen.copy(alpha = 0.75f),
            fontSize = BareTokens.CaptionSp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        repeat(3) { i ->
            val value = when (i) {
                0 -> smoothed.ax
                1 -> smoothed.ay
                else -> smoothed.az
            }
            ImuBipolarMeterRow(
                label = AXIS_LABELS[i],
                value = value,
                range = ACC_RANGE,
                valueText = "%+.1f".format(value),
                highlight = AxisHighlight.None,
                compact = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .padding(vertical = 1.dp),
            )
        }
        Text(
            text = "陀螺仪 °/s",
            color = NeonGreen.copy(alpha = 0.75f),
            fontSize = BareTokens.CaptionSp,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
        repeat(3) { i ->
            val value = when (i) {
                0 -> smoothed.gxDeg
                1 -> smoothed.gyDeg
                else -> smoothed.gzDeg
            }
            ImuBipolarMeterRow(
                label = AXIS_LABELS[i],
                value = value,
                range = GYRO_RANGE_DEG,
                valueText = "%+.0f".format(value),
                highlight = AxisHighlight.None,
                compact = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .padding(vertical = 1.dp),
            )
        }
    }
}

@Composable
fun ImuGyroMeterStrip(
    readings: SixAxisReading,
    modifier: Modifier = Modifier,
    expectedAxisIndex: Int? = null,
    showDominantHighlight: Boolean = false,
) {
    val smoothed = rememberSmoothedReadings(readings)
    val gyroValues = floatArrayOf(smoothed.gxDeg, smoothed.gyDeg, smoothed.gzDeg)
    val gyroRad = floatArrayOf(smoothed.gxRad, smoothed.gyRad, smoothed.gzRad)
    val dominantIndex = if (showDominantHighlight && gyroRad.maxOf { abs(it) } >= DOMINANT_GYRO_RAD_S) {
        gyroRad.indices.maxByOrNull { abs(gyroRad[it]) }
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PitchBlack),
    ) {
        repeat(3) { i ->
            val highlight = when {
                dominantIndex == i -> AxisHighlight.Dominant
                expectedAxisIndex == i -> AxisHighlight.Expected
                else -> AxisHighlight.None
            }
            ImuBipolarMeterRow(
                label = AXIS_LABELS[i],
                value = gyroValues[i],
                range = GYRO_RANGE_DEG,
                valueText = null,
                highlight = highlight,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
            )
        }
    }
}

@Composable
fun ImuGyroMeterStripWithLabels(
    readings: SixAxisReading,
    stepIndex: Int,
    modifier: Modifier = Modifier,
) {
    val motion = ImuMotionKind.entries.getOrNull(stepIndex)
    val expectedIndex = motion?.expectedGyroAxisIndex()
    Column(modifier = modifier.fillMaxWidth()) {
        motion?.let {
            Text(
                text = "期望 ${GyroAxis.fromIndex(it.expectedGyroAxisIndex()).label}",
                color = NeonGreen.copy(alpha = 0.7f),
                fontSize = BareTokens.CaptionSp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        ImuGyroMeterStrip(
            readings = readings,
            expectedAxisIndex = expectedIndex,
            showDominantHighlight = true,
        )
    }
}

@Composable
private fun ImuBipolarMeterRow(
    label: String,
    value: Float,
    range: Float,
    valueText: String?,
    highlight: AxisHighlight,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = NeonGreen.copy(alpha = if (compact) 0.65f else 0.85f),
            fontSize = if (compact) BareTokens.CaptionSp else BareTokens.BodySp,
            modifier = Modifier.width(if (compact) 10.dp else 14.dp),
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(if (compact) 12.dp else 20.dp),
        ) {
            drawBipolarMeterTrack(
                value = value,
                range = range,
                highlight = highlight,
                compact = compact,
            )
        }
        if (!compact && valueText != null) {
            Text(
                text = valueText,
                color = NeonGreen.copy(alpha = 0.85f),
                fontSize = BareTokens.CaptionSp,
                modifier = Modifier.width(42.dp),
            )
        }
    }
}

private fun DrawScope.drawBipolarMeterTrack(
    value: Float,
    range: Float,
    highlight: AxisHighlight,
    compact: Boolean,
) {
    val trackW = size.width
    val centerY = size.height / 2f
    val centerX = trackW / 2f
    val stroke = if (compact) BareTokens.STROKE_THIN else BareTokens.STROKE_NORMAL
    val dotR = if (compact) 2.5f else 4f
    val trackH = if (compact) 6f else 12f
    val tickH = if (compact) 4f else 8f
    val color = when (highlight) {
        AxisHighlight.Dominant -> NeonGreen
        AxisHighlight.Expected -> NeonGreen.copy(alpha = 0.9f)
        AxisHighlight.None -> NeonGreen.copy(alpha = if (compact) 0.75f else 0.85f)
    }
    val trackColor = when (highlight) {
        AxisHighlight.Dominant -> NeonGreen
        AxisHighlight.Expected -> NeonGreen.copy(alpha = 0.65f)
        AxisHighlight.None -> NeonGreen.copy(alpha = if (compact) 0.45f else 0.55f)
    }

    drawRect(
        color = trackColor,
        topLeft = Offset(0f, centerY - trackH / 2f),
        size = Size(trackW, trackH),
        style = Stroke(stroke),
    )
    drawLine(
        color = trackColor,
        start = Offset(centerX, centerY - tickH),
        end = Offset(centerX, centerY + tickH),
        strokeWidth = BareTokens.STROKE_THIN,
    )

    val norm = (value / range).coerceIn(-1f, 1f)
    val dotX = centerX + norm * (trackW / 2f - dotR - 2f)
    if (highlight == AxisHighlight.Dominant || highlight == AxisHighlight.Expected) {
        drawCircle(
            color = NeonGreen.copy(alpha = 0.35f),
            radius = dotR + 2f,
            center = Offset(dotX, centerY),
        )
    }
    if (highlight == AxisHighlight.Dominant) {
        drawCircle(color = NeonGreen, radius = dotR, center = Offset(dotX, centerY))
    } else {
        drawCircle(
            color = color,
            radius = dotR,
            center = Offset(dotX, centerY),
            style = Stroke(if (compact) 1.5f else stroke),
        )
    }
}

private data class SmoothedReadings(
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f,
    val gxRad: Float = 0f,
    val gyRad: Float = 0f,
    val gzRad: Float = 0f,
) {
    val gxDeg: Float get() = Math.toDegrees(gxRad.toDouble()).toFloat()
    val gyDeg: Float get() = Math.toDegrees(gyRad.toDouble()).toFloat()
    val gzDeg: Float get() = Math.toDegrees(gzRad.toDouble()).toFloat()
}

@Composable
private fun rememberSmoothedReadings(readings: SixAxisReading): SmoothedReadings {
    var ax by remember { mutableFloatStateOf(readings.ax) }
    var ay by remember { mutableFloatStateOf(readings.ay) }
    var az by remember { mutableFloatStateOf(readings.az) }
    var gx by remember { mutableFloatStateOf(readings.gxRad) }
    var gy by remember { mutableFloatStateOf(readings.gyRad) }
    var gz by remember { mutableFloatStateOf(readings.gzRad) }

    SideEffect {
        ax = LOW_PASS * ax + (1f - LOW_PASS) * readings.ax
        ay = LOW_PASS * ay + (1f - LOW_PASS) * readings.ay
        az = LOW_PASS * az + (1f - LOW_PASS) * readings.az
        gx = LOW_PASS * gx + (1f - LOW_PASS) * readings.gxRad
        gy = LOW_PASS * gy + (1f - LOW_PASS) * readings.gyRad
        gz = LOW_PASS * gz + (1f - LOW_PASS) * readings.gzRad
    }

    return SmoothedReadings(ax, ay, az, gx, gy, gz)
}
