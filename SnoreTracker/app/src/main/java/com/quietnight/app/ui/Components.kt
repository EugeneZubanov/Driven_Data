package com.quietnight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.quietnight.app.data.Clip
import com.quietnight.app.data.NightStats
import com.quietnight.app.data.formatDuration
import kotlin.math.abs
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

private fun ampFraction(amp: Int) = ((amp - 4) / 34f).coerceIn(0.12f, 1f)

/** Thin strip preview of a night for list rows. */
@Composable
fun MiniTimeline(session: Session, modifier: Modifier = Modifier) {
    Spacer(
        modifier.height(36.dp).drawWithCache {
            val count = (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1)
            val cols = NightStats.columns(session, session.startMs, session.endMs, count)
            val w = size.width / count
            val base = size.height * 0.3f
            onDrawBehind {
                drawRoundRect(Palette.Quiet, Offset(0f, size.height - base), Size(size.width, base), CornerRadius(3.dp.toPx()))
                for (c in 0 until count) {
                    val l = cols.levels[c]
                    if (l == 0) continue
                    val h = base + (size.height - base) * ampFraction(cols.amps[c])
                    drawRect(Palette.levels[l], Offset(c * w, size.height - h), Size((w - 2f).coerceAtLeast(1f), h))
                }
            }
        },
    )
}

private const val HOUR_MS = 3_600_000L
private const val CLIP_MS = 10_000L

/** Spoken summary of the timeline for screen readers. */
private fun timelineDescription(session: Session): String = buildString {
    append("Timeline from ${clock(session.startMs)} to ${clock(session.endMs)}. ")
    append("Snoring ${formatDuration(session.snoringSec)}.")
    NightStats.loudestHour(session)?.let { append(" Loudest hour from ${clock(it)}.") }
    if (session.clips.isNotEmpty()) append(" ${session.clips.size} saved clips, listed below.")
}

/**
 * The night as a bar timeline: bar height is loudness above the room's noise floor, colour is the
 * snoring level. Touch or drag sideways to read the time and level under the finger. Saved clips
 * are marked along the top; tap a marker to play it. The hour buttons below zoom to one hour.
 */
