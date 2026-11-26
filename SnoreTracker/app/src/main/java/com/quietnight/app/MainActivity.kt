package com.quietnight.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietnight.app.audio.RecordingService
import com.quietnight.app.data.DemoData
import com.quietnight.app.ui.Palette
import com.quietnight.app.ui.QuietNightTheme
import com.quietnight.app.ui.RecordTab
import com.quietnight.app.ui.ResultsListScreen
import com.quietnight.app.ui.SessionDetailScreen
import com.quietnight.app.ui.SettingsScreen
import com.quietnight.app.ui.TrendsScreen
import com.quietnight.app.ui.rememberDataActions
import com.quietnight.app.ui.IntroScreen
import com.quietnight.app.ui.rememberReminderToggle
import com.quietnight.app.data.WeightStore
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep any night that was cut short because the app was closed while recording.
        RecordingService.recoverInterrupted(application as QuietNightApp)
        // A reminder (or widget) tap asks for a tab; only on a fresh start, not when restoring state.
        if (savedInstanceState == null) openTabRequest.value = Reminder.requestedTab(intent)
        setContent { QuietNightTheme { AppRoot(application as QuietNightApp) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Reminder.requestedTab(intent)?.let { openTabRequest.value = it }
    }

    companion object {
        /** Tab asked for by the launching intent; AppRoot consumes it. */
        val openTabRequest = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    }
}

