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
    // Per bucket: OtherSound kind bits heard, and the highest snore confidence (0–100).
    private var others = ByteArray(4096)
    private var confs = ByteArray(4096)
    private var totalSamples = 0L
    /** Time with no audio (see [skip]) since listening began; shifts detector times onto the night's clock. */
    private var skippedMs = 0L

    var eventCount = 0
        private set

    private val detector = SnoreDetector(sampleRate, thresholdDb) { onSnore(it) }.also { d -> d.onOther = { onOther(it) } }
    val currentDbAboveFloor: Float get() = if (listening) detector.currentDbAboveFloor else 0f
    val listening: Boolean get() = totalSamples >= delaySamples

    // The last RING_BUCKETS buckets of audio. The detector confirms the first snores of a run up
    // to ~25 s late (it waits for three breaths), so a bucket's level is only treated as settled,
    // and its clip decided, once RING_BUCKETS - 1 more buckets have passed (40 s at 10 s buckets).
    // Kept at [CLIP_RATE] whatever the capture rate: 1.6 MB of memory, 320 KB per clip.
    private val clipRate = minOf(sampleRate, CLIP_RATE)
    private val clipBucketSamples = clipRate * bucketSec
    private val resampler = if (clipDir != null && sampleRate > CLIP_RATE) ClipResampler(sampleRate, CLIP_RATE) else null
    /** Samples written to the ring timeline so far, at [clipRate]. */
    private var clipCount = 0L
    private val ring = if (clipDir != null) Array(RING_BUCKETS) { ShortArray(clipBucketSamples) } else emptyArray()
    private val clips = ArrayList<Clip>()
    /** First bucket whose clip has not been decided yet. */
    private var nextClipBucket = 0

    fun feed(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val bucket = (totalSamples / bucketSamples).toInt()
            val posInBucket = (totalSamples % bucketSamples).toInt()
            val n = minOf(count - offset, bucketSamples - posInBucket)
            if (ring.isNotEmpty()) toRing(samples, offset, n)
            if (totalSamples >= delaySamples) detector.feed(samples, offset, n)
            totalSamples += n
            offset += n
            if (posInBucket + n == bucketSamples) decideClipsUpTo(bucket - (RING_BUCKETS - 1))
        }
    }

    private fun toRing(samples: ShortArray, offset: Int, n: Int) {
        val r = resampler
        if (r == null) {
            var off = offset
            var left = n
            while (left > 0) {
                val pos = (clipCount % clipBucketSamples).toInt()
                val k = minOf(left, clipBucketSamples - pos)
                System.arraycopy(samples, off, ring[((clipCount / clipBucketSamples) % RING_BUCKETS).toInt()], pos, k)
                clipCount += k; off += k; left -= k
            }
        } else {
            for (i in offset until offset + n) {
                val v = r.push(samples[i])
                if (v != ClipResampler.NONE) putClipSample(v.toShort())
            }
        }
    }

    private fun putClipSample(v: Short) {
        ring[((clipCount / clipBucketSamples) % RING_BUCKETS).toInt()][(clipCount % clipBucketSamples).toInt()] = v
        clipCount++
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
        val beforePos = (clipCount % clipBucketSamples).toInt()
        val newBucket = ((before + n) / bucketSamples).toInt()
        // Audio before the gap is final now: decide its clips while it is still in the ring.
        if (newBucket > beforeBucket) decideClipsUpTo(if (beforePos > 0) beforeBucket else beforeBucket - 1)
        totalSamples += n
        if (totalSamples > delaySamples) skippedMs += (totalSamples - maxOf(before, delaySamples)) * 1000 / sampleRate
        if (newBucket > beforeBucket) nextClipBucket = maxOf(nextClipBucket, newBucket)
        clipCount = totalSamples * clipRate / sampleRate
        resampler?.restart()
        // Silence the part of the ring the gap covers, so no stale audio ends up in a clip.
        if (newBucket - beforeBucket >= RING_BUCKETS) {
            for (r in ring) r.fill(0)
        } else {
            for (b in beforeBucket..newBucket) {
                if (ring.isNotEmpty()) ring[b % RING_BUCKETS].fill(0, if (b == beforeBucket) beforePos else 0, clipBucketSamples)
            }
        }
    }

    private fun onSnore(e: SnoreEvent) {
        eventCount++
        val level = Level.fromDb(e.dbAboveFloor).toByte()
        val amp = e.dbAboveFloor.toInt().coerceIn(0, 100).toByte()
        val conf = (e.confidence * 100).toInt().coerceIn(0, 100).toByte()
        val first = ((delayOffsetMs + skippedMs + e.startMs) / 1000 / bucketSec).toInt()
        val last = ((delayOffsetMs + skippedMs + e.startMs + e.durationMs - 1) / 1000 / bucketSec).toInt()
        for (b in first..last) {
            ensure(b + 1)
            if (level > levels[b]) levels[b] = level
            if (amp > amps[b]) amps[b] = amp
            if (conf > confs[b]) confs[b] = conf
        }
    }

    private fun onOther(o: OtherSound) {
        val first = ((delayOffsetMs + skippedMs + o.startMs) / 1000 / bucketSec).toInt()
        val last = ((delayOffsetMs + skippedMs + o.startMs + o.durationMs - 1) / 1000 / bucketSec).toInt()
        for (b in first..last) {
            ensure(b + 1)
            others[b] = (others[b].toInt() or o.kind).toByte()
        }
    }

    private fun ensure(size: Int) {
        if (size > levels.size) {
            val n = max(size, levels.size * 2)
            levels = levels.copyOf(n)
            amps = amps.copyOf(n)
            others = others.copyOf(n)
            confs = confs.copyOf(n)
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
            writeWav(File(dir, candidate.file), ring[bucket % RING_BUCKETS], clipRate)
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
            others = others.copyOf(count),
            conf = confs.copyOf(count),
        )
    }

    companion object {
        private const val RING_BUCKETS = 5
        /** Clips and the clip ring are kept at this rate (or the capture rate, if lower). */
        const val CLIP_RATE = 16000

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

/**
 * Streams audio down to a lower rate for clips: an 8th-order Butterworth low-pass at ~0.44 of the
 * output rate (7 kHz for 16 kHz), then linear interpolation. Plenty for listening back to snoring;
 * not a hi-fi resampler. No allocation per sample.
 */
internal class ClipResampler(private val inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val cutoff = outRate * 0.44
    // Butterworth Q values for four cascaded sections (8th order).
    private val qs = doubleArrayOf(0.5098, 0.6013, 0.9000, 2.5629)
    private var f0 = SnoreDetector.Biquad.lowPass(inRate, cutoff, qs[0])
    private var f1 = SnoreDetector.Biquad.lowPass(inRate, cutoff, qs[1])
    private var f2 = SnoreDetector.Biquad.lowPass(inRate, cutoff, qs[2])
    private var f3 = SnoreDetector.Biquad.lowPass(inRate, cutoff, qs[3])
    private var inCount = 0L
    private var outCount = 0L
    private var prev = 0.0

    /**
     * Takes one input sample; returns the output sample due between the previous input and this
     * one, or [NONE]. Downsampling only, so there is never more than one.
     */
    fun push(x: Short): Int {
        val y = f3.process(f2.process(f1.process(f0.process(x.toDouble()))))
        var out = NONE
        val t = outCount * step
        if (t <= inCount) {
            val frac = t - (inCount - 1)
            val v = if (frac <= 0) y else prev + (y - prev) * frac
            out = v.toInt().coerceIn(-32768, 32767)
            outCount++
        }
        prev = y
        inCount++
        return out
    }

    /** After a gap: forget the filter state; the next sample starts a fresh timeline. */
    fun restart() {
        f0 = SnoreDetector.Biquad.lowPass(inRate, cutoff, qs[0])
        f1 = SnoreDetector.Biquad.lowPass(inRate, cutoff, qs[1])
        f2 = SnoreDetector.Biquad.lowPass(inRate, cutoff, qs[2])
        f3 = SnoreDetector.Biquad.lowPass(inRate, cutoff, qs[3])
        inCount = 0
        outCount = 0
        prev = 0.0
    }

    companion object {
        const val NONE = Int.MIN_VALUE
    }
}