@Composable
fun TimelineChart(
    session: Session,
    modifier: Modifier = Modifier,
    playingClip: String? = null,
    playProgress: () -> Float = { 0f },
    onClipTap: ((Clip) -> Unit)? = null,
) {
    val hours = remember(session.id, session.startMs, session.endMs) { NightStats.hourStarts(session) }
    var zoom by rememberSaveable(session.id) { mutableStateOf<Long?>(null) }
    val winStart = zoom ?: session.startMs
    val winEnd = zoom?.plus(HOUR_MS) ?: maxOf(session.endMs, session.startMs + 1)
    var touchX by remember(session.id) { mutableStateOf<Float?>(null) }
    val measurer = rememberTextMeasurer()
    val description = remember(session) { timelineDescription(session) }
    val clips = remember(session, winStart, winEnd) {
        session.clips.filter { session.startMs + it.offsetSec * 1000L in winStart until winEnd }
    }
    val currentOnClipTap by rememberUpdatedState(onClipTap)

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (zoom == null) "${clock(session.startMs)} – ${clock(session.endMs)}" else "${clock(winStart)} – ${clock(winEnd)}",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
            Spacer(Modifier.weight(1f))
            if (zoom != null) {
                TextButton({ zoom = null }) { Text("Whole night", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .semantics { contentDescription = description }
                .pointerInput(session.id, winStart, clips) {
                    detectTapGestures(
                        onPress = { touchX = it.x; tryAwaitRelease(); touchX = null },
                        onTap = { pos ->
                            val tap = currentOnClipTap ?: return@detectTapGestures
                            if (pos.y > 30.dp.toPx()) return@detectTapGestures
                            val span = (winEnd - winStart).toFloat()
                            clips.minByOrNull { c ->
                                abs((session.startMs + c.offsetSec * 1000L + CLIP_MS / 2 - winStart) / span * size.width - pos.x)
                            }?.let { c ->
                                val x = (session.startMs + c.offsetSec * 1000L + CLIP_MS / 2 - winStart) / span * size.width
                                if (abs(x - pos.x) < 24.dp.toPx()) tap(c)
                            }
                        },
                    )
                }
                .pointerInput(session.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { touchX = it.x },
                        onDragEnd = { touchX = null },
                        onDragCancel = { touchX = null },
                    ) { change, _ -> touchX = change.position.x }
                }
                .drawWithCache {
                    val band = if (session.clips.isNotEmpty()) 18.dp.toPx() else 0f
                    val axisH = 20.dp.toPx()
                    val plotTop = band
                    val plotH = size.height - axisH - band
                    val plotBottom = plotTop + plotH
                    val count = (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1)
                    val cols = NightStats.columns(session, winStart, winEnd, count)
                    val w = size.width / count
                    val base = plotH * 0.1f
                    val span = (winEnd - winStart).toFloat()
                    fun xOf(t: Long) = (t - winStart) / span * size.width

                    // Clock-time ticks: whole hours (every 2 h for long nights), or 15 minutes when zoomed.
                    val ticks: List<Long> = if (zoom != null) {
                        (0..4).map { winStart + it * 15 * 60_000L }
                    } else {
                        val step = if (hours.size > 11) 2 else 1
                        hours.filter { it > winStart }.filterIndexed { i, _ -> i % step == 0 }
                    }
                    val labelStyle = TextStyle(color = Palette.Muted, fontSize = 11.sp)
                    val minGap = 6.dp.toPx()
                    var lastRight = -Float.MAX_VALUE
                    val labels = ticks.mapNotNull { t ->
                        val x = xOf(t)
                        val layout = measurer.measure(clock(t), labelStyle)
                        val left = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                        if (left < lastRight + minGap) null else {
                            lastRight = left + layout.size.width
                            Triple(x, left, layout)
                        }
                    }
                    val readoutStyle = TextStyle(color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    val coveredLeft = cols.firstCovered * w
                    val coveredRight = (cols.lastCovered + 1) * w

                    onDrawBehind {
                        // recessive grid
                        for (f in listOf(0.4f, 0.7f, 1f)) {
                            val y = plotBottom - base - (plotH - base) * f
                            drawLine(Palette.Outline.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1f)
                        }
                        if (cols.lastCovered >= 0) {
                            drawRect(Palette.Quiet, Offset(coveredLeft, plotBottom - base), Size(coveredRight - coveredLeft, base))
                        }
                        for (c in 0 until count) {
                            val l = cols.levels[c]
                            if (l == 0) continue
                            val h = base + (plotH - base) * ampFraction(cols.amps[c])
                            val bw = (w - 2f).coerceAtLeast(1f) // 2px gap between neighbouring bars
                            drawRoundRect(Palette.levels[l], Offset(c * w, plotBottom - h), Size(bw, h), CornerRadius(bw / 2.5f))
                        }

                        for ((x, left, layout) in labels) {
                            drawLine(Palette.Outline, Offset(x, plotBottom), Offset(x, plotBottom + 4.dp.toPx()), 1f)
                            drawText(layout, topLeft = Offset(left, plotBottom + 5.dp.toPx()))
                        }

                        // saved clips along the top
                        val tri = 6.dp.toPx()
                        for (clip in clips) {
                            val x = xOf(session.startMs + clip.offsetSec * 1000L + CLIP_MS / 2)
                            val isPlaying = clip.file == playingClip
                            val color = Palette.levels[clip.level.coerceIn(1, 3)]
                            if (isPlaying) drawCircle(Palette.Moon, tri * 1.25f, Offset(x, band / 2), style = Stroke(1.5.dp.toPx()))
                            val path = Path().apply {
                                moveTo(x - tri / 1.4f, band / 2 - tri / 2)
                                lineTo(x + tri / 1.4f, band / 2 - tri / 2)
                                lineTo(x, band / 2 + tri / 2)
                                close()
                            }
                            drawPath(path, if (isPlaying) Palette.Moon else color)
                        }

                        // playhead of the playing clip
                        clips.firstOrNull { it.file == playingClip }?.let { clip ->
                            val t = session.startMs + clip.offsetSec * 1000L + (CLIP_MS * playProgress()).toLong()
                            val x = xOf(t).coerceIn(0f, size.width)
                            drawLine(Palette.Moon, Offset(x, plotTop), Offset(x, plotBottom), 2.dp.toPx())
                        }

                        // touch read-out
                        touchX?.let { raw ->
                            val x = raw.coerceIn(0f, size.width - 1f)
                            val c = (x / w).toInt().coerceIn(0, count - 1)
                            val at = winStart + (span * (x / size.width)).toLong()
                            if (at < session.startMs || at > session.endMs) return@let
                            drawLine(Palette.Text.copy(alpha = 0.7f), Offset(x, plotTop), Offset(x, plotBottom), 1.5f)
                            val layout = measurer.measure("${clock(at)} · ${Level.names[cols.levels[c]]}", readoutStyle)
                            val pad = 6.dp.toPx()
                            val bw = layout.size.width + pad * 2
                            val bx = (x - bw / 2).coerceIn(0f, size.width - bw)
                            drawRoundRect(Palette.SurfaceHigh, Offset(bx, plotTop), Size(bw, layout.size.height + pad), CornerRadius(pad))
                            drawCircle(Palette.levels[cols.levels[c]], 4.dp.toPx(), Offset(x, plotTop + layout.size.height + pad + 6.dp.toPx()))
                            drawText(layout, topLeft = Offset(bx + pad, plotTop + pad / 2))
                        }
                    }
                },
        )
        if (hours.size > 1) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Zoom", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                for (h in hours) {
                    val selected = zoom == h
                    Text(
                        clock(h),
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Palette.Moon else Palette.SurfaceHigh)
                            .clickable(onClickLabel = if (selected) "Show whole night" else "Zoom to this hour") {
                                zoom = if (selected) null else h
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Palette.Background else Palette.Text,
                    )
                }
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
