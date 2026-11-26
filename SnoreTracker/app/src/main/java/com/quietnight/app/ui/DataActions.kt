package com.quietnight.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.quietnight.app.QuietNightApp
import com.quietnight.app.data.Backup
import com.quietnight.app.data.BackupException
import com.quietnight.app.data.Csv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Status of the current or last backup, restore or export. */
data class DataJobState(val busy: Boolean = false, val message: String? = null)

/**
 * Runs backup, restore and CSV export in an app-wide scope, so they finish and their result message
 * stays visible even if the user leaves the Settings tab while they run.
 */
object DataJobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DataJobState())
    val state: StateFlow<DataJobState> = _state

    fun run(working: String, block: (progress: (String) -> Unit) -> String) {
        if (_state.value.busy) return
        _state.value = DataJobState(true, working)
        scope.launch {
            val msg = try {
                block { p -> _state.value = DataJobState(true, p) }
            } catch (e: BackupException) {
                e.message ?: "Could not read the backup."
            } catch (e: Exception) {
                "Something went wrong. Nothing was changed."
            }
            _state.value = DataJobState(false, msg)
        }
    }

    fun backup(app: QuietNightApp, resolver: ContentResolver, uri: Uri) = run("Saving backup…") {
        val n = resolver.openOutputStream(uri)?.use { out ->
            Backup.export(app.repository.sessions.value, app.repository::clipFile, out)
        } ?: throw IllegalStateException()
        "Backed up $n ${if (n == 1) "night" else "nights"}."
    }

    fun restore(app: QuietNightApp, resolver: ContentResolver, uri: Uri, cacheDir: File) = run("Restoring…") { progress ->
        val r = resolver.openInputStream(uri)?.use { input ->
            Backup.restore(
                input,
                app.repository.sessions.value.map { it.id }.toSet(),
                File(cacheDir, "restore"),
                app.repository::clipDir,
                app.repository::save,
            ) { files -> if (files % 10 == 0) progress("Restoring… $files files read") }
        } ?: throw IllegalStateException()
        buildString {
            append("Added ${r.added} ${if (r.added == 1) "night" else "nights"}.")
            if (r.skipped > 0) append(" ${r.skipped} already here.")
            if (r.invalid > 0) append(" ${r.invalid} could not be read.")
        }
    }

    fun exportCsv(app: QuietNightApp, resolver: ContentResolver, uri: Uri) = run("Saving CSV…") {
        val text = Csv.export(app.repository.sessions.value)
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: throw IllegalStateException()
        "Saved the spreadsheet file."
    }
}

/** Backup, restore and CSV export through the system file picker (no storage permission needed). */
class DataActions(
    val onBackup: () -> Unit,
    val onRestore: () -> Unit,
    val onExportCsv: () -> Unit,
    val message: String?,
    val busy: Boolean,
)

@Composable
fun rememberDataActions(app: QuietNightApp): DataActions {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val state by DataJobs.state.collectAsStateWithLifecycle()

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) DataJobs.backup(app, resolver, uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) DataJobs.restore(app, resolver, uri, context.cacheDir)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) DataJobs.exportCsv(app, resolver, uri)
    }

    return DataActions(
        onBackup = { backupLauncher.launch(Backup.fileName(System.currentTimeMillis())) },
        // Some file managers label zips oddly, so accept any type and let the restore validate it.
        onRestore = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        onExportCsv = { csvLauncher.launch(Csv.fileName(System.currentTimeMillis())) },
        message = state.message,
        busy = state.busy,
    )
}
