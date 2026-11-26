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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.FilterChip
import androidx.compose.ui.semantics.semantics
import com.quietnight.app.data.Clip
import com.quietnight.app.data.Span
import com.quietnight.app.data.NightStats
import com.quietnight.app.data.formatDuration
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Date
import java.util.Locale

/** Device 12/24-hour setting for [clock]; set once from the app root (see MainActivity). */
object ClockFormat {
    var is24 by mutableStateOf(true)
}

/** "23:05" or "11:05 PM". */
fun formatClock(ms: Long, is24: Boolean, locale: Locale = Locale.getDefault(), tz: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", locale).apply { timeZone = tz }.format(Date(ms))

/** Short label for a whole hour: "23:00" or "11 PM". */
fun formatHour(ms: Long, is24: Boolean, locale: Locale = Locale.getDefault(), tz: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat(if (is24) "HH:mm" else "h a", locale).apply { timeZone = tz }.format(Date(ms))

/** Clock time in the device's 12/24-hour format. */
fun clock(ms: Long): String = formatClock(ms, ClockFormat.is24)

/** Whole-hour label in the device's 12/24-hour format. */
fun hourLabel(ms: Long): String = formatHour(ms, ClockFormat.is24)

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
    Text(text, modifier = modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** Top-of-tab title used by Results, Trends and Settings: same size, weight and top spacing, marked as a heading. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text, Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            )
            trailing?.invoke()
        }
        if (subtitle != null) Text(subtitle, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One choice in a row of options or filters; the same look as the Settings and Record chips. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = modifier)
}

/**
 * Ring showing how the night was split between snoring levels (the unlit part is quiet time),
 * with the score in the middle.
 */
@Composable
fun ScoreDial(session: Session, size: Dp, modifier: Modifier = Modifier) {
    val total = session.listenedSec.coerceAtLeast(1).toFloat()
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
fun LevelLegend(modifier: Modifier = Modifier, showGaps: Boolean = false) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0..3) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Palette.levels[i]))
                Spacer(Modifier.width(6.dp))
                Text(Level.names[i], style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
        if (showGaps) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp))) { drawGap(0f, 0f, size.width, size.height, 3.dp.toPx()) }
                Spacer(Modifier.width(6.dp))
                Text("No sound", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
    }
}

/** Bars the user marked as not snoring: neutral, so they read as neither snoring nor quiet. */
val NotSnoringColor get() = Palette.Muted.copy(alpha = 0.4f)

