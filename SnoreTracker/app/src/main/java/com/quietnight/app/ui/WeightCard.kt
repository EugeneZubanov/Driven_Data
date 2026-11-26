package com.quietnight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quietnight.app.data.Insights
import com.quietnight.app.data.Session
import com.quietnight.app.data.Trends
import com.quietnight.app.data.Weight
import com.quietnight.app.data.WeightLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val WINDOW_DAYS = 90

/**
 * Weight and the 7-night average score as two small charts sharing one time axis (never a dual axis).
 * [todayMs] is for tests.
 */
@Composable
fun WeightCard(
    sessions: List<Session>,
    log: WeightLog,
    onWeight: ((WeightLog) -> WeightLog) -> Unit,
    todayMs: Long = System.currentTimeMillis(),
) {
    var adding by remember { mutableStateOf(false) }
    val today = Trends.dayOf(todayMs).timeInMillis
    val from = today - WINDOW_DAYS * 86_400_000L
    val weights = log.entries.filter { it.dayMs >= from }.map { it.dayMs to Weight.toDisplay(it.kg, log.pounds) }
    val scores = remember(sessions) { Insights.rollingSeries(sessions) }.filter { it.first >= from }
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Weight and snoring")
                    Text("Last $WINDOW_DAYS days, on the same dates.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton({ adding = true }) { Text("Add weight") }
            }
            if (weights.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add your weight now and then to see it next to your snore score.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(12.dp))
                val latest = log.entries.last()
                Text("Weight (${Weight.unit(log.pounds)}) · latest ${Weight.format(latest.kg, log.pounds)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                LineChart(weights, from, today, Palette.Moon, decimals = 1)
                Spacer(Modifier.height(12.dp))
                Text("Snore score, 7-night average", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                if (scores.isEmpty()) {
                    Text("Needs 7 recorded nights.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    LineChart(scores, from, today, ScoreRamp.colors[3], decimals = 0)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Shown side by side to compare; a shared trend does not mean one causes the other.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (adding) WeightDialog(log, todayMs, onWeight) { adding = false }
}

/** Settings card: unit choice and a way to add today's weight. */
@Composable
fun WeightSettingsCard(log: WeightLog, onWeight: ((WeightLog) -> WeightLog) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Card {
        Column {
            SectionTitle("Weight log")
            Text(
                "Optional. Shown on the Trends tab next to your snore score. Kept on this phone only.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!log.pounds, { onWeight { it.copy(pounds = false) } }, label = { Text("kg") })
                FilterChip(log.pounds, { onWeight { it.copy(pounds = true) } }, label = { Text("lb") })
                Spacer(Modifier.weight(1f))
                OutlinedButton({ adding = true }) { Text("Add weight") }
            }
            log.entries.lastOrNull()?.let {
                Text(
                    "Latest: ${Weight.format(it.kg, log.pounds)} on ${SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(it.dayMs))} · ${log.entries.size} ${if (log.entries.size == 1) "entry" else "entries"}",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (adding) WeightDialog(log, System.currentTimeMillis(), onWeight) { adding = false }
}

@Composable
private fun WeightDialog(log: WeightLog, todayMs: Long, onWeight: ((WeightLog) -> WeightLog) -> Unit, onClose: () -> Unit) {
    val day = Trends.dayOf(todayMs).timeInMillis
    val existing = log.entries.firstOrNull { it.dayMs == day }
    var text by remember {
        mutableStateOf(existing?.let { ((Weight.toDisplay(it.kg, log.pounds) * 10).roundToInt() / 10f).toString() } ?: "")
    }
    val kg = Weight.parseKg(text, log.pounds)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Today's weight") },
        text = {
            Column {
                OutlinedTextField(
                    text, { text = it.take(6) },
                    singleLine = true,
                    label = { Text(Weight.unit(log.pounds)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = text.isNotBlank() && kg == null,
                    supportingText = { if (text.isNotBlank() && kg == null) Text("Enter a weight between ${Weight.format(Weight.MIN_KG, log.pounds)} and ${Weight.format(Weight.MAX_KG, log.pounds)}") },
                )
                if (existing != null) {
                    TextButton({ onWeight { Weight.remove(it, day) }; onClose() }) { Text("Remove today's entry", color = Palette.Heavy) }
                }
            }
        },
        confirmButton = { TextButton({ kg?.let { k -> onWeight { Weight.put(it, day, k) } }; onClose() }, enabled = kg != null) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

/** Small line chart over a fixed date range: one y axis with min and max labels, start and end dates below. */
@Composable
private fun LineChart(points: List<Pair<Long, Float>>, fromMs: Long, toMs: Long, color: Color, decimals: Int) {
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(color = Palette.Muted, fontSize = 11.sp)
    val lo = points.minOf { it.second }
    val hi = points.maxOf { it.second }
    val pad = maxOf((hi - lo) * 0.15f, if (decimals == 0) 2f else 0.5f)
    val yMin = lo - pad
    val yMax = hi + pad
    fun fmt(v: Float) = if (decimals == 0) v.roundToInt().toString() else ((v * 10).roundToInt() / 10f).toString()
    val dates = SimpleDateFormat("d MMM", Locale.getDefault())
    Canvas(Modifier.fillMaxWidth().height(92.dp)) {
        val left = 40.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val top = 6.dp.toPx()
        fun x(t: Long) = left + (size.width - left) * ((t - fromMs).toFloat() / (toMs - fromMs).coerceAtLeast(1))
        fun y(v: Float) = top + (bottom - top) * (1 - (v - yMin) / (yMax - yMin))
        for (v in listOf(lo, hi)) {
            drawLine(Palette.Outline.copy(alpha = 0.5f), Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            val l = measurer.measure(fmt(v), axis)
            drawText(l, topLeft = Offset(left - l.size.width - 6.dp.toPx(), y(v) - l.size.height / 2))
            if (lo == hi) break
        }
        drawLine(Palette.Outline, Offset(left, bottom), Offset(size.width, bottom), 1f)
        val path = Path()
        points.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
        if (points.size > 1) drawPath(path, color, style = Stroke(2.dp.toPx()))
        points.forEach { (t, v) -> drawCircle(color, if (points.size > 20) 2.dp.toPx() else 3.dp.toPx(), Offset(x(t), y(v))) }
        val a = measurer.measure(dates.format(Date(fromMs)), axis)
        drawText(a, topLeft = Offset(left, bottom + 3.dp.toPx()))
        val b = measurer.measure(dates.format(Date(toMs)), axis)
        drawText(b, topLeft = Offset(size.width - b.size.width, bottom + 3.dp.toPx()))
    }
}
