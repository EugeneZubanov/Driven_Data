package com.quietnight.app.corpus

import com.quietnight.app.audio.NightDiagnostics
import com.quietnight.app.audio.NightRecorder
import com.quietnight.app.audio.OtherSound
import com.quietnight.app.audio.SensitivityAdvice
import com.quietnight.app.data.Session
import com.quietnight.app.data.Span
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiagnosticsAdviceTest {
    private val rate = 16000

    private fun night(pcm: ShortArray, dir: File?, threshold: Float = 8f): NightRecorder {
        val rec = NightRecorder("n", 1_800_000_000_000L, rate, threshold, delayMs = 0, tags = emptyList(), clipDir = dir)
        rec.feed(pcm, pcm.size)
        rec.finish()
        return rec
    }

    @Test fun diagnosticsCountOutcomesAndArePersisted() {
        val pcm = Scene(180.0, 96).roomNoise().snoring(2.0, 90.0, 400.0).speech(95.0, 150.0, 600.0).barking(150.0, 180.0, 1500.0).pcm()
        val dir = File("build/tmp/diag").apply { deleteRecursively() }
        val d = night(pcm, dir).diagnostics()
        assertEquals(rate, d.captureRate)
        assertEquals(180L, d.listenedSec)
        assertTrue("$d", d.reported >= 15 && d.speechLike >= 3 && d.bursts >= d.reported + d.speechLike)
        assertTrue("$d", d.floorMinDb in -75f..-55f && d.floorMedianDb >= d.floorMinDb && d.floorMaxDb >= d.floorMedianDb)
        val back = NightDiagnostics.read(dir)!!
        assertEquals(d.toJson().toString(), back.toJson().toString())
        val text = back.format()
        assertTrue(text, text.lines().size == 5 && "like talking" in text && "16000 Hz" in text)
        assertNull(NightDiagnostics.read(File("build/tmp/none")))
    }

    @Test fun quietSnoringShowsUpAsNearMisses() {
        // ~5 dB snoring: mostly below the normal 8 dB threshold, so little is reported but it is counted.
        val pcm = Scene(180.0, 97).roomNoise().snoring(2.0, 180.0, 60.0, jitterDb = 1.0).pcm()
        val d = night(pcm, null).diagnostics()
        assertTrue("$d", d.reported <= 3 && d.nearMisses >= 20)
    }

    private fun session(id: String, minutes: Int, snoringBuckets: IntRange, notSnoring: List<Span> = emptyList(), talking: IntRange? = null): Session {
        val n = minutes * 6
        val levels = ByteArray(n) { if (it in snoringBuckets) 1 else 0 }
        val others = if (talking == null) ByteArray(0) else ByteArray(n) { if (it in talking) OtherSound.TALKING.toByte() else 0 }
        return Session(
            id = id, startMs = 1_800_000_000_000L, endMs = 1_800_000_000_000L + minutes * 60_000L, bucketSec = 10,
            levels = levels, amps = ByteArray(n), tags = emptyList(), clips = emptyList(), notSnoring = notSnoring, others = others,
        )
    }

    private fun diag(hours: Int, near: Int) = NightDiagnostics(16000, 8f, hours * 3600L, 0f, 0, 0, 0, 0, 0, 0, 0, 0, 0, near, -60f, -58f, -50f)

    @Test fun manyCorrectionsSuggestLowerSensitivity() {
        // Two nights, 30 min detected each, 15 min of it marked not snoring, during talking.
        val nights = (1..2).map { session("c$it", 480, 0 until 180, listOf(Span(0, 900)), talking = 0 until 90) to null }
        val a = SensitivityAdvice.suggest(1, nights)!!
        assertEquals(0, a.sensitivity)
        assertTrue(a.reason, "30 min" in a.reason && "talking" in a.reason)
        assertNull(SensitivityAdvice.suggest(0, nights)) // already low
        assertNull(SensitivityAdvice.suggest(1, nights.take(1))) // one night is not a pattern
    }

    @Test fun nearMissesWithLittleSnoringSuggestHigherSensitivity() {
        val nights = (1..3).map { session("q$it", 480, 0 until 2) to diag(8, 120) }
        val a = SensitivityAdvice.suggest(1, nights)!!
        assertEquals(2, a.sensitivity)
        assertNull(SensitivityAdvice.suggest(2, nights))
        assertNull(SensitivityAdvice.suggest(1, (1..3).map { session("q$it", 480, 0 until 2) to diag(8, 5) }))
        assertNull(SensitivityAdvice.suggest(1, (1..2).map { session("q$it", 480, 0 until 2) to diag(8, 120) }))
    }

    @Test fun normalNightsGetNoAdvice() {
        assertNull(SensitivityAdvice.suggest(1, emptyList()))
        assertNull(SensitivityAdvice.suggest(1, (1..5).map { session("n$it", 480, 0 until 600, listOf(Span(0, 60))) to diag(8, 30) }))
    }
}
