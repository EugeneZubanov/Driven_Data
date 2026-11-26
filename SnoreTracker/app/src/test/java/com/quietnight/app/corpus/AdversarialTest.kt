package com.quietnight.app.corpus

import com.quietnight.app.audio.NightRecorder
import com.quietnight.app.audio.SnoreDetector
import com.quietnight.app.audio.SnoreEvent
import com.quietnight.app.data.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Awkward inputs a phone can produce, checked on synthetic audio. */
class AdversarialTest {
    private val rate = 16000

    private fun detect(pcm: ShortArray, chunk: Int = 1600, d: (SnoreDetector) -> Unit = {}): List<SnoreEvent> {
        val events = ArrayList<SnoreEvent>()
        val det = SnoreDetector(rate, 8f) { events += it }
        var off = 0
        while (off < pcm.size) { val n = minOf(chunk, pcm.size - off); det.feed(pcm, off, n); off += n; d(det) }
        assertTrue(det.currentDbAboveFloor.isFinite())
        return events
    }

    private fun hitRate(scene: Scene, events: List<SnoreEvent>, offsetMs: Long = 0) =
        scene.snores.count { s -> events.any { e -> e.startMs - offsetMs < s[1] && e.startMs - offsetMs + e.durationMs > s[0] } }.toDouble() / scene.snores.size

    private fun snoring(seed: Int, sec: Double = 120.0) = Scene(sec, seed).roomNoise().snoring(2.0, sec, 350.0)

    private fun map(pcm: ShortArray, f: (Int, Double) -> Double) = ShortArray(pcm.size) { f(it, pcm[it].toDouble()).toInt().coerceIn(-32768, 32767).toShort() }

    /** The night's levels for a quiet-room snore (~20 dB above the room) are Loud. */
    private fun mostlyLoud(events: List<SnoreEvent>) =
        events.count { Level.fromDb(it.dbAboveFloor) == Level.LOUD } >= events.size * 0.8

    @Test fun digitalSilenceThenARoomDoesNotInflateLevels() {
        val scene = snoring(70)
        val pcm = ShortArray(10 * 60 * rate) + scene.pcm()
        val events = detect(pcm)
        assertTrue(hitRate(scene, events, 600_000) >= 0.9)
        assertTrue("levels ${events.map { it.dbAboveFloor }}", mostlyLoud(events))
    }

    @Test fun nightThatStartsLoud() {
        val scene = Scene(240.0, 71).roomNoise().fan(1500.0, 0.0, 90.0).snoring(100.0, 240.0, 350.0)
        val events = detect(scene.pcm())
        assertTrue(hitRate(scene, events) >= 0.9)
        assertTrue(events.all { it.startMs >= 95_000 })
        assertTrue(mostlyLoud(events))
    }

    @Test fun dcOffset() {
        val scene = snoring(72)
        assertTrue(hitRate(scene, detect(map(scene.pcm()) { _, x -> x + 4000 })) >= 0.9)
    }

    @Test fun micWithStrongHighPass() {
        // 2nd-order high-pass at 150 Hz, applied twice: almost nothing under 150 Hz.
        val scene = snoring(73)
        var pcm = scene.pcm()
        repeat(2) {
            val w = 2 * PI * 150 / rate; val al = sin(w) / (2 * 0.7071); val c = cos(w); val a0 = 1 + al
            val b0 = (1 + c) / 2 / a0; val b1 = -(1 + c) / a0; val a1 = -2 * c / a0; val a2 = (1 - al) / a0
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            pcm = map(pcm) { _, x -> val y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; y }
        }
        assertTrue(hitRate(scene, detect(pcm)) >= 0.85)
    }

    @Test fun noiseSuppressionGatesTheFloorToZero() {
        // Anything quieter than 4x the room noise is zeroed (per 10 ms block), as aggressive
        // suppression does. Snoring still has to be found and nothing else reported.
        val scene = snoring(74)
        val raw = scene.pcm()
        val gated = raw.copyOf()
        for (b in 0 until raw.size / 160) {
            var e = 0.0
            for (i in b * 160 until b * 160 + 160) e += raw[i].toDouble() * raw[i]
            if (sqrt(e / 160) < 140) for (i in b * 160 until b * 160 + 160) gated[i] = 0
        }
        val events = detect(gated)
        assertTrue(hitRate(scene, events) >= 0.85)
        assertEquals(0, detect(map(Scene(120.0, 75).roomNoise().pcm()) { _, _ -> 0.0 }).size)
    }

    @Test fun agcPumping() {
        // Gain drops fast on loud sound and creeps back up 6 dB/s in quiet, within 1x–16x.
        val scene = Scene(180.0, 76).roomNoise(8.0).snoring(2.0, 180.0, 80.0)
        val raw = scene.pcm()
        var gain = 16.0
        val out = ShortArray(raw.size)
        for (b in 0 until raw.size / 160) {
            var e = 0.0
            for (i in b * 160 until b * 160 + 160) e += raw[i].toDouble() * raw[i]
            val level = sqrt(e / 160) * gain
            gain = if (level > 3000) (gain * 0.8).coerceAtLeast(1.0) else (gain * 1.0069).coerceAtMost(16.0)
            for (i in b * 160 until b * 160 + 160) out[i] = (raw[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        assertTrue(hitRate(scene, detect(out)) >= 0.8)
    }

    @Test fun oddChunkSizesGiveTheSameEvents() {
        val pcm = snoring(77, 60.0).pcm()
        val ref = detect(pcm, 1600)
        assertTrue(ref.size >= 10)
        for (c in intArrayOf(1, 7, 441, rate)) assertEquals("chunk $c", ref, detect(pcm, c))
    }

    @Test fun interruptAtAnyMomentIsSafe() {
        val scene = snoring(78, 120.0)
        var fed = 0
        // Interrupt every 2.3 s for the first minute (often mid-burst), then leave it alone.
        val events = detect(scene.pcm(), 1600) { d -> fed += 1600; if (fed < 60 * rate && fed % (rate * 23 / 10) < 1600) d.interrupt() }
        assertTrue(events.all { it.startMs > 55_000 })
        assertTrue(events.size >= 10)
    }

    @Test fun fullScaleSquareWaveStaysFinite() {
        val pcm = ShortArray(20 * rate) { if ((it / 40) % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        detect(pcm)
    }

    @Test fun recorderTimelineAfterTwentyThreeHoursOfGap() {
        val scene = Scene(90.0, 79).roomNoise().snoring(2.0, 90.0, 400.0)
        val rec = NightRecorder("t", 0L, rate, 8f, delayMs = 0, tags = emptyList(), clipDir = null)
        rec.skip(23 * 3600_000L)
        val pcm = scene.pcm()
        rec.feed(pcm, pcm.size)
        val s = rec.snapshot()
        assertEquals(23 * 360 + 9, s.levels.size)
        assertTrue((0 until 23 * 360).all { s.levels[it].toInt() == 0 })
        assertTrue((23 * 360 + 2 until 23 * 360 + 8).all { s.levels[it] > 0 })
    }
}
