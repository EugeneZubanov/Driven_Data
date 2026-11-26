package com.quietnight.app.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quietnight.app.data.Level
import com.quietnight.app.data.Session
import com.quietnight.app.data.Tags
import com.quietnight.app.data.formatDuration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun fmt(pattern: String, ms: Long) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Palette.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
fun ResultsListScreen(sessions: List<Session>, onOpen: (String) -> Unit) {
    if (sessions.isEmpty()) {
        EmptyState("No nights yet", "Record a night from the Record tab, or add sample nights in Settings to look around.")
        return
    }
    val byMonth = sessions.groupBy { fmt("MMMM yyyy", it.endMs) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item { Spacer(Modifier.height(20.dp)); Text("Results", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
        byMonth.forEach { (month, list) ->
            item(key = month) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp)) {
                    Text(month, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${list.size} ${if (list.size == 1) "night" else "nights"}", color = Palette.Muted)
                }
            }
            items(list, key = { it.id }) { s ->
                Card(Modifier.padding(bottom = 10.dp).clickable { onOpen(s.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(72.dp)) {
                            Text(fmt("EEE d", s.endMs), fontWeight = FontWeight.SemiBold)
                            Text(formatDuration(s.durationSec), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                            if (s.demo) Text("Sample", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        MiniTimeline(s, Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        ScoreDial(s, 52.dp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionDetailScreen(
    session: Session,
    onBack: () -> Unit,
    onOlder: (() -> Unit)?,
    onNewer: (() -> Unit)?,
    onDelete: () -> Unit,
    clipFile: (String) -> File,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            IconButton({ onOlder?.invoke() }, enabled = onOlder != null) { Icon(Icons.Filled.ChevronLeft, "Older night") }
            Text(fmt("EEE d MMM", session.endMs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton({ onNewer?.invoke() }, enabled = onNewer != null) { Icon(Icons.Filled.ChevronRight, "Newer night") }
            Spacer(Modifier.weight(1f))
            IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete night") }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            if (session.demo) {
                Text("Sample night, not a real recording", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            TimelineChart(session)
            Spacer(Modifier.height(10.dp))
            LevelLegend()

            Spacer(Modifier.height(20.dp))
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Snore score", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                session.score < 5 -> "A quiet night"
                                session.score < 15 -> "Some snoring"
                                session.score < 30 -> "A noisy night"
                                else -> "A very noisy night"
                            },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    ScoreDial(session, 96.dp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Card {
                Column {
                    StatRow("Time recorded", formatDuration(session.durationSec))
                    StatRow(
                        "Time snoring",
                        "${formatDuration(session.snoringSec)} · ${(session.snoringFraction * 100).roundToInt()}%",
                    )
                    for (l in 1..3) {
                        StatRow(Level.names[l], formatDuration(session.secondsByLevel[l]), dot = Palette.levels[l])
                    }
                }
            }

            if (session.tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Column {
                        SectionTitle("Notes for this night")
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (t in session.tags) {
                                Text(
                                    (if (Tags.isRemedy(t)) "Remedy: " else "Factor: ") + Tags.label(t),
                                    Modifier.clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            if (session.clips.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ClipList(session, clipFile)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this night?") },
            text = { Text("The timeline and its audio clips will be removed.") },
            confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ClipList(session: Session, clipFile: (String) -> File) {
    var playing by remember(session.id) { mutableStateOf<String?>(null) }
    val player = remember(session.id) { arrayOfNulls<MediaPlayer>(1) }
    fun stop() {
        player[0]?.let { runCatching { it.stop() }; it.release() }
        player[0] = null
        playing = null
    }
    DisposableEffect(session.id) { onDispose { stop() } }

    Card {
        Column {
            SectionTitle("Loudest moments")
            Text("Ten-second clips of the strongest snoring.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            for (clip in session.clips) {
                val isPlaying = playing == clip.file
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (isPlaying) stop() else {
                            stop()
                            runCatching {
                                player[0] = MediaPlayer().apply {
                                    setDataSource(clipFile(clip.file).path)
                                    setOnCompletionListener { stop() }
                                    prepare()
                                    start()
                                }
                                playing = clip.file
                            }
                        }
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (isPlaying) "Stop" else "Play")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(clock(session.startMs + clip.offsetSec * 1000L), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Palette.levels[clip.level.coerceIn(0, 3)]))
                    Spacer(Modifier.width(6.dp))
                    Text(Level.names[clip.level.coerceIn(0, 3)], color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
