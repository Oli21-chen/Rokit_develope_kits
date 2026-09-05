package com.rokid.glassesbaredevsample.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rokid.glassesbaredevsample.ui.theme.NeonGreen

data class BareKeyGuide(
    val click: String? = null,
    val doubleClick: String? = null,
    val longPress: String? = null,
    val swipeForward: String? = null,
    val swipeBack: String? = null,
) {
    companion object {
        val Hub = BareKeyGuide(
            swipeForward = "下一项",
            swipeBack = "上一项",
            click = "进入",
            doubleClick = "退出",
        )
    }
}

@Composable
fun BareKeyLegendBar(
    guide: BareKeyGuide,
    modifier: Modifier = Modifier,
) {
    val rows = buildList {
        guide.click?.let { add(KeyRowKind.Click to it) }
        guide.swipeForward?.let { add(KeyRowKind.SwipeForward to it) }
        guide.swipeBack?.let { add(KeyRowKind.SwipeBack to it) }
        guide.doubleClick?.let { add(KeyRowKind.DoubleClick to it) }
        guide.longPress?.let { add(KeyRowKind.LongPress to it) }
    }
    if (rows.isEmpty()) return

    val rowH = if (rows.size >= 4) 14.dp else 16.dp
    val totalH = (4 + rows.size * rowH.value).dp.coerceAtMost(BareTokens.LegendH)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(totalH),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp),
        ) {
            drawLine(
                color = NeonGreen,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = BareTokens.STROKE_THIN,
            )
        }
        rows.forEach { (kind, action) ->
            BareKeyLegendRow(kind = kind, action = action, rowHeight = rowH)
        }
    }
}

private enum class KeyRowKind(val badge: String) {
    Click("单击"),
    SwipeForward("前滑"),
    SwipeBack("后滑"),
    DoubleClick("双击"),
    LongPress("长按"),
}

@Composable
private fun BareKeyLegendRow(kind: KeyRowKind, action: String, rowHeight: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BareKeyBadge(label = kind.badge)
        Text(
            text = action,
            color = NeonGreen,
            fontSize = BareTokens.LegendSp,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun BareKeyBadge(label: String) {
    val badgeW = if (label.length <= 2) 36.dp else 40.dp
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .width(badgeW)
            .height(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRoundRect(
                color = NeonGreen,
                style = Stroke(width = BareTokens.STROKE_THIN),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
        Text(
            text = label,
            color = NeonGreen,
            fontSize = BareTokens.CaptionSp,
            fontWeight = FontWeight.Medium,
        )
    }
}
