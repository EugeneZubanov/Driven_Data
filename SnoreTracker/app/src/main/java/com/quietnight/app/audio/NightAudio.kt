package com.quietnight.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compresses 16 kHz mono PCM into one audio file. */
interface SegmentEncoder {
    fun write(pcm: ShortArray, n: Int)
    /** Ends the file. Returns true if it holds playable audio; false means the file should be dropped. */
    fun finish(): Boolean
}

fun interface EncoderFactory {
    fun open(file: File, sampleRate: Int): SegmentEncoder
}

/**
 * Streaming low-pass and resample from [inRate] to [outRate] (lower or equal). A windowed-sinc FIR
 * with its cut-off just under the output's Nyquist frequency removes what would otherwise alias,
 * then output samples are read off the filtered signal by linear interpolation, which is accurate
 * once the signal is band-limited well below the input rate. Keeps state across calls.
 */
class Resampler(private val inRate: Int, private val outRate: Int, taps: Int = 63) {
    private val step = inRate.toDouble() / outRate
    private val passthrough = inRate == outRate
    private val kernel: FloatArray
    private val history: FloatArray
    private var histPos = 0
    /** Filtered input samples not yet used for output, newest last; at most two are kept between calls. */
    private var prev = 0f
    private var cur = 0f
    private var haveCur = false
    /** Position of the next output sample, in input samples after [prev]. */
    private var frac = 0.0

    init {
        require(outRate in 1..inRate)
        val cutoff = 0.45 * outRate / inRate // just under half the output rate, as a fraction of the input rate
        val m = taps - 1
        kernel = FloatArray(taps) { i ->
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2 * cutoff else kotlin.math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x)
            val window = 0.42 - 0.5 * kotlin.math.cos(2 * Math.PI * i / m) + 0.08 * kotlin.math.cos(4 * Math.PI * i / m) // Blackman
            (sinc * window).toFloat()
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        history = FloatArray(taps)
    }

    /** Resamples [n] samples of [input]; returns the output samples ready so far. */
    fun process(input: ShortArray, n: Int): ShortArray {
        if (passthrough) return input.copyOf(n)
        val out = ShortArray((n / step).toInt() + 2)
        var count = 0
        for (i in 0 until n) {
            history[histPos] = input[i].toFloat()
            histPos = (histPos + 1) % history.size
            var acc = 0f
            var h = histPos
            for (k in kernel.indices) { acc += kernel[k] * history[h]; h++; if (h == history.size) h = 0 }
            if (!haveCur) { cur = acc; haveCur = true; continue }
            prev = cur
            cur = acc
            // Emit every output sample that falls between prev (0) and cur (1).
            while (frac < 1.0) {
                val v = prev + (cur - prev) * frac.toFloat()
                out[count++] = v.toInt().coerceIn(-32768, 32767).toShort()
                frac += step
            }
            frac -= 1.0
        }
        return out.copyOf(count)
    }
}

/** One finished piece of the night's audio: [file] in the night's clip folder, from [startMs] into the night. */
data class AudioSegment(val file: String, val startMs: Long, val durationMs: Long)

/**
 * The list of finished full-night audio segments, kept as `night-audio.json` in the night's clip
 * folder. What a reader can rely on:
 *
 * - Only finished, playable files are listed. Each is added (atomically, temp file + rename) only
 *   after its encoder has closed it, so a file in the folder that is NOT listed (e.g. the one in
 *   progress when the app was killed) must be ignored. A segment dropped at the end (unplayable,
 *   or under a second) is simply absent; there is no placeholder.
 * - Segments are in time order and never overlap: `startMs[i+1] >= startMs[i] + durationMs[i]`.
 *   A larger gap means no audio was kept for that stretch (microphone in use, lost audio, or full
 *   audio turned off), never silence inside a file.
 * - `startMs` is milliseconds from the night's start (Session.startMs) on the same clock as the
 *   timeline buckets, so segment time t is bucket `(startMs + t) / 1000 / bucketSec`.
 * - `durationMs` is the captured audio length; the decoded file can differ by about 0.1 s (AAC
 *   frames and encoder delay), so trust the file for its own end.
 * - Files are AAC-LC mono 16 kHz in MPEG-4 (.m4a). No index file means no full audio was kept.
 */
object SegmentIndex {
    const val FILE = "night-audio.json"

    fun toJson(segments: List<AudioSegment>): String = JSONObject().put("segments", JSONArray().also { a ->
        segments.forEach { a.put(JSONObject().put("f", it.file).put("t", it.startMs).put("d", it.durationMs)) }
    }).toString()

