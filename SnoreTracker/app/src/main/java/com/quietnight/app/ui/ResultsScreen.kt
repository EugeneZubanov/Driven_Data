package com.quietnight.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quietnight.app.data.Level
import com.quietnight.app.data.NightStats
import com.quietnight.app.data.Session
import com.quietnight.app.data.Tags
import com.quietnight.app.data.formatDuration
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun fmt(pattern: String, ms: Long) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))

private fun tagText(t: String) = (if (Tags.isRemedy(t)) "Remedy: " else "Factor: ") + Tags.label(t)

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

/**
 * Nights newest first: the latest night pinned on top, then one group per month with its night
 * count and average score. Filter by tag; swipe a night left to delete it, with Undo.
 */
@Composable
fun ResultsListScreen(
    sessions: List<Session>,
    onDelete: (String) -> Unit = {},
    onUndoDelete: (String) -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
    onOpen: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val tags = remember(sessions) { NightStats.usedTags(sessions) }
    val filter = chosen?.takeIf { it in tags }

    val delete: (Session) -> Unit = { s ->
        onDelete(s.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Night of ${fmt("EEE d MMM", s.endMs)} deleted", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) onUndoDelete(s.id)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (sessions.isEmpty()) {
            EmptyState("No nights yet", "Record a night from the Record tab, or add sample nights in Settings to look around.")
        } else {
            val shown = remember(sessions, filter) { NightStats.filterByTag(sessions, filter) }
            val pinned = shown.firstOrNull()
            val byMonth = remember(shown) { shown.drop(1).groupBy { fmt("MMMM yyyy", it.endMs) } }
            val monthAll = remember(shown) { shown.groupBy { fmt("MMMM yyyy", it.endMs) } }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                item { Spacer(Modifier.height(20.dp)); Text("Results", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
                if (tags.isNotEmpty()) {
                    item(key = "filters") {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TagChip("All nights", filter == null) { chosen = null }
                            for (t in tags) TagChip(Tags.label(t), filter == t) { chosen = if (filter == t) null else t }
                        }
                    }
                }
                if (pinned != null) {
                    item(key = "pinned-" + pinned.id) {
                        val recent = nowMs - pinned.endMs in 0..(36 * 3_600_000L)
                        Text(
                            if (filter != null) "Latest with ${Tags.label(filter)}" else if (recent) "Last night" else "Latest night",
                            Modifier.padding(top = 20.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        SwipeToDelete(pinned, delete) { PinnedCard(pinned) { onOpen(pinned.id) } }
                    }
                }
                if (shown.isEmpty()) {
                    item { Text("No nights with this tag.", Modifier.padding(top = 20.dp), color = Palette.Muted) }
                }
                monthAll.forEach { (month, all) ->
                    val list = byMonth[month].orEmpty()
                    item(key = month) {
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                            Text(month, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${all.size} ${if (all.size == 1) "night" else "nights"} · avg ${NightStats.averageScore(all)}",
                                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(list, key = { it.id }) { s ->
                        SwipeToDelete(s, delete) { NightRow(s) { onOpen(s.id) } }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Palette.Text,
            selectedContainerColor = Palette.Moon,
            selectedLabelColor = Palette.Background,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(session: Session, onDelete: (Session) -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v -> if (v == SwipeToDismissBoxValue.EndToStart) { onDelete(session); true } else false },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.padding(bottom = 10.dp).semantics {
            customActions = listOf(CustomAccessibilityAction("Delete night") { onDelete(session); true })
        },
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Palette.Heavy.copy(alpha = 0.25f)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text("Delete", color = Palette.Text)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Delete, null, tint = Palette.Text)
            }
        },
    ) { content() }
}

@Composable
private fun NightRow(s: Session, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
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

@Composable
private fun PinnedCard(s: Session, onClick: () -> Unit) {
    Card(Modifier.border(1.dp, Palette.Outline, RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fmt("EEEE d MMMM", s.endMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${clock(s.startMs)} – ${clock(s.endMs)} · ${formatDuration(s.durationSec)}",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Snoring ${formatDuration(s.snoringSec)}" + if (s.demo) " · Sample" else "",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                ScoreDial(s, 64.dp)
            }
            Spacer(Modifier.height(10.dp))
            MiniTimeline(s, Modifier.fillMaxWidth())
        }
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
    /** All nights, for comparing this one with recent ones. */
    history: List<Session> = emptyList(),
    /** Saves the night after tags or the note are edited. Null hides editing. */
    onSave: ((Session) -> Unit)? = null,
    customTags: Set<String> = emptySet(),
    player: ClipPlayer = rememberClipPlayer(session.id),
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember(session.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val comparison = remember(session, history) { NightStats.compareWithRecent(session, history) }
    val onset = remember(session) { NightStats.snoringStartSec(session) }
    val quiet = remember(session) { NightStats.longestQuiet(session) }
    val loudest = remember(session) { NightStats.loudestHour(session) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            IconButton({ onOlder?.invoke() }, enabled = onOlder != null) { Icon(Icons.Filled.ChevronLeft, "Older night") }
            Text(fmt("EEE d MMM", session.endMs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton({ onNewer?.invoke() }, enabled = onNewer != null) { Icon(Icons.Filled.ChevronRight, "Newer night") }
            Spacer(Modifier.weight(1f))
            IconButton({
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, NightStats.shareText(session, history))
                runCatching { context.startActivity(Intent.createChooser(send, "Share night")) }
            }) { Icon(Icons.Filled.Share, "Share night as text") }
            IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete night") }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            if (session.demo) {
                Text("Sample night, not a real recording", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            TimelineChart(
                session,
                playingClip = player.playing,
                playProgress = { player.progress },
                onClipTap = { player.toggle(it.file, clipFile(it.file)) },
            )
            Spacer(Modifier.height(10.dp))
            LevelLegend()
            if (session.clips.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▼", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Text("Saved clip, tap to play", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }

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
                        if (comparison != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                NightStats.comparisonText(session.score, comparison) + " (${comparison.average})",
                                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                    if (onset != null) StatRow("Snoring started", clock(session.startMs + onset * 1000L))
                    if (quiet != null) StatRow("Longest quiet stretch", formatDuration(quiet.lengthSec))
                    if (loudest != null) StatRow("Loudest hour", "${clock(loudest)} – ${clock(loudest + 3_600_000L)}")
                }
            }

            if (session.tags.isNotEmpty() || session.note.isNotBlank() || onSave != null) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Notes for this night", Modifier.weight(1f))
                            if (onSave != null) {
                                TextButton({ editing = true }) {
                                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Edit")
                                }
                            }
                        }
                        if (session.tags.isEmpty() && session.note.isBlank()) {
                            Text("Add factors, remedies or a note.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (session.tags.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (t in session.tags) {
                                    Text(
                                        tagText(t),
                                        Modifier.clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        if (session.note.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(session.note, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (session.clips.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ClipList(session, player, clipFile)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editing && onSave != null) {
        EditNightDialog(session, customTags, onDismiss = { editing = false }) { tags, note ->
            editing = false
            onSave(session.withEdits(tags, note))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this night?") },
            text = { Text("The timeline and its audio clips will be removed.") },
            confirmButton = { TextButton({ confirmDelete = false; player.stop(); onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

const val NOTE_MAX = 280

/** Tags offered for a night: built-in ones, the user's own, and anything already on it. Factors first. */
private fun tagOptions(session: Session, customTags: Set<String>, kind: String): List<String> {
    val builtIn = if (kind == "f") Tags.factors else Tags.remedies
    return (builtIn.map { "$kind:$it" } + (customTags + session.tags).filter { it.startsWith("$kind:") }.sorted()).distinct()
}

@Composable
fun EditNightDialog(
    session: Session,
    customTags: Set<String>,
    onDismiss: () -> Unit,
    onSave: (tags: List<String>, note: String) -> Unit,
) {
    var selected by remember(session.id) { mutableStateOf(session.tags.toSet()) }
    var note by remember(session.id) { mutableStateOf(session.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("Edit night") },
        text = {
            EditNightFields(session, customTags, selected, { t -> selected = if (t in selected) selected - t else selected + t }, note) { note = it }
        },
        confirmButton = {
            TextButton({
                // Keep a stable order: factors then remedies, as listed.
                val order = tagOptions(session, customTags, "f") + tagOptions(session, customTags, "r")
                onSave(order.filter { it in selected }, note.trim())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditNightFields(
    session: Session,
    customTags: Set<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    note: String,
    onNote: (String) -> Unit,
) {
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
        for ((title, kind) in listOf("Factors" to "f", "Remedies" to "r")) {
            Text(title, color = Palette.Muted, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in tagOptions(session, customTags, kind)) TagChip(Tags.label(t), t in selected) { onToggle(t) }
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = note,
            onValueChange = { onNote(it.take(NOTE_MAX)) },
            label = { Text("Note") },
            placeholder = { Text("Anything else about this night") },
            supportingText = { Text("${note.length} / $NOTE_MAX") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Palette.Text,
                unfocusedTextColor = Palette.Text,
                focusedBorderColor = Palette.Moon,
                unfocusedBorderColor = Palette.Outline,
                focusedLabelColor = Palette.Moon,
                unfocusedLabelColor = Palette.Muted,
                unfocusedPlaceholderColor = Palette.Muted,
                focusedPlaceholderColor = Palette.Muted,
                cursorColor = Palette.Moon,
            ),
        )
    }
}

@Composable
private fun ClipList(session: Session, player: ClipPlayer, clipFile: (String) -> File) {
    Card {
        Column {
            SectionTitle("Loudest moments")
            Text("Ten-second clips of the strongest snoring.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            for (clip in session.clips) {
                val isPlaying = player.playing == clip.file
                val at = clock(session.startMs + clip.offsetSec * 1000L)
                val level = Level.names[clip.level.coerceIn(0, 3)]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isPlaying) Palette.SurfaceHigh else Palette.Surface)
                        .clickable(onClickLabel = if (isPlaying) "Stop clip" else "Play clip") { player.toggle(clip.file, clipFile(clip.file)) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(if (isPlaying) Palette.Moon else Palette.SurfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                if (isPlaying) "Stop clip from $at" else "Play clip from $at",
                                tint = if (isPlaying) Palette.Background else Palette.Text,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(at, fontWeight = FontWeight.Medium)
                        if (player.failed == clip.file) {
                            Spacer(Modifier.width(8.dp))
                            Text("Can't play", color = Palette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Palette.levels[clip.level.coerceIn(0, 3)]))
                        Spacer(Modifier.width(6.dp))
                        Text(level, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (isPlaying) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { player.progress },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = Palette.Moon,
                            trackColor = Palette.Outline,
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        }
    }
}
