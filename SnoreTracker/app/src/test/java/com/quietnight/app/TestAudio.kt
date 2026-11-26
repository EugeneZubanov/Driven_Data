package com.quietnight.app

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic bedroom audio for recording tests: room noise plus snore-like bursts in chosen windows. */
object TestAudio {
    const val RATE = 16000

    /** One sample at time [t] seconds; bursts every 4 s while [snoring] says so. */
    fun sample(t: Double, rnd: Random, snoring: (Double) -> Boolean, noiseAmp: Double = 60.0, burstAmp: Double = 1500.0): Short {
        var v = (rnd.nextDouble() * 2 - 1) * noiseAmp
        if (snoring(t)) {
            val phase = t % 4.0
            if (phase < 1.2) {
                val env = sin(PI * phase / 1.2)
                val rough = 0.6 + 0.4 * sin(2 * PI * 28 * t)
                v += burstAmp * env * rough * (sin(2 * PI * 110 * t) + 0.5 * sin(2 * PI * 220 * t) + 0.25 * sin(2 * PI * 330 * t))
            }
        }
        return v.toInt().coerceIn(-32768, 32767).toShort()
    }

    /** [n] samples starting at [fromSec] seconds. */
    fun chunk(fromSec: Double, n: Int, rnd: Random, snoring: (Double) -> Boolean) =
        ShortArray(n) { sample(fromSec + it.toDouble() / RATE, rnd, snoring) }
}