    fun fromJson(text: String): List<AudioSegment> {
        val a = JSONObject(text).optJSONArray("segments") ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it).let { o -> AudioSegment(o.getString("f"), o.getLong("t"), o.getLong("d")) } }
    }

    /** Segments of a night, or empty if it kept no full audio. For the playback side. */
    fun read(clipDir: File): List<AudioSegment> =
        runCatching { fromJson(File(clipDir, FILE).readText()) }.getOrDefault(emptyList())

    fun write(clipDir: File, segments: List<AudioSegment>) {
        val tmp = File(clipDir, "$FILE.tmp")
        tmp.writeText(toJson(segments))
        tmp.renameTo(File(clipDir, FILE))
    }
}

/**
 * Writes the night's audio as rolling segments of about [segmentMs]. Each segment is finished
 * (made playable) and added to the index before the next starts, so a crash loses at most the
 * segment in progress. A gap in the audio (microphone taken) ends the current segment, so every
 * segment's start offset stays true to the night's clock. If the encoder fails, full audio stops
 * for the night and the recording itself carries on.
 */
class NightAudioWriter(
    private val dir: File,
    /** Capture rate of the samples passed to [write]; they are resampled to [ENCODE_RATE]. */
    private val sampleRate: Int,
    private val factory: EncoderFactory,
    private val segmentMs: Long = 10 * 60_000L,
) {
    private var resampler = Resampler(sampleRate, ENCODE_RATE)
    private val done = ArrayList<AudioSegment>()
    private var current: SegmentEncoder? = null
    private var currentFile: File? = null
    private var currentStartMs = 0L
    private var currentSamples = 0L

    var failed = false
        private set
    val segments: List<AudioSegment> get() = done.toList()

    /** [pcm] holds [n] samples starting [offsetMs] into the night. */
    fun write(pcm: ShortArray, n: Int, offsetMs: Long) {
        if (failed || n <= 0) return
        try {
            if (current == null) {
                dir.mkdirs()
                val f = File(dir, "audio-%03d.m4a".format(done.size + 1))
                currentFile = f
                currentStartMs = offsetMs
                currentSamples = 0
                resampler = Resampler(sampleRate, ENCODE_RATE) // each file starts clean
                current = factory.open(f, ENCODE_RATE)
            }
            val down = resampler.process(pcm, n)
            if (down.isNotEmpty()) current!!.write(down, down.size)
            currentSamples += n
            if (currentSamples * 1000 / sampleRate >= segmentMs) endSegment()
        } catch (e: Exception) {
            fail()
        }
    }

    /** Audio stopped arriving; close the segment so the next one starts at the right offset. */
    fun gap() {
        if (current != null) runCatching { endSegment() }.onFailure { fail() }
    }

    /** End of the night, or full audio turned off (e.g. storage low). */
    fun close() = gap()

    private fun endSegment() {
        val enc = current ?: return
        current = null
        val playable = enc.finish()
        if (!playable || currentSamples < sampleRate) {
            // Under a second holds no useful sound and may not make a valid file.
            currentFile?.delete()
            return
        }
        done += AudioSegment(currentFile!!.name, currentStartMs, currentSamples * 1000 / sampleRate)
        SegmentIndex.write(dir, done)
    }

    private fun fail() {
        failed = true
        runCatching { current?.finish() }
        current = null
        currentFile?.takeIf { f -> done.none { it.file == f.name } }?.delete()
    }

    companion object {
        /** The encoder always gets 16 kHz mono, a rate every AAC-LC encoder supports. */
        const val ENCODE_RATE = 16_000

        /**
         * 24 kbit/s AAC-LC at 16 kHz mono: inside the range Android's built-in AAC encoders accept
         * for that rate (the actual range is checked with [AacSupport] and the value clamped to it),
         * clear enough for snoring and talking, ~11 MB an hour.
         */
        const val BITRATE = 24_000

        /** Bytes for [hours] of audio at [bitrate], for the estimate next to the toggle. */
        fun estimateBytes(hours: Double, bitrate: Int = BITRATE): Long = (hours * 3600 * bitrate / 8).toLong()

        /** Below this much free space, full audio is turned off for the rest of the night. */
        const val MIN_FREE_BYTES = 300L * 1024 * 1024
    }
}

/** User setting: keep the whole night's audio (off by default). */
object NightAudioPref {
    private const val PREFS = "night_audio"
    private val _on = MutableStateFlow<Boolean?>(null)

    fun state(context: Context): StateFlow<Boolean?> {
        if (_on.value == null) _on.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)
        return _on
    }

    fun set(context: Context, on: Boolean) {
        _on.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("on", on) }
    }
}

/** Finds an AAC-LC encoder for 16 kHz mono on this phone, and the bitrate to give it. */
object AacSupport {
    data class Choice(val codecName: String, val bitrate: Int)

