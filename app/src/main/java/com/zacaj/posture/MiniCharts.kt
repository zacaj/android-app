package com.zacaj.posture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.zacaj.posture.core.Posture

/** Small android.graphics renderings for the ongoing notification and the home-screen widget. */
object MiniCharts {
    private val colors = mapOf(
        Posture.SITTING to 0xFF3987E5.toInt(),
        Posture.STANDING to 0xFFD95926.toInt(),
        Posture.WALKING to 0xFF199E70.toInt(),
    )
    private const val OUT_OF_POCKET = 0xFF383835.toInt()
    private const val MUTED = 0xFF9A9A96.toInt()

    fun color(p: Posture?) = colors[p] ?: MUTED

    /**
     * Notification background: the last [windowMs] as faint posture bands, with the sitting/standing
     * loads as lines scaled so the top edge is the larger limit.
     */
    fun strip(history: List<HistPoint>, nowMs: Long, sitLimitMin: Int, standLimitMin: Int,
              w: Int = 720, h: Int = 128, windowMs: Long = 2 * 3600_000L): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val start = nowMs - windowMs
        fun x(t: Long) = ((t - start).toFloat() / windowMs * w).coerceIn(0f, w.toFloat())
        val top = maxOf(sitLimitMin, standLimitMin, 15) * 60_000f
        fun y(ms: Long) = h - 2f - (ms / top).coerceAtMost(1f) * (h - 4f)
        val pts = history.filter { it.t >= start - 60_000 }
        val band = Paint()
        for ((i, p) in pts.withIndex()) {
            val x0 = x(p.t); val x1 = x(pts.getOrNull(i + 1)?.t ?: nowMs)
            if (x1 <= x0) continue
            band.color = if (!p.inPocket) OUT_OF_POCKET else color(p.state)
            band.alpha = if (!p.inPocket) 120 else 70
            if (p.inPocket && p.state == Posture.UNKNOWN) continue
            c.drawRect(x0, 0f, x1, h.toFloat(), band)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = h / 40f; strokeCap = Paint.Cap.ROUND
        }
        for ((p, sel) in listOf<Pair<Posture, (HistPoint) -> Long>>(
            Posture.SITTING to { it.sitLoadMs }, Posture.STANDING to { it.standLoadMs },
        )) {
            if (pts.size < 2) break
            line.color = color(p); line.alpha = 200
            val path = android.graphics.Path()
            pts.forEachIndexed { i, hp -> if (i == 0) path.moveTo(x(hp.t), y(sel(hp))) else path.lineTo(x(hp.t), y(sel(hp))) }
            c.drawPath(path, line)
        }
        return bmp
    }

    /** 1x1 widget: current state label on top, sitting/standing load bars as % of limit below. */
    fun widget(s: Status, sitLimitMin: Int, standLimitMin: Int, size: Int = 220): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = size / 220f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61C1C1A.toInt() }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 28 * f, 28 * f, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = 34 * f; isFakeBoldText = true
        }
        val (label, labelColor) = when {
            !s.running -> "off" to MUTED
            !s.inPocket -> "pocket?" to MUTED
            s.posture == Posture.UNKNOWN -> "…" to MUTED
            else -> s.posture.name.lowercase() to color(s.posture)
        }
        text.color = labelColor
        c.drawText(label, size / 2f, 46 * f, text)

        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 22 * f; color = MUTED }
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF33332F.toInt() }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val over = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * f; color = 0xFFFFFFFF.toInt() }
        val barTop = 66 * f; val barBottom = 176 * f; val barW = 56 * f
        for ((i, triple) in listOf(
            Triple(Posture.SITTING, s.sitLoadMs, sitLimitMin),
            Triple(Posture.STANDING, s.standLoadMs, standLimitMin),
        ).withIndex()) {
            val (p, loadMs, limit) = triple
            val cx = size * (if (i == 0) 0.3f else 0.7f)
            val r = RectF(cx - barW / 2, barTop, cx + barW / 2, barBottom)
            c.drawRoundRect(r, 10 * f, 10 * f, track)
            val frac = if (limit > 0) loadMs / (limit * 60_000f) else 0f
            fill.color = color(p)
            if (!s.running) fill.alpha = 90
            val ft = barBottom - frac.coerceIn(0f, 1f) * (barBottom - barTop)
            if (frac > 0f) c.drawRoundRect(RectF(r.left, ft, r.right, r.bottom), 10 * f, 10 * f, fill)
            if (frac >= 1f) c.drawRoundRect(r, 10 * f, 10 * f, over)
            val pct = if (limit > 0) "${(frac * 100).toInt()}%" else "${loadMs / 60_000}m"
            small.color = if (s.running && s.posture == p && s.inPocket) color(p) else MUTED
            c.drawText(if (i == 0) "sit $pct" else "std $pct", cx, 204 * f, small)
        }
        return bmp
    }
}
