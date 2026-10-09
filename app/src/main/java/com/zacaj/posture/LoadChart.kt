package com.zacaj.posture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zacaj.posture.core.Posture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Categorical slots 1-3 (dark steps), validated as a set; identity is also carried by the legend. */
val postureColor = mapOf(
    Posture.SITTING to Color(0xFF3987E5),
    Posture.STANDING to Color(0xFFD95926),
    Posture.WALKING to Color(0xFF199E70),
)
private val outOfPocket = Color(0xFF383835)
private val gridColor = Color(0x33FFFFFF)
private val inkMuted = Color(0xFF9A9A96)

/**
 * Posture timeline as background bands with sitting/standing load lines on one minutes axis.
 * Drag or tap for a crosshair readout.
 */
@Composable
fun LoadChart(history: List<HistPoint>, sitLimitMin: Int, standLimitMin: Int, nowMs: Long) {
    var rangeH by remember { mutableStateOf(4) }
    // Crosshair position as a fraction of the plot width (null = none).
    var cursor by remember { mutableStateOf<Float?>(null) }
    val measurer = rememberTextMeasurer()
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val end = nowMs
    val start = end - rangeH * 3600_000L
    val pts = history.filter { it.t >= start - HIST_LOOKBACK_MS }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 4, 12, 24).forEach { h ->
                FilterChip(rangeH == h, { rangeH = h; cursor = null }, label = { Text("${h}h") })
            }
        }
        val yMaxMin = maxOf(
            sitLimitMin, standLimitMin, 15,
            ((pts.maxOfOrNull { maxOf(it.sitLoadMs, it.standLoadMs) } ?: 0L) / 60_000).toInt() + 5,
        )
        Box {
            Canvas(
                Modifier.fillMaxWidth().height(200.dp)
                    .pointerInput(Unit) {
                        val left = CHART_LEFT.toPx()
                        fun frac(x: Float) = ((x - left) / (size.width - left)).coerceIn(0f, 1f)
                        detectTapGestures { cursor = frac(it.x) }
                    }
                    .pointerInput(Unit) {
                        val left = CHART_LEFT.toPx()
                        detectDragGestures { change, _ ->
                            cursor = ((change.position.x - left) / (size.width - left)).coerceIn(0f, 1f)
                        }
                    },
            ) {
                val left = CHART_LEFT.toPx()
                val bottom = size.height - 18.dp.toPx()
                val w = size.width - left
                fun x(t: Long) = left + (t - start).toFloat() / (end - start) * w
                fun y(ms: Long) = bottom - (ms / 60_000f) / yMaxMin * bottom

                // posture bands
                for ((i, p) in pts.withIndex()) {
                    val x0 = x(maxOf(p.t, start)).coerceAtLeast(left)
                    val x1 = x(pts.getOrNull(i + 1)?.t ?: end).coerceAtMost(size.width)
                    if (x1 <= x0) continue
                    val c = if (!p.inPocket) outOfPocket else postureColor[p.state]?.copy(alpha = 0.22f) ?: continue
                    drawRect(c, Offset(x0, 0f), Size(x1 - x0, bottom))
                }
                // grid + y labels every 15 min
                var m = 0
                while (m <= yMaxMin) {
                    val yy = y(m * 60_000L)
                    drawLine(gridColor, Offset(left, yy), Offset(size.width, yy), 1f)
                    label(measurer, "$m", Offset(0f, yy - 7.dp.toPx()))
                    m += if (yMaxMin > 90) 30 else 15
                }
                // x labels
                val stepH = if (rangeH <= 4) 1 else if (rangeH <= 12) 3 else 6
                val firstHour = (start / 3600_000L + 1) * 3600_000L
                var t = firstHour
                while (t < end) {
                    if ((t / 3600_000L) % stepH == 0L) label(measurer, fmt.format(Date(t)), Offset(x(t) - 14.dp.toPx(), bottom + 2.dp.toPx()))
                    t += 3600_000L
                }
                // limits (dashed) and load lines
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                for ((p, lim) in listOf(Posture.SITTING to sitLimitMin, Posture.STANDING to standLimitMin)) {
                    if (lim <= 0) continue
                    val yy = y(lim * 60_000L)
                    drawLine(postureColor[p]!!.copy(alpha = 0.7f), Offset(left, yy), Offset(size.width, yy), 1.5f, pathEffect = dash)
                }
                for ((p, sel) in listOf<Pair<Posture, (HistPoint) -> Long>>(
                    Posture.SITTING to { it.sitLoadMs }, Posture.STANDING to { it.standLoadMs },
                )) {
                    val path = Path()
                    pts.forEachIndexed { i, h ->
                        val o = Offset(x(h.t).coerceAtLeast(left), y(sel(h)))
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, postureColor[p]!!, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
                cursor?.let { f ->
                    val cx = left + f * w
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, bottom), 1f)
                }
            }
        }
        // readout for the crosshair, else the latest point
        val cursorT = cursor?.let { start + (it * (end - start)).toLong() }
        CursorReadout(pts, cursorT, fmt)
        Legend()
    }
}

private const val HIST_LOOKBACK_MS = 60_000L
private val CHART_LEFT = 36.dp

@Composable
private fun CursorReadout(pts: List<HistPoint>, cursorT: Long?, fmt: SimpleDateFormat) {
    val p = if (cursorT == null) pts.lastOrNull() else pts.lastOrNull { it.t <= cursorT } ?: pts.firstOrNull()
    if (p == null) {
        Text("No data yet — history fills in while tracking runs.", style = MaterialTheme.typography.bodySmall, color = inkMuted)
        return
    }
    val what = if (!p.inPocket) "out of pocket" else p.state.name.lowercase()
    Text(
        "${fmt.format(Date(cursorT ?: p.t))} · $what · sitting load ${p.sitLoadMs / 60_000} min · " +
            "standing load ${p.standLoadMs / 60_000} min",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        postureColor.forEach { (p, c) -> Swatch(c, p.name.lowercase()) }
        Swatch(outOfPocket, "out of pocket")
    }
    Text("Bands: detected posture · lines: load (min) · dashed: limit", style = MaterialTheme.typography.bodySmall, color = inkMuted)
}

@Composable
private fun Swatch(c: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.size(10.dp)) { drawRoundRect(c, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())) }
    Text(" $label", style = MaterialTheme.typography.bodySmall)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(m: TextMeasurer, s: String, at: Offset) {
    drawText(m, s, at, style = TextStyle(color = inkMuted, fontSize = 10.sp))
}
