package com.quietnight.app.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.quietnight.app.MainActivity
import com.quietnight.app.QuietNightApp
import com.quietnight.app.R
import com.quietnight.app.data.Session
import com.quietnight.app.ui.clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

data class RecState(
    val recording: Boolean = false,
    val startMs: Long = 0,
    val delayMs: Long = 0,
    val listening: Boolean = false,
    val dbAboveFloor: Float = 0f,
    val eventCount: Int = 0,
    /** Set when a night has just been saved, so the UI can open it. */
    val finishedId: String? = null,
    val error: String? = null,
    /** True while no audio is arriving (a call or another app has the microphone). */
    val micPaused: Boolean = false,
    /** Seconds of the night so far without audio. */
    val gapSec: Int = 0,
    /** A calm note for the Record tab, e.g. why the last night ended. */
    val notice: String? = null,
    /** The night [notice] is about, if it was kept. */
    val noticeSessionId: String? = null,
    /** This is a short test run, not a whole night. */
    val testRun: Boolean = false,
    /** Wake-up time the night will stop at, or 0. */
    val wakeAtMs: Long = 0,
    /**
     * Id of the night being recorded (its periodic saves appear in the night list). Other screens
     * should not offer delete for it; tag and note edits made meanwhile are kept by later saves.
     */
    val activeSessionId: String? = null,
)

/**
 * Foreground service that listens to the microphone through the night.
 *
 * Returns START_NOT_STICKY on purpose: since Android 14 a microphone service cannot be started
 * from the background, so a system restart could not record anyway. If the process dies, the
 * night saved so far is recovered on the next launch instead (see [recoverInterrupted]).
 */
class RecordingService : Service() {

