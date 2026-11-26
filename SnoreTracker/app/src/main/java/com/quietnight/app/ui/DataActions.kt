package com.quietnight.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.quietnight.app.QuietNightApp
import com.quietnight.app.data.Backup
import com.quietnight.app.data.BackupException
import com.quietnight.app.data.Csv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Backup, restore and CSV export through the system file picker (no storage permission needed). */
class DataActions(
    val onBackup: () -> Unit,
    val onRestore: () -> Unit,
    val onExportCsv: () -> Unit,
    val message: String?,
)

@Composable
fun rememberDataActions(app: QuietNightApp): DataActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val resolver = context.contentResolver

    fun run(working: String, block: () -> String) {
        message = working
        scope.launch {
            message = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: BackupException) {
                    e.message ?: "Could not read the backup."
                } catch (e: Exception) {
                    "Something went wrong. Nothing was changed."
                }
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) run("Saving backup…") {
            val n = resolver.openOutputStream(uri)?.use { out ->
                Backup.export(app.repository.sessions.value, app.repository::clipFile, out)
            } ?: throw IllegalStateException()
            "Backed up $n ${if (n == 1) "night" else "nights"}."
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run("Restoring…") {
            val r = resolver.openInputStream(uri)?.use { input ->
                Backup.restore(
                    input,
                    app.repository.sessions.value.map { it.id }.toSet(),
                    File(context.cacheDir, "restore"),
                    app.repository::clipDir,
                    app.repository::save,
                )
            } ?: throw IllegalStateException()
            buildString {
                append("Added ${r.added} ${if (r.added == 1) "night" else "nights"}.")
                if (r.skipped > 0) append(" ${r.skipped} already here.")
                if (r.invalid > 0) append(" ${r.invalid} could not be read.")
            }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) run("Saving CSV…") {
            val text = Csv.export(app.repository.sessions.value)
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: throw IllegalStateException()
            "Saved the spreadsheet file."
        }
    }

    return DataActions(
        onBackup = { backupLauncher.launch(Backup.fileName(System.currentTimeMillis())) },
        // Some file managers label zips oddly, so accept any type and let the restore validate it.
        onRestore = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        onExportCsv = { csvLauncher.launch(Csv.fileName(System.currentTimeMillis())) },
        message = message,
    )
}
