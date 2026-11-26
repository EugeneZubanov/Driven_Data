package com.quietnight.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quietnight.app.data.Prefs

@Composable
fun SettingsScreen(
    prefs: Prefs,
    hasDemo: Boolean,
    hasSessions: Boolean,
    onPrefs: ((Prefs) -> Prefs) -> Unit,
    onAddDemo: () -> Unit,
    onRemoveDemo: () -> Unit,
    onDeleteAll: () -> Unit,
    onBackup: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    onExportCsv: (() -> Unit)? = null,
    dataMessage: String? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))

        Card {
            Column {
                SectionTitle("Detection sensitivity")
                Text(
                    "Raise it if quiet snoring is missed; lower it if other noises are counted.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Low", "Normal", "High").forEachIndexed { i, name ->
                        FilterChip(prefs.sensitivity == i, { onPrefs { it.copy(sensitivity = i) } }, label = { Text(name) })
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Keep audio clips")
                    Text(
                        "Saves up to 15 ten-second clips of the loudest snoring each night. Audio never leaves this phone.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(prefs.saveClips, { v -> onPrefs { it.copy(saveClips = v) } })
            }
        }

        Spacer(Modifier.height(12.dp))
        Card {
            Column {
                SectionTitle("Sample nights")
                Text(
                    "Adds made-up nights, clearly marked as samples, so you can explore Results and Trends.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                if (hasDemo) OutlinedButton(onRemoveDemo) { Text("Remove sample nights") }
                else OutlinedButton(onAddDemo) { Text("Add sample nights") }
            }
        }

        if (onBackup != null || onRestore != null || onExportCsv != null) {
            Spacer(Modifier.height(12.dp))
            DataCard(hasSessions, onBackup, onRestore, onExportCsv, dataMessage)
        }

        Spacer(Modifier.height(12.dp))
        Card {
            Column {
                SectionTitle("How the score works")
                Text(
                    "The snore score is the share of the night spent snoring, weighted by loudness: " +
                        "mild snoring counts 0.4×, loud 1× and heavy 2×. A night with no snoring scores 0. " +
                        "Loudness is measured relative to how quiet your room is, so scores are comparable " +
                        "night to night on the same phone in the same spot.\n\n" +
                        "This app is a personal tracking tool, not a medical device. If you are worried about " +
                        "pauses in breathing or daytime sleepiness, talk to a doctor.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (hasSessions) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ confirmDelete = true }) { Text("Delete all nights", color = Palette.Heavy) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all nights?") },
            text = { Text("Every recorded night and its audio clips will be removed. This cannot be undone.") },
            confirmButton = { TextButton({ confirmDelete = false; onDeleteAll() }) { Text("Delete everything") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DataCard(
    hasSessions: Boolean,
    onBackup: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    onExportCsv: (() -> Unit)?,
    message: String?,
) {
    Card {
        Column {
            SectionTitle("Your data")
            Text(
                "Save a backup file of every night and its clips wherever you choose, and restore it on this " +
                    "or another phone. Restoring adds missing nights and keeps the ones already here. " +
                    "The spreadsheet file has one row per night. Sample nights are left out.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBackup != null) OutlinedButton(onBackup, enabled = hasSessions) { Text("Back up") }
                if (onRestore != null) OutlinedButton(onRestore) { Text("Restore") }
                if (onExportCsv != null) OutlinedButton(onExportCsv, enabled = hasSessions) { Text("Export CSV") }
            }
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
