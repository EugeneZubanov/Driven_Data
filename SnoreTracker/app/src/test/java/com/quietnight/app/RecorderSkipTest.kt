package com.quietnight.app

import com.quietnight.app.audio.NightRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/** Snores heard after a gap without audio must land where they happened on the wall clock. */
class RecorderSkipTest {
    private val rate = TestAudio.RATE

    private fun feed(r: NightRecorder, fromSec: Int, toSec: Int, rnd: Random, snoring: (Double) -> Boolean) {
        var t = fromSec.toDouble()
        while (t < toSec) {
            val buf = TestAudio.chunk(t, rate / 10, rnd, snoring)
            r.feed(buf, buf.size)
            t += 0.1
        }
    }

    @Test fun snoresAfterGapLandInTheirWallClockBuckets() {
        val dir = File("build/tmp/skip-clips").apply { deleteRecursively() }
        val r = NightRecorder("s", 0L, rate, 8f, delayMs = 0, tags = emptyList(), clipDir = dir, maxClips = 5)
        val rnd = Random(3)
        val snoring = { t: Double -> t in 400.0..470.0 }
        feed(r, 0, 120, rnd, snoring)          // 2 min quiet room
        r.skip(240_000)                        // 4 min with no audio (a phone call)
        feed(r, 360, 540, rnd, snoring)        // 3 min more; snoring at 400–470 s on the wall clock
        val s = r.snapshot()

        assertEquals(540, s.durationSec)
        val snoringBuckets = s.levels.indices.filter { s.levels[it] > 0 }
        assertTrue("buckets $snoringBuckets", snoringBuckets.isNotEmpty())
        // Without the skip offset these would sit 240 s (24 buckets) earlier, around 16–23.
        assertTrue("buckets $snoringBuckets", snoringBuckets.all { it in 39..47 })
        assertTrue((0 until 36).all { s.levels[it].toInt() == 0 })

        // Clips come from the snoring audio after the gap, not from the zeroed ring or the quiet start.
        assertTrue(s.clips.isNotEmpty())
        for (c in s.clips) {
            assertTrue("clip at ${c.offsetSec}", c.offsetSec in 390..470)
            val f = File(dir, c.file)
            RandomAccessFile(f, "r").use { raf ->
                val bytes = ByteArray((raf.length() - 44).toInt()); raf.seek(44); raf.readFully(bytes)
                val nonZero = (bytes.indices step 2).count { bytes[it].toInt() != 0 || bytes[it + 1].toInt() != 0 }
                assertTrue("clip ${c.file} mostly silent", nonZero > rate * 5)
            }
        }
    }

    @Test fun skipInsideFallAsleepDelayStillListensFromTheRightTime() {
        val r = NightRecorder("s", 0L, rate, 8f, delayMs = 120_000, tags = emptyList(), clipDir = null)
        val rnd = Random(5)
        val snoring = { t: Double -> t in 200.0..260.0 }
        feed(r, 0, 60, rnd, snoring)
        r.skip(90_000)                         // gap runs past the end of the 2 min delay
        feed(r, 150, 300, rnd, snoring)
        val s = r.snapshot()
        assertEquals(300, s.durationSec)
        val b = s.levels.indices.filter { s.levels[it] > 0 }
        assertTrue("buckets $b", b.isNotEmpty() && b.all { it in 19..27 })
    }
}
