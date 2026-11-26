package com.quietnight.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One confirmed snore: a low-pitched burst that repeats at a breathing rhythm.
 *
 * [dbAboveFloor] is the peak loudness above the night's quiet baseline: the lowest noise floor
 * that held for a whole minute so far. A fan switched on later in the night therefore does not
 * make snoring over it look milder. [dbAboveLocalFloor] is relative to the floor at that moment.
 * [confidence] (0–1) is a rough indication of how snore-like the burst was; it is not calibrated.
 */
data class SnoreEvent(
    val startMs: Long,
    val durationMs: Long,
    val dbAboveFloor: Float,
    val dbAboveLocalFloor: Float = dbAboveFloor,
    val confidence: Float = 1f,
)

/**
 * Streaming snore detector. Pure Kotlin so it can be unit-tested off-device.
 *
 * Per 50 ms frame it measures:
 *  - loudness relative to a room-noise floor (minimum of the quietest frame over the last ~10 s,
 *    so a fan switching on is absorbed within seconds instead of minutes),
 *  - the share of energy between 70 and 900 Hz (snoring is low-pitched; sibilants, alarms,
 *    rustling and clicks are not),
 *  - "roughness": how strongly the low-band envelope flutters at 15–70 Hz. Snoring is often a
 *    train of soft-tissue pulses at a few tens per second; voiced speech pulses at 100+ Hz and
 *    music notes are smooth, so they score low.
 *
 * Consecutive loud, low-pitched frames form a burst. A burst is a snore *candidate* if it lasts
 * 0.35–4 s and is not chopped up like syllables (several sharp dips per second) unless it is rough.
 * Candidates are reported only once three of them recur at a steady breathing interval
 * (1.8–10 s apart, intervals within ~±40 % of each other). After that, each candidate that lands
 * on the established rhythm (allowing one skipped breath) is reported straight away.
 *
 * All state is preallocated; nothing is allocated per sample or per frame.
 *
 * Tuned on synthetic audio only (see the corpus in the unit tests): it has not been validated
 * against recordings of real people snoring.
 */
