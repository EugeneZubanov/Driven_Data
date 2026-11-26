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
    /** Samples per second this input actually delivers. */
    val sampleRate: Int
    /** Blocking read like [AudioRecord.read]: samples read, 0, or a negative error code. */
    fun read(buf: ShortArray, size: Int): Int
    /** True when the system is feeding silence because a call or another app has the microphone. */
    fun silenced(): Boolean
    fun close()
}

/** The service's links to the outside world; tests swap these for fakes. */
object RecordingEnv {
    /** Opens the microphone at the first of the given sample rates that works. */
    @Volatile var openMic: (Context, List<Int>) -> MicInput? = ::openAudioRecord
    @Volatile var elapsedMs: () -> Long = SystemClock::elapsedRealtime
    @Volatile var wallMs: () -> Long = System::currentTimeMillis
    @Volatile var sleep: (Long) -> Unit = { Thread.sleep(it) }
    @Volatile var battery: (Context) -> Pair<Int?, Boolean> = RecordingService::readBattery
    @Volatile var encoder: EncoderFactory = EncoderFactory { f, _ -> AacSegmentEncoder(f, AacSupport.find() ?: error("no AAC encoder")) }
    @Volatile var freeBytes: (Context) -> Long = ::freeSpace
    /** Wraps the full-audio writer; tests running simulated time faster than real time use it directly. */
    @Volatile var audioSink: (AudioSink) -> AudioSink = { AsyncAudioSink(it) }
    /** Whether this phone has an encoder for full-night audio. */
    @Volatile var audioSupported: () -> Boolean = { AacSupport.find() != null }

    fun reset() {
        encoder = EncoderFactory { f, _ -> AacSegmentEncoder(f, AacSupport.find() ?: error("no AAC encoder")) }
        freeBytes = ::freeSpace
        audioSink = { AsyncAudioSink(it) }
        audioSupported = { AacSupport.find() != null }
        openMic = ::openAudioRecord
        elapsedMs = SystemClock::elapsedRealtime
        wallMs = System::currentTimeMillis
        sleep = { Thread.sleep(it) }
        battery = RecordingService::readBattery
    }
}

/**
 * Bytes the app can still write to its own storage. StorageManager also counts cache the system
 * would clear for us, which File.usableSpace does not.
 */
fun freeSpace(context: Context): Long = runCatching {
    val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
    sm.getAllocatableBytes(sm.getUuidForPath(context.filesDir))
}.getOrElse { context.filesDir.usableSpace }

/** Capture rates to try, best first. Only 44.1 kHz is guaranteed on every Android device. */
val CAPTURE_RATES = listOf(16000, 48000, 44100)

/** The first of [rates] for which [open] gives something, with that result. */
fun <T : Any> firstWorkingRate(rates: List<Int>, open: (Int) -> T?): T? {
    for (r in rates) open(r)?.let { return it }
    return null
}

/**
 * A started AudioRecord at [rate] from the raw microphone if the phone has one, or null if this
 * rate cannot be used. Automatic gain and noise suppression would hide quiet snoring.
 */
@SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
fun openRawAudioRecord(context: Context, rate: Int): AudioRecord? {
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return null // ERROR or ERROR_BAD_VALUE: rate not supported
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    return firstWorkingSource(captureSources(unprocessed)) { source -> openAt(source, rate, minBuf) }
}

/**
 * Audio sources to try, best first: the raw microphone when the phone says it has one, then the
 * speech-tuned source (little processing on most phones), then the plain microphone. Some phones
 * advertise UNPROCESSED yet refuse it at a given rate.
 */
fun captureSources(unprocessedAdvertised: Boolean): List<Int> = buildList {
    if (unprocessedAdvertised) add(MediaRecorder.AudioSource.UNPROCESSED)
    add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
    add(MediaRecorder.AudioSource.MIC)
}

fun <T : Any> firstWorkingSource(sources: List<Int>, open: (Int) -> T?): T? {
    for (src in sources) open(src)?.let { return it }
    return null
}

@SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
private fun openAt(source: Int, rate: Int, minBuf: Int): AudioRecord? {
    val audio = try {
        AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate * 2 * RecordingPolicy.MIC_BUFFER_SECONDS)) // 16-bit mono, so a slow moment cannot overrun
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

private fun openAudioRecord(context: Context, rates: List<Int>): MicInput? = firstWorkingRate(rates) { rate ->
    openRawAudioRecord(context, rate)?.let { audio ->
        object : MicInput {
            override val sampleRate = rate
            override fun read(buf: ShortArray, size: Int) = audio.read(buf, 0, size)
            override fun silenced() =
                Build.VERSION.SDK_INT >= 29 && runCatching { audio.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
            override fun close() {
                runCatching { audio.stop() }
                runCatching { audio.release() }
            }
        }
    }
}
