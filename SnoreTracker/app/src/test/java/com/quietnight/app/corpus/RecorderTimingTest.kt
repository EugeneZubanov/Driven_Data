package com.quietnight.app.corpus

import com.quietnight.app.audio.NightRecorder
import com.quietnight.app.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** How late-confirmed snores and gaps without audio land on the timeline and in clips. */
class RecorderTimingTest {
    private val rate = 16000

    private fun recorder(dir: File?, delayMs: Long = 0) =
        NightRecorder("t", 0L, rate, 8f, delayMs = delayMs, tags = emptyList(), clipDir = dir)

    private fun feed(rec: NightRecorder, pcm: ShortArray) {
        val buf = ShortArray(1600)
        var off = 0
        while (off < pcm.size) { val n = minOf(1600, pcm.size - off); System.arraycopy(pcm, off, buf, 0, n); rec.feed(buf, n); off += n }
    }

    private fun dir(name: String) = File("build/tmp/$name").apply { deleteRecursively() }

    private fun peakOf(f: File): Int {
        val bytes = RandomAccessFile(f, "r").use { val b = ByteArray(it.length().toInt()); it.readFully(b); b }
        var peak = 0
        var i = 44
        while (i + 1 < bytes.size) { peak = maxOf(peak, kotlin.math.abs(((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt())); i += 2 }
        return peak
    }

    @Test fun firstSnoresOfASlowRunStillGetAClip() {
        // Three breaths ~10 s apart: the run is confirmed ~22 s after the first snore (at 19.5 s,
        // bucket 1). With a three-bucket ring that bucket's clip was decided at 40 s, too early.
        val scene = Scene(80.0, 60).roomNoise().snoreAt(19.5, 1.2, 1500.0).snoreAt(29.2, 1.5, 1300.0).snoreAt(39.1, 1.8, 1700.0)
        val d = dir("timing-first")
        val rec = recorder(d, delayMs = 0)
        feed(rec, scene.pcm())
        rec.finish()
        val s = rec.snapshot()
        assertTrue("levels ${s.levels.toList()}", s.levels[1] > 0 && s.levels[2] > 0 && s.levels[3] > 0)
        val first = s.clips.firstOrNull { it.offsetSec == 10 }
        assertTrue("clips ${s.clips}", first != null)
        assertTrue(peakOf(File(d, first!!.file)) > 1000)
    }

    @Test fun finishDecidesClipsForTheLastBuckets() {
        val scene = Scene(60.0, 61).roomNoise().snoring(2.0, 60.0, 1200.0)
        val d = dir("timing-finish")
        val rec = recorder(d)
        feed(rec, scene.pcm())
        val before = rec.snapshot().clips.size
        rec.finish()
        val after = rec.snapshot().clips
        assertTrue("before $before after ${after.size}", after.size > before)
        assertTrue(after.any { it.offsetSec >= 20 })
        assertTrue(after.all { peakOf(File(d, it.file)) > 1000 })
    }

    @Test fun snoresAreNotPairedAcrossAGap() {
        // Two breaths, a 2-minute gap, one breath. Without the gap they would be 4 s apart, a perfect
        // rhythm; across it they are not, so nothing to report.
        val a = Scene(10.0, 62).roomNoise().snoreAt(3.0, 1.2, 1500.0).snoreAt(7.0, 1.4, 1500.0)
        val b = Scene(10.0, 63).roomNoise().snoreAt(1.0, 1.3, 1500.0)
        val rec = recorder(null)
        feed(rec, a.pcm())
        rec.skip(120_000)
        feed(rec, b.pcm())
        assertEquals(0, rec.eventCount)
    }

    @Test fun snoringAroundAGapLandsOnTheRightSideAndClipsKeepTheirAudio() {
        val a = Scene(65.0, 64).roomNoise().snoring(2.0, 65.0, 1500.0)
        val b = Scene(60.0, 65).roomNoise().snoring(1.0, 60.0, 1500.0)
        val d = dir("timing-gap")
        val rec = recorder(d)
        feed(rec, a.pcm())
        rec.skip(300_000)
        feed(rec, b.pcm())
        rec.finish()
        val s: Session = rec.snapshot()
        assertEquals(43, s.levels.size)
        // 0..64 s audio (buckets 0–6), gap 65..365 s, audio 365..425 s (buckets 36–42)
        assertTrue("before gap ${s.levels.toList()}", (1..5).all { s.levels[it] > 0 })
        assertTrue("gap ${s.levels.toList()}", (7..35).all { s.levels[it].toInt() == 0 })
        assertTrue("after gap ${s.levels.toList()}", (38..41).all { s.levels[it] > 0 })
        assertTrue(s.clips.any { it.offsetSec < 65 } && s.clips.any { it.offsetSec > 365 })
        assertTrue(s.clips.all { peakOf(File(d, it.file)) > 1000 })
    }
}
