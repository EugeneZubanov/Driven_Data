package com.quietnight.app.audio

import com.quietnight.app.data.Clip
import com.quietnight.app.data.Level
import com.quietnight.app.data.Session
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Turns a stream of microphone samples into a night's timeline and keeps audio clips of the
 * strongest snoring. Not thread-safe: call everything from the audio thread.
 */
class NightRecorder(
    private val id: String,
    private val startMs: Long,
    private val sampleRate: Int,
    thresholdDb: Float,
    delayMs: Long,
    private val tags: List<String>,
    /** Folder for WAV clips, or null to keep no audio. */
    private val clipDir: File?,
    private val maxClips: Int = 15,
    private val bucketSec: Int = 10,
) {
    private val bucketSamples = sampleRate * bucketSec
    private val delaySamples = delayMs * sampleRate / 1000
    private val delayOffsetMs = delaySamples * 1000 / sampleRate

    private var levels = ByteArray(4096)
    private var amps = ByteArray(4096)
    private var totalSamples = 0L
    /** Time with no audio (see [skip]) since listening began; shifts detector times onto the night's clock. */
    private var skippedMs = 0L

    var eventCount = 0
        private set

    private val detector = SnoreDetector(sampleRate, thresholdDb) { onSnore(it) }
    val currentDbAboveFloor: Float get() = if (listening) detector.currentDbAboveFloor else 0f
    val listening: Boolean get() = totalSamples >= delaySamples

    // The last three buckets of audio; a bucket's level is settled once two more have passed.
    private val ring = if (clipDir != null) Array(3) { ShortArray(bucketSamples) } else emptyArray()
    private val clips = ArrayList<Clip>()

    fun feed(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val bucket = (totalSamples / bucketSamples).toInt()
            val posInBucket = (totalSamples % bucketSamples).toInt()
            val n = minOf(count - offset, bucketSamples - posInBucket)
            if (ring.isNotEmpty()) System.arraycopy(samples, offset, ring[bucket % 3], posInBucket, n)
            if (totalSamples >= delaySamples) {
                if (offset == 0 && n == count) detector.feed(samples, count)
                else detector.feed(samples.copyOfRange(offset, offset + n), n)
            }
            totalSamples += n
            offset += n
            if (posInBucket + n == bucketSamples && bucket >= 2) considerClip(bucket - 2)
        }
    }

    /**
     * Moves the night's clock on by [ms] without audio, e.g. while a phone call holds the
     * microphone. Those buckets stay quiet and the timeline keeps matching the wall clock.
     */
    fun skip(ms: Long) {
        val n = ms * sampleRate / 1000
        if (n <= 0) return
        val before = totalSamples
        totalSamples += n
        if (totalSamples > delaySamples) skippedMs += (totalSamples - maxOf(before, delaySamples)) * 1000 / sampleRate
        // The audio ring no longer lines up with the buckets around the gap; start clean.
        for (r in ring) r.fill(0)
    }

    private fun onSnore(e: SnoreEvent) {
        eventCount++
        val level = Level.fromDb(e.dbAboveFloor).toByte()
        val amp = e.dbAboveFloor.toInt().coerceIn(0, 100).toByte()
        val first = ((delayOffsetMs + skippedMs + e.startMs) / 1000 / bucketSec).toInt()
        val last = ((delayOffsetMs + skippedMs + e.startMs + e.durationMs - 1) / 1000 / bucketSec).toInt()
        for (b in first..last) {
            ensure(b + 1)
            if (level > levels[b]) levels[b] = level
            if (amp > amps[b]) amps[b] = amp
        }
    }

    private fun ensure(size: Int) {
        if (size > levels.size) {
            val n = max(size, levels.size * 2)
            levels = levels.copyOf(n)
            amps = amps.copyOf(n)
        }
    }

    private fun considerClip(bucket: Int) {
        val dir = clipDir ?: return
        if (bucket >= levels.size || levels[bucket].toInt() == 0) return
        val candidate = Clip(bucket * bucketSec, "c$bucket.wav", levels[bucket].toInt(), amps[bucket].toInt())
        val weakest = clips.minByOrNull { rank(it) }
        if (clips.size >= maxClips) {
            if (weakest == null || rank(candidate) <= rank(weakest)) return
            File(dir, weakest.file).delete()
            clips.remove(weakest)
        }
        runCatching {
            dir.mkdirs()
            writeWav(File(dir, candidate.file), ring[bucket % 3], sampleRate)
            clips.add(candidate)
        }
    }

    private fun rank(c: Clip) = c.level * 1000 + c.amp

    /** The night so far. Safe to call repeatedly for periodic saves. */
    fun snapshot(): Session {
        val count = ((totalSamples + bucketSamples - 1) / bucketSamples).toInt()
        ensure(count)
        return Session(
            id = id,
            startMs = startMs,
            endMs = startMs + totalSamples * 1000 / sampleRate,
            bucketSec = bucketSec,
            levels = levels.copyOf(count),
            amps = amps.copyOf(count),
            tags = tags,
            clips = clips.sortedBy { it.offsetSec },
        )
    }

    companion object {
        fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
            val dataLen = pcm.size * 2
            val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            buf.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            buf.put("data".toByteArray()).putInt(dataLen)
            // Quiet bedroom recordings are faint; normalise so the clip is audible on playback.
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }.coerceAtLeast(1)
            val gain = (20000f / peak).coerceIn(1f, 30f)
            for (s in pcm) buf.putShort((s * gain).toInt().coerceIn(-32768, 32767).toShort())
            RandomAccessFile(file, "rw").use { it.setLength(0); it.write(buf.array()) }
        }
    }
}
