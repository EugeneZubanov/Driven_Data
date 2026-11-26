package com.quietnight.app

import com.quietnight.app.audio.NightRecorder
import com.quietnight.app.audio.SnoreDetector
import com.quietnight.app.audio.SnoreEvent
import com.quietnight.app.data.DemoData
import com.quietnight.app.data.Level
import com.quietnight.app.data.Session
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class DetectorTest {
    private val rate = 16000

    /** Room noise plus optional bursts: [burstHz] tone with harmonics and roughness, [burstSec] long every [everySec]. */
    private fun audio(
        seconds: Int, noiseAmp: Double = 60.0, burstAmp: Double = 0.0, burstHz: Double = 110.0,
        burstSec: Double = 1.2, everySec: Double = 4.0, fromSec: Double = 5.0, seed: Int = 1,
    ): ShortArray {
        val rnd = Random(seed)
        return ShortArray(seconds * rate) { i ->
            val t = i.toDouble() / rate
            var v = (rnd.nextDouble() * 2 - 1) * noiseAmp
            if (burstAmp > 0 && t >= fromSec) {
                val phase = (t - fromSec) % everySec
                if (phase < burstSec) {
                    val env = sin(PI * phase / burstSec)
                    val rough = 0.6 + 0.4 * sin(2 * PI * 28 * t)
                    v += burstAmp * env * rough * (sin(2 * PI * burstHz * t) + 0.5 * sin(2 * PI * 2 * burstHz * t) + 0.25 * sin(2 * PI * 3 * burstHz * t))
                }
            }
            v.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun detect(pcm: ShortArray, threshold: Float = 8f): List<SnoreEvent> {
        val events = ArrayList<SnoreEvent>()
        val d = SnoreDetector(rate, threshold) { events += it }
        var off = 0
        while (off < pcm.size) {
            val n = minOf(1600, pcm.size - off)
            d.feed(pcm.copyOfRange(off, off + n), n)
            off += n
        }
        return events
    }

    @Test fun rhythmicLowBurstsAreSnores() {
        val events = detect(audio(60, burstAmp = 1500.0))
        // bursts every 4 s from 5 s to 60 s -> about 13
        assertTrue("got ${events.size}", events.size in 11..14)
        assertTrue(events.all { it.durationMs in 500..1600 })
    }

    @Test fun louderSnoresGetHigherLevels() {
        fun level(amp: Double) = detect(audio(60, burstAmp = amp)).map { Level.fromDb(it.dbAboveFloor) }.groupingBy { it }.eachCount().maxBy { it.value }.key
        assertEquals(Level.MILD, level(250.0))
        assertEquals(Level.LOUD, level(1200.0))
        assertEquals(Level.HEAVY, level(8000.0))
    }

    @Test fun steadyNoiseIsNotSnoring() {
        assertEquals(0, detect(audio(120)).size)
        assertEquals(0, detect(audio(120, noiseAmp = 2000.0)).size)
    }

    @Test fun highPitchedBeepingIsNotSnoring() {
        assertEquals(0, detect(audio(60, burstAmp = 3000.0, burstHz = 2500.0, burstSec = 0.8, everySec = 3.0)).size)
    }

    @Test fun singleThumpIsNotSnoring() {
        assertEquals(0, detect(audio(40, burstAmp = 6000.0, everySec = 100.0)).size)
    }

    @Test fun longDroneIsNotSnoring() {
        // e.g. a passing truck: low-pitched but far longer than a breath
        assertEquals(0, detect(audio(60, burstAmp = 3000.0, burstSec = 12.0, everySec = 20.0)).size)
    }

    @Test fun recorderBuildsTimelineAndClips() {
        val dir = File("build/tmp/clips-test").apply { deleteRecursively() }
        val rec = NightRecorder("t", 1_000_000L, rate, 8f, delayMs = 20_000, tags = listOf("f:Alcohol"), clipDir = dir, maxClips = 3)
        // 30 s quiet-ish with snores ignored by the delay, then snoring from 40 s to 120 s, then quiet
        val pcm = audio(120, burstAmp = 1500.0, fromSec = 0.0) + audio(60)
        var off = 0
        while (off < pcm.size) { val n = minOf(1600, pcm.size - off); rec.feed(pcm.copyOfRange(off, off + n), n); off += n }
        val s = rec.snapshot()
        assertEquals(180, s.durationSec)
        assertEquals(18, s.levels.size)
        assertEquals("delay must mute the first buckets", 0, s.levels[0].toInt() + s.levels[1].toInt())
        assertTrue("snoring buckets: ${s.levels.toList()}", (3..11).all { s.levels[it] > 0 })
        assertTrue((13..17).all { s.levels[it].toInt() == 0 })
        assertEquals(3, s.clips.size)
        assertEquals(3, dir.listFiles()!!.size)
        assertTrue(s.clips.all { File(dir, it.file).length() == 44L + 10 * rate * 2 })
        assertTrue(s.score > 0)
    }

    @Test fun sessionSurvivesJson() {
        val s = DemoData.generate(1_800_000_000_000L).first()
        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s.score, back.score)
        assertEquals(s.tags, back.tags)
        assertTrue(s.levels.contentEquals(back.levels))
        assertTrue(s.amps.contentEquals(back.amps))
    }
}
