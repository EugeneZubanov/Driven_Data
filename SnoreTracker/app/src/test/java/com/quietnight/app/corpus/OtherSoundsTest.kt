package com.quietnight.app.corpus

import com.quietnight.app.audio.NightRecorder
import com.quietnight.app.audio.OtherSound
import com.quietnight.app.audio.SnoreDetector
import com.quietnight.app.audio.SnoreEvent
import com.quietnight.app.audio.withRecordingInfo
import com.quietnight.app.data.Backup
import com.quietnight.app.data.Session
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OtherSoundsTest {
    private val rate = 16000

    private fun run(pcm: ShortArray, listen: Boolean = true): Pair<List<SnoreEvent>, List<OtherSound>> {
        val events = ArrayList<SnoreEvent>()
        val others = ArrayList<OtherSound>()
        val d = SnoreDetector(rate, 8f) { events += it }
        if (listen) d.onOther = { others += it }
        var off = 0
        while (off < pcm.size) { val n = minOf(1600, pcm.size - off); d.feed(pcm, off, n); off += n }
        return events to others
    }

    @Test fun talkingIsReportedAsTalking() {
        val (events, others) = run(Scene(120.0, 80).roomNoise().speech(2.0, 120.0, 600.0).pcm())
        assertEquals(0, events.size)
        val talkSec = others.filter { it.kind == OtherSound.TALKING }.sumOf { it.durationMs } / 1000.0
        assertTrue("talking ${talkSec}s of $others", talkSec > 30)
    }

    @Test fun coughsAndBarksAreOther() {
        for (scene in listOf(Scene(120.0, 81).roomNoise().coughing(0.0, 120.0, 1500.0), Scene(120.0, 82).roomNoise().barking(0.0, 120.0, 1500.0))) {
            val (events, others) = run(scene.pcm())
            assertEquals(0, events.size)
            assertTrue(others.isNotEmpty() && others.all { it.kind == OtherSound.OTHER })
        }
    }

    @Test fun snoringIsNotOther() {
        val scene = Scene(120.0, 83).roomNoise().snoring(2.0, 120.0, 400.0)
        val (_, others) = run(scene.pcm())
        // Only the first breaths, before the rhythm is confirmed, could show up; usually none.
        assertTrue("$others", others.size <= 1)
    }

    @Test fun listeningForOthersNeverChangesSnoreDecisions() {
        val pcm = Scene(180.0, 84).roomNoise().snoring(2.0, 180.0, 500.0).speech(40.0, 140.0, 400.0, 200.0, 1.0, 5.0).coughing(0.0, 180.0, 1200.0).pcm()
        assertEquals(run(pcm, listen = false).first, run(pcm, listen = true).first)
    }

    @Test fun recorderStoresOthersAndConfidencePerBucket() {
        val pcm = Scene(60.0, 85).roomNoise().snoring(2.0, 30.0, 400.0).speech(35.0, 58.0, 600.0).pcm()
        val rec = NightRecorder("n1", 1_800_000_000_000L, rate, 8f, delayMs = 0, tags = emptyList(), clipDir = null)
        rec.feed(pcm, pcm.size)
        val s = rec.snapshot().withRecordingInfo()
        assertEquals(s.levels.size, s.others.size)
        assertEquals(s.levels.size, s.conf.size)
        assertTrue("others ${s.others.toList()}", (4..5).any { s.others[it].toInt() and OtherSound.TALKING != 0 })
        assertTrue("conf ${s.conf.toList()}", (1..2).all { s.conf[it] in 40..100 })
        assertTrue(s.levels.indices.all { s.levels[it] > 0 || s.conf[it].toInt() == 0 })

        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertArrayEquals(s.others, back.others)
        assertArrayEquals(s.conf, back.conf)
        assertTrue(Backup.isValid(back))
        val edited = back.withEdits(listOf("f:Alcohol"), "note")
        assertArrayEquals(s.others, edited.others)
        assertArrayEquals(s.conf, edited.conf)
    }

    @Test fun oldJsonWithoutTheFieldsStillLoads() {
        val s = Session("old", 1_800_000_000_000L, 1_800_000_060_000L, 10, ByteArray(6) { 1 }, ByteArray(6) { 20 }, emptyList(), emptyList())
        val json = s.toJson()
        assertTrue(!json.has("others") && !json.has("conf"))
        val back = Session.fromJson(JSONObject(json.toString()))
        assertEquals(0, back.others.size)
        assertEquals(0, back.conf.size)
        assertTrue(Backup.isValid(back))
    }

    @Test fun backupRejectsMalformedOthers() {
        val base = Session("x", 1_800_000_000_000L, 1_800_000_060_000L, 10, ByteArray(6), ByteArray(6), emptyList(), emptyList())
        fun with(others: ByteArray = ByteArray(0), conf: ByteArray = ByteArray(0)) = Session(
            id = base.id, startMs = base.startMs, endMs = base.endMs, bucketSec = 10, levels = base.levels, amps = base.amps,
            tags = emptyList(), clips = emptyList(), others = others, conf = conf,
        )
        assertTrue(Backup.isValid(with(ByteArray(6) { 3 }, ByteArray(6) { 100 })))
        assertTrue(!Backup.isValid(with(others = ByteArray(5))))
        assertTrue(!Backup.isValid(with(others = ByteArray(6) { 4 })))
        assertTrue(!Backup.isValid(with(conf = ByteArray(6) { 101.toByte() })))
    }
}