    @Volatile private var stopRequested = false
    @Volatile private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    /** A Start arrived while the previous night was still saving; run it once that is done. Main thread only. */
    private var queued: Intent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            queued = null
            stopRequested = true
            if (worker == null) {
                // e.g. "Stop and save" tapped after the phone closed the app: keep what was saved.
                recoverInterrupted(application as QuietNightApp)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // Every start through startForegroundService must be answered with startForeground, even a
        // duplicate one, or the system kills the app.
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(System.currentTimeMillis(), "Starting…"), type)
        } catch (e: Exception) {
            // Android 12+ refuses from the background; Android 14+ refuses without the microphone permission.
            starting = false
            if (worker == null) {
                _state.value = RecState(error = "Could not start recording. Open the app and try again.")
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent == null) {
            // Restarted by the system with no request behind it; there is nothing to record.
            if (worker == null) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        when (ServiceLifecycle.onStart(workerAlive = worker != null, workerStopping = stopRequested)) {
            ServiceLifecycle.OnStart.LAUNCH -> launch(intent)
            ServiceLifecycle.OnStart.QUEUE_AFTER_CURRENT -> queued = intent
            ServiceLifecycle.OnStart.IGNORE_DUPLICATE -> {}
        }
        return START_NOT_STICKY
    }

    private fun launch(intent: Intent) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            starting = false
            _state.value = RecState(error = "Microphone permission is needed to record.")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        recoverInterrupted(application as QuietNightApp)
        WakeAlarm.silence(this)

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuietNight:recording")
            .apply { setReferenceCounted(false); acquire(RecordingPolicy.WAKE_LOCK_MS) }

        val testRunMs = intent.getLongExtra(EXTRA_TEST_RUN_MS, 0L)
        stopRequested = false
        starting = true
        worker = thread(name = "night-recorder") { record(testRunMs) }
    }

    /** Runs on the main thread once a night's worker has finished and saved. */
    private fun onWorkerEnd() {
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        val next = queued
        queued = null
        when (ServiceLifecycle.onWorkerEnd(startQueued = next != null)) {
            ServiceLifecycle.OnWorkerEnd.RELAUNCH -> launch(next!!)
            ServiceLifecycle.OnWorkerEnd.STOP_SERVICE -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun sleepUnlessStopped(ms: Long) {
        val until = RecordingEnv.elapsedMs() + ms
        while (!stopRequested && RecordingEnv.elapsedMs() < until) RecordingEnv.sleep(200)
    }

    private fun record(testRunMs: Long) {
        val app = application as QuietNightApp
        val prefs = app.settings.prefs.value
        val startMs = RecordingEnv.wallMs()
        val startElapsed = RecordingEnv.elapsedMs()
        val id = "s$startMs"
        val testRun = testRunMs > 0
        val delayMs = if (testRun) 0L else prefs.delayMin * 60_000L
        val wakeAtMs = if (testRun) 0L else WakeAlarm.wakeAtFor(this, startMs)
        var audio: MicInput? = null
        var recorder: NightRecorder? = null
        val gaps = GapTracker()
        var finishedId: String? = null
        var problem: String? = null
        var stopReason: RecordingPolicy.StopReason? = null
        var audioWriter: NightAudioWriter? = null
        var audioNote: String? = null
        var notified = ""
        fun offsetNow() = RecordingEnv.elapsedMs() - startElapsed
        fun snapshot(): Session {
            val fresh = recorder!!.snapshot().withRecordingInfo(gaps = gaps.snapshot(offsetNow()))
            // Keep tags or a note the user edited on a periodic save while the night went on.
            val edited = app.repository.sessions.value.firstOrNull { it.id == id }
            return when {
                edited != null -> fresh.withEdits(edited.tags, edited.note)
                testRun -> fresh.withEdits(fresh.tags, TEST_RUN_NOTE)
                else -> fresh
            }
        }
        fun notifyText(text: String) {
            if (text == notified) return
            notified = text
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(startMs, text))
            }
        }
        try {
            // A just-finished microphone check may still be letting go of the input; give it a moment.
            audio = RecordingEnv.openMic(this, CAPTURE_RATES) ?: run { RecordingEnv.sleep(600); RecordingEnv.openMic(this, CAPTURE_RATES) }
                ?: error("Could not open the microphone. Another app may be using it.")
            // The whole night uses the rate the microphone opened at; reopening must match it.
            val rate = audio.sampleRate
            val keepClips = prefs.saveClips && filesDir.usableSpace >= RecordingPolicy.NO_CLIPS_STORAGE_BYTES
            recorder = NightRecorder(
                id = id,
                startMs = startMs,
                sampleRate = rate,
                thresholdDb = prefs.thresholdDb,
                delayMs = delayMs,
                tags = prefs.selectedTags.sorted(),
                clipDir = if (keepClips) app.repository.clipDir(id) else null,
            )
            // Mark as active before writing the marker, so a launch in between never "recovers" a live night.
            active = true
            markRecording(this, id)
            if (!testRun && NightAudioPref.state(this).value == true) {
                if (RecordingEnv.freeBytes(filesDir) >= NightAudioWriter.MIN_FREE_BYTES) {
                    audioWriter = NightAudioWriter(app.repository.clipDir(id), rate, RecordingEnv.encoder)
                } else audioNote = AUDIO_LOW_STORAGE
            }
            starting = false
            _state.value = RecState(
                recording = true, startMs = startMs, delayMs = delayMs, testRun = testRun, wakeAtMs = wakeAtMs, activeSessionId = id,
            )
            val power = getSystemService(POWER_SERVICE) as PowerManager
            var screenOn = true
            var lastUi = Long.MIN_VALUE / 2

            val buf = ShortArray(rate / 10)
            var lastSave = RecordingEnv.elapsedMs()
            var lastCheck = Long.MIN_VALUE / 2
            var silenced = false
            var accountedMs = 0L // audio fed plus time skipped, to keep the timeline on the wall clock
            var failures = 0
            var saved = false
            while (!stopRequested) {
                val now = RecordingEnv.elapsedMs()
                if (now - lastCheck >= 2_000) {
                    lastCheck = now
                    silenced = audio?.silenced() == true
                    screenOn = runCatching { power.isInteractive }.getOrDefault(true)
                    val (pct, charging) = RecordingEnv.battery(this)
                    stopReason = RecordingPolicy.safetyStop(offsetNow(), pct, charging)
                        ?: RecordingPolicy.plannedStop(offsetNow(), RecordingEnv.wallMs(), testRunMs, wakeAtMs)
                    if (stopReason != null) stopRequested = true
                    audioWriter?.let { w ->
                        if (w.failed) { audioNote = AUDIO_FAILED; audioWriter = null }
                        else if (RecordingEnv.freeBytes(filesDir) < NightAudioWriter.MIN_FREE_BYTES) {
                            w.close(); audioNote = AUDIO_LOW_STORAGE; audioWriter = null
                        }
                    }
                }
                if (stopRequested) break

                var n = 0
                if (audio != null) {
                    n = audio.read(buf, buf.size)
                    if (n < 0) {
                        // Dead or invalidated input (e.g. a call took the microphone). Reopen later.
                        audio.close()
                        audio = null
                    }
                }
                if (audio == null) {
                    audioWriter?.gap()
                    gaps.begin(offsetNow())
                    _state.value = _state.value.copy(micPaused = true)
                    notifyText("Microphone in use elsewhere. Will carry on when it is free.")
                    sleepUnlessStopped(RecordingPolicy.reopenDelayMs(++failures))
                    if (!stopRequested) audio = RecordingEnv.openMic(this, listOf(rate))
                    continue
                }
                if (n == 0) { RecordingEnv.sleep(20); continue }

                if (silenced || RecordingPolicy.isDigitalSilence(buf, n)) {
                    // Feeding muted input would drag the noise floor down; count it as a gap instead.
                    audioWriter?.gap()
                    gaps.begin(offsetNow())
                } else {
                    failures = 0
                    if (gaps.inGap) {
                        gaps.end(offsetNow())
                        val behind = offsetNow() - n * 1000L / rate - accountedMs
                        if (behind > 0) { recorder.skip(behind); accountedMs += behind }
                    }
                    audioWriter?.write(buf, n, accountedMs)
                    recorder.feed(buf, n)
                    accountedMs += n * 1000L / rate
                }
                val paused = gaps.inGap
                // Nobody sees the live meter with the screen off or dimmed; update far less often then.
                if (paused != _state.value.micPaused || now - lastUi >= RecordingPolicy.uiUpdateEveryMs(screenOn, uiDimmed)) {
                    lastUi = now
                    _state.value = _state.value.copy(
                        listening = recorder.listening,
                        dbAboveFloor = if (paused) 0f else recorder.currentDbAboveFloor,
                        eventCount = recorder.eventCount,
                        micPaused = paused,
                        gapSec = gaps.snapshot(offsetNow()).sumOf { it.toSec - it.fromSec },
                    )
                }
                notifyText(
                    when {
                        paused -> "Microphone in use elsewhere. Will carry on when it is free."
                        testRun -> "Test run: listening for 5 minutes."
                        recorder.listening -> "Listening for snoring."
                        else -> "Listening starts ${clock(startMs + delayMs)}."
                    }
                )
                // Save progress regularly so a night is not lost if the phone kills the app or dies.
                if (now - lastSave > RecordingPolicy.SAVE_EVERY_MS) {
                    app.repository.save(snapshot())
                    saved = true
                    lastSave = now
                }
            }
            audioWriter?.close()
            if (audioWriter?.failed == true) audioNote = AUDIO_FAILED
            // Time at the end with no audio still belongs to the night.
            val tail = offsetNow() - accountedMs
            if (gaps.inGap && tail > 0) recorder.skip(tail)
            recorder.finish() // decide clips for the last buckets
            val session = snapshot()
            gaps.end(offsetNow())
            if (session.durationSec >= MIN_SESSION_SEC) {
                app.repository.save(session)
                finishedId = id
            } else {
                if (saved) app.repository.delete(id) else app.repository.clipDir(id).deleteRecursively()
                problem = "Recording was shorter than $MIN_SESSION_SEC seconds, so it was not saved."
            }
        } catch (e: Exception) {
            problem = e.message ?: "Recording failed."
            runCatching { audioWriter?.close() }
            // Keep whatever was heard before the failure.
            if (recorder != null) runCatching {
                recorder.finish()
                val s = snapshot()
                if (s.durationSec >= MIN_SESSION_SEC) { app.repository.save(s); finishedId = id }
            }
            if (finishedId == null && app.repository.sessions.value.any { it.id == id }) finishedId = id
        } finally {
            // From here on a new Start is queued until this night has finished saving.
            stopRequested = true
            runCatching { audio?.close() }
            clearRecordingMark(this)
            if (stopReason == RecordingPolicy.StopReason.WAKE_TIME) runCatching { WakeAlarm.ring(this) }
            val notice = listOfNotNull(stopReason?.takeIf { it != RecordingPolicy.StopReason.TEST_DONE }?.message, audioNote)
                .joinToString(" ").ifEmpty { null }
            _state.value = RecState(finishedId = finishedId, error = problem, notice = notice, noticeSessionId = notice?.let { finishedId })
            active = false
            starting = false
            main.post { onWorkerEnd() }
        }
    }

    override fun onDestroy() {
        stopRequested = true
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun buildNotification(startMs: Long, text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Night recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("Recording your night")
            .setContentText(text)
            // The system clock in the shade counts up from the start, so elapsed time stays current for free.
            .setWhen(startMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop and save", stop)
            .build()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MIN_SESSION_SEC = 30
        internal const val ACTION_STOP = "com.quietnight.app.STOP"
        internal const val EXTRA_TEST_RUN_MS = "testRunMs"
        /** Note put on a test-run night so it is easy to tell apart. */
        const val TEST_RUN_NOTE = "Test run"
        private const val AUDIO_LOW_STORAGE = "Full-night audio was turned off for this night because the phone is low on storage."
        private const val AUDIO_FAILED = "Full-night audio could not be recorded on this phone; snore clips were still kept."
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val MARK_PREFS = "recording"
        private const val MARK_ID = "activeId"

        /** A night is being recorded in this process right now. */
        @Volatile private var active = false
        /** Start was pressed and the service has not answered yet; ignores double taps. */
        @Volatile private var starting = false

        private val _state = MutableStateFlow(RecState())
        val state: StateFlow<RecState> = _state

        /** Set by the Record tab while its night view is dimmed. */
        @Volatile var uiDimmed = false

        /** True while a night is being recorded in this process (the microphone is taken). */
        val isRecording: Boolean get() = active || starting

        fun start(context: Context, testRun: Boolean = false) {
            if (active || starting) return
            // Checked here as well as in the service: on Android 14+ a microphone service started
            // without the permission crashes the app before it can explain anything.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _state.value = RecState(error = "Microphone permission is needed to record.")
                return
            }
            MicCheck.stop()
            starting = true
            _state.value = RecState()
            try {
                val i = Intent(context, RecordingService::class.java)
                if (testRun) i.putExtra(EXTRA_TEST_RUN_MS, RecordingPolicy.TEST_RUN_MS)
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                starting = false
                _state.value = RecState(error = "Could not start recording. Try again.")
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP)) }
        }

        fun clearFinished() {
            _state.value = _state.value.copy(finishedId = null, error = null)
        }

        fun clearNotice() {
            _state.value = _state.value.copy(notice = null, noticeSessionId = null)
        }

        /** Battery percent (null if unknown) and whether the phone is plugged in. */
        fun readBattery(context: Context): Pair<Int?, Boolean> {
            val i = runCatching {
                ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            }.getOrNull() ?: return null to true
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
            return pct to (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }

        private fun readSavedNight(app: QuietNightApp, id: String): Session? = runCatching {
            Session.fromJson(org.json.JSONObject(java.io.File(app.filesDir, "sessions/$id.json").readText()))
        }.getOrNull()

        private fun markRecording(context: Context, id: String) {
            context.getSharedPreferences(MARK_PREFS, Context.MODE_PRIVATE).edit().putString(MARK_ID, id).commit()
        }

        private fun clearRecordingMark(context: Context) {
            context.getSharedPreferences(MARK_PREFS, Context.MODE_PRIVATE).edit().remove(MARK_ID).commit()
        }

        /**
         * If the app's process died while recording (killed overnight, crash, or the microphone
         * permission was revoked), keep the part of the night that was saved, mark it as cut short,
         * and leave a note for the Record tab. Safe to call on every launch.
         */
        fun recoverInterrupted(app: QuietNightApp) {
            val sp = app.getSharedPreferences(MARK_PREFS, Context.MODE_PRIVATE)
            val markerId = sp.getString(MARK_ID, null)
            if (markerId != null) app.repository.ensureLoaded() // need the saved nights to decide
            // Read the file itself: the repository may still be loading its list in the background.
            val saved = markerId?.let { id ->
                app.repository.sessions.value.firstOrNull { it.id == id } ?: readSavedNight(app, id)
            }
            val outcome = NightRecovery.check(markerId, saved, active) ?: return
            when (outcome) {
                is NightRecovery.Outcome.Kept -> app.repository.save(outcome.session)
                is NightRecovery.Outcome.Lost -> app.repository.clipDir(outcome.id).deleteRecursively()
            }
            sp.edit().remove(MARK_ID).commit()
            _state.value = _state.value.copy(
                notice = NightRecovery.message(outcome, ::clock),
                noticeSessionId = (outcome as? NightRecovery.Outcome.Kept)?.session?.id,
            )
        }
    }
}