/** Neutral hatched area for time with no audio, so it does not read as quiet sleep. */
fun DrawScope.drawGap(left: Float, top: Float, right: Float, bottom: Float, spacing: Float) {
    if (right <= left) return
    drawRect(Palette.Outline.copy(alpha = 0.35f), Offset(left, top), Size(right - left, bottom - top))
    clipRect(left, top, right, bottom) {
        val h = bottom - top
        var x = left - h
        while (x < right) {
            drawLine(Palette.Muted.copy(alpha = 0.45f), Offset(x, bottom), Offset(x + h, top), 1f)
            x += spacing
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
                for (g in session.gaps) {
                    val span = (session.endMs - session.startMs).coerceAtLeast(1).toFloat()
                    val l = (g.fromSec * 1000f / span * size.width).coerceIn(0f, size.width)
                    val r = (g.toSec * 1000f / span * size.width).coerceIn(0f, size.width)
                    drawGap(l, 0f, r, size.height, 4.dp.toPx())
                }
                for (c in 0 until count) {
                    val l = cols.levels[c]
                    if (l == 0 || cols.gaps[c]) continue
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
    if (session.gapSec > 0) append(" No sound for ${formatDuration(session.gapSec)}.")
    if (session.interruptedAtMs > 0) append(" Cut short at ${clock(session.interruptedAtMs)}.")
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
    /** Highlighted stretch, e.g. one picked for marking as not snoring. */
    selection: Span? = null,
    /** Touch and hold: the bucket index under the finger. Null turns it off. */
    onLongPressBucket: ((Int) -> Unit)? = null,
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
    val currentOnLongPress by rememberUpdatedState(onLongPressBucket)
    // Keep the playing clip on screen: if it is outside the zoomed hour, move to its hour.
    LaunchedEffect(playingClip) {
        val clip = session.clips.firstOrNull { it.file == playingClip } ?: return@LaunchedEffect
        val z = zoom ?: return@LaunchedEffect
        val t = session.startMs + clip.offsetSec * 1000L
        if (t !in z until z + HOUR_MS) zoom = hours.lastOrNull { it <= t } ?: z
    }

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
                        onLongPress = { pos ->
                            val cb = currentOnLongPress ?: return@detectTapGestures
                            val t = winStart + ((winEnd - winStart) * (pos.x / size.width)).toLong()
                            val b = ((t - session.startMs) / (session.bucketSec * 1000L)).toInt()
                            if (b in session.levels.indices) cb(b)
                        },
                        onTap = { pos ->
                            val tap = currentOnClipTap ?: return@detectTapGestures
                            if (pos.y > 40.dp.toPx()) return@detectTapGestures
                            val span = (winEnd - winStart).toFloat()
                            clips.minByOrNull { c ->
                                abs((session.startMs + c.offsetSec * 1000L + CLIP_MS / 2 - winStart) / span * size.width - pos.x)
                            }?.let { c ->
                                val x = (session.startMs + c.offsetSec * 1000L + CLIP_MS / 2 - winStart) / span * size.width
                                if (abs(x - pos.x) < 20.dp.toPx()) tap(c) // 40dp wide target
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
                    val band = if (session.clips.isNotEmpty()) 22.dp.toPx() else 0f
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
                        val layout = measurer.measure(if (zoom == null) hourLabel(t) else clock(t), labelStyle)
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
                            if (cols.gaps[c]) continue
                            val bw = (w - 2f).coerceAtLeast(1f) // 2px gap between neighbouring bars
                            if (l == 0) {
                                // detected, then marked not snoring by the user: neutral
                                if (cols.corrected[c] == 0) continue
                                val h = base + (plotH - base) * ampFraction(cols.corrected[c])
                                drawRoundRect(NotSnoringColor, Offset(c * w, plotBottom - h), Size(bw, h), CornerRadius(bw / 2.5f))
                                continue
                            }
                            val h = base + (plotH - base) * ampFraction(cols.amps[c])
                            drawRoundRect(Palette.levels[l], Offset(c * w, plotBottom - h), Size(bw, h), CornerRadius(bw / 2.5f))
                        }
                        selection?.let { sel ->
                            val l = xOf(session.startMs + sel.fromSec * 1000L).coerceIn(0f, size.width)
                            val r = xOf(session.startMs + sel.toSec * 1000L).coerceIn(0f, size.width)
                            if (r > l) {
                                drawRect(Palette.Moon.copy(alpha = 0.16f), Offset(l, plotTop), Size(r - l, plotH))
                                drawLine(Palette.Moon, Offset(l, plotTop), Offset(l, plotBottom), 1.5f)
                                drawLine(Palette.Moon, Offset(r, plotTop), Offset(r, plotBottom), 1.5f)
                            }
                        }

                        // stretches with no audio
                        for (g in session.gaps) {
                            val l = xOf(session.startMs + g.fromSec * 1000L).coerceIn(0f, size.width)
                            val r = xOf(session.startMs + g.toSec * 1000L).coerceIn(0f, size.width)
                            drawGap(l, plotTop, r, plotBottom, 6.dp.toPx())
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
                            val corrected = session.notSnoringMask.getOrElse(clip.offsetSec / session.bucketSec) { false }
                            val color = if (corrected) NotSnoringColor else Palette.levels[clip.level.coerceIn(1, 3)]
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
                            val what = when {
                                cols.gaps[c] -> "No sound"
                                cols.levels[c] == 0 && cols.corrected[c] > 0 -> "Not snoring"
                                else -> Level.names[cols.levels[c]]
                            }
                            val layout = measurer.measure("${clock(at)} · $what", readoutStyle)
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
                    ChoiceChip(hourLabel(h), selected) { zoom = if (selected) null else h }
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
