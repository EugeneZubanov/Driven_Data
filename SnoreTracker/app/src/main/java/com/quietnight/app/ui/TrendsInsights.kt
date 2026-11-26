package com.quietnight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quietnight.app.data.Direction
import com.quietnight.app.data.HourSlot
import com.quietnight.app.data.Insights
import com.quietnight.app.data.Level
import com.quietnight.app.data.ReminderTime
import com.quietnight.app.data.Session
import com.quietnight.app.data.TagEffect
import com.quietnight.app.data.Tags
import com.quietnight.app.data.Trends
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val HOURLY_NIGHTS = 30

/** Mild / Loud / Heavy key for charts that only show snoring. */
@Composable
fun SnoreLevelKey(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (l in Level.MILD..Level.HEAVY) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Palette.levels[l]))
                Spacer(Modifier.width(6.dp))
                Text(Level.names[l], style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
    }
}

/** "When do you snore": share of recorded time spent snoring in each clock hour. */
@Composable
fun HourlyCard(sessions: List<Session>) {
    val slots = remember(sessions) { Insights.hourly(sessions, HOURLY_NIGHTS) }
    if (slots.isEmpty()) return
    val nights = minOf(sessions.size, HOURLY_NIGHTS)
    // Hours recorded on fewer than a quarter of the nights are too thin to compare; draw them faded.
    val thin = maxOf(3600L, nights * 900L)
    val is24 = rememberIs24Hour()
    val peak = slots.filter { it.recordedSec >= thin }.maxByOrNull { it.totalShare }
    Card {
        Column {
            SectionTitle("When you snore")
            Text(
                "Share of each hour spent snoring, over the last $nights ${if (nights == 1) "night" else "nights"}.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            HourlyChart(slots, thin, peak?.hour, is24)
            Spacer(Modifier.height(8.dp))
            SnoreLevelKey()
            if (peak != null && peak.totalShare > 0f) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Most snoring between ${ReminderTime.label(peak.hour * 60, is24)} and ${ReminderTime.label((peak.hour + 1) % 24 * 60, is24)} " +
                        "(${(peak.totalShare * 100).roundToInt()}% of that hour).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}


@Composable
private fun HourlyChart(slots: List<HourSlot>, thin: Long, peakHour: Int?, is24: Boolean) {
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(color = Palette.Muted, fontSize = 11.sp)
    val maxShare = max(slots.maxOf { it.totalShare }, 0.05f)
    // Round the top up to a tidy percentage.
    val step = if (maxShare > 0.4f) 20 else if (maxShare > 0.2f) 10 else 5
    val top = (ceil(maxShare * 100 / step) * step / 100f).coerceAtMost(1f)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val left = 34.dp.toPx()
        val labelH = 20.dp.toPx()
        val plotTop = 8.dp.toPx()
        val plotH = size.height - labelH - plotTop
        val slot = (size.width - left) / slots.size
        val barW = (slot - 2.dp.toPx()).coerceAtLeast(2f)
        val gap = 2.dp.toPx()
        fun y(v: Float) = plotTop + plotH * (1 - v / top)

        for (v in listOf(0f, top)) {
            drawLine(Palette.Outline.copy(alpha = if (v == 0f) 1f else 0.5f), Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            val l = measurer.measure("${(v * 100).roundToInt()}%", axis)
            drawText(l, topLeft = Offset(left - l.size.width - 6.dp.toPx(), y(v) - l.size.height / 2))
        }
        val every = ceil(slots.size / 6f).toInt().coerceAtLeast(1)
        slots.forEachIndexed { i, s ->
            val x = left + slot * i + (slot - barW) / 2
            val alpha = if (s.recordedSec < thin) 0.35f else 1f
            var acc = 0f
            for (p in 0 until 3) {
                val v = s.share(p)
                if (v <= 0f) continue
                val yb = y(acc); val yt = y(acc + v)
                val g = if (acc > 0f) gap else 0f
                drawRect(Palette.levels[p + 1].copy(alpha = alpha), Offset(x, yt), Size(barW, (yb - yt - g).coerceAtLeast(1f)))
                acc += v
            }
            if (i % every == 0) {
                val l = measurer.measure(ReminderTime.hourLabel(s.hour, is24), axis.copy(color = if (s.hour == peakHour) Palette.Text else Palette.Muted))
                val lx = (left + slot * i + slot / 2 - l.size.width / 2).coerceIn(left, size.width - l.size.width)
                drawText(l, topLeft = Offset(lx, size.height - labelH + 4.dp.toPx()))
            }
        }
    }
}

/** Rolling average, quiet run, best and worst nights, and the weekday pattern. */
@Composable
fun PatternsCard(sessions: List<Session>, onOpenNight: (String) -> Unit) {
    val rolling = remember(sessions) { Insights.rolling(sessions) } ?: return
    val run = remember(sessions) { Insights.quietRun(sessions) }
    val best = remember(sessions) { Insights.best(sessions) }
    val worst = remember(sessions) { Insights.worst(sessions) }
    Card {
        Column {
            SectionTitle("Patterns")
            Spacer(Modifier.height(8.dp))
            val avg = rolling.average.roundToInt()
            Text("Last ${rolling.nights} ${if (rolling.nights == 1) "night" else "nights"}: average $avg", fontWeight = FontWeight.Medium)
            val prev = rolling.previous
            Text(
                when (rolling.direction) {
                    Direction.DOWN -> "▼ Down from ${prev!!.roundToInt()} the 7 nights before"
                    Direction.UP -> "▲ Up from ${prev!!.roundToInt()} the 7 nights before"
                    Direction.STEADY -> "About the same as the 7 nights before (${prev!!.roundToInt()})"
                    null -> "Record 14 nights to see which way it is heading."
                },
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when (run) {
                    0 -> "No quiet run right now"
                    1 -> "1 quiet night in a row"
                    else -> "$run quiet nights in a row"
                },
                fontWeight = FontWeight.Medium,
            )
            Text("A quiet night scores ${Insights.QUIET_SCORE} or less.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)

            if (sessions.size >= 2 && best != null && worst != null) {
                Spacer(Modifier.height(6.dp))
                NightRow("Quietest night", best, onOpenNight)
                NightRow("Loudest night", worst, onOpenNight)
            }

            val weekdays = remember(sessions) { Insights.byWeekday(sessions) }
            if (sessions.size >= 7) {
                Spacer(Modifier.height(10.dp))
                Text("By night of the week", fontWeight = FontWeight.Medium)
                Text("Average score; the night belongs to the evening it started.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                val maxAvg = max(weekdays.maxOf { it.avgScore }, 1f)
                val name = SimpleDateFormat("EEE", Locale.getDefault())
                for (w in weekdays) {
                    val label = name.format(Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, w.dayOfWeek) }.time)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(44.dp), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        BoxWithConstraints(Modifier.weight(1f).height(14.dp)) {
                            if (w.nights > 0) {
                                Box(
                                    Modifier.width(maxOf(maxWidth * (w.avgScore / maxAvg), 2.dp)).height(14.dp)
                                        .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).background(ScoreRamp.colors[3]),
                                )
                            }
                        }
                        Text(
                            if (w.nights == 0) "–" else w.avgScore.roundToInt().toString(),
                            Modifier.width(36.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
                        )
                        Text("${w.nights}n", Modifier.width(32.dp), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("n = nights recorded", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun NightRow(label: String, s: Session, onOpenNight: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpenNight(s.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            Text(SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(s.endMs)), style = MaterialTheme.typography.bodyMedium)
        }
        Text("Score ${s.score}", fontWeight = FontWeight.Medium)
        Text("  ›", color = Palette.Muted)
    }
}

/** Nights with each tag compared with nights without, only once there are enough of both. */
@Composable
fun TagEffectsCard(sessions: List<Session>) {
    val result = remember(sessions) { Trends.tagEffects(sessions) }
    if (result.effects.isEmpty() && result.pending.isEmpty()) return
    Card {
        Column {
            SectionTitle("Nights with and without")
            Text(
                "Average score on nights tagged with each factor or remedy, compared with nights without it.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            if (result.effects.isNotEmpty()) {
                val span = max(result.effects.maxOf { max(abs(it.low), abs(it.high)) }, 5f)
                for (e in result.effects) {
                    Spacer(Modifier.height(10.dp))
                    EffectRow(e, span)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("◀ quieter with", Modifier.weight(1f), color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
                    Text("louder with ▶", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "Bars show the likely range of the difference. A wide bar crossing the centre line means it could be chance.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (result.pending.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Not enough nights yet", fontWeight = FontWeight.Medium)
                Text(
                    "Each needs ${Trends.MIN_NIGHTS} nights with it and ${Trends.MIN_NIGHTS} without: " +
                        result.pending.joinToString(", ") { "${Tags.label(it.tag)} (${it.nights} with, ${it.withoutNights} without)" } + ".",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "This shows which nights went together, not what caused what. Other things may have changed on the same nights.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EffectRow(e: TagEffect, span: Float) {
    val d = e.delta.roundToInt()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tags.label(e.tag), Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(
                when {
                    !e.consistent -> "no clear difference"
                    d < 0 -> "${abs(d)} lower"
                    else -> "$d higher"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "With: ${e.withAvg.roundToInt()} avg, ${e.nights} nights · without: ${e.withoutAvg.roundToInt()} avg, ${e.withoutNights} nights",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val mid = size.width / 2
            fun x(v: Float) = mid + (v / span).coerceIn(-1f, 1f) * (size.width / 2 - 6.dp.toPx())
            val cy = size.height / 2
            drawLine(Palette.Outline.copy(alpha = 0.5f), Offset(0f, cy), Offset(size.width, cy), 1f)
            drawLine(Palette.Muted, Offset(mid, 0f), Offset(mid, size.height), 1.5f)
            val color = if (e.consistent) Palette.Text else Palette.Muted
            drawRoundRect(
                color.copy(alpha = 0.35f), Offset(x(e.low), cy - 3.dp.toPx()), Size(x(e.high) - x(e.low), 6.dp.toPx()),
                CornerRadius(3.dp.toPx()),
            )
            drawCircle(color, 4.dp.toPx(), Offset(x(e.delta), cy))
        }
    }
}
