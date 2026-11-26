package com.quietnight.app.corpus

import com.quietnight.app.audio.NightRecorder
import com.quietnight.app.audio.SnoreDetector
import com.quietnight.app.audio.SnoreEvent
import com.quietnight.app.data.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Targeted checks of the detector's rules, on the synthetic generators in [Scene]. */
class SnoreDetectorRobustnessTest {
    private val rate = 16000

    private fun detect(pcm: ShortArray, chunk: Int = 1600, threshold: Float = 8f): List<SnoreEvent> {
        val events = ArrayList<SnoreEvent>()
        val d = SnoreDetector(rate, threshold) { events += it }
        var off = 0
        while (off < pcm.size) { val n = minOf(chunk, pcm.size - off); d.feed(pcm, off, n); off += n }
        return events
    }

    private fun hits(scene: Scene, events: List<SnoreEvent>, fromMs: Long = 0) =
        scene.snores.filter { it[0] >= fromMs }.count { s -> events.any { e -> e.startMs < s[1] && e.startMs + e.durationMs > s[0] } }

    @Test fun twoBurstsAreNotEnoughButThreeAre() {
        val two = Scene(20.0, 1).roomNoise().snoring(2.0, 10.5, 400.0, periodJitter = 0.0)
        assertEquals(2, two.snores.size)
        assertEquals(0, detect(two.pcm()).size)
        val three = Scene(20.0, 1).roomNoise().snoring(2.0, 14.5, 400.0, periodJitter = 0.0)
        assertEquals(3, three.snores.size)
        assertEquals(3, detect(three.pcm()).size)
    }

    @Test fun fanSwitchingOnBlindsForSecondsNotMinutes() {
        val scene = Scene(180.0, 2).roomNoise().fan(400.0, 60.0).snoring(5.0, 180.0, 1600.0)
        val events = detect(scene.pcm())
        val after = scene.snores.count { it[0] >= 80_000 }
        assertTrue("hits after fan settled: ${hits(scene, events, 80_000)}/$after", hits(scene, events, 80_000) >= after * 0.9)
    }

    @Test fun fanSwitchingOnAndOffIsNotSnoring() {
        assertEquals(0, detect(Scene(200.0, 3).roomNoise().fan(800.0, 30.0, 100.0).fan(400.0, 130.0, 170.0).pcm()).size)
    }

    @Test fun speechIsNotSnoring() {
        assertEquals(0, detect(Scene(180.0, 4).roomNoise().speech(0.0, 180.0, 800.0, 110.0).pcm()).size)
        assertEquals(0, detect(Scene(180.0, 5).roomNoise().speech(0.0, 180.0, 500.0, 220.0, 1.5, 4.0).pcm()).size)
    }

    @Test fun clippedSnoringIsStillFoundAndHeavy() {
        val scene = Scene(90.0, 6).roomNoise().snoring(2.0, 90.0, 60000.0)
        val events = detect(scene.pcm())
        assertTrue(hits(scene, events) >= scene.snores.size - 1)
        assertTrue(events.all { Level.fromDb(it.dbAboveFloor) == Level.HEAVY })
    }

    @Test fun quantisationNoiseOnASilentInputIsNotLoud() {
        // Digital silence with ±1 LSB flicker and a few 3-LSB blips: nothing to report.
        val r = kotlin.random.Random(7)
        val pcm = ShortArray(60 * rate) { i -> if ((i / 8000) % 4 == 1) (r.nextInt(7) - 3).toShort() else (r.nextInt(3) - 1).toShort() }
        assertEquals(0, detect(pcm).size)
    }

    @Test fun veryQuietPhoneStillHearsSnoring() {
        val scene = Scene(180.0, 8).roomNoise().snoring(2.0, 180.0, 350.0)
        // Room noise below 1 LSB, snores ~9 LSB rms: most should still count.
        val events = detect(scene.pcm(1.0 / 40))
        assertTrue("${hits(scene, events)}/${scene.snores.size}", hits(scene, events) >= scene.snores.size * 0.8)
    }

    @Test fun chunkSizeDoesNotChangeResults() {
        val pcm = Scene(60.0, 9).roomNoise().snoring(2.0, 60.0, 400.0).pcm()
        assertEquals(detect(pcm, 1600), detect(pcm, 777))
        assertEquals(detect(pcm, 1600), detect(pcm, pcm.size))
    }

    @Test fun recorderTimelineIsIndependentOfChunkSize() {
        val pcm = Scene(120.0, 10).roomNoise().snoring(2.0, 120.0, 400.0).pcm()
        fun levels(chunk: Int): List<Byte> {
            val rec = NightRecorder("t", 0L, rate, 8f, delayMs = 5_000, tags = emptyList(), clipDir = null)
            var off = 0
            val buf = ShortArray(chunk)
            while (off < pcm.size) { val n = minOf(chunk, pcm.size - off); System.arraycopy(pcm, off, buf, 0, n); rec.feed(buf, n); off += n }
            return rec.snapshot().levels.toList()
        }
        val a = levels(1600)
        assertTrue(a.count { it > 0 } >= 8)
        assertEquals(a, levels(1234))
    }
}

class MachineRhythmTest {
    @Test fun clockworkBurstsAreAMachineNotSnoring() {
        val events = ArrayList<SnoreEvent>()
        val d = SnoreDetector(16000, 8f) { events += it }
        val pcm = Scene(60.0, 50).roomNoise().dualToneRinger(5.0, 15, 1500.0).pcm()
        d.feed(pcm, 0, pcm.size)
        assertEquals(0, events.size)
    }
}
