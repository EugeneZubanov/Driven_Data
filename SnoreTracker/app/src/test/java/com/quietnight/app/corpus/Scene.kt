package com.quietnight.app.corpus

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic synthetic bedroom audio for detector tests, with ground truth for every snore.
 *
 * What this can show: whether the detector's rules behave as intended on sounds that share the
 * broad traits of snoring and of common night-time noises (pitch range, burst length, rhythm,
 * envelope shape, pulse rate), and whether a change makes any of them worse.
 * What it cannot show: accuracy on real people. These are crude source–filter models written by
 * the same hand that tuned the detector, so a good score here is necessary, not sufficient.
 *
 * Levels are RMS in 16-bit sample units at full envelope (white room noise of rms 35 ≈ −60 dBFS).
 */
class Scene(val seconds: Double, seed: Int, val rate: Int = 16000) {
    val n = (seconds * rate).toInt()
    val buf = DoubleArray(n)
    private val rnd = Random(seed)

    /** Ground-truth snores, [startMs, endMs). */
    val snores = ArrayList<LongArray>()

    fun pcm(gain: Double = 1.0) = ShortArray(n) { (buf[it] * gain).roundToInt().coerceIn(-32768, 32767).toShort() }

    // ---------------------------------------------------------------- building blocks

    private fun r(a: Double, b: Double) = a + rnd.nextDouble() * (b - a)
    private fun gauss(): Double {
        val u = rnd.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2 * ln(u)) * sin(2 * PI * rnd.nextDouble())
    }

    private inner class Reso(hz: Double, bw: Double) {
        private val rr = exp(-PI * bw / rate)
        private val a1 = 2 * rr * kotlin.math.cos(2 * PI * hz / rate)
        private val a2 = -rr * rr
        private val g = 1 - rr
        private var y1 = 0.0
        private var y2 = 0.0
        fun p(x: Double): Double { val y = g * x + a1 * y1 + a2 * y2; y2 = y1; y1 = y; return y }
    }

    private inner class OnePole(hz: Double) {
        private val a = exp(-2 * PI * hz / rate)
        private var y = 0.0
        fun lp(x: Double): Double { y = (1 - a) * x + a * y; return y }
        fun hp(x: Double): Double = x - lp(x)
    }

    private fun rmsOf(seg: DoubleArray): Double {
        var s = 0.0
        for (v in seg) s += v * v
        return sqrt(s / seg.size.coerceAtLeast(1)).coerceAtLeast(1e-9)
    }

    /** Scales [seg] to [rms], applies attack/release ramps, and adds it at [startSec]. */
    private fun mix(startSec: Double, seg: DoubleArray, rms: Double, attackSec: Double = 0.01, releaseSec: Double = 0.02) {
        val k = rms / rmsOf(seg)
        val start = (startSec * rate).toInt()
        val att = (attackSec * rate).toInt().coerceAtLeast(1)
        val rel = (releaseSec * rate).toInt().coerceAtLeast(1)
        for (i in seg.indices) {
            val j = start + i
            if (j < 0 || j >= n) continue
            var env = 1.0
            if (i < att) env = 0.5 - 0.5 * kotlin.math.cos(PI * i / att)
            val fromEnd = seg.size - 1 - i
            if (fromEnd < rel) env = minOf(env, 0.5 - 0.5 * kotlin.math.cos(PI * fromEnd / rel))
            buf[j] += seg[i] * k * env
        }
    }

    private fun seg(sec: Double) = DoubleArray((sec * rate).toInt().coerceAtLeast(1))

    // ---------------------------------------------------------------- backgrounds

    fun roomNoise(rms: Double = 35.0) = apply {
        val lp = OnePole(3000.0)
        val s = DoubleArray(n) { lp.lp(gauss()) }
        mix(0.0, s, rms)
    }

    /** Fan: low-passed noise, a little motor hum and blade flutter; switches on/off with a 1.5 s ramp. */
    fun fan(rms: Double, fromSec: Double = 0.0, toSec: Double = seconds) = apply {
        val lp = OnePole(700.0)
        val s = seg(toSec - fromSec)
        for (i in s.indices) {
            val t = i.toDouble() / rate
            s[i] = lp.lp(gauss()) * (1 + 0.1 * sin(2 * PI * 47 * t)) + 0.02 * sin(2 * PI * 100 * t)
        }
        mix(fromSec, s, rms, 1.5, 1.5)
    }

    /** Rain: broadband hiss plus random drops. */
    fun rain(rms: Double) = apply {
        val hp = OnePole(800.0)
        val drop = Reso(3500.0, 900.0)
        val s = DoubleArray(n)
        var dropAmp = 0.0
        for (i in s.indices) {
            if (rnd.nextDouble() < 40.0 / rate) dropAmp = r(2.0, 12.0)
            dropAmp *= 0.995
            s[i] = hp.hp(gauss()) + drop.p(gauss() * dropAmp) * 4
        }
        mix(0.0, s, rms)
    }

    // ---------------------------------------------------------------- snoring

    /**
     * Inspiratory snoring: one snore per breath, [periodSec] at the start drifting to [periodEndSec],
     * ±12 % breath-to-breath jitter, ±[jitterDb] loudness jitter, ~8 % of breaths silent.
     * Source: a pulse train of [pulseHz] (±15 % per breath, slight wobble) through three resonances
     * (pharynx ~150 Hz, ~480 Hz, ~1 kHz). [noisy] uses turbulent noise with weak pulsing instead,
     * a model of snores that are more hiss than flutter.
     */
    fun snoring(
        fromSec: Double, toSec: Double, rms: Double, periodSec: Double = 4.0, periodEndSec: Double = periodSec,
        pulseHz: Double = 35.0, noisy: Boolean = false, jitterDb: Double = 3.0,
    ) = apply {
        var t = fromSec + r(0.0, 1.0)
        while (true) {
            val frac = (t - fromSec) / (toSec - fromSec)
            val p = (periodSec + (periodEndSec - periodSec) * frac) * r(0.88, 1.12)
            val dur = (p * r(0.25, 0.38)).coerceIn(0.6, 2.2)
            if (t + dur > toSec) break
            if (rnd.nextDouble() >= 0.08) {
                val amp = rms * Math.pow(10.0, r(-jitterDb, jitterDb) / 20)
                mix(t, snoreSound(dur, pulseHz * r(0.85, 1.15), noisy), amp, 0.25, 0.3)
                snores += longArrayOf((t * 1000).toLong(), ((t + dur) * 1000).toLong())
            }
            t += p
        }
    }

    private fun snoreSound(dur: Double, pulseHz: Double, noisy: Boolean): DoubleArray {
        val s = seg(dur)
        val f1 = Reso(r(120.0, 200.0), 70.0)
        val f2 = Reso(r(420.0, 560.0), 130.0)
        val f3 = Reso(r(900.0, 1150.0), 250.0)
        var phase = 0.0
        var pulseGain = 1.0
        for (i in s.indices) {
            val t = i.toDouble() / rate
            val hz = pulseHz * (1 + 0.08 * sin(2 * PI * 0.9 * t))
            phase += hz / rate
            val x = if (noisy) {
                gauss() * (0.8 + 0.2 * sin(2 * PI * phase))
            } else if (phase >= 1) {
                phase -= 1
                pulseGain = r(0.7, 1.3)
                pulseGain * 40 + gauss() * 0.3
            } else gauss() * 0.3
            if (phase >= 1) phase -= 1
            s[i] = f1.p(x) + 0.6 * f2.p(x) + 0.25 * f3.p(x)
        }
        return s
    }

    // ---------------------------------------------------------------- distractors

    /** Speech-like syllable trains: voiced pulses at [f0] through vowel formants, some fricatives. */
    fun speech(fromSec: Double, toSec: Double, rms: Double, f0: Double = 120.0, pauseMin: Double = 0.3, pauseMax: Double = 2.5) = apply {
        val vowels = arrayOf(300.0 to 2300.0, 400.0 to 2000.0, 600.0 to 1700.0, 700.0 to 1200.0, 500.0 to 900.0, 350.0 to 800.0)
        var t = fromSec
        while (t < toSec) {
            val syllables = rnd.nextInt(2, 11)
            val phraseF0 = f0 * r(0.9, 1.15)
            for (k in 0 until syllables) {
                if (rnd.nextDouble() < 0.3) {
                    val d = r(0.05, 0.12)
                    val hp = OnePole(2500.0)
                    mix(t, DoubleArray((d * rate).toInt()) { hp.hp(gauss()) }, rms * 0.4)
                    t += d
                }
                val d = r(0.1, 0.28)
                val (a, b) = vowels[rnd.nextInt(vowels.size)]
                val r1 = Reso(a, 80.0); val r2 = Reso(b, 120.0); val r3 = Reso(2600.0, 200.0)
                val tilt = OnePole(500.0)
                val s = seg(d)
                var ph = 0.0
                val decl = phraseF0 * (1 - 0.15 * k / syllables)
                for (i in s.indices) {
                    ph += decl * (1 + 0.05 * sin(2 * PI * 3 * i / rate)) / rate
                    val pulse = if (ph >= 1) { ph -= 1; 30.0 } else 0.0
                    val x = tilt.lp(pulse + gauss() * 0.05)
                    s[i] = r1.p(x) + 0.5 * r2.p(x) + 0.25 * r3.p(x)
                }
                mix(t, s, rms * r(0.6, 1.2), 0.015, 0.04)
                t += d + r(0.02, 0.12)
            }
            t += r(pauseMin, pauseMax)
        }
    }

    private fun tone(dur: Double, hz: Double, harmonics: Int, decaySec: Double): DoubleArray {
        val s = seg(dur)
        for (i in s.indices) {
            val t = i.toDouble() / rate
            var v = 0.0
            for (h in 1..harmonics) v += sin(2 * PI * hz * h * t) / h
            s[i] = v * exp(-t / decaySec)
        }
        return s
    }

    /** Music: 120 bpm kick, bass every two beats, melody on beats and half-beats. */
    fun music(fromSec: Double, toSec: Double, rms: Double) = apply {
        val track = seg(toSec - fromSec)
        val sub = Scene(toSec - fromSec, rnd.nextInt(), rate)
        val scale = doubleArrayOf(262.0, 294.0, 330.0, 392.0, 440.0, 523.0, 587.0)
        var beat = 0
        var t = 0.0
        while (t < toSec - fromSec - 1) {
            val kick = sub.seg(0.15)
            for (i in kick.indices) { val tt = i.toDouble() / rate; kick[i] = sin(2 * PI * (50 + 70 * exp(-tt / 0.03)) * tt) * exp(-tt / 0.05) }
            sub.mix(t, kick, rms * 1.2)
            if (beat % 2 == 0) sub.mix(t, tone(0.95, scale[rnd.nextInt(3)] / 2.0, 4, 0.6), rms * 0.8)
            sub.mix(t, tone(r(0.25, 0.45), scale[rnd.nextInt(scale.size)], 4, 0.3), rms * 0.6)
            if (rnd.nextBoolean()) sub.mix(t + 0.25, tone(0.22, scale[rnd.nextInt(scale.size)], 4, 0.2), rms * 0.5)
            t += 0.5
            beat++
        }
        System.arraycopy(sub.buf, 0, track, 0, track.size)
        mix(fromSec, track, rms)
    }

    /** Vehicles passing every 8–30 s: 4–12 s swells of low rumble with engine harmonics. */
    fun traffic(fromSec: Double, toSec: Double, rms: Double) = apply {
        var t = fromSec + r(0.0, 5.0)
        while (t < toSec - 4) {
            val d = r(4.0, 12.0)
            val lp1 = OnePole(150.0); val lp2 = OnePole(150.0)
            val eng = r(30.0, 60.0)
            val s = seg(d)
            for (i in s.indices) {
                val tt = i.toDouble() / rate
                val env = exp(-((tt - d / 2) * (tt - d / 2)) / (2 * (d / 5) * (d / 5)))
                s[i] = env * (lp2.lp(lp1.lp(gauss())) * 8 + 0.3 * sin(2 * PI * eng * tt) + 0.15 * sin(4 * PI * eng * tt))
            }
            mix(t, s, rms * r(0.5, 1.0))
            t += d + r(4.0, 20.0)
        }
    }

    /** Coughing fits every 20–40 s: 3–6 coughs 0.35–0.7 s apart, each a 0.25–0.4 s noisy burst. */
    fun coughing(fromSec: Double, toSec: Double, rms: Double) = apply {
        var t = fromSec + r(1.0, 5.0)
        while (t < toSec - 4) {
            val coughs = rnd.nextInt(3, 7)
            var c = t
            repeat(coughs) {
                val d = r(0.25, 0.4)
                val r1 = Reso(r(350.0, 500.0), 200.0); val r2 = Reso(1500.0, 600.0); val r3 = Reso(2500.0, 800.0)
                val s = seg(d)
                for (i in s.indices) { val x = gauss() * exp(-i.toDouble() / rate / 0.09); s[i] = r1.p(x) + r2.p(x) + 0.7 * r3.p(x) }
                mix(c, s, rms * r(0.7, 1.2), 0.005, 0.05)
                c += r(0.35, 0.7)
            }
            t += r(20.0, 40.0)
        }
    }

    /** Bed rustling: irregular 0.3–3 s crackly high-band noise. */
    fun rustling(fromSec: Double, toSec: Double, rms: Double) = apply {
        var t = fromSec + r(0.0, 3.0)
        while (t < toSec - 3) {
            val d = r(0.3, 3.0)
            val hp = OnePole(900.0)
            val s = seg(d)
            var g = 1.0
            for (i in s.indices) { if (i % 160 == 0) g = r(0.1, 1.5); s[i] = hp.hp(gauss()) * g + 0.3 * gauss() * g }
            mix(t, s, rms * r(0.5, 1.2), 0.05, 0.1)
            t += d + r(1.0, 10.0)
        }
    }

    /** Barking: series of 3–8 barks every [spacingSec] (±15 %), series every 10–25 s. */
    fun barking(fromSec: Double, toSec: Double, rms: Double, spacingSec: Double = 1.1) = apply {
        var t = fromSec + r(0.0, 3.0)
        while (t < toSec - 5) {
            val barks = rnd.nextInt(3, 9)
            var b = t
            repeat(barks) {
                val d = r(0.15, 0.35)
                val f0 = r(450.0, 650.0)
                val form = Reso(r(1000.0, 1500.0), 400.0)
                val s = seg(d)
                for (i in s.indices) {
                    val tt = i.toDouble() / rate
                    var v = 0.0
                    for (h in 1..6) v += sin(2 * PI * f0 * h * tt * (1 - 0.3 * tt)) / h
                    s[i] = (v + 0.3 * gauss()) * exp(-tt / 0.12) + form.p(v) * 3
                }
                mix(b, s, rms * r(0.7, 1.2), 0.005, 0.05)
                b += spacingSec * r(0.85, 1.15)
            }
            t = b + r(10.0, 25.0)
        }
    }

    /** Ticking clock: a click every second, tick/tock alternating. */
    fun clock(rms: Double) = apply {
        var t = 0.3
        var tick = true
        while (t < seconds - 0.1) {
            val res = Reso(if (tick) 3000.0 else 2000.0, 300.0)
            val s = seg(0.02)
            for (i in s.indices) s[i] = res.p(gauss() * exp(-i.toDouble() / rate / 0.002))
            mix(t, s, rms, 0.0005, 0.005)
            t += 1.0
            tick = !tick
        }
    }

    /** Phone notification chimes every 15–45 s; sometimes repeated after 2–3 s. */
    fun notifications(fromSec: Double, toSec: Double, rms: Double) = apply {
        var t = fromSec + r(2.0, 10.0)
        while (t < toSec - 4) {
            val repeats = if (rnd.nextBoolean()) 1 else 3
            var c = t
            repeat(repeats) {
                mix(c, tone(0.35, 1320.0, 3, 0.15), rms, 0.003, 0.05)
                mix(c + 0.15, tone(0.4, 1760.0, 3, 0.2), rms, 0.003, 0.05)
                c += r(2.0, 3.0)
            }
            t = c + r(15.0, 45.0)
        }
    }
}
