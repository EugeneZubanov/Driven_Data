package com.quietnight.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quietnight.app.audio.RecState
import com.quietnight.app.data.Prefs
import com.quietnight.app.data.Session
import com.quietnight.app.data.Tags
import com.quietnight.app.data.formatDuration
import kotlinx.coroutines.delay

@Composable
fun RecordScreen(
    prefs: Prefs,
    rec: RecState,
    lastSession: Session?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPrefs: ((Prefs) -> Prefs) -> Unit,
    onOpenSession: (String) -> Unit,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    if (rec.recording) RecordingView(rec, onStop, nowMs)
    else IdleView(prefs, rec, lastSession, onStart, onPrefs, onOpenSession)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleView(
    prefs: Prefs,
    rec: RecState,
    lastSession: Session?,
    onStart: () -> Unit,
    onPrefs: ((Prefs) -> Prefs) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    var addKind by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Icon(Icons.Filled.Bedtime, null, tint = Palette.Moon, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text("Quiet Night", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Snore tracker", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.size(148.dp).clip(CircleShape).background(Palette.Moon).clickable(onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mic, null, tint = Palette.Background, modifier = Modifier.size(40.dp))
                Text("Start", color = Palette.Background, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Plug the phone in and leave it face up near your bed.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
        rec.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Palette.Heavy, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(24.dp))
        Card {
            Column {
                SectionTitle("Time to fall asleep")
                Text(
                    "Listening starts after this delay.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(0, 10, 20, 30, 45)) {
                        FilterChip(
                            selected = prefs.delayMin == m,
                            onClick = { onPrefs { it.copy(delayMin = m) } },
                            label = { Text(if (m == 0) "None" else "$m min") },
                        )
                    }
                }
            }
        }

        for ((kind, title, hint, builtIn) in listOf(
            TagGroup("f", "Factors tonight", "Things that might make snoring worse.", Tags.factors),
            TagGroup("r", "Remedies tonight", "Things you are trying to snore less.", Tags.remedies),
        )) {
            Spacer(Modifier.height(12.dp))
            Card {
                Column {
                    SectionTitle(title)
                    Text(hint, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    val all = builtIn.map { "$kind:$it" } + prefs.customTags.filter { it.startsWith("$kind:") }.sorted()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tag in all) {
                            val on = tag in prefs.selectedTags
                            FilterChip(
                                selected = on,
                                onClick = {
                                    onPrefs { it.copy(selectedTags = if (on) it.selectedTags - tag else it.selectedTags + tag) }
                                },
                                label = { Text(Tags.label(tag)) },
                            )
                        }
                        AssistChip(
                            onClick = { addKind = kind },
                            label = { Text("Add") },
                            leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
        }

        if (lastSession != null) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.clickable { onOpenSession(lastSession.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Last night")
                        Text(
                            "${formatDuration(lastSession.durationSec)} recorded · ${formatDuration(lastSession.snoringSec)} snoring",
                            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ScoreDial(lastSession, 56.dp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    addKind?.let { kind ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addKind = null },
            title = { Text(if (kind == "f") "New factor" else "New remedy") },
            text = { OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val tag = "$kind:${name.trim()}"
                        onPrefs { it.copy(customTags = it.customTags + tag, selectedTags = it.selectedTags + tag) }
                        addKind = null
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton({ addKind = null }) { Text("Cancel") } },
        )
    }
}

private data class TagGroup(val kind: String, val title: String, val hint: String, val builtIn: List<String>)

@Composable
private fun RecordingView(rec: RecState, onStop: () -> Unit, nowMs: () -> Long) {
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = nowMs() }
    }
    var confirm by remember { mutableStateOf(false) }
    val elapsed = ((now - rec.startMs) / 1000).toInt().coerceAtLeast(0)
    val delayLeft = ((rec.startMs + rec.delayMs - now) / 1000).toInt()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Bedtime, null, tint = Palette.Moon.copy(alpha = 0.7f), modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(16.dp))
        Text(clock(now), fontSize = 64.sp, fontWeight = FontWeight.Light, color = Palette.Text.copy(alpha = 0.85f))
        Text("Recording for ${formatDuration(elapsed)}", color = Palette.Muted)
        Spacer(Modifier.height(28.dp))

        if (!rec.listening && delayLeft > 0) {
            Text("Listening starts in ${formatDuration(delayLeft + 59)}", color = Palette.Muted)
        } else {
            Text("Listening", color = Palette.Muted)
            Spacer(Modifier.height(10.dp))
            // live loudness above the room's noise floor
            val frac = (rec.dbAboveFloor / 35f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(0.7f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Quiet)) {
                Box(
                    Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (rec.dbAboveFloor >= 28) Palette.Heavy else if (rec.dbAboveFloor >= 18) Palette.Loud else Palette.Mild),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("${rec.eventCount} snores detected", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(48.dp))
        Button(onClick = { confirm = true }) {
            Icon(Icons.Filled.Stop, null)
            Spacer(Modifier.width(8.dp))
            Text("Stop and see results")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "You can turn the screen off. Recording continues until you stop it.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Stop recording?") },
            confirmButton = { TextButton({ confirm = false; onStop() }) { Text("Stop") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Keep recording") } },
        )
    }
}
