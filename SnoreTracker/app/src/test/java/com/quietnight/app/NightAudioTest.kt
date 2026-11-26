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
    override fun finish(): Boolean { file.appendText("|done:$samples"); return true }
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

    @Test fun unplayableSegmentIsDroppedNotListed() {
        val w = NightAudioWriter(dir, rate, { f, _ ->
            object : SegmentEncoder {
                init { f.writeText("x") }
                override fun write(pcm: ShortArray, n: Int) {}
                override fun finish() = false
            }
        }, segmentMs = 60_000)
        w.feed(0, 70_000)
        w.close()
        assertTrue(w.segments.isEmpty())
        assertFalse(w.failed)
        assertFalse(File(dir, "audio-001.m4a").exists())
    }

    @Test fun capturesAtHigherRatesAreResampledTo16kHzForTheEncoder() {
        for (inRate in listOf(48000, 44100)) {
            val got = ArrayList<Int>()
            var encodedRate = 0
            var samples = 0L
            val w = NightAudioWriter(File(dir, "r$inRate"), inRate, { f, r ->
                encodedRate = r
                object : SegmentEncoder {
                    override fun write(pcm: ShortArray, n: Int) { samples += n; got += n }
                    override fun finish(): Boolean { f.writeText("ok"); return true }
                }
            }, segmentMs = 60_000)
            val chunk = ShortArray(inRate / 10)
            var t = 0L
            while (t < 90_000) { w.write(chunk, chunk.size, t); t += 100 }
            w.close()
            assertEquals(16000, encodedRate)
            // 90 s of input becomes ~90 s at 16 kHz, and segment times follow the capture clock.
            assertTrue("$inRate: $samples", samples in 1_439_000L..1_441_000L)
            assertEquals(listOf(0L, 60_000L), w.segments.map { it.startMs })
            assertEquals(listOf(60_000L, 30_000L), w.segments.map { it.durationMs })
        }
    }

    private fun rms(x: ShortArray, from: Int = 0): Double =
        kotlin.math.sqrt(x.drop(from).sumOf { it.toDouble() * it } / (x.size - from))

    private fun tone(hz: Double, inRate: Int, seconds: Int) =
        ShortArray(inRate * seconds) { (8000 * kotlin.math.sin(2 * Math.PI * hz * it / inRate)).toInt().toShort() }

    @Test fun resamplerKeepsTheBandAndRemovesWhatWouldAlias() {
        for (inRate in listOf(48000, 44100)) {
            val r1 = com.quietnight.app.audio.Resampler(inRate, 16000)
            val low = r1.process(tone(440.0, inRate, 2), inRate * 2)
            assertTrue("$inRate count ${low.size}", low.size in 31_990..32_010)
            val kept = rms(low, 1000) / (8000 / kotlin.math.sqrt(2.0))
            assertTrue("$inRate 440 Hz kept $kept", kept in 0.9..1.1)

            val r2 = com.quietnight.app.audio.Resampler(inRate, 16000)
            val high = r2.process(tone(12_000.0, inRate, 2), inRate * 2) // above 8 kHz: would alias to 4 kHz
            val leaked = rms(high, 1000) / (8000 / kotlin.math.sqrt(2.0))
            assertTrue("$inRate 12 kHz leaked $leaked", leaked < 0.05) // > 26 dB down
        }
        // Streaming in small pieces gives the same samples as one call.
        val input = tone(1000.0, 48000, 1)
        val whole = com.quietnight.app.audio.Resampler(48000, 16000).process(input, input.size)
        val r = com.quietnight.app.audio.Resampler(48000, 16000)
        val pieces = (0 until input.size step 1600).flatMap { r.process(input.copyOfRange(it, it + 1600), 1600).toList() }
        assertEquals(whole.toList(), pieces)
        // Same rate passes straight through.
        assertEquals(input.toList(), com.quietnight.app.audio.Resampler(16000, 16000).process(input, input.size).toList())
    }

    @Test fun bitrateIsClampedToTheEncodersRange() {
        assertEquals(24_000, com.quietnight.app.audio.AacSupport.pickBitrate(24_000, 8_000, 64_000))
        assertEquals(32_000, com.quietnight.app.audio.AacSupport.pickBitrate(24_000, 32_000, 128_000))
        assertEquals(20_000, com.quietnight.app.audio.AacSupport.pickBitrate(24_000, 8_000, 20_000))
    }

    @Test fun indexIsOrderedAndNeverOverlapsAcrossRolloverGapsAndDrops() {
        var opened = 0
        val w = NightAudioWriter(dir, 44100, { f, _ ->
            opened++
            object : SegmentEncoder {
                val drop = opened == 3 // the third file turns out unplayable
                override fun write(pcm: ShortArray, n: Int) {}
                override fun finish(): Boolean { f.writeText("x"); return !drop }
            }
        }, segmentMs = 60_000)
        val chunk = ShortArray(4410)
        fun feed(from: Long, to: Long) { var t = from; while (t < to) { w.write(chunk, chunk.size, t); t += 100 } }
        feed(0, 130_000)          // two full segments and the start of a third
        w.gap()                   // the third ends early (and is dropped)
        feed(200_000, 230_000)    // after a gap
        w.close()
        val s = SegmentIndex.read(dir)
        assertEquals(listOf(0L, 60_000L, 200_000L), s.map { it.startMs })
        for (i in 1 until s.size) assertTrue(s[i].startMs >= s[i - 1].startMs + s[i - 1].durationMs)
        assertTrue(s.all { File(dir, it.file).exists() })
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
        assertTrue("$mb MB", mb in 70..100)
    }
}
