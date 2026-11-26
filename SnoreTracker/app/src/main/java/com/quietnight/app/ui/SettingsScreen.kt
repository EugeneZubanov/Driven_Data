package com.quietnight.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quietnight.app.BuildConfig
import com.quietnight.app.data.Prefs
import com.quietnight.app.data.ReminderTime
import com.quietnight.app.data.WeightLog

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
    dataBusy: Boolean = false,
    onShowIntro: (() -> Unit)? = null,
    onReminder: ((on: Boolean, minuteOfDay: Int) -> Unit)? = null,
    weight: WeightLog? = null,
    onWeight: (((WeightLog) -> WeightLog) -> Unit)? = null,
    onSaveReport: (() -> Unit)? = null,
    onShareReport: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        ScreenTitle("Settings")
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
                Switch(prefs.saveClips, { v -> onPrefs { it.copy(saveClips = v) } }, Modifier.semantics { contentDescription = "Keep audio clips" })
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
            DataCard(hasSessions, onBackup, onRestore, onExportCsv, dataMessage, dataBusy, onSaveReport, onShareReport)
        }

        if (onReminder != null) {
            Spacer(Modifier.height(12.dp))
            ReminderCard(prefs.reminderOn, prefs.reminderMinute, onReminder)
        }

        if (weight != null && onWeight != null) {
            Spacer(Modifier.height(12.dp))
            WeightSettingsCard(weight, onWeight)
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
                if (onShowIntro != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onShowIntro) { Text("Show the introduction again") }
                }
            }
        }

        if (hasSessions) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ confirmDelete = true }) { Text("Delete all nights", color = Palette.Heavy) }
        }
        Spacer(Modifier.height(16.dp))
        Text("Quiet Night ${BuildConfig.VERSION_NAME}", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
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
    busy: Boolean,
    onSaveReport: (() -> Unit)?,
    onShareReport: (() -> Unit)?,
) {
    Card {
        Column {
            SectionTitle("Your data")
            Text(
                "Save a backup file of every night and its clips wherever you choose, and restore it on this " +
                    "or another phone. Restoring adds missing nights and keeps the ones already here. " +
                    "The spreadsheet file has one row per night; the report is a printable summary of the last 30 nights. " +
                    "Sample nights are left out.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBackup != null) OutlinedButton(onBackup, enabled = hasSessions && !busy) { Text("Back up") }
                if (onRestore != null) OutlinedButton(onRestore, enabled = !busy) { Text("Restore") }
                if (onExportCsv != null) OutlinedButton(onExportCsv, enabled = hasSessions && !busy) { Text("Export CSV") }
                if (onSaveReport != null) OutlinedButton(onSaveReport, enabled = hasSessions && !busy) { Text("30-night report (PDF)") }
                if (onShareReport != null) OutlinedButton(onShareReport, enabled = !busy) { Text("Share report") }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Moon, trackColor = Palette.SurfaceHigh)
            }
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderCard(on: Boolean, minute: Int, onReminder: (Boolean, Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val is24 = rememberIs24Hour()
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Bedtime reminder")
                    Text(
                        "A daily notification to start recording. It may arrive a few minutes late to save battery.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(on, { onReminder(it, minute) }, Modifier.semantics { contentDescription = "Bedtime reminder" })
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ picking = true }) { Text("Time: ${ReminderTime.label(minute, is24)}") }
        }
    }
    if (picking) {
        val state = rememberTimePickerState(minute / 60, minute % 60, is24Hour = is24)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Reminder time") },
            text = { TimeInput(state) },
            confirmButton = { TextButton({ picking = false; onReminder(true, state.hour * 60 + state.minute) }) { Text("Set") } },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        )
    }
}
