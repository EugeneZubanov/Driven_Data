package com.quietnight.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

data class MicCheckState(
    val running: Boolean = false,
    /** 0..1 for the live level bar. */
    val level: Float = 0f,
    /** A sound clearly louder than the room was heard. */
    val heard: Boolean = false,
    /** The check has finished at least once. */
    val done: Boolean = false,
    val error: String? = null,
)

/** A short live-level test so the user can see the phone hears them before going to sleep. */
object MicCheck {
    const val DURATION_MS = 10_000L
    /** How much louder than the room's first second a sound must be to count as heard. */
    const val HEARD_DB = 12f

    private val _state = MutableStateFlow(MicCheckState())
    val state: StateFlow<MicCheckState> = _state

    @Volatile private var worker: Thread? = null
    @Volatile private var stopRequested = false

    /** Root-mean-square level of [n] samples in dB below full scale, floored at -100. */
    fun dbfs(buf: ShortArray, n: Int): Float {
        if (n <= 0) return -100f
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        val rms = sqrt(sum / n)
        return if (rms < 1e-3) -100f else (20 * log10(rms / 32768.0)).toFloat().coerceAtLeast(-100f)
    }

    /** Level bar position for a dBFS reading: a quiet room sits low, a voice near the phone fills most of it. */
    fun barFraction(db: Float): Float = ((db + 75f) / 55f).coerceIn(0f, 1f)

    /** Whether any reading after the first second rose [HEARD_DB] above the median of the first second. */
    fun heard(levels: List<Float>, perSecond: Int = 10): Boolean {
        if (levels.size <= perSecond) return false
        val baseline = levels.take(perSecond).sorted()[perSecond / 2]
        return levels.drop(perSecond).any { it - baseline >= HEARD_DB }
    }

    @SuppressLint("MissingPermission") // the caller asks for RECORD_AUDIO first
    fun start(context: Context) {
        if (worker != null || RecordingService.isRecording) return
        val app = context.applicationContext
        stopRequested = false
        _state.value = MicCheckState(running = true)
        worker = thread(name = "mic-check") {
            val levels = ArrayList<Float>()
            var audio: AudioRecord? = null
            var error: String? = null
            try {
                val rate = RecordingService.SAMPLE_RATE
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                    MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
                audio = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                val buf = ShortArray(rate / 10)
                val until = System.currentTimeMillis() + DURATION_MS
                while (!stopRequested && System.currentTimeMillis() < until) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n < 0) error("read")
                    if (n == 0) { Thread.sleep(20); continue }
                    val db = dbfs(buf, n)
                    levels.add(db)
                    _state.value = MicCheckState(running = true, level = barFraction(db), heard = heard(levels))
                }
            } catch (e: Exception) {
                error = "Could not use the microphone. Another app may be using it."
            } finally {
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
                _state.value = MicCheckState(done = true, heard = heard(levels), error = error)
                worker = null
            }
        }
    }

    fun stop() {
        stopRequested = true
    }
}