@Composable
private fun AppRoot(app: QuietNightApp) {
    val context = LocalContext.current
    val sessions by app.repository.sessions.collectAsStateWithLifecycle()
    val prefs by app.settings.prefs.collectAsStateWithLifecycle()
    val rec by RecordingService.state.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showIntro by rememberSaveable { mutableStateOf(false) }
    val weightStore = remember { WeightStore.get(context.filesDir) }
    val weight by weightStore.log.collectAsStateWithLifecycle()
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val tabRequest by MainActivity.openTabRequest.collectAsStateWithLifecycle()
    LaunchedEffect(tabRequest) {
        tabRequest?.let { tab = it; detailId = null; MainActivity.openTabRequest.value = null }
    }
    var deletedNotice by remember { mutableStateOf<com.quietnight.app.data.Session?>(null) }
    val loaded by app.repository.loaded.collectAsStateWithLifecycle()
    var audioVersion by remember { mutableIntStateOf(0) } // bumped when a night's audio is deleted
    val is24 = com.quietnight.app.ui.rememberIs24Hour()
    androidx.compose.runtime.SideEffect { com.quietnight.app.ui.ClockFormat.is24 = is24 } // clock() follows the device setting
    var freshId by rememberSaveable { mutableStateOf<String?>(null) } // just recorded: show the morning summary
    val clipTotal by androidx.compose.runtime.produceState<Long?>(null, sessions) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.repository.clipBytes() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // start() explains on the Record tab if the microphone was refused.
        if (granted.isNotEmpty()) RecordingService.start(context)
    }
    val start = {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) RecordingService.start(context) else permissionLauncher.launch(needed.toTypedArray())
    }

    // Open the night that has just been saved.
    LaunchedEffect(rec.finishedId) {
        rec.finishedId?.let {
            tab = 1
            detailId = it
            freshId = it
            RecordingService.clearFinished()
        }
    }

    // Keep the dimmed recording screen from locking only while it is showing; recording itself
    // continues with the screen off.
    val activity = context as? ComponentActivity
    DisposableEffect(rec.recording, tab) {
        val keepOn = rec.recording && tab == 0
        if (keepOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val detail = sessions.firstOrNull { it.id == detailId }
    BackHandler(enabled = detail != null || tab != 0) {
        if (detail != null) detailId = null else tab = 0
    }

    // Never cover a recording in progress (e.g. opened from its notification) with the first-run guide.
    if ((!prefs.introSeen && !rec.recording) || showIntro) {
        val finishIntro = { showIntro = false; app.settings.update { it.copy(introSeen = true) } }
        BackHandler(enabled = showIntro) { finishIntro() }
        IntroScreen(onDone = finishIntro)
        return
    }

    Scaffold(
        containerColor = Palette.Background,
        bottomBar = {
            NavigationBar(containerColor = Palette.Surface) {
                listOf(
                    "Record" to Icons.Filled.Mic,
                    "Results" to Icons.Filled.NightsStay,
                    "Trends" to Icons.Filled.BarChart,
                    "Settings" to Icons.Filled.Settings,
                ).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; detailId = null },
                        icon = { Icon(icon, null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                tab == 1 && detail != null -> {
                    // Whole-night audio index and size, read off the main thread.
                    // Keyed on the night itself, so a save that finishes after it opened (the service writing its
                    // last segment) is picked up.
                    val nightAudio by androidx.compose.runtime.produceState(Triple(emptyList<com.quietnight.app.audio.AudioSegment>(), 0L, 0L), detail, audioVersion) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val dir = app.repository.clipDir(detail.id)
                            Triple(com.quietnight.app.audio.SegmentIndex.read(dir), app.repository.audioBytes(detail.id), app.repository.clipBytes(detail.id))
                        }
                    }
                    val index = sessions.indexOf(detail) // newest first
                    SessionDetailScreen(
                        session = detail,
                        onBack = { detailId = null },
                        onOlder = sessions.getOrNull(index + 1)?.let { s -> { detailId = s.id } },
                        onNewer = sessions.getOrNull(index - 1)?.let { s -> { detailId = s.id } },
                        onDelete = { deletedNotice = detail; detailId = null; app.repository.trash(detail.id) },
                        clipFile = { app.repository.clipFile(detail.id, it) },
                        history = sessions,
                        onSave = app.repository::save,
                        customTags = prefs.customTags,
                        justFinished = detail.id == freshId,
                        onDismissSummary = { freshId = null },
                        clipBytes = nightAudio.third,
                        onDeleteClips = { app.repository.deleteClips(detail.id) },
                        audioSegments = nightAudio.first,
                        audioDir = app.repository.clipDir(detail.id),
                        audioBytes = nightAudio.second,
                        onDeleteAudio = { app.repository.deleteNightAudio(detail.id); audioVersion++ },
                        recordingInProgress = detail.id == rec.activeSessionId,
                    )
                }
                tab == 0 -> RecordTab(
                    prefs = prefs,
                    rec = rec,
                    lastSession = sessions.firstOrNull { !it.demo },
                    onStart = start,
                    onPrefs = app.settings::update,
                    onOpenSession = { tab = 1; detailId = it },
                )
                tab == 1 -> ResultsListScreen(
                    sessions,
                    onDelete = app.repository::trash,
                    onUndoDelete = { app.repository.restore(it) },
                    deletedNotice = deletedNotice,
                    onDeletedNoticeShown = { deletedNotice = null },
                    loading = !loaded,
                    clipBytes = clipTotal,
                ) { detailId = it }
                tab == 2 -> {
                    val data = rememberDataActions(app)
                    TrendsScreen(
                        sessions, onOpenNight = { tab = 1; detailId = it }, weight = weight, onWeight = weightStore::update,
                        onSaveReport = data.onSaveReport, onShareReport = data.onShareReport, reportStatus = data.message,
                    )
                }
                else -> {
                    val data = rememberDataActions(app)
                    val storage = com.quietnight.app.ui.rememberStorageState(app)
                    SettingsScreen(
                    prefs = prefs,
                    hasDemo = sessions.any { it.demo },
                    hasSessions = sessions.isNotEmpty(),
                    hasRealNights = sessions.any { !it.demo },
                    canReport = com.quietnight.app.data.ReportData.available(sessions, System.currentTimeMillis()),
                    onPrefs = app.settings::update,
                    onAddDemo = { Thread { DemoData.generate(System.currentTimeMillis()).forEach(app.repository::save) }.start() }, // ~35 files: off the main thread
                    onRemoveDemo = { Thread { app.repository.deleteWhere { it.demo } }.start() },
                    onDeleteAll = { Thread { app.repository.deleteWhere { true } }.start() },
                    onBackup = data.onBackup,
                    onRestore = data.onRestore,
                    onExportCsv = data.onExportCsv,
                    dataMessage = data.message,
                    dataBusy = data.busy,
                    onShowIntro = { showIntro = true },
                    onReminder = rememberReminderToggle(app),
                    weight = weight,
                    onWeight = weightStore::update,
                    onSaveReport = data.onSaveReport,
                    onShareReport = data.onShareReport,
                    storageBytes = storage.bytes,
                    onClipCleanup = storage.onCleanupDays,
                    storageMessage = storage.message,
                    detection = com.quietnight.app.ui.rememberDetectionCheck(app, prefs.sensitivity),
                )
                }
            }
        }
    }
}
