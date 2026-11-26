package com.quietnight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quietnight.app.data.Insights
import com.quietnight.app.data.Session
import com.quietnight.app.data.Trends
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Single-hue (violet) sequential ramp for score bands, dim to bright on the dark surface. */
object ScoreRamp {
    val colors = listOf(Color(0xFF34305F), Color(0xFF4B3F8F), Color(0xFF6A55BF), Color(0xFF9077E0), Color(0xFFBCA8F5))

    /** Day-number colour that stays readable on each band. */
    fun textOn(band: Int): Color = if (band >= 3) Color(0xFF1B1F36) else Palette.Text
}

/**
 * Month grid of nights, each recorded day shaded by its score. Tapping a recorded day calls [onOpenNight]
 * with that night's session id. Opens on the month of the latest night.
 */
@Composable
fun NightCalendar(sessions: List<Session>, onOpenNight: (String) -> Unit, todayMs: Long = System.currentTimeMillis()) {
    val byDay = remember(sessions) { Insights.byDay(sessions) }
    // Start on the month of the latest night.
    val latest = sessions.maxOfOrNull { it.endMs } ?: todayMs
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val month = remember(latest, offset) {
        Trends.dayOf(latest).apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, offset) }
    }
    val earliest = sessions.minOfOrNull { it.endMs } ?: latest
    val canBack = month.timeInMillis > Trends.dayOf(earliest).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    val today = Trends.dayOf(todayMs).timeInMillis

    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Calendar", Modifier.weight(1f))
                IconButton({ offset-- }, enabled = canBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", tint = if (canBack) Palette.Text else Palette.Outline)
                }
                Text(
                    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.time),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
                IconButton({ offset++ }, enabled = offset < 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", tint = if (offset < 0) Palette.Text else Palette.Outline)
                }
            }
            Spacer(Modifier.height(4.dp))

            val first = month.firstDayOfWeek
            val dayName = SimpleDateFormat("EEE", Locale.getDefault())
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, (first - 1 + i) % 7 + 1) }
                    Text(
                        dayName.format(c.time), Modifier.weight(1f),
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            val lead = (month.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
            val days = month.getActualMaximum(Calendar.DAY_OF_MONTH)
            val rows = (lead + days + 6) / 7
            val cell = month.clone() as Calendar
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val idx = r * 7 + col - lead
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(1.dp)) {
                            if (idx in 0 until days) {
                                cell.timeInMillis = month.timeInMillis
                                cell.set(Calendar.DAY_OF_MONTH, idx + 1)
                                DayCell(idx + 1, byDay[cell.timeInMillis], cell.timeInMillis > today, onOpenNight)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            ScoreLegend()
        }
    }
}

@Composable
private fun DayCell(day: Int, session: Session?, future: Boolean, onOpenNight: (String) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    if (session == null) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).border(1.dp, Palette.Outline.copy(alpha = 0.5f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(day.toString(), color = if (future) Palette.Outline else Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        val band = Insights.band(session.score)
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(ScoreRamp.colors[band])
                .clickable { onOpenNight(session.id) }
                .semantics { contentDescription = "Day $day, score ${session.score}" },
            contentAlignment = Alignment.Center,
        ) {
            Text(day.toString(), color = ScoreRamp.textOn(band), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ScoreLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Score", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Insights.bandFloors.forEachIndexed { i, floor ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 2.dp)) {
                Box(Modifier.size(width = 30.dp, height = 10.dp).clip(RoundedCornerShape(2.dp)).background(ScoreRamp.colors[i]))
                Text(if (i == Insights.bandFloors.lastIndex) "$floor+" else "$floor", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).border(1.dp, Palette.Outline, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text("Not recorded", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
    }
}
