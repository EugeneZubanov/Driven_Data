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

    // The last RING_BUCKETS buckets of audio. The detector confirms the first snores of a run up
    // to ~25 s late (it waits for three breaths), so a bucket's level is only treated as settled,
    // and its clip decided, once RING_BUCKETS - 1 more buckets have passed (40 s at 10 s buckets).
    private val ring = if (clipDir != null) Array(RING_BUCKETS) { ShortArray(bucketSamples) } else emptyArray()
    private val clips = ArrayList<Clip>()
    /** First bucket whose clip has not been decided yet. */
    private var nextClipBucket = 0

    fun feed(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val bucket = (totalSamples / bucketSamples).toInt()
            val posInBucket = (totalSamples % bucketSamples).toInt()
            val n = minOf(count - offset, bucketSamples - posInBucket)
            if (ring.isNotEmpty()) System.arraycopy(samples, offset, ring[bucket % RING_BUCKETS], posInBucket, n)
            if (totalSamples >= delaySamples) detector.feed(samples, offset, n)
            totalSamples += n
            offset += n
            if (posInBucket + n == bucketSamples) decideClipsUpTo(bucket - (RING_BUCKETS - 1))
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
        // Breaths on either side of the gap must not be paired into a rhythm, and every event
        // the detector reports from now on then starts after the gap, so [skippedMs] at report
        // time is also the offset in force when the snore happened.
        detector.interrupt()
        val beforeBucket = (before / bucketSamples).toInt()
        val beforePos = (before % bucketSamples).toInt()
        val newBucket = ((before + n) / bucketSamples).toInt()
        // Audio before the gap is final now: decide its clips while it is still in the ring.
        if (newBucket > beforeBucket) decideClipsUpTo(if (beforePos > 0) beforeBucket else beforeBucket - 1)
        totalSamples += n
        if (totalSamples > delaySamples) skippedMs += (totalSamples - maxOf(before, delaySamples)) * 1000 / sampleRate
        if (newBucket > beforeBucket) nextClipBucket = maxOf(nextClipBucket, newBucket)
        // Silence the part of the ring the gap covers, so no stale audio ends up in a clip.
        if (newBucket - beforeBucket >= RING_BUCKETS) {
            for (r in ring) r.fill(0)
        } else {
            for (b in beforeBucket..newBucket) {
                if (ring.isNotEmpty()) ring[b % RING_BUCKETS].fill(0, if (b == beforeBucket) beforePos else 0, bucketSamples)
            }
        }
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

    /**
     * Decides clips for the buckets still in the ring, e.g. when the night ends; without it the
     * last ~40 s cannot yield a clip. Call before the final [snapshot].
     */
    fun finish() {
        decideClipsUpTo(((totalSamples - 1) / bucketSamples).toInt())
    }

    private fun decideClipsUpTo(last: Int) {
        if (ring.isEmpty()) return
        val lastWritten = ((totalSamples - 1) / bucketSamples).toInt()
        while (nextClipBucket <= last) {
            // Only buckets whose audio is still in the ring.
            if (nextClipBucket > lastWritten - RING_BUCKETS) considerClip(nextClipBucket)
            nextClipBucket++
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
            writeWav(File(dir, candidate.file), ring[bucket % RING_BUCKETS], sampleRate)
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
        private const val RING_BUCKETS = 5

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
