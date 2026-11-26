package com.quietnight.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.quietnight.app.data.ReportData
import com.quietnight.app.data.Tags
import com.quietnight.app.data.Trends
import com.quietnight.app.data.Weight
import com.quietnight.app.data.formatDuration
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the one-page 30-night report on a light background for printing. Units are PDF points
 * (A4 = 595 × 842); the same code draws into a PdfDocument page or a bitmap for tests.
 */
object ReportRenderer {
    const val PAGE_W = 595
    const val PAGE_H = 842
    private const val M = 40f

    private val ink = Color.rgb(0x1B, 0x1F, 0x36)
    private val muted = Color.rgb(0x5A, 0x60, 0x80)
    private val rule = Color.rgb(0xD5, 0xD8, 0xE4)
    private val levelColors = intArrayOf(
        Color.rgb(0x3B, 0x8E, 0xEA), Color.rgb(0xBC, 0x96, 0x1C), Color.rgb(0xE0, 0x45, 0x7B),
    )
    private val weightColor = Color.rgb(0x5B, 0x4B, 0xB0)

    private fun text(size: Float, color: Int = ink, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    /** Writes the report as a one-page PDF. */
    fun writePdf(data: ReportData, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
            draw(page.canvas, data)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun draw(c: Canvas, d: ReportData) {
        c.drawColor(Color.WHITE)
        val w = PAGE_W - 2 * M
        var y = M + 18f
        c.drawText("Quiet Night – ${d.days}-night report", M, y, text(20f, bold = true))
        y += 18f
        c.drawText(d.rangeLabel(), M, y, text(11f, muted))
        y += 28f

        // Summary figures.
        val stats = listOf(
            "Nights recorded" to "${d.nights.size} of ${d.days}",
            "Average snore score" to if (d.hasNights) "${d.averageScore}" else "–",
            "Time snoring" to formatDuration(d.snoringSec.sum()),
            "Share of recorded time" to if (d.recordedSec > 0) "${(100.0 * d.snoringSec.sum() / d.recordedSec).roundToInt()}%" else "–",
        )
        val colW = w / stats.size
        stats.forEachIndexed { i, (label, value) ->
            c.drawText(value, M + i * colW, y, text(18f, bold = true))
            c.drawText(label, M + i * colW, y + 14f, text(9f, muted))
        }
        y += 34f
        c.drawText("Score: share of the night spent snoring, louder snoring counting more. 0 = no snoring; lower is quieter.", M, y, text(8.5f, muted))
        y += 22f

        y = nightlyChart(c, d, y, w)
        y = levelSplit(c, d, y + 18f, w)
        y = tagTable(c, d, y + 18f, w)
        if (d.weight.isNotEmpty()) y = weightChart(c, d, y + 18f, w)

        val footer = text(9f, muted)
        c.drawLine(M, PAGE_H - M - 16f, PAGE_W - M, PAGE_H - M - 16f, fill(rule))
        c.drawText(ReportData.NOTE, M, PAGE_H - M, footer)
    }

    private fun section(c: Canvas, title: String, y: Float): Float {
        c.drawText(title, M, y, text(12f, bold = true))
        return y + 14f
    }

    private fun legend(c: Canvas, x0: Float, y: Float) {
        var x = x0
        val t = text(9f, muted)
        listOf("Mild", "Loud", "Heavy").forEachIndexed { i, name ->
            c.drawRoundRect(RectF(x, y - 7f, x + 8f, y + 1f), 1.5f, 1.5f, fill(levelColors[i]))
            c.drawText(name, x + 11f, y, t)
            x += 11f + t.measureText(name) + 12f
        }
    }

    private fun nightlyChart(c: Canvas, d: ReportData, y0: Float, w: Float): Float {
        var y = section(c, "Nightly score", y0)
        legend(c, PAGE_W - M - 140f, y0)
        val h = 120f
        val left = M + 22f
        val plotW = w - 22f
        val byDay = d.nights.groupBy { Trends.dayOf(it).timeInMillis }
        val maxScore = max(d.nights.maxOfOrNull { it.scoreParts.sum() } ?: 0f, 10f)
        val top = ceil(maxScore / 10f) * 10f
        fun yv(v: Float) = y + h * (1 - v / top)
        val axis = text(8f, muted)
        for (v in listOf(0f, top / 2, top)) {
            c.drawLine(left, yv(v), left + plotW, yv(v), fill(if (v == 0f) muted else rule))
            val s = v.roundToInt().toString()
            c.drawText(s, left - 4f - axis.measureText(s), yv(v) + 3f, axis)
        }
        val slot = plotW / d.days
        val barW = max(slot - 2f, 1f)
        val dayMs = 86_400_000L
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        for (i in 0 until d.days) {
            val key = Trends.dayOf(d.fromMs + i * dayMs + dayMs / 2).timeInMillis
            val x = left + i * slot + (slot - barW) / 2
            byDay[key]?.maxByOrNull { it.startMs }?.let { s ->
                var acc = 0f
                for (p in 0 until 3) {
                    val v = s.scoreParts[p]
                    if (v <= 0f) continue
                    val gap = if (acc > 0f) 1f else 0f
                    c.drawRect(x, yv(acc + v), x + barW, yv(acc) - gap, fill(levelColors[p]))
                    acc += v
                }
            }
            if (i == 0 || i == d.days - 1 || i == d.days / 2) {
                val label = fmt.format(Date(key))
                val lx = (x + barW / 2 - axis.measureText(label) / 2).coerceIn(left, left + plotW - axis.measureText(label))
                c.drawText(label, lx, y + h + 11f, axis)
            }
        }
        if (!d.hasNights) c.drawText("No nights recorded in this period.", left + 8f, y + h / 2, text(10f, muted))
        return y + h + 16f
    }

    private fun levelSplit(c: Canvas, d: ReportData, y0: Float, w: Float): Float {
        var y = section(c, "Time snoring by level", y0)
        val total = d.snoringSec.sum()
        if (total == 0) {
            c.drawText("No snoring detected.", M, y + 8f, text(10f, muted))
            return y + 14f
        }
        var x = M
        for (i in 0 until 3) {
            val seg = w * d.snoringSec[i] / total
            if (seg <= 0f) continue
            c.drawRect(x, y, x + seg - if (i < 2) 2f else 0f, y + 12f, fill(levelColors[i]))
            x += seg
        }
        y += 26f
        val t = text(10f)
        val line = listOf("Mild", "Loud", "Heavy").mapIndexed { i, n ->
            "$n ${formatDuration(d.snoringSec[i])} (${(100.0 * d.snoringSec[i] / total).roundToInt()}%)"
        }.joinToString("   ·   ")
        c.drawText(line, M, y, t)
        return y + 4f
    }

    private fun tagTable(c: Canvas, d: ReportData, y0: Float, w: Float): Float {
        var y = section(c, "Nights with and without each factor or remedy", y0)
        val small = text(9f, muted)
        if (d.tags.effects.isEmpty()) {
            c.drawText("Not enough tagged nights to compare yet (needs ${Trends.MIN_NIGHTS} with and ${Trends.MIN_NIGHTS} without).", M, y + 6f, small)
            return y + 12f
        }
        val cols = floatArrayOf(0f, 0.30f, 0.50f, 0.70f)
        val head = text(9f, muted, bold = true)
        listOf("Tag", "With: avg (nights)", "Without: avg (nights)", "Difference (likely range)").forEachIndexed { i, s ->
            c.drawText(s, M + cols[i] * w, y + 6f, head)
        }
        y += 12f
        val body = text(10f)
        for (e in d.tags.effects.take(8)) {
            y += 14f
            c.drawLine(M, y - 10f, M + w, y - 10f, fill(rule))
            c.drawText(Tags.label(e.tag), M, y, body)
            c.drawText("${e.withAvg.roundToInt()} (${e.nights})", M + cols[1] * w, y, body)
            c.drawText("${e.withoutAvg.roundToInt()} (${e.withoutNights})", M + cols[2] * w, y, body)
            val d0 = e.delta.roundToInt()
            val verdict = when {
                !e.consistent -> "no clear difference"
                d0 < 0 -> "${abs(d0)} lower"
                else -> "$d0 higher"
            }
            c.drawText("$verdict  (${e.low.roundToInt()} to ${e.high.roundToInt()})", M + cols[3] * w, y, body)
        }
        y += 14f
        c.drawText("Shows which nights went together, not what caused what.", M, y, small)
        return y + 2f
    }

    private fun weightChart(c: Canvas, d: ReportData, y0: Float, w: Float): Float {
        var y = section(c, "Weight (${Weight.unit(d.pounds)})", y0)
        val first = d.weight.first(); val last = d.weight.last()
        c.drawText(
            "${Weight.format(first.kg, d.pounds)} → ${Weight.format(last.kg, d.pounds)} · ${d.weight.size} ${if (d.weight.size == 1) "reading" else "readings"}",
            M, y + 6f, text(10f),
        )
        y += 14f
        val h = 50f
        val left = M + 30f
        val plotW = w - 30f
        val vals = d.weight.map { Weight.toDisplay(it.kg, d.pounds) }
        val lo = vals.min(); val hi = vals.max()
        val pad = max((hi - lo) * 0.15f, 0.5f)
        fun xv(t: Long) = left + plotW * ((t - d.fromMs).toFloat() / max(d.toMs - d.fromMs, 1L))
        fun yv(v: Float) = y + h * (1 - (v - (lo - pad)) / ((hi + pad) - (lo - pad)))
        val axis = text(8f, muted)
        for (v in listOf(lo, hi).distinct()) {
            c.drawLine(left, yv(v), left + plotW, yv(v), fill(rule))
            val s = ((v * 10).roundToInt() / 10f).toString()
            c.drawText(s, left - 4f - axis.measureText(s), yv(v) + 3f, axis)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = weightColor; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        val path = Path()
        d.weight.forEachIndexed { i, e ->
            val x = xv(e.dayMs); val yy = yv(vals[i])
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            c.drawCircle(x, yy, 2f, fill(weightColor))
        }
        if (d.weight.size > 1) c.drawPath(path, stroke)
        return y + h + 6f
    }
}
