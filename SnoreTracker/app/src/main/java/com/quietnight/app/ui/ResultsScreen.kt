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
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import com.quietnight.app.data.Span
import com.quietnight.app.data.Clip
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
    /** A night just deleted from its detail screen, to offer Undo for. */
    deletedNotice: Session? = null,
    onDeletedNoticeShown: () -> Unit = {},
    /** True while saved nights are still being read. */
    loading: Boolean = false,
    /** Bytes used by all clips, if known. */
    clipBytes: Long? = null,
    onOpen: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val hasNotes = remember(sessions) { sessions.any { it.note.isNotBlank() } }
    val tags = remember(sessions) { NightStats.usedTags(sessions) }
    val filter = chosen?.takeIf { it in tags }

    val offerUndo: (Session) -> Unit = { s ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Night of ${fmt("EEE d MMM", s.endMs)} deleted", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) onUndoDelete(s.id)
        }
    }
    val delete: (Session) -> Unit = { s -> onDelete(s.id); offerUndo(s) }
    LaunchedEffect(deletedNotice) {
        deletedNotice?.let { onDeletedNoticeShown(); offerUndo(it) }
    }

    Box(Modifier.fillMaxSize()) {
        if (loading && sessions.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Palette.Moon)
                Spacer(Modifier.height(12.dp))
                Text("Loading nights", color = Palette.Muted)
            }
        } else if (sessions.isEmpty()) {
            EmptyState("No nights yet", "Record a night from the Record tab, or add sample nights in Settings to look around.")
        } else {
            val shown = remember(sessions, filter, query) { NightStats.search(NightStats.filterByTag(sessions, filter), query) }
            val pinned = shown.firstOrNull()
            val byMonth = remember(shown) { shown.drop(1).groupBy { fmt("MMMM yyyy", it.endMs) } }
            val monthAll = remember(shown) { shown.groupBy { fmt("MMMM yyyy", it.endMs) } }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                item {
                    ScreenTitle("Results", trailing = if (clipBytes != null && clipBytes > 0) {
                        { Text("Clips use ${NightStats.formatBytes(clipBytes)}", color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
                    } else null)
                }
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
                if (hasNotes) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.take(60) },
                            placeholder = { Text("Search notes") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            trailingIcon = if (query.isEmpty()) null else {
                                { IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear search") } }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Palette.Text,
                                unfocusedTextColor = Palette.Text,
                                focusedBorderColor = Palette.Moon,
                                unfocusedBorderColor = Palette.Outline,
                                unfocusedPlaceholderColor = Palette.Muted,
                                focusedPlaceholderColor = Palette.Muted,
                                unfocusedLeadingIconColor = Palette.Muted,
                                focusedLeadingIconColor = Palette.Muted,
                                cursorColor = Palette.Moon,
                            ),
                        )
                    }
                }
                if (pinned != null) {
                    item(key = "pinned-" + pinned.id) {
                        val recent = nowMs - pinned.endMs in 0..(36 * 3_600_000L)
                        Text(
                            if (filter != null) "Latest with ${Tags.label(filter)}" else if (query.isNotBlank()) "Latest match" else if (recent) "Last night" else "Latest night",
                            Modifier.padding(top = 20.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        SwipeToDelete(pinned, delete) { PinnedCard(pinned) { onOpen(pinned.id) } }
                    }
                }
                if (shown.isEmpty()) {
                    item { Text(if (query.isNotBlank()) "No nights match." else "No nights with this tag.", Modifier.padding(top = 20.dp), color = Palette.Muted) }
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
    ChoiceChip(label, selected, onClick = onClick)
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
                if (s.interruptedAtMs > 0) Text("Cut short", color = Palette.Loud, style = MaterialTheme.typography.labelSmall)
                if (s.note == Session.TEST_RUN_NOTE) Text("Test run", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
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
                        "Snoring ${formatDuration(s.snoringSec)}" + (if (s.demo) " · Sample" else "") + (if (s.note == Session.TEST_RUN_NOTE) " · Test run" else ""),
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
    initialCompareId: String? = null,
    /** Shows the morning summary at the top (the night has just been recorded). */
    justFinished: Boolean = false,
    onDismissSummary: () -> Unit = {},
    /** Bytes used by this night's clips, if known. */
    clipBytes: Long? = null,
    /** Deletes the clips and keeps the night. Null hides the action. */
    onDeleteClips: (() -> Unit)? = null,
    /** This night is still being recorded: no editing or deleting. */
    recordingInProgress: Boolean = false,
) {
    val canEdit = onSave != null && !recordingInProgress
    var selection by remember(session.id) { mutableStateOf<Span?>(null) }
    val markNotSnoring: (Span) -> Unit = { span -> onSave?.invoke(session.withNotSnoring(session.notSnoring + span)) }
    val unmark: (Span) -> Unit = { span ->
        onSave?.invoke(session.withNotSnoring(session.notSnoring.filterNot { it.fromSec < span.toSec && it.toSec > span.fromSec }))
    }
    player.setQueue(session.clips.map { it.file to clipFile(it.file) })
    var editing by remember(session.id) { mutableStateOf(false) }
    var picking by remember(session.id) { mutableStateOf(false) }
    var compareId by rememberSaveable(session.id) { mutableStateOf(initialCompareId) }
    val compareWith = history.firstOrNull { it.id == compareId && it.id != session.id }
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
            Text(fmt("EEE d MMM", session.endMs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            IconButton({ onNewer?.invoke() }, enabled = onNewer != null) { Icon(Icons.Filled.ChevronRight, "Newer night") }
            Spacer(Modifier.weight(1f))
            IconButton({
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, NightStats.shareText(session, history, is24 = ClockFormat.is24))
                runCatching { context.startActivity(Intent.createChooser(send, "Share night")) }
            }) { Icon(Icons.Filled.Share, "Share night as text") }
            // Deleting goes to the trash; the Results list offers Undo.
            if (!recordingInProgress) IconButton({ player.stop(); onDelete() }) { Icon(Icons.Filled.Delete, "Delete night") }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            if (session.demo) {
                Text("Sample night, not a real recording", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (session.interruptedAtMs > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, null, tint = Palette.Loud, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Cut short at ${clock(session.interruptedAtMs)}. The phone stopped the recording early.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (recordingInProgress) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Mic, null, tint = Palette.Moon, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Recording in progress. This night is still being added to.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (justFinished) {
                Spacer(Modifier.height(8.dp))
                MorningSummary(
                    text = remember(session, history) { NightStats.morningSummary(session, history) },
                    askForNotes = canEdit && session.tags.isEmpty() && session.note.isBlank(),
                    onAddNotes = { editing = true },
                    onDismiss = onDismissSummary,
                )
            }

            // First screenful: how was the night?
            Spacer(Modifier.height(8.dp))
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
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Snoring ${formatDuration(session.snoringSec)} · ${(session.snoringFraction * 100).roundToInt()}% of the night",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (comparison != null) {
                            Text(
                                NightStats.comparisonText(session.score, comparison) + " (${comparison.average})",
                                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    ScoreDial(session, 84.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            TimelineChart(
                session,
                playingClip = player.playing,
                playProgress = { player.progress },
                onClipTap = { player.toggle(it.file, clipFile(it.file)) },
                selection = selection,
                onLongPressBucket = if (canEdit) { b -> selection = NightStats.episodeAt(session, b) } else null,
            )
            Spacer(Modifier.height(4.dp))
            LevelLegend(showGaps = session.gaps.isNotEmpty())
            selection?.let { sel ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${clock(session.startMs + sel.fromSec * 1000L)} – ${clock(session.startMs + sel.toSec * 1000L)}",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton({ selection = null }) { Text("Cancel") }
                    TextButton({ markNotSnoring(sel); selection = null }) { Text("Not snoring") }
                }
            }
            if (session.otherSec > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 10.dp, height = 10.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 2.dp, height = 8.dp).background(Palette.Muted))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Talking or other sounds, not counted as snoring", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (session.notSnoring.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(NotSnoringColor))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Marked not snoring · ${formatDuration(session.correctedSec)}",
                        Modifier.weight(1f), color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    if (canEdit) {
                        TextButton({ onSave?.invoke(session.withNotSnoring(session.notSnoring.dropLast(1))) }) { Text("Undo last") }
                    }
                }
            } else if (canEdit && session.snoringSec > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Not snoring? Touch and hold the timeline to correct a stretch.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (session.clips.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▼", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Text("Saved clip, tap to play", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Then the details.
            Spacer(Modifier.height(16.dp))
            Card {
                Column {
                    SectionTitle("The night in detail")
                    Spacer(Modifier.height(4.dp))
                    StatRow("Time recorded", formatDuration(session.durationSec))
                    for (l in 1..3) {
                        StatRow(Level.names[l], formatDuration(session.secondsByLevel[l]), dot = Palette.levels[l])
                    }
                    if (onset != null) StatRow("Snoring started", clock(session.startMs + onset * 1000L))
                    if (quiet != null) StatRow("Longest quiet stretch", formatDuration(quiet.lengthSec))
                    if (loudest != null) StatRow("Loudest hour", "${clock(loudest)} – ${clock(loudest + 3_600_000L)}")
                    if (session.gapSec > 0) StatRow("No sound for", formatDuration(session.gapSec))
                    if (session.otherSec > 0) StatRow("Talking or other sounds", formatDuration(session.otherSec))
                }
            }

            if (session.tags.isNotEmpty() || session.note.isNotBlank() || onSave != null) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Notes for this night", Modifier.weight(1f))
                            if (canEdit) {
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
                ClipList(
                    session, player, clipFile, clipBytes,
                    onDeleteClips?.takeIf { !recordingInProgress }?.let { del -> { player.stop(); del() } },
                    onMark = if (canEdit) { clip, notSnoring ->
                        val span = Span(clip.offsetSec, clip.offsetSec + session.bucketSec)
                        if (notSnoring) markNotSnoring(span) else unmark(span)
                    } else null,
                )
            }

            Spacer(Modifier.height(12.dp))
            if (compareWith != null) {
                CompareCard(session, compareWith, onChange = { picking = true }, onClose = { compareId = null })
            } else if (history.any { it.id != session.id }) {
                TextButton({ picking = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Compare with another night")
                }
            }
            remember(session) { NightStats.tip(session) }?.let { tip ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Lightbulb, null, tint = Palette.Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tip, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
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

    if (picking) {
        PickNightDialog(history.filter { it.id != session.id }, onDismiss = { picking = false }) {
            picking = false
            compareId = it
        }
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
private fun ClipList(
    session: Session, player: ClipPlayer, clipFile: (String) -> File, bytes: Long?, onDeleteClips: (() -> Unit)?,
    onMark: ((Clip, Boolean) -> Unit)? = null,
) {
    var confirm by remember(session.id) { mutableStateOf(false) }
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Loudest moments", Modifier.weight(1f))
                if (bytes != null && bytes > 0) Text(NightStats.formatBytes(bytes), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("Ten-second clips of the strongest snoring.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            ClipControls(session, player)
            for (clip in session.clips) {
                val isPlaying = player.playing == clip.file
                val at = clock(session.startMs + clip.offsetSec * 1000L)
                val bucket = clip.offsetSec / session.bucketSec
                val marked = session.notSnoringMask.getOrElse(bucket) { false }
                val level = if (marked) "Not snoring" else Level.names[clip.level.coerceIn(0, 3)]
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
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(if (marked) NotSnoringColor else Palette.levels[clip.level.coerceIn(0, 3)]))
                        Spacer(Modifier.width(6.dp))
                        Text(level, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    // Listen, then correct: offered on the playing clip, and Undo on any marked clip.
                    if (onMark != null && (isPlaying || marked)) {
                        TextButton({ onMark(clip, !marked) }, Modifier.padding(start = 40.dp)) {
                            Text(if (marked) "Undo, it was snoring" else "This isn't snoring")
                        }
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
            if (onDeleteClips != null) {
                TextButton({ confirm = true }, Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete clips, keep the night")
                }
            }
        }
    }
    if (confirm && onDeleteClips != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this night's clips?") },
            text = { Text("The recordings are removed to free space. The timeline, score and notes stay.") },
            confirmButton = { TextButton({ confirm = false; onDeleteClips() }) { Text("Delete clips") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MorningSummary(text: String, askForNotes: Boolean, onAddNotes: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.border(1.dp, Palette.Moon.copy(alpha = 0.5f), RoundedCornerShape(20.dp))) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Good morning", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Palette.Moon)
                IconButton(onDismiss) { Icon(Icons.Filled.Close, "Dismiss summary", tint = Palette.Muted) }
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (askForNotes) {
                Spacer(Modifier.height(6.dp))
                Text("Anything different last night? Tag it now while you remember.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onAddNotes, Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add factors or a note")
                }
            }
        }
    }
}
