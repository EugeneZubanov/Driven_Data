package com.quietnight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quietnight.app.data.Level
import com.quietnight.app.data.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .padding(16.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/**
 * Ring showing how the night was split between snoring levels (the unlit part is quiet time),
 * with the score in the middle.
 */
@Composable
fun ScoreDial(session: Session, size: Dp, modifier: Modifier = Modifier) {
    val total = (session.levels.size * session.bucketSec).coerceAtLeast(1).toFloat()
    val shares = FloatArray(3) { session.secondsByLevel[it + 1] / total }
    ScoreDial(session.score, shares, size, modifier)
}

@Composable
fun ScoreDial(score: Int, shares: FloatArray, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.13f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            val sweepAll = 270f
            drawArc(Palette.Quiet, 135f, sweepAll, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            var start = 135f
            val gap = 2.5f
            for (i in 0 until 3) {
                val sweep = sweepAll * shares[i]
                if (sweep <= 0f) continue
                // Keep even a sliver visible, and leave a small gap between segments.
                val drawn = (sweep - gap).coerceAtLeast(1.5f)
                drawArc(Palette.levels[i + 1], start, drawn, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        Text(
            score.toString(),
            fontSize = (size.value * 0.34f).sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Text,
        )
    }
}

@Composable
fun LevelLegend(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0..3) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Palette.levels[i]))
                Spacer(Modifier.width(6.dp))
                Text(Level.names[i], style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
    }
}

/** Columns of the timeline: several buckets merged per column, keeping the strongest one. */
private fun columns(session: Session, count: Int): Pair<IntArray, IntArray> {
    val lv = IntArray(count)
    val amp = IntArray(count)
    val n = session.levels.size
    if (n == 0) return lv to amp
    for (b in 0 until n) {
        val c = (b.toLong() * count / n).toInt().coerceAtMost(count - 1)
        val l = session.levels[b].toInt()
        val a = session.amps[b].toInt()
        if (l > lv[c] || (l == lv[c] && a > amp[c])) { lv[c] = l; amp[c] = a }
    }
    return lv to amp
}

private fun ampFraction(amp: Int) = ((amp - 4) / 34f).coerceIn(0.12f, 1f)

/** Thin strip preview of a night for list rows. */
@Composable
fun MiniTimeline(session: Session, modifier: Modifier = Modifier) {
    Canvas(modifier.height(36.dp)) {
        val count = (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1)
        val (lv, amp) = columns(session, count)
        val w = size.width / count
        val base = size.height * 0.3f
        drawRoundRect(Palette.Quiet, Offset(0f, size.height - base), Size(size.width, base), CornerRadius(3.dp.toPx()))
        for (c in 0 until count) {
            if (lv[c] == 0) continue
            val h = base + (size.height - base) * ampFraction(amp[c])
            drawRect(Palette.levels[lv[c]], Offset(c * w, size.height - h), Size((w - 1f).coerceAtLeast(1f), h))
        }
    }
}

/**
 * The night as a bar timeline: bar height is loudness above the room's noise floor, colour is the
 * snoring level. Touch or drag to read the time and level under the finger.
 */
@Composable
fun TimelineChart(session: Session, modifier: Modifier = Modifier) {
    var touchX by remember(session.id) { mutableStateOf<Float?>(null) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.Muted, fontSize = 11.sp)

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            val readout = touchX
            if (readout == null) {
                Text(clock(session.startMs), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Spacer(Modifier.weight(1f))
                Text(clock(session.endMs), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .pointerInput(session.id) {
                    detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease(); touchX = null })
                }
                .pointerInput(session.id) {
                    detectDragGestures(
                        onDragStart = { touchX = it.x },
                        onDragEnd = { touchX = null },
                        onDragCancel = { touchX = null },
                    ) { change, _ -> touchX = change.position.x }
                },
        ) {
            val axisH = 20.dp.toPx()
            val plotH = size.height - axisH
            val count = (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1)
            val (lv, amp) = columns(session, count)
            val w = size.width / count
            val base = plotH * 0.1f

            // recessive grid
            for (f in listOf(0.4f, 0.7f, 1f)) {
                val y = plotH - base - (plotH - base) * f
                drawLine(Palette.Outline.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            drawRect(Palette.Quiet, Offset(0f, plotH - base), Size(size.width, base))
            for (c in 0 until count) {
                if (lv[c] == 0) continue
                val h = base + (plotH - base) * ampFraction(amp[c])
                drawRoundRect(
                    Palette.levels[lv[c]],
                    Offset(c * w, plotH - h),
                    Size((w - 1f).coerceAtLeast(1f), h),
                    CornerRadius(w / 2.5f),
                )
            }

            // hour marks along the bottom
            val hours = session.durationSec / 3600f
            val step = if (hours > 10) 2 else 1
            var hmark = step
            while (hmark < hours) {
                val x = size.width * hmark / hours
                val layout = measurer.measure("${hmark}h", labelStyle)
                drawLine(Palette.Outline, Offset(x, plotH), Offset(x, plotH + 4.dp.toPx()), 1f)
                if (x + layout.size.width / 2 < size.width) {
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2, plotH + 5.dp.toPx()))
                }
                hmark += step
            }

            // touch read-out
            touchX?.let { raw ->
                val x = raw.coerceIn(0f, size.width - 1f)
                val c = (x / w).toInt().coerceIn(0, count - 1)
                drawLine(Palette.Text.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, plotH), 1.5f)
                val at = session.startMs + ((session.endMs - session.startMs) * (x / size.width)).toLong()
                val text = "${clock(at)} · ${Level.names[lv[c]]}"
                val layout = measurer.measure(text, TextStyle(color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                val pad = 6.dp.toPx()
                val bw = layout.size.width + pad * 2
                val bx = (x - bw / 2).coerceIn(0f, size.width - bw)
                drawRoundRect(Palette.SurfaceHigh, Offset(bx, 0f), Size(bw, layout.size.height + pad), CornerRadius(pad))
                drawCircle(Palette.levels[lv[c]], 4.dp.toPx(), Offset(x, layout.size.height + pad + 6.dp.toPx()))
                drawText(layout, topLeft = Offset(bx + pad, pad / 2))
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(dot))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