class SnoreDetector(
    private val sampleRate: Int,
    /** dB above the noise floor a frame must reach to count. Lower = more sensitive. */
    private val thresholdDb: Float = 8f,
    private val onEvent: (SnoreEvent) -> Unit,
) {
    private val frameSize = sampleRate / 20 // 50 ms
    private val frameMs = 50L

    private val highPass = Biquad.highPass(sampleRate, 70.0)
    // Two cascaded sections give a steeper (24 dB/oct) edge, so speech formants at 1–2 kHz leak less.
    private val lowPass1 = Biquad.lowPass(sampleRate, 900.0)
    private val lowPass2 = Biquad.lowPass(sampleRate, 900.0)

    // Envelope of the low band, averaged down to ~800 Hz, then band-passed at 15–70 Hz.
    private val envDecim = max(1, sampleRate / 800)
    private val modHighPass = Biquad.highPass(sampleRate / envDecim, 15.0)
    // Two sections so the 100+ Hz ripple of a deep voice does not leak in.
    private val modLowPass1 = Biquad.lowPass(sampleRate / envDecim, 70.0)
    private val modLowPass2 = Biquad.lowPass(sampleRate / envDecim, 70.0)
    private var envPos = 0
    private var envAcc = 0.0
    private var sumEnv2 = 0.0
    private var sumMod2 = 0.0

    private var framePos = 0
    private var sumAll = 0.0
    private var sumLow = 0.0
    private var clipped = 0
    private var frameIndex = 0L

    // Noise floor: minimum frame level per 1 s block, over the last FLOOR_BLOCKS blocks.
    private val blockMins = FloatArray(FLOOR_BLOCKS) { Float.NaN }
    private var blockMin = Float.POSITIVE_INFINITY
    private var blockFrames = 0
    private var blockIndex = 0
    private var floorDb = Float.NaN
    // Quiet baseline for levels: the lowest per-minute maximum of the floor seen so far.
    private var baselineDb = Float.POSITIVE_INFINITY
    private var minuteMaxFloor = Float.NEGATIVE_INFINITY
    private var minuteFrames = 0

    /** Loudness of the most recent frame relative to the noise floor, for the live meter. */
    @Volatile
    var currentDbAboveFloor = 0f
        private set

    // current burst
    private var burstStart = -1L
    private var burstLastActive = -1L
    private var burstPeak = 0f
    private var burstPeakDb = 0f
    private var burstActiveFrames = 0
    private var burstLowSum = 0f
    private var burstRoughSum = 0f
    private var burstDips = 0
    private var dipRef = 0f
    private var inDip = false

    // Recent snore candidates, a ring buffer of parallel arrays (no per-burst allocation).
    private val candStart = LongArray(RING)
    private val candDur = LongArray(RING)
    private val candPeak = FloatArray(RING)
    private val candPeakDb = FloatArray(RING)
    private val candConf = FloatArray(RING)
    private val candReported = BooleanArray(RING)
    private var candCount = 0 // total ever added; slot = index % RING

    // Established breathing rhythm, once three candidates have lined up.
    private var periodMs = 0L
    private var lastSnoreStart = -1L

    fun feed(samples: ShortArray, count: Int) = feed(samples, 0, count)

    /** Processes [count] samples starting at [offset]; lets callers pass a slice without copying. */
    fun feed(samples: ShortArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            val s = samples[i].toInt()
            if (s >= CLIP || s <= -CLIP) clipped++
            val x = highPass.process(s.toDouble())
            val low = lowPass2.process(lowPass1.process(x))
            sumAll += x * x
            sumLow += low * low
            envAcc += abs(low)
            if (++envPos == envDecim) {
                val e = envAcc / envDecim
                val m = modLowPass2.process(modLowPass1.process(modHighPass.process(e)))
                sumEnv2 += e * e
                sumMod2 += m * m
                envPos = 0
                envAcc = 0.0
            }
            if (++framePos == frameSize) {
                endFrame()
                framePos = 0
                sumAll = 0.0
                sumLow = 0.0
                sumEnv2 = 0.0
                sumMod2 = 0.0
                clipped = 0
            }
        }
    }

    private fun endFrame() {
        val rms = sqrt(sumAll / frameSize)
        val db = (20 * log10(max(rms, 1.0) / 32768.0)).toFloat()
        val lowRatio = if (sumAll > 0) (sumLow / sumAll).toFloat() else 0f
        val rough = if (sumEnv2 > 0) (sumMod2 / sumEnv2).toFloat() else 0f
        val clipping = clipped * 50 > frameSize // more than 2 % of samples at full scale
        val nowMs = frameIndex * frameMs
        frameIndex++

        updateFloor(db)
        // Never measure against less than ~1.4 LSB of noise: on a very quiet or digitally silent
        // input, a few quantisation steps would otherwise look like loud events.
        val above = db - max(floorDb, MIN_FLOOR_DB)
        currentDbAboveFloor = above

        // Clipping spreads energy into high harmonics, so accept a lower low-band share then.
        val lowNeeded = if (clipping) 0.3f else LOW_RATIO
        val active = above >= thresholdDb && lowRatio >= lowNeeded
        if (active) {
            if (burstStart < 0) {
                burstStart = nowMs
                burstPeak = above
                burstPeakDb = db
                burstActiveFrames = 0
                burstLowSum = 0f
                burstRoughSum = 0f
                burstDips = 0
                dipRef = db
                inDip = false
            } else {
                burstPeak = max(burstPeak, above)
                burstPeakDb = max(burstPeakDb, db)
            }
            burstActiveFrames++
            burstLowSum += if (clipping) max(lowRatio, BURST_LOW_RATIO) else lowRatio
            burstRoughSum += rough
            burstLastActive = nowMs
        } else if (burstStart >= 0 && nowMs - burstLastActive > 200) {
            closeBurst(burstStart, burstLastActive + frameMs - burstStart, burstPeak)
            burstStart = -1
        }
        if (burstStart >= 0) trackDips(db)
        expireRhythm(nowMs)
    }

    /** Counts sharp dips inside a burst (≥ 6 dB down and back up), as between spoken syllables. */
    private fun trackDips(db: Float) {
        if (!inDip) {
            if (db > dipRef) dipRef = db
            else if (db < dipRef - DIP_DB) { inDip = true; dipRef = db }
        } else {
            if (db < dipRef) dipRef = db
            else if (db > dipRef + DIP_DB) { inDip = false; burstDips++; dipRef = db }
        }
    }

    private fun updateFloor(db: Float) {
        if (db < blockMin) blockMin = db
        if (++blockFrames == FLOOR_BLOCK_FRAMES) {
            blockMins[blockIndex] = blockMin
            blockIndex = (blockIndex + 1) % FLOOR_BLOCKS
            blockMin = Float.POSITIVE_INFINITY
            blockFrames = 0
        }
        var target = blockMin
        for (m in blockMins) if (!m.isNaN() && m < target) target = m
        floorDb = when {
            floorDb.isNaN() -> db
            // Drop quickly when the room gets quieter. Rise straight to the window minimum: it only
            // rises once every frame of the last ~10 s was louder (a fan or heater came on), and a
            // slow climb would let the new noise hover around the threshold and look like bursts.
            // Snoring cannot lift it because breaths leave quiet gaps inside the window.
            target < floorDb -> floorDb + (target - floorDb) * 0.5f
            else -> target
        }
        minuteMaxFloor = max(minuteMaxFloor, floorDb)
        if (++minuteFrames == 1200) {
            // A minute at the clamp is digital silence (mic muted, blocked or gated by noise
            // suppression), not a quiet room: it would make every later snore look heavy.
            if (minuteMaxFloor > MIN_FLOOR_DB + 3f) baselineDb = minOf(baselineDb, minuteMaxFloor)
            minuteMaxFloor = Float.NEGATIVE_INFINITY
            minuteFrames = 0
        }
    }


    private fun closeBurst(startMs: Long, durationMs: Long, peak: Float) {
        if (durationMs < MIN_BURST_MS || durationMs > MAX_BURST_MS) return
        val frames = max(1, burstActiveFrames)
        // Frames need a 0.5 low-band share, the burst as a whole 0.6: broadband noise hovering
        // around 0.5 (a fan before the floor adapts) flickers into short bursts that fail this.
        if (burstLowSum / frames < BURST_LOW_RATIO) return
        val rough = burstRoughSum / frames
        val dipsPerSec = burstDips * 1000f / durationMs
        // Syllable-like chopping without the flutter of a snore: probably talking. On the synthetic
        // corpus speech scores 0.02–0.05, pulsed snores ~0.3, hiss-like snores ~0.1, and snores
        // overlapped by talking 0.13–0.26. Creaky voice may well be rough too; unverified.
        if (burstDips >= 1 && dipsPerSec >= 1.5f && rough < ROUGH_SNORE) return
        // Chopped up at a syllable rate (~3+ per second) is talking even if the voice is creaky.
        if (burstDips >= 3 && dipsPerSec >= 2.5f) return
        // A rough 0–1 score from how clearly the burst shows the snore traits; not calibrated.
        var conf = 0.4f
        if (rough >= 0.15f) conf += 0.2f
        if (burstDips == 0) conf += 0.2f
        if (peak >= thresholdDb + 6f) conf += 0.2f
        addCandidate(startMs, durationMs, peak, burstPeakDb, conf)
    }

    private fun addCandidate(startMs: Long, durationMs: Long, peak: Float, peakDb: Float, conf: Float) {
        val slot = candCount % RING
        candStart[slot] = startMs
        candDur[slot] = durationMs
        candPeak[slot] = peak
        candPeakDb[slot] = peakDb
        candConf[slot] = conf
        candReported[slot] = false
        candCount++

        if (mechanical(slot)) return
        // Already snoring: report a candidate that falls on the rhythm (or one breath later).
        if (periodMs > 0 && lastSnoreStart >= 0) {
            val gap = startMs - lastSnoreStart
            val p = periodMs.toFloat()
            val onBeat = gap >= 0.6f * p && gap <= 1.45f * p
            val skipped = gap > 1.45f * p && gap <= 2.4f * p
            if ((onBeat || skipped) && gap in MIN_INTERVAL_MS..MAX_INTERVAL_MS + 4000) {
                if (onBeat) periodMs = ((periodMs * 3 + gap) / 4).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                report(slot)
                lastSnoreStart = startMs
                return
            }
        }
        // Otherwise look for two earlier candidates that, with this one, keep a steady rhythm.
        val n = min(candCount, RING)
        for (bi in 1 until n) {
            val b = (candCount - 1 - bi) % RING
            val i2 = startMs - candStart[b]
            if (i2 < MIN_INTERVAL_MS) continue
            if (i2 > MAX_INTERVAL_MS) break
            for (ai in bi + 1 until n) {
                val a = (candCount - 1 - ai) % RING
                val i1 = candStart[b] - candStart[a]
                if (i1 < MIN_INTERVAL_MS) continue
                if (i1 > MAX_INTERVAL_MS) break
                val ratio = max(i1, i2).toFloat() / min(i1, i2)
                if (ratio > MAX_INTERVAL_RATIO) continue
                val dMax = max(durationMs, max(candDur[a], candDur[b]))
                val dMin = min(durationMs, min(candDur[a], candDur[b]))
                if (dMax > dMin * 3) continue
                report(a); report(b); report(slot)
                periodMs = (i1 + i2) / 2
                lastSnoreStart = startMs
                return
            }
        }
    }

    /**
     * Breathing is never clockwork. If this burst and two earlier ones repeat at intervals equal to
     * within one frame, with durations within 60 ms and levels within 1 dB, it is a
     * machine: a ringer, an alarm, an appliance cycling. Checks every pair in the ring, so a
     * ringer whose rings split into several bursts is caught too.
     */
    private fun mechanical(slot: Int): Boolean {
        val n = min(candCount, RING)
        val t = candStart[slot]
        for (xi in 1 until n) {
            val x = (candCount - 1 - xi) % RING
            val i2 = t - candStart[x]
            if (i2 < 500 || !similar(slot, x)) continue
            for (yi in xi + 1 until n) {
                val y = (candCount - 1 - yi) % RING
                val i1 = candStart[x] - candStart[y]
                if (abs(i1 - i2) <= 60 && similar(slot, y)) return true
            }
        }
        return false
    }

    private fun similar(a: Int, b: Int) =
        abs(candDur[a] - candDur[b]) <= 60 && abs(candPeak[a] - candPeak[b]) <= 1f

    private fun report(slot: Int) {
        if (candReported[slot]) return
        candReported[slot] = true
        val base = max(min(baselineDb, floorDb), MIN_FLOOR_DB)
        val aboveBase = max(candPeakDb[slot] - base, candPeak[slot])
        onEvent(SnoreEvent(candStart[slot], candDur[slot], aboveBase, candPeak[slot], candConf[slot]))
    }

    /**
     * Forgets any burst in progress and the breathing rhythm, e.g. after a stretch without audio:
     * intervals measured across the gap would be meaningless. Unconfirmed bursts are dropped, so
     * every event reported afterwards starts after this call. The noise floor is kept.
     */
    fun interrupt() {
        burstStart = -1
        candCount = 0
        periodMs = 0
        lastSnoreStart = -1
    }

    private fun expireRhythm(nowMs: Long) {
        if (periodMs > 0 && nowMs - lastSnoreStart > periodMs * 5 / 2 + 2000) {
            periodMs = 0
            lastSnoreStart = -1
        }
    }

    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        companion object {
            private const val Q = 0.7071

            fun lowPass(sampleRate: Int, hz: Double): Biquad {
                val w = 2 * PI * hz / sampleRate
                val alpha = sin(w) / (2 * Q)
                val c = cos(w)
                val a0 = 1 + alpha
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            fun highPass(sampleRate: Int, hz: Double): Biquad {
                val w = 2 * PI * hz / sampleRate
                val alpha = sin(w) / (2 * Q)
                val c = cos(w)
                val a0 = 1 + alpha
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
    }

    private companion object {
        const val CLIP = 32000
        const val MIN_FLOOR_DB = -87f
        const val LOW_RATIO = 0.5f
        const val BURST_LOW_RATIO = 0.6f
        const val DIP_DB = 6f
        const val ROUGH_SNORE = 0.08f
        const val MIN_BURST_MS = 350L
        const val MAX_BURST_MS = 4000L
        const val MIN_INTERVAL_MS = 1800L
        const val MAX_INTERVAL_MS = 10000L
        const val MAX_INTERVAL_RATIO = 1.5f
        const val RING = 8
        const val FLOOR_BLOCK_FRAMES = 20 // 1 s
        const val FLOOR_BLOCKS = 10
    }
}
