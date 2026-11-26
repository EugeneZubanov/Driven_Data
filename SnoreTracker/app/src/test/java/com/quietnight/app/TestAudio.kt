package com.quietnight.app

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic bedroom audio for recording tests: room noise plus snore-like bursts in chosen windows. */
object TestAudio {
    const val RATE = 16000

    /** One sample at time [t] seconds; bursts every 4 s while [snoring] says so. */
    fun sample(t: Double, rnd: Random, snoring: (Double) -> Boolean, noiseAmp: Double = 60.0, burstAmp: Double = 1500.0): Short {
        var v = (rnd.nextDouble() * 2 - 1) * noiseAmp
        if (snoring(t)) {
            // Human-like jitter per breath: the detector rejects clockwork-regular sounds as machines.
            val k = floor(t / 4.0).toLong()
            val start = 0.3 * jitter(k, 1)
            val len = 1.2 * (1 + 0.12 * jitter(k, 2))
            val amp = burstAmp * (1 + 0.2 * jitter(k, 3))
            val phase = t - k * 4.0 - 0.3 - start
            if (phase >= 0 && phase < len) {
                val env = sin(PI * phase / len)
                val rough = 0.6 + 0.4 * sin(2 * PI * 28 * t)
                v += amp * env * rough * (sin(2 * PI * 110 * t) + 0.5 * sin(2 * PI * 220 * t) + 0.25 * sin(2 * PI * 330 * t))
            }
        }
        return v.toInt().coerceIn(-32768, 32767).toShort()
    }

    /** Deterministic value in -1..1 for breath [k]. */
    private fun jitter(k: Long, salt: Int): Double = Random(k * 31 + salt).nextDouble() * 2 - 1

    /** [n] samples starting at [fromSec] seconds. */
    fun chunk(fromSec: Double, n: Int, rnd: Random, snoring: (Double) -> Boolean) =
        ShortArray(n) { sample(fromSec + it.toDouble() / RATE, rnd, snoring) }
}
