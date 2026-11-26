package com.quietnight.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compresses PCM into one audio file. [finish] must leave a complete, playable file. */
interface SegmentEncoder {
    fun write(pcm: ShortArray, n: Int)
    fun finish()
}

fun interface EncoderFactory {
    fun open(file: File, sampleRate: Int): SegmentEncoder
}

/** One finished piece of the night's audio: [file] in the night's clip folder, from [startMs] into the night. */
data class AudioSegment(val file: String, val startMs: Long, val durationMs: Long)

/** The list of finished segments, kept as its own small JSON file next to them. */
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
    private val sampleRate: Int,
    private val factory: EncoderFactory,
    private val segmentMs: Long = 10 * 60_000L,
) {
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
                current = factory.open(f, sampleRate)
            }
            current!!.write(pcm, n)
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
        enc.finish()
        if (currentSamples < sampleRate) {
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
        /** About 16 kbit/s AAC: plenty to hear snoring and talking, ~7 MB an hour. */
        const val BITRATE = 16_000

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
    }
}

/**
 * AAC-LC mono in an MPEG-4 file via MediaCodec and MediaMuxer. Not exercised by the unit tests
 * (Robolectric has no codecs); verified only by reasoning until run on a phone.
 */
class AacSegmentEncoder(file: File, private val sampleRate: Int, bitrate: Int = NightAudioWriter.BITRATE) : SegmentEncoder {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var samplesQueued = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    override fun write(pcm: ShortArray, n: Int) {
        var off = 0
        var spins = 0
        while (off < n) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(endOfStream = false)
                check(++spins < 200) { "encoder stalled" }
                continue
            }
            val buf = codec.getInputBuffer(idx)!!.order(ByteOrder.LITTLE_ENDIAN)
            buf.clear()
            val count = minOf(n - off, buf.remaining() / 2)
            buf.asShortBuffer().put(pcm, off, count)
            codec.queueInputBuffer(idx, 0, count * 2, samplesQueued * 1_000_000 / sampleRate, 0)
            samplesQueued += count
            off += count
            drain(endOfStream = false)
        }
    }

    override fun finish() {
        try {
            var idx = -1
            for (i in 0 until 100) { idx = codec.dequeueInputBuffer(10_000); if (idx >= 0) break; drain(false) }
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, samplesQueued * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(endOfStream = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || ++waits > 300) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                out >= 0 -> {
                    val data: ByteBuffer = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        data.position(info.offset).limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
