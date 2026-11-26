package com.quietnight.app.corpus

import com.quietnight.app.audio.SnoreEvent

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming snore detector. Pure Kotlin so it can be unit-tested off-device.
 *
 * Per 50 ms frame it measures loudness relative to an adaptive room-noise floor and how much of
 * the energy sits below ~900 Hz (snoring is low-pitched; speech sibilants, alarms and rustling
 * are not). Consecutive loud, low-pitched frames form a burst; a burst of plausible length that
 * recurs at a breathing interval (roughly 2–10 s) is reported as a snore.
 */
/** The first heuristic detector, kept unchanged as a baseline for the corpus table. */
class LegacySnoreDetector(
    private val sampleRate: Int,
    /** dB above the noise floor a frame must reach to count. Lower = more sensitive. */
    private val thresholdDb: Float = 8f,
    private val onEvent: (SnoreEvent) -> Unit,
) {
    private val frameSize = sampleRate / 20 // 50 ms
    private val frameMs = 50L

    private val lowPass = Biquad.lowPass(sampleRate, 900.0)
    private val highPass = Biquad.highPass(sampleRate, 70.0)

    private var framePos = 0
    private var sumAll = 0.0
    private var sumLow = 0.0
    private var frameIndex = 0L

    private var floorDb = Float.NaN

    /** Loudness of the most recent frame relative to the noise floor, for the live meter. */
    @Volatile
    var currentDbAboveFloor = 0f
        private set

    // current burst
    private var burstStart = -1L
    private var burstLastActive = -1L
    private var burstPeak = 0f

    private class Burst(val startMs: Long, val durationMs: Long, val peak: Float, var reported: Boolean)

    private var previous: Burst? = null

    fun feed(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val x = highPass.process(samples[i].toDouble())
            val low = lowPass.process(x)
            sumAll += x * x
            sumLow += low * low
            if (++framePos == frameSize) {
                endFrame()
                framePos = 0
                sumAll = 0.0
                sumLow = 0.0
            }
        }
    }

    private fun endFrame() {
        val rms = sqrt(sumAll / frameSize)
        val db = (20 * log10(max(rms, 1.0) / 32768.0)).toFloat()
        val lowRatio = if (sumAll > 0) (sumLow / sumAll).toFloat() else 0f
        val nowMs = frameIndex * frameMs
        frameIndex++

        floorDb = when {
            floorDb.isNaN() -> db
            // Follow drops quickly, creep upward slowly so sustained snoring cannot raise the floor much.
            db < floorDb -> floorDb + (db - floorDb) * 0.2f
            else -> floorDb + minOf(db - floorDb, 0.01f)
        }
        val above = db - floorDb
        currentDbAboveFloor = above

        val active = above >= thresholdDb && lowRatio >= 0.5f
        if (active) {
            if (burstStart < 0) {
                burstStart = nowMs
                burstPeak = above
            } else {
                burstPeak = max(burstPeak, above)
            }
            burstLastActive = nowMs
        } else if (burstStart >= 0 && nowMs - burstLastActive > 200) {
            closeBurst(burstStart, burstLastActive + frameMs - burstStart, burstPeak)
            burstStart = -1
        }
    }

    private fun closeBurst(startMs: Long, durationMs: Long, peak: Float) {
        if (durationMs < 250 || durationMs > 4000) return
        val burst = Burst(startMs, durationMs, peak, false)
        val prev = previous
        if (prev != null) {
            val interval = startMs - prev.startMs
            if (interval in 1800..10000) {
                if (!prev.reported) {
                    prev.reported = true
                    onEvent(SnoreEvent(prev.startMs, prev.durationMs, prev.peak))
                }
                burst.reported = true
                onEvent(SnoreEvent(startMs, durationMs, peak))
            }
        }
        previous = burst
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
}