    /** Clamps the wanted bitrate into the encoder's supported range. */
    fun pickBitrate(wanted: Int, lower: Int, upper: Int): Int = wanted.coerceIn(lower, upper)

    private var cached: Choice? = null
    private var looked = false

    /** Null when no encoder can do it; the full-audio switch is then not offered. Cached after the first look. */
    @Synchronized
    fun find(): Choice? {
        if (looked) return cached
        looked = true
        cached = runCatching {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, NightAudioWriter.ENCODE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            val name = list.findEncoderForFormat(format) ?: return@runCatching null
            val info = list.codecInfos.first { it.name == name }
            val audio = info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).audioCapabilities
                ?: return@runCatching null
            if (!audio.isSampleRateSupported(NightAudioWriter.ENCODE_RATE)) return@runCatching null
            val range = audio.bitrateRange
            Choice(name, pickBitrate(NightAudioWriter.BITRATE, range.lower, range.upper))
        }.getOrNull()
        return cached
    }
}

/**
 * AAC-LC mono at 16 kHz in an MPEG-4 file via MediaCodec (synchronous mode) and MediaMuxer. Robolectric
 * has no codecs, so this class is not exercised by the unit tests: it is checked against the
 * documented MediaCodec state machine (comments below) and must be tried on a phone.
 */
class AacSegmentEncoder(file: File, private val choice: AacSupport.Choice) : SegmentEncoder {
    private val rate = NightAudioWriter.ENCODE_RATE
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var samplesQueued = 0L
    private var samplesWritten = 0

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, choice.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        // Created by the name MediaCodecList reported for exactly this format (Configured state after configure()).
        codec = MediaCodec.createByCodecName(choice.codecName)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start() // Executing (Flushed -> Running once the first input is queued)
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    override fun write(pcm: ShortArray, n: Int) {
        var off = 0
        var waits = 0
        while (off < n) {
            // Input side: take a free input buffer; if none is free yet, empty the output side
            // first (the codec may be waiting for us to take output), then try again.
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(endOfStream = false)
                // Runs on the audio thread: never block it for long, or the microphone overruns.
                // After ~200 ms without a free buffer, give up (full audio turns off for the night).
                check(++waits < 20) { "encoder stalled" }
                continue
            }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            buf.order(ByteOrder.nativeOrder()) // MediaCodec raw audio is 16-bit PCM in native byte order
            val count = minOf(n - off, buf.remaining() / 2)
            buf.asShortBuffer().put(pcm, off, count)
            // Presentation time of the first sample in this buffer, from the running sample count,
            // so timestamps rise strictly and match the audio's own clock.
            codec.queueInputBuffer(idx, 0, count * 2, samplesQueued * 1_000_000 / rate, 0)
            samplesQueued += count
            off += count
            drain(endOfStream = false)
        }
    }

    override fun finish(): Boolean {
        var ok = false
        try {
            // End of stream is signalled by an empty input buffer with BUFFER_FLAG_END_OF_STREAM,
            // queued after all data; the codec then flushes its last frames and marks the last
            // output buffer with the same flag.
            var idx = -1
            for (i in 0 until 20) {
                idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) break
                drain(endOfStream = false)
            }
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, samplesQueued * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(endOfStream = true)
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            // MediaMuxer.stop() writes the moov box that makes the file playable; it throws if no
            // sample was written, so only stop when something was. Errors above still propagate.
            ok = muxing && samplesWritten > 0 && runCatching { muxer.stop() }.isSuccess
            runCatching { muxer.release() } // release() itself stops a started muxer and throws if it had no samples
        }
        return ok
    }

    /**
     * Takes every output buffer the codec has ready. With [endOfStream] it waits (10 ms at a time,
     * at most ~0.5 s) until the buffer flagged END_OF_STREAM arrives; otherwise it returns as soon as
     * nothing is ready.
     */
    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                // Nothing ready: done for now, or keep waiting for the end-of-stream buffer.
                // (Bounded to ~0.5 s: if the last frames never come, the file is still closed with
                // what was written, which keeps it playable.)
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || ++waits > 50) return
                // Sent once, before the first encoded frame: the output format now carries the
                // codec-specific data (csd-0), which the muxer needs to add the track. The muxer can
                // only be started after its track is added, and nothing may be written before that.
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxing) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                }
                out >= 0 -> {
                    val data: ByteBuffer = codec.getOutputBuffer(out)!!
                    // A CODEC_CONFIG buffer holds the same csd already in the track format; writing
                    // it as a sample would corrupt the file, so it is skipped.
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        data.position(info.offset).limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                        samplesWritten++
                    }
                    // Every output buffer must be handed back, written or not.
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                // INFO_OUTPUT_BUFFERS_CHANGED (deprecated) needs nothing when using getOutputBuffer(index).
                else -> {}
            }
        }
    }
}
