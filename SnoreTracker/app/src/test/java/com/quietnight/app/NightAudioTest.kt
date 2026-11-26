package com.quietnight.app

import com.quietnight.app.audio.AudioSegment
import com.quietnight.app.audio.EncoderFactory
import com.quietnight.app.audio.NightAudioWriter
import com.quietnight.app.audio.SegmentEncoder
import com.quietnight.app.audio.SegmentIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A stand-in encoder: writes raw samples and a trailer on finish, so tests can tell finished files. */
class FakeEncoder(private val file: File) : SegmentEncoder {
    var samples = 0L
    init { file.writeText("") }
    override fun write(pcm: ShortArray, n: Int) { samples += n; file.appendText("x") }
    override fun finish() { file.appendText("|done:$samples") }
}

@RunWith(RobolectricTestRunner::class)
class NightAudioTest {
    private val rate = 16000
    private val dir = File("build/tmp/night-audio-test").apply { deleteRecursively(); mkdirs() }
    private val buf = ShortArray(rate / 10)

    private fun NightAudioWriter.feed(fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t < toMs) { write(buf, buf.size, t); t += 100 }
    }

    @Test fun rollsOverIntoFinishedSegmentsWithIndex() {
        val w = NightAudioWriter(dir, rate, { f, _ -> FakeEncoder(f) }, segmentMs = 60_000)
        w.feed(0, 150_000)
        w.close()
        val expected = listOf(
            AudioSegment("audio-001.m4a", 0, 60_000),
            AudioSegment("audio-002.m4a", 60_000, 60_000),
            AudioSegment("audio-003.m4a", 120_000, 30_000),
        )
        assertEquals(expected, w.segments)
        assertEquals(expected, SegmentIndex.read(dir))
        assertTrue(expected.all { File(dir, it.file).readText().contains("|done:") })
    }

    @Test fun gapEndsSegmentSoOffsetsStayTrue() {
        val w = NightAudioWriter(dir, rate, { f, _ -> FakeEncoder(f) }, segmentMs = 600_000)
        w.feed(0, 30_000)
        w.gap(); w.gap() // repeated gap calls are harmless
        w.feed(90_000, 120_000)
        w.close()
        assertEquals(listOf(0L, 90_000L), w.segments.map { it.startMs })
        assertEquals(listOf(30_000L, 30_000L), w.segments.map { it.durationMs })
    }

    @Test fun crashLosesOnlyTheSegmentInProgress() {
        val w = NightAudioWriter(dir, rate, { f, _ -> FakeEncoder(f) }, segmentMs = 60_000)
        w.feed(0, 95_000)
        // No close(): the process died. The index on disk lists only the finished segment.
        assertEquals(listOf(AudioSegment("audio-001.m4a", 0, 60_000)), SegmentIndex.read(dir))
    }

    @Test fun encoderFailureStopsFullAudioButKeepsFinishedSegments() {
        var opened = 0
        val w = NightAudioWriter(dir, rate, EncoderFactory { f, _ ->
            if (++opened == 2) { f.writeText("partial"); throw IllegalStateException("no codec") }
            FakeEncoder(f)
        }, segmentMs = 60_000)
        w.feed(0, 130_000)
        w.close()
        assertTrue(w.failed)
        assertEquals(listOf("audio-001.m4a"), w.segments.map { it.file })
        assertFalse(File(dir, "audio-002.m4a").exists())
        assertEquals(1, SegmentIndex.read(dir).size)
    }

    @Test fun subSecondSegmentIsDropped() {
        val w = NightAudioWriter(dir, rate, { f, _ -> FakeEncoder(f) })
        w.feed(0, 500)
        w.close()
        assertTrue(w.segments.isEmpty())
        assertFalse(File(dir, "audio-001.m4a").exists())
    }

    @Test fun indexRoundTripsAndMissingIndexIsEmpty() {
        val s = listOf(AudioSegment("a.m4a", 5, 6), AudioSegment("b.m4a", 7, 8))
        assertEquals(s, SegmentIndex.fromJson(SegmentIndex.toJson(s)))
        assertTrue(SegmentIndex.read(File(dir, "nope")).isEmpty())
    }

    @Test fun storageEstimate() {
        val mb = NightAudioWriter.estimateBytes(8.0) / 1_000_000
        assertTrue("$mb MB", mb in 40..70)
    }
}
