package com.quietnight.app.corpus

import com.quietnight.app.audio.NightDiagnostics
import com.quietnight.app.audio.NightRecorder
import com.quietnight.app.data.Backup
import com.quietnight.app.data.Level
import com.quietnight.app.data.Prefs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** NightRecorder as the service drives it: 100 ms reads, periodic snapshots, skips, finish. */
@RunWith(RobolectricTestRunner::class)
class RecorderIntegrationTest {
    private val pcm16 by lazy {
        Scene(360.0, 120).roomNoise().snoring(5.0, 200.0, 600.0).speech(150.0, 230.0, 500.0)
            .coughing(200.0, 260.0, 1500.0).snoring(250.0, 360.0, 900.0, 4.5).pcm()
    }

    /** Feeds [pcm] in 100 ms reads; optional snapshots every [snapEverySec] and skips at given seconds. */
    private fun night(
        dir: File, rate: Int, pcm: ShortArray, snapEverySec: Int = 0,
        skips: Map<Int, Long> = mapOf(120 to 30_000L, 180 to 800L),
    ): NightRecorder {
        val rec = NightRecorder("n1", 1_800_000_000_000L, rate, 8f, delayMs = 20_000, tags = listOf("f:Alcohol"), clipDir = dir, maxClips = 4)
        val buf = ShortArray(rate / 10)
        var off = 0
        var reads = 0
        while (off < pcm.size) {
            val n = minOf(buf.size, pcm.size - off)
            System.arraycopy(pcm, off, buf, 0, n)
            rec.feed(buf, n)
            off += n
            reads++
            if (reads % 10 == 0) {
                val sec = reads / 10
                skips[sec]?.let { rec.skip(it) }
                if (snapEverySec > 0 && sec % snapEverySec == 0) {
                    val s = rec.snapshot()
                    assertEquals(s.levels.size, s.amps.size)
                    assertEquals(s.levels.size, s.others.size)
                    assertEquals(s.levels.size, s.conf.size)
                    assertTrue(Backup.isValid(s))
                }
            }
        }
        rec.finish()
        return rec
    }

    private fun files(dir: File) = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }.associate { it.name to it.readBytes() }

    @Test fun periodicSnapshotsDoNotChangeTheNight() {
        for (rate in intArrayOf(16000, 44100)) {
            val pcm = if (rate == 16000) pcm16 else Scene(240.0, 121, rate).roomNoise().snoring(5.0, 240.0, 600.0).speech(150.0, 200.0, 500.0).pcm()
            val a = File("build/tmp/integ-a-$rate").apply { deleteRecursively() }
            val b = File("build/tmp/integ-b-$rate").apply { deleteRecursively() }
            val plain = night(a, rate, pcm)
            val snapped = night(b, rate, pcm, snapEverySec = 120)
            assertEquals(plain.snapshot().toJson().toString(), snapped.snapshot().toJson().toString())
            val fa = files(a); val fb = files(b)
            assertEquals(fa.keys, fb.keys)
            for (k in fa.keys) assertArrayEquals("$rate $k", fa[k], fb[k])
            assertTrue("$rate clips ${fa.keys}", fa.keys.count { it.endsWith(".wav") } in 1..4 && NightDiagnostics.FILE_NAME in fa.keys)
        }
    }

    @Test fun finishTwiceChangesNothing() {
        val dir = File("build/tmp/integ-twice").apply { deleteRecursively() }
        val rec = night(dir, 16000, pcm16)
        val s1 = rec.snapshot().toJson().toString()
        val f1 = files(dir)
        rec.finish()
        assertEquals(s1, rec.snapshot().toJson().toString())
        val f2 = files(dir)
        assertEquals(f1.keys, f2.keys)
        for (k in f1.keys) assertArrayEquals(k, f1[k], f2[k])
    }

    @Test fun shortSkipsKeepTheRhythm() {
        // A 300 ms skip every 6 s (e.g. noise-gated all-zero reads between breaths, which the
        // service treats as no audio) must not throw away the breathing rhythm each time.
        val pcm = Scene(240.0, 122).roomNoise().snoring(2.0, 240.0, 600.0).pcm()
        val dir = File("build/tmp/integ-skip").apply { deleteRecursively() }
        val none = night(dir, 16000, pcm, skips = emptyMap()).eventCount
        val skipped = night(dir, 16000, pcm, skips = (1..39).associate { it * 6 to 300L }).eventCount
        assertTrue("$skipped of $none", skipped >= none * 0.85)
    }

    @Test fun thresholdsHaveOneSource() {
        assertEquals(Level.SENSITIVITY_DB.toList(), (0..2).map { Prefs(sensitivity = it).thresholdDb })
        assertEquals(Level.LOUD, Level.fromDb(Level.LOUD_DB))
        assertEquals(Level.MILD, Level.fromDb(Level.LOUD_DB - 0.1f))
        assertEquals(Level.HEAVY, Level.fromDb(Level.HEAVY_DB))
        assertEquals(5.5f, Level.thresholdDb(7))
    }
}
