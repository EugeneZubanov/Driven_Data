package com.quietnight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quietnight.app.data.Period
import com.quietnight.app.data.Session
import com.quietnight.app.data.TrendBar
import com.quietnight.app.data.ReportData
import com.quietnight.app.data.Trends
import com.quietnight.app.data.WeightLog
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    allSessions: List<Session>,
    onOpenNight: (String) -> Unit = {},
    weight: WeightLog? = null,
    onWeight: (((WeightLog) -> WeightLog) -> Unit)? = null,
    onSaveReport: (() -> Unit)? = null,
    onShareReport: (() -> Unit)? = null,
    reportStatus: String? = null,
) {
    // Test runs and nights with under an hour listened to would distort every average here.
    val sessions = remember(allSessions) { Trends.counted(allSessions) }
    if (sessions.isEmpty()) {
        EmptyState(
            "No trends yet",
            if (allSessions.isEmpty()) "Trends appear once you have recorded a few nights."
            else "Trends appear once you have a night with at least an hour recorded. Test runs are left out.",
        )
        return
    }
    var period by remember { mutableStateOf(Period.DAYS) }
    val bars = remember(sessions, period) {
        Trends.bars(sessions, period, when (period) { Period.DAYS -> 14; Period.WEEKS -> 8; Period.MONTHS -> 6 })
    }
    var selectedKey by remember(period) { mutableStateOf<Long?>(null) }
    val selected = bars.indexOfFirst { it.key == selectedKey && !it.isEmpty }.let { if (it < 0) bars.lastIndex else it }
    val average = sessions.map { it.score }.average().toFloat()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        ScreenTitle("Trends", subtitle = "Snore score over time. Lower is quieter.")
        Spacer(Modifier.height(16.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = period == p,
                    onClick = { period = p },
                    shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                ) { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }

        Spacer(Modifier.height(16.dp))
        TrendChart(bars, selected, average) { if (!bars[it].isEmpty) selectedKey = bars[it].key }
        Spacer(Modifier.height(8.dp))
        LevelLegend()
        Spacer(Modifier.height(6.dp))
        Text("Dashed line: your average across all nights (${average.roundToInt()}).", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)

        val bar = bars[selected]
        Spacer(Modifier.height(16.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle(bar.longLabel)
                    val n = bar.sessions.size
                    Text("$n ${if (n == 1) "night" else "nights"} recorded", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    val prev = (selected - 1 downTo 0).firstOrNull { !bars[it].isEmpty }
                    if (prev != null) {
                        val change = bar.score - bars[prev].score
                        val than = if (prev == selected - 1) "the ${period.unit} before" else "the last ${period.unit} recorded"
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                change == 0 -> "Same as $than"
                                change < 0 -> "▼ ${-change} lower than $than"
                                else -> "▲ $change higher than $than"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                val total = bar.sessions.sumOf { it.listenedSec }.coerceAtLeast(1).toFloat()
                ScoreDial(bar.score, FloatArray(3) { i -> bar.sessions.sumOf { it.secondsByLevel[i + 1] } / total }, 84.dp)
            }
        }

        Spacer(Modifier.height(12.dp))
        NightCalendar(sessions, onOpenNight)
        Spacer(Modifier.height(12.dp))
        HourlyCard(sessions)
        Spacer(Modifier.height(12.dp))
        PatternsCard(sessions, onOpenNight)
        Spacer(Modifier.height(12.dp))
        TagEffectsCard(sessions)
        if (weight != null && onWeight != null) {
            Spacer(Modifier.height(12.dp))
            WeightCard(sessions, weight, onWeight)
        }
        if (onSaveReport != null) {
            Spacer(Modifier.height(12.dp))
            Card {
                Column {
                    SectionTitle("Share a summary")
                    Text(
                        "A one-page PDF of the last 30 nights to print or send, for example to a doctor or partner.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    val canReport = remember(allSessions) { ReportData.available(allSessions, System.currentTimeMillis()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onSaveReport, enabled = canReport) { Text("Save report") }
                        if (onShareReport != null) OutlinedButton(onShareReport) { Text("Share") }
                    }
                    if (!canReport) {
                        Spacer(Modifier.height(6.dp))
                        Text(ReportData.UNAVAILABLE, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (reportStatus != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(reportStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private val Period.unit: String
    get() = when (this) { Period.DAYS -> "night"; Period.WEEKS -> "week"; Period.MONTHS -> "month" }

/** Stacked bars: each bar's height is the average score, split by how much each level contributed. */
@Composable
private fun TrendChart(bars: List<TrendBar>, selected: Int, average: Float, onSelect: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Palette.Muted, fontSize = 11.sp)
    val maxScore = maxOf(bars.maxOf { it.parts.sum() }, average, 10f)
    val top = (ceil(maxScore / 10f) * 10f)

    Canvas(
        Modifier.fillMaxWidth().height(220.dp).pointerInput(bars) {
            detectTapGestures { pos ->
                val left = 28.dp.toPx()
                val slot = (size.width - left) / bars.size
                onSelect(((pos.x - left) / slot).toInt().coerceIn(0, bars.lastIndex))
            }
        },
    ) {
        val left = 28.dp.toPx()
        val labelH = 22.dp.toPx()
        val valueH = 18.dp.toPx()
        val plotH = size.height - labelH - valueH
        val slot = (size.width - left) / bars.size
        val barW = minOf(slot * 0.6f, 28.dp.toPx())
        val gap = 2.dp.toPx()
        fun y(v: Float) = valueH + plotH * (1 - v / top)

        // recessive grid with y labels
        for (v in listOf(0f, top / 2, top)) {
            drawLine(Palette.Outline.copy(alpha = if (v == 0f) 1f else 0.5f), Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            val l = measurer.measure(v.roundToInt().toString(), axisStyle)
            drawText(l, topLeft = Offset(left - l.size.width - 6.dp.toPx(), y(v) - l.size.height / 2))
        }

        bars.forEachIndexed { i, bar ->
            val cx = left + slot * i + slot / 2
            val alpha = if (i == selected) 1f else 0.55f
            var acc = 0f
            val topPart = bar.parts.indexOfLast { it > 0f }
            for (p in 0 until 3) {
                val v = bar.parts[p]
                if (v <= 0f) continue
                val yBottom = y(acc)
                val yTop = y(acc + v)
                val h = (yBottom - yTop - if (p == topPart) 0f else gap).coerceAtLeast(1f)
                val r = if (p == topPart) 4.dp.toPx() else 0f
                val color = Palette.levels[p + 1].copy(alpha = alpha)
                if (r > 0f && h > r) {
                    drawRoundRect(color, Offset(cx - barW / 2, yBottom - h), Size(barW, h), CornerRadius(r))
                    drawRect(color, Offset(cx - barW / 2, yBottom - h + r), Size(barW, h - r)) // square off the base
                } else {
                    drawRect(color, Offset(cx - barW / 2, yBottom - h), Size(barW, h))
                }
                acc += v
            }
            if (bar.isEmpty) drawCircle(Palette.Outline, 2.dp.toPx(), Offset(cx, y(0f) - 4.dp.toPx()))
            else if (acc == 0f) drawRect(Palette.Quiet, Offset(cx - barW / 2, y(0f) - 2.dp.toPx()), Size(barW, 2.dp.toPx()))

            // value on the selected bar only; x label on a readable subset
            if (i == selected) {
                val l = measurer.measure(bar.score.toString(), TextStyle(color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                // Sit above the average line when the two would touch, so the dash never crosses the number.
                val near = abs(y(acc) - y(average)) < l.size.height + 2.dp.toPx()
                val base = if (near) minOf(y(acc), y(average)) else y(acc)
                drawText(l, topLeft = Offset(cx - l.size.width / 2, base - l.size.height - 2.dp.toPx()))
            }
            val every = ceil(bars.size / 5f).toInt().coerceAtLeast(1)
            if (i == selected || (i % every == 0 && abs(i - selected) >= every)) {
                val l = measurer.measure(bar.label, axisStyle.copy(color = if (i == selected) Palette.Text else Palette.Muted))
                val x = (cx - l.size.width / 2).coerceIn(left, size.width - l.size.width)
                drawText(l, topLeft = Offset(x, size.height - labelH + 5.dp.toPx()))
            }
        }

        // all-time average
        drawLine(
            Palette.Text.copy(alpha = 0.6f), Offset(left, y(average)), Offset(size.width, y(average)), 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
    }
}
