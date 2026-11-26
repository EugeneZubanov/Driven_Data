package com.quietnight.app.corpus

import com.quietnight.app.audio.ClipResampler
import com.quietnight.app.audio.NightRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Clips are written at 16 kHz on phones that capture at 44.1 or 48 kHz. */
class ClipRateTest {
    /** Amplitude of [hz] in [x] (Goertzel), relative to a full-amplitude sine. */
    private fun amplitude(x: ShortArray, rate: Int, hz: Double, from: Int = 2000): Double {
        val w = 2 * PI * hz / rate
        val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in from until x.size) { val s = x[i] + c * s1 - s2; s2 = s1; s1 = s }
        val n = x.size - from
        return sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) * 2 / n
    }

    private fun resample(x: ShortArray, inRate: Int): ShortArray {
        val r = ClipResampler(inRate, 16000)
        val out = ArrayList<Short>()
        for (s in x) { val v = r.push(s); if (v != ClipResampler.NONE) out += v.toShort() }
        return out.toShortArray()
    }

    @Test fun resamplerKeepsSnoreBandAndRejectsAliases() {
        for (rate in intArrayOf(44100, 48000)) {
            val sec = 2
            val low = ShortArray(rate * sec) { (8000 * sin(2 * PI * 300.0 * it / rate)).toInt().toShort() }
            val out = resample(low, rate)
            assertTrue("$rate: ${out.size}", kotlin.math.abs(out.size - 16000 * sec) <= 1)
            assertEquals(8000.0, amplitude(out, 16000, 300.0), 400.0)
            // 12 kHz would fold to 4 kHz at 16 kHz; it must be at least 40 dB down.
            val high = ShortArray(rate * sec) { (8000 * sin(2 * PI * 12000.0 * it / rate)).toInt().toShort() }
            val alias = amplitude(resample(high, rate), 16000, 4000.0)
            assertTrue("$rate alias $alias", alias < 80)
        }
    }

    private fun header(f: File): ByteBuffer {
        val b = ByteArray(44)
        RandomAccessFile(f, "r").use { it.readFully(b) }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun samples(f: File): ShortArray {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((bytes.size - 44) / 2) { bb.short }
    }

    @Test fun clipsAreSixteenKilohertzAtHighCaptureRates() {
        for (rate in intArrayOf(16000, 44100, 48000)) {
            val scene = Scene(90.0, 90, rate).roomNoise().snoring(2.0, 90.0, 1200.0)
            val dir = File("build/tmp/cliprate-$rate").apply { deleteRecursively() }
            val rec = NightRecorder("n", 0L, rate, 8f, delayMs = 0, tags = emptyList(), clipDir = dir)
            val pcm = scene.pcm()
            val buf = ShortArray(rate / 10)
            var off = 0
            while (off < pcm.size) { val n = minOf(buf.size, pcm.size - off); System.arraycopy(pcm, off, buf, 0, n); rec.feed(buf, n); off += n }
            rec.finish()
            val clips = rec.snapshot().clips
            assertTrue("$rate: ${clips.size} clips", clips.size >= 5)
            for (c in clips) {
                val f = File(dir, c.file)
                assertEquals("$rate size", 44L + 10 * 16000 * 2, f.length())
                assertEquals("$rate header rate", 16000, header(f).getInt(24))
                // Snoring is in the clip: most of its energy is below 1 kHz and it is not silent.
                val x = samples(f)
                var all = 0.0; var low = 0.0; var y = 0.0
                val a = kotlin.math.exp(-2 * PI * 1000 / 16000)
                for (s in x) { y = (1 - a) * s + a * y; all += s.toDouble() * s; low += y * y }
                assertTrue("$rate silent", all / x.size > 1000.0 * 1000)
                assertTrue("$rate low share ${low / all}", low / all > 0.5)
            }
        }
    }
}
