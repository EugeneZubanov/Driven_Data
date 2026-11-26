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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
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
)

/** Foreground service that listens to the microphone through the night. */
class RecordingService : Service() {

    @Volatile private var stopRequested = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY

        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = RecState(error = "Microphone permission is needed to record.")
            stopSelf()
            return START_NOT_STICKY
        }

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuietNight:recording")
            .apply { acquire(14 * 60 * 60 * 1000L) }

        stopRequested = false
        worker = thread(name = "night-recorder") { record() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val app = application as QuietNightApp
        val prefs = app.settings.prefs.value
        val startMs = System.currentTimeMillis()
        val id = "s$startMs"
        val delayMs = prefs.delayMin * 60_000L
        var audio: AudioRecord? = null
        var finishedId: String? = null
        var problem: String? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // Prefer the raw microphone: automatic gain and noise suppression would hide quiet snoring.
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            audio = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2))
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Could not open the microphone." }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone is in use by another app." }

            val recorder = NightRecorder(
                id = id,
                startMs = startMs,
                sampleRate = SAMPLE_RATE,
                thresholdDb = prefs.thresholdDb,
                delayMs = delayMs,
                tags = prefs.selectedTags.sorted(),
                clipDir = if (prefs.saveClips) app.repository.clipDir(id) else null,
            )
            _state.value = RecState(recording = true, startMs = startMs, delayMs = delayMs)

            val buf = ShortArray(SAMPLE_RATE / 10)
            var lastSave = SystemClock.elapsedRealtime()
            var saved = false
            while (!stopRequested) {
                val n = audio.read(buf, 0, buf.size)
                if (n < 0) error("Microphone stopped unexpectedly.")
                if (n == 0) continue
                recorder.feed(buf, n)
                _state.value = _state.value.copy(
                    listening = recorder.listening,
                    dbAboveFloor = recorder.currentDbAboveFloor,
                    eventCount = recorder.eventCount,
                )
                // Save progress regularly so a night is not lost if the phone kills the app or dies.
                val now = SystemClock.elapsedRealtime()
                if (now - lastSave > 5 * 60_000) {
                    app.repository.save(recorder.snapshot())
                    saved = true
                    lastSave = now
                }
            }
            val session = recorder.snapshot()
            if (session.durationSec >= MIN_SESSION_SEC) {
                app.repository.save(session)
                finishedId = id
            } else {
                if (saved) app.repository.delete(id) else app.repository.clipDir(id).deleteRecursively()
                problem = "Recording was shorter than $MIN_SESSION_SEC seconds, so it was not saved."
            }
        } catch (e: Exception) {
            problem = e.message ?: "Recording failed."
            if (app.repository.sessions.value.any { it.id == id }) finishedId = id
        } finally {
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            _state.value = RecState(finishedId = finishedId, error = problem)
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopRequested = true
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
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
            .setContentText("Listening for snoring. Tap to open.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MIN_SESSION_SEC = 30
        private const val ACTION_STOP = "com.quietnight.app.STOP"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow(RecState())
        val state: StateFlow<RecState> = _state

        fun start(context: Context) {
            _state.value = RecState()
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        fun clearFinished() {
            _state.value = _state.value.copy(finishedId = null, error = null)
        }
    }
}
