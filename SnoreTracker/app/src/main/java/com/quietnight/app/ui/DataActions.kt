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
import android.content.Intent
import com.quietnight.app.data.Csv
import com.quietnight.app.data.RestoreSpace
import com.quietnight.app.data.StorageCleanup
import androidx.compose.runtime.setValue
import com.quietnight.app.data.ReportData
import com.quietnight.app.data.WeightStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Status of the current or last backup, restore or export. */
data class DataJobState(val busy: Boolean = false, val message: String? = null, val reportUri: Uri? = null)

/**
 * Runs backup, restore and CSV export in an app-wide scope, so they finish and their result message
 * stays visible even if the user leaves the Settings tab while they run.
 */
object DataJobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DataJobState())
    val state: StateFlow<DataJobState> = _state

    fun run(app: QuietNightApp, working: String, block: (progress: (String) -> Unit) -> String) {
        if (_state.value.busy) return
        val report = _state.value.reportUri
        _state.value = DataJobState(true, working, report)
        scope.launch {
            val msg = try {
                // Nights may still be loading in the background; these jobs need the full list.
                app.repository.ensureLoaded()
                block { p -> _state.value = DataJobState(true, p, report) }
            } catch (e: BackupException) {
                e.message ?: "Could not read the backup."
            } catch (e: Exception) {
                "Something went wrong. Nothing was changed."
            }
            _state.value = DataJobState(false, msg, _state.value.reportUri)
        }
    }

    fun report(app: QuietNightApp, resolver: ContentResolver, uri: Uri) = run(app, "Making the report…") {
        val data = ReportData.build(app.repository.sessions.value, WeightStore.get(app.filesDir).log.value, System.currentTimeMillis())
        writeOrDelete(resolver, uri) { ReportRenderer.writePdf(data, it) }
        _state.value = _state.value.copy(reportUri = uri)
        "Saved the report (${data.nights.size} ${if (data.nights.size == 1) "night" else "nights"})."
    }

    fun backup(app: QuietNightApp, resolver: ContentResolver, uri: Uri) = run(app, "Saving backup…") {
        val n = writeOrDelete(resolver, uri) { out ->
            Backup.export(app.repository.sessions.value, app.repository::clipFile, out, WeightStore.get(app.filesDir).log.value)
        }
        "Backed up $n ${if (n == 1) "night" else "nights"}."
    }

    fun restore(app: QuietNightApp, resolver: ContentResolver, uri: Uri, cacheDir: File) = run(app, "Restoring…") { progress ->
        // Staging plus the final copy can need about twice the zip's size; say so up front instead of failing midway.
        RestoreSpace.problem(sizeOf(resolver, uri), allocatable(app, cacheDir), allocatable(app, app.filesDir))?.let { throw BackupException(it) }
        val r = resolver.openInputStream(uri)?.use { input ->
            Backup.restore(
                input,
                app.repository.sessions.value.map { it.id }.toSet(),
                File(cacheDir, "restore"),
                app.repository::clipDir,
                app.repository::save,
                onProgress = { files -> if (files % 10 == 0) progress("Restoring… $files files read") },
                mergeWeight = WeightStore.get(app.filesDir)::mergeFrom,
            )
        } ?: throw IllegalStateException()
        buildString {
            append("Added ${r.added} ${if (r.added == 1) "night" else "nights"}.")
            if (r.skipped > 0) append(" ${r.skipped} already here.")
            if (r.invalid > 0) append(" ${r.invalid} could not be read.")
            if (r.weightsAdded > 0) append(" Added ${r.weightsAdded} weight ${if (r.weightsAdded == 1) "entry" else "entries"}.")
        }
    }

    /**
     * Writes to a document the user just created; if writing fails, removes the half-written file so a
     * broken backup or report is not left behind, then rethrows.
     */
    private fun <T> writeOrDelete(resolver: ContentResolver, uri: Uri, write: (java.io.OutputStream) -> T): T = try {
        resolver.openOutputStream(uri)?.use(write) ?: throw IllegalStateException("Could not open the file")
    } catch (e: Exception) {
        runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
        throw e
    }

    /** Bytes the app could use in [dir], counting cache the system can clear; falls back to plain free space. */
    private fun allocatable(app: QuietNightApp, dir: File): Long = runCatching {
        val sm = app.getSystemService(android.os.storage.StorageManager::class.java)!!
        sm.getAllocatableBytes(sm.getUuidForPath(dir))
    }.getOrElse { @Suppress("UsableSpace") dir.usableSpace }

    /** Size of a picked document, or null when the provider does not say. */
    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    fun exportCsv(app: QuietNightApp, resolver: ContentResolver, uri: Uri) = run(app, "Saving CSV…") {
        val text = Csv.export(app.repository.sessions.value, WeightStore.get(app.filesDir).log.value)
        writeOrDelete(resolver, uri) { it.write(text.toByteArray()) }
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
    val onSaveReport: () -> Unit = {},
    /** Opens the share sheet for the last saved report; null until one has been saved. */
    val onShareReport: (() -> Unit)? = null,
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
    val reportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) DataJobs.report(app, resolver, uri)
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
        onSaveReport = { reportLauncher.launch(ReportData.fileName(System.currentTimeMillis())) },
        // Shares the document the user just saved: the picker granted us access to it, and we pass that on,
        // so no FileProvider is needed.
        onShareReport = state.reportUri?.let { uri ->
            {
                val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(Intent.createChooser(send, "Share report"))
                } catch (_: Exception) {
                    // No app can take it, or the grant was lost; the saved file is still where the user put it.
                }
            }
        },
    )
}

/** Audio storage shown in Settings, and the automatic clean-up choice. */
class StorageState(val bytes: Long?, val message: String?, val onCleanupDays: (Int) -> Unit)

@Composable
fun rememberStorageState(app: QuietNightApp): StorageState {
    val sessions by app.repository.sessions.collectAsStateWithLifecycle()
    var message by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var refresh by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val bytes by androidx.compose.runtime.produceState<Long?>(null, sessions, refresh) {
        value = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { app.repository.clipBytes() }.getOrNull() }
    }
    return StorageState(bytes, message) { days ->
        app.settings.update { it.copy(clipCleanupDays = days) }
        message = null
        StorageCleanup.runInBackground(app.repository, days) { freed ->
            message = if (freed > 0) "Freed ${StorageCleanup.formatBytes(freed)}." else "Nothing old enough to remove yet."
            refresh++
        }
    }
}
