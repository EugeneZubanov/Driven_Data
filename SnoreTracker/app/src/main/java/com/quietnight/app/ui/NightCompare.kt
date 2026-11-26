package com.quietnight.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quietnight.app.data.NightStats
import com.quietnight.app.data.Session
import com.quietnight.app.data.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun day(ms: Long) = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))

/** Previous / play / next for the night's clips, plus an auto-play switch. */
@Composable
fun ClipControls(session: Session, player: ClipPlayer) {
    val index = session.clips.indexOfFirst { it.file == (player.playing ?: player.current) }
    val shown = session.clips.getOrNull(index)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ player.previous() }, enabled = index > 0) { Icon(Icons.Filled.SkipPrevious, "Previous clip") }
            IconButton({
                if (player.playing != null) player.stop() else player.playCurrent()
            }) {
                Icon(
                    if (player.playing != null) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    if (player.playing != null) "Stop clip" else "Play clip",
                )
            }
            IconButton({ player.next() }, enabled = index < session.clips.size - 1) { Icon(Icons.Filled.SkipNext, "Next clip") }
            Spacer(Modifier.width(8.dp))
            Text(
                if (shown == null) "${session.clips.size} clips" else "Clip ${index + 1} of ${session.clips.size} · ${clock(session.startMs + shown.offsetSec * 1000L)}",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Toggle auto-play") { player.autoAdvance = !player.autoAdvance },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Play clips one after another", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = player.autoAdvance,
                onCheckedChange = { player.autoAdvance = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.Background,
                    checkedTrackColor = Palette.Moon,
                    uncheckedThumbColor = Palette.Muted,
                    uncheckedTrackColor = Palette.SurfaceHigh,
                    uncheckedBorderColor = Palette.Outline,
                ),
            )
        }
    }
}

/** One night's columns between two times, with gaps hatched. */
@Composable
private fun NightStrip(session: Session, winStart: Long, winEnd: Long, modifier: Modifier = Modifier) {
    Spacer(
        modifier.fillMaxWidth().height(44.dp).drawWithCache {
            val count = (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1)
            val cols = NightStats.columns(session, winStart, winEnd, count)
            val w = size.width / count
            val base = size.height * 0.2f
            val span = (winEnd - winStart).toFloat()
            onDrawBehind {
                if (cols.lastCovered >= 0) {
                    drawRect(Palette.Quiet, Offset(cols.firstCovered * w, size.height - base), Size((cols.lastCovered - cols.firstCovered + 1) * w, base))
                }
                for (g in session.gaps) {
                    val l = ((session.startMs + g.fromSec * 1000L - winStart) / span * size.width).coerceIn(0f, size.width)
                    val r = ((session.startMs + g.toSec * 1000L - winStart) / span * size.width).coerceIn(0f, size.width)
                    drawGap(l, 0f, r, size.height, 4.dp.toPx())
                }
                for (c in 0 until count) {
                    val l = cols.levels[c]
                    if (l == 0 || cols.gaps[c]) continue
                    val h = base + (size.height - base) * ((cols.amps[c] - 4) / 34f).coerceIn(0.12f, 1f)
                    drawRect(Palette.levels[l], Offset(c * w, size.height - h), Size((w - 2f).coerceAtLeast(1f), h))
                }
            }
        },
    )
}

/** Two nights stacked on one clock axis, with their numbers side by side. */
@Composable
fun CompareCard(a: Session, b: Session, onChange: () -> Unit, onClose: () -> Unit) {
    val windows = remember(a, b) { NightStats.sharedWindows(listOf(a, b)) }
    val measurer = rememberTextMeasurer()
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Compared with ${day(b.endMs)}", Modifier.weight(1f))
                TextButton(onChange) { Text("Change") }
                IconButton(onClose) { Icon(Icons.Filled.Close, "Stop comparing") }
            }
            for ((night, win) in listOf(a to windows[0], b to windows[1])) {
                Spacer(Modifier.height(8.dp))
                Text(day(night.endMs), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Spacer(Modifier.height(4.dp))
                NightStrip(
                    night, win.first, win.second,
                    Modifier.semantics {
                        contentDescription = "${day(night.endMs)}: ${clock(night.startMs)} to ${clock(night.endMs)}, score ${night.score}"
                    },
                )
            }
            // Shared clock axis: both strips cover the same evening times.
            val (ws, we) = windows[0]
            Spacer(
                Modifier.fillMaxWidth().height(20.dp).drawWithCache {
                    val style = TextStyle(color = Palette.Muted, fontSize = 11.sp)
                    val span = (we - ws).toFloat()
                    val hours = NightStats.hourStarts(Session("", ws, we, 60, ByteArray(0), ByteArray(0), emptyList(), emptyList()))
                        .filter { it > ws }
                    val step = if (hours.size > 7) 2 else 1
                    var lastRight = -Float.MAX_VALUE
                    val labels = hours.filterIndexed { i, _ -> i % step == 0 }.mapNotNull { t ->
                        val layout = measurer.measure(hourLabel(t), style)
                        val x = (t - ws) / span * size.width
                        val left = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                        if (left < lastRight + 6.dp.toPx()) null else { lastRight = left + layout.size.width; left to layout }
                    }
                    onDrawBehind { for ((left, layout) in labels) drawText(layout, topLeft = Offset(left, 4.dp.toPx())) }
                },
            )
            Spacer(Modifier.height(8.dp))
            CompareRow("", day(a.endMs), day(b.endMs), header = true)
            CompareRow("Score", a.score.toString(), b.score.toString())
            CompareRow("Time snoring", formatDuration(a.snoringSec), formatDuration(b.snoringSec))
            CompareRow("Share snoring", "${(a.snoringFraction * 100).roundToInt()}%", "${(b.snoringFraction * 100).roundToInt()}%")
            CompareRow("Time recorded", formatDuration(a.durationSec), formatDuration(b.durationSec))
            CompareRow(
                "Longest quiet",
                NightStats.longestQuiet(a)?.let { formatDuration(it.lengthSec) } ?: "–",
                NightStats.longestQuiet(b)?.let { formatDuration(it.lengthSec) } ?: "–",
            )
            if (a.gapSec > 0 || b.gapSec > 0) CompareRow("No sound", formatDuration(a.gapSec), formatDuration(b.gapSec))
        }
    }
}

@Composable
private fun CompareRow(label: String, a: String, b: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.2f), color = Palette.Muted, style = style)
        Text(a, Modifier.weight(1f), style = style, textAlign = TextAlign.End, color = if (header) Palette.Muted else Palette.Text, fontWeight = if (header) null else FontWeight.Medium)
        Text(b, Modifier.weight(1f), style = style, textAlign = TextAlign.End, color = if (header) Palette.Muted else Palette.Text, fontWeight = if (header) null else FontWeight.Medium)
    }
}

@Composable
fun PickNightDialog(nights: List<Session>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("Compare with") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(nights, key = { it.id }) { n ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(n.id) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(day(n.endMs), Modifier.weight(1f))
                        Text("Score ${n.score}", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
