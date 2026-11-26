package com.quietnight.app.audio

import android.Manifest
import android.annotation.SuppressLint
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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.quietnight.app.MainActivity
import com.quietnight.app.QuietNightApp
import com.quietnight.app.R
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            if (worker == null) {
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
        if (worker != null) return START_NOT_STICKY

        if (intent == null ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            starting = false
            if (intent != null) _state.value = RecState(error = "Microphone permission is needed to record.")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        recoverInterrupted(application as QuietNightApp)

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuietNight:recording")
            .apply { setReferenceCounted(false); acquire(RecordingPolicy.WAKE_LOCK_MS) }

        stopRequested = false
        worker = thread(name = "night-recorder") { record() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Prefer the raw microphone: automatic gain and noise suppression would hide quiet snoring.
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val audio = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2))
        } catch (e: Exception) {
            return null
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); return null }
        try {
            audio.startRecording()
        } catch (e: Exception) {
            audio.release(); return null
        }
        if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) { audio.release(); return null }
        return audio
    }

    /** True when the system is feeding this app silence because a call or another app has the microphone. */
    private fun silencedBySystem(audio: AudioRecord): Boolean =
        Build.VERSION.SDK_INT >= 29 && runCatching { audio.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)

    private fun battery(): Pair<Int?, Boolean> = readBattery(this)

    private fun sleepUnlessStopped(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!stopRequested && SystemClock.elapsedRealtime() < until) Thread.sleep(200)
    }

    private fun record() {
        val app = application as QuietNightApp
        val prefs = app.settings.prefs.value
        val startMs = System.currentTimeMillis()
        val startElapsed = SystemClock.elapsedRealtime()
        val id = "s$startMs"
        val delayMs = prefs.delayMin * 60_000L
        var audio: AudioRecord? = null
        var recorder: NightRecorder? = null
        val gaps = GapTracker()
        var finishedId: String? = null
        var problem: String? = null
        var notice: String? = null
        var notified = ""
        fun offsetNow() = SystemClock.elapsedRealtime() - startElapsed
        fun snapshot() = recorder!!.snapshot().withRecordingInfo(gaps = gaps.snapshot(offsetNow()))
        fun notifyText(text: String) {
            if (text == notified) return
            notified = text
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(startMs, text))
            }
        }
        try {
            // A just-finished microphone check may still be letting go of the input; give it a moment.
            audio = openMic() ?: run { Thread.sleep(600); openMic() } ?: error("Could not open the microphone. Another app may be using it.")
            val keepClips = prefs.saveClips && filesDir.usableSpace >= RecordingPolicy.NO_CLIPS_STORAGE_BYTES
            recorder = NightRecorder(
                id = id,
                startMs = startMs,
                sampleRate = SAMPLE_RATE,
                thresholdDb = prefs.thresholdDb,
                delayMs = delayMs,
                tags = prefs.selectedTags.sorted(),
                clipDir = if (keepClips) app.repository.clipDir(id) else null,
            )
            markRecording(this, id)
            active = true
            starting = false
            _state.value = RecState(recording = true, startMs = startMs, delayMs = delayMs)

            val buf = ShortArray(SAMPLE_RATE / 10)
            var lastSave = SystemClock.elapsedRealtime()
            var lastCheck = 0L
            var silenced = false
            var accountedMs = 0L // audio fed plus time skipped, to keep the timeline on the wall clock
            var failures = 0
            var saved = false
            while (!stopRequested) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCheck >= 2_000) {
                    lastCheck = now
                    silenced = audio != null && silencedBySystem(audio)
                    val (pct, charging) = battery()
                    RecordingPolicy.safetyStop(offsetNow(), pct, charging)?.let { notice = it.message; stopRequested = true }
                }
                if (stopRequested) break

                var n = 0
                if (audio != null) {
                    n = audio.read(buf, 0, buf.size)
                    if (n < 0) {
                        // Dead or invalidated input (e.g. a call took the microphone). Reopen later.
                        runCatching { audio.stop() }
                        runCatching { audio.release() }
                        audio = null
                    }
                }
                if (audio == null) {
                    gaps.begin(offsetNow())
                    _state.value = _state.value.copy(micPaused = true)
                    notifyText("Microphone in use elsewhere. Will carry on when it is free.")
                    sleepUnlessStopped(RecordingPolicy.reopenDelayMs(++failures))
                    if (!stopRequested) audio = openMic()
                    continue
                }
                if (n == 0) { Thread.sleep(20); continue }

                if (silenced || RecordingPolicy.isDigitalSilence(buf, n)) {
                    // Feeding muted input would drag the noise floor down; count it as a gap instead.
                    gaps.begin(offsetNow())
                } else {
                    failures = 0
                    if (gaps.inGap) {
                        gaps.end(offsetNow())
                        val behind = offsetNow() - n * 1000L / SAMPLE_RATE - accountedMs
                        if (behind > 0) { recorder.skip(behind); accountedMs += behind }
                    }
                    recorder.feed(buf, n)
                    accountedMs += n * 1000L / SAMPLE_RATE
                }
                val paused = gaps.inGap
                _state.value = _state.value.copy(
                    listening = recorder.listening,
                    dbAboveFloor = if (paused) 0f else recorder.currentDbAboveFloor,
                    eventCount = recorder.eventCount,
                    micPaused = paused,
                    gapSec = gaps.snapshot(offsetNow()).sumOf { it.toSec - it.fromSec },
                )
                notifyText(
                    when {
                        paused -> "Microphone in use elsewhere. Will carry on when it is free."
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
            // Time at the end with no audio still belongs to the night.
            val tail = offsetNow() - accountedMs
            if (gaps.inGap && tail > 0) recorder.skip(tail)
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
            // Keep whatever was heard before the failure.
            val r = recorder
            if (r != null) runCatching {
                val s = snapshot()
                if (s.durationSec >= MIN_SESSION_SEC) { app.repository.save(s); finishedId = id }
            }
            if (finishedId == null && app.repository.sessions.value.any { it.id == id }) finishedId = id
        } finally {
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            clearRecordingMark(this)
            _state.value = RecState(finishedId = finishedId, error = problem, notice = notice, noticeSessionId = notice?.let { finishedId })
            active = false
            starting = false
            worker = null
            wakeLock?.let { if (it.isHeld) it.release() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        private const val ACTION_STOP = "com.quietnight.app.STOP"
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

        /** True while a night is being recorded in this process (the microphone is taken). */
        val isRecording: Boolean get() = active || starting

        fun start(context: Context) {
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
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
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
            val saved = markerId?.let { id -> app.repository.sessions.value.firstOrNull { it.id == id } }
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
