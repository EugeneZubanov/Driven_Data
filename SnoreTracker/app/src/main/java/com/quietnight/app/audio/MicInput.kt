package com.quietnight.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock

/** An open microphone. Lets tests drive [RecordingService] with simulated audio. */
interface MicInput {
    /** Blocking read like [AudioRecord.read]: samples read, 0, or a negative error code. */
    fun read(buf: ShortArray, size: Int): Int
    /** True when the system is feeding silence because a call or another app has the microphone. */
    fun silenced(): Boolean
    fun close()
}

/** The service's links to the outside world; tests swap these for fakes. */
object RecordingEnv {
    @Volatile var openMic: (Context) -> MicInput? = ::openAudioRecord
    @Volatile var elapsedMs: () -> Long = SystemClock::elapsedRealtime
    @Volatile var wallMs: () -> Long = System::currentTimeMillis
    @Volatile var sleep: (Long) -> Unit = { Thread.sleep(it) }
    @Volatile var battery: (Context) -> Pair<Int?, Boolean> = RecordingService::readBattery

    fun reset() {
        openMic = ::openAudioRecord
        elapsedMs = SystemClock::elapsedRealtime
        wallMs = System::currentTimeMillis
        sleep = { Thread.sleep(it) }
        battery = RecordingService::readBattery
    }
}

@SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before recording
private fun openAudioRecord(context: Context): MicInput? {
    val rate = RecordingService.SAMPLE_RATE
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    // Prefer the raw microphone: automatic gain and noise suppression would hide quiet snoring.
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
        MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    val audio = try {
        AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate * 2))
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
    return object : MicInput {
        override fun read(buf: ShortArray, size: Int) = audio.read(buf, 0, size)
        override fun silenced() =
            Build.VERSION.SDK_INT >= 29 && runCatching { audio.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
        override fun close() {
            runCatching { audio.stop() }
            runCatching { audio.release() }
        }
    }
}
