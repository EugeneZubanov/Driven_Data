package com.quietnight.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.quietnight.app.audio.MicInput
import com.quietnight.app.audio.RecordingEnv
import com.quietnight.app.audio.RecordingService
import com.quietnight.app.data.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Drives the real [RecordingService] through simulated nights: a fake microphone hands out audio
 * in step with a fake clock, so minutes pass in milliseconds. The nearest thing to a device test.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingServiceTest {
    private val app = ApplicationProvider.getApplicationContext<QuietNightApp>()
    private val clockMs = AtomicLong(0)
    private val wallBase = 1_795_650_000_000L
    private var battery: Pair<Int?, Boolean> = 90 to true

    /** Wall-clock seconds when the microphone is taken by "a call". */
    private var takenSec = -1.0..-1.0
    /** Wall-clock seconds at which the next stop is pressed. */
    private val stops = ArrayDeque<Double>()
    private var snoring: (Double) -> Boolean = { false }
    private lateinit var controller: ServiceController<RecordingService>
    private val rnd = Random(7)

    private fun now() = clockMs.get() / 1000.0

    /** Capture rates the simulated phone supports, and every rate list the service asked for. */
    private var supportedRates = listOf(16000)
    private var overrunAtSec: Double? = null
    private var noiseGate = false
    /** Samples per read if the phone delivers less than asked (e.g. 4096 at 44.1 kHz). */
    private var readSize: Int? = null
    private var subMs = 0.0
    private val requested = java.util.Collections.synchronizedList(ArrayList<List<Int>>())

    private inner class FakeMic(override val sampleRate: Int) : MicInput {
        override fun read(buf: ShortArray, size: Int): Int {
            val t = now()
            if (stops.isNotEmpty() && t >= stops.first()) {
                stops.removeFirst()
                controller.get().onStartCommand(Intent(app, RecordingService::class.java).setAction("com.quietnight.app.STOP"), 0, 99)
            }
            if (t in takenSec) return -3 // AudioRecord.ERROR_INVALID_OPERATION
            if (overrunAtSec != null && t >= overrunAtSec!!) {
                // The app fell behind and the microphone's buffer overflowed: 8 s of audio never arrive.
                overrunAtSec = null
                clockMs.addAndGet(8_000)
            }
            val n = minOf(size, readSize ?: size)
            val chunk = TestAudio.chunk(t, n, rnd, snoring, sampleRate)
            // A noise gate (strong noise suppression) outputs exact zeros for anything quiet.
            if (noiseGate) for (i in 0 until n) if (kotlin.math.abs(chunk[i].toInt()) < 300) chunk[i] = 0
            chunk.copyInto(buf)
            // Advance the clock by exactly the audio delivered, carrying fractions of a millisecond.
            subMs += n * 1000.0 / sampleRate
            val whole = subMs.toLong()
            subMs -= whole
            clockMs.addAndGet(whole)
            return n
        }
        override fun silenced() = false
        override fun close() {}
    }

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.update { it.copy(delayMin = 0, saveClips = true) }
        RecordingEnv.openMic = { _, rates ->
            requested += rates
            if (now() in takenSec) null else rates.firstOrNull { it in supportedRates }?.let { FakeMic(it) }
        }
        RecordingEnv.elapsedMs = { clockMs.get() }
        RecordingEnv.wallMs = { wallBase + clockMs.get() }
        // Simulated time flies, but slowly enough for the test thread to step in.
        RecordingEnv.sleep = { clockMs.addAndGet(it); Thread.sleep(1) }
        RecordingEnv.battery = { battery }
        RecordingService.clearFinished()
        RecordingService.clearNotice()
        controller = Robolectric.buildService(RecordingService::class.java, Intent(app, RecordingService::class.java)).create()
    }

    @After fun tearDown() {
        RecordingEnv.reset()
    }

    private fun startNight() = controller.get().onStartCommand(Intent(app, RecordingService::class.java), 0, 1)

    /** Waits for the worker thread to save and hand back to the main thread. */
    private fun awaitNightEnd() {
        val deadline = System.currentTimeMillis() + 120_000
        while (RecordingService.isRecording || RecordingService.state.value.finishedId == null && RecordingService.state.value.error == null) {
            assertTrue("night did not end", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun saved(id: String?): Session = app.repository.sessions.value.first { it.id == id }

    private fun marker() = app.getSharedPreferences("recording", Context.MODE_PRIVATE).getString("activeId", null)

    @Test fun nightWithAPhoneCallKeepsTimelineGapsAndSnores() {
        snoring = { t -> t in 60.0..120.0 || t in 360.0..420.0 }
        takenSec = 180.0..300.0
        stops += 540.0
        startNight()
        awaitNightEnd()

        val state = RecordingService.state.value
        assertNull(state.error)
        val s = saved(state.finishedId)
        assertTrue("duration ${s.durationSec}", s.durationSec in 538..545)
        assertEquals(1, s.gaps.size)
        val g = s.gaps.single()
        assertTrue("gap $g", g.fromSec in 179..181 && g.toSec in 300..340)

        val snoringBuckets = s.levels.indices.filter { s.levels[it] > 0 }
        assertTrue("buckets $snoringBuckets", snoringBuckets.any { it in 6..12 })
        assertTrue("buckets $snoringBuckets", snoringBuckets.any { it in 36..42 })
        assertTrue("buckets $snoringBuckets", snoringBuckets.all { it in 5..13 || it in 35..43 })
        assertTrue(s.clips.isNotEmpty())
        assertEquals(0L, s.interruptedAtMs)

        assertNull("marker cleared", marker())
        assertFalse(RecordingService.isRecording)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun phoneThatOnlyRecordsAt44kHzStillGetsARightNight() {
        supportedRates = listOf(44100)
        snoring = { t -> t in 60.0..120.0 || t in 360.0..420.0 }
        takenSec = 180.0..300.0
        stops += 480.0
        startNight()
        awaitNightEnd()
        val s = saved(RecordingService.state.value.finishedId)
        assertTrue("duration ${s.durationSec}", s.durationSec in 478..485)
        val g = s.gaps.single()
        assertTrue("gap $g", g.fromSec in 179..181 && g.toSec in 300..340)
        val b = s.levels.indices.filter { s.levels[it] > 0 }
        assertTrue("buckets $b", b.any { it in 6..12 } && b.any { it in 36..42 })
        assertTrue("buckets $b", b.all { it in 5..13 || it in 35..43 })
        // First open walks the list; reopening after the call asks only for the night's own rate.
        assertEquals(listOf(16000, 48000, 44100), requested.first())
        assertTrue(requested.drop(1).isNotEmpty() && requested.drop(1).all { it == listOf(44100) })
    }

    @Test fun phoneAt48kHzRecordsTheRightLength() {
        supportedRates = listOf(48000, 44100)
        snoring = { t -> t in 30.0..90.0 }
        stops += 120.0
        startNight()
        awaitNightEnd()
        val s = saved(RecordingService.state.value.finishedId)
        assertTrue("duration ${s.durationSec}", s.durationSec in 119..123)
        assertTrue(s.levels.indices.filter { s.levels[it] > 0 }.all { it in 2..10 })
    }

    @Test fun fullNightAudioIsKeptInSegmentsAroundACall() {
        com.quietnight.app.audio.NightAudioPref.set(app, true)
        RecordingEnv.encoder = com.quietnight.app.audio.EncoderFactory { f, _ -> FakeEncoder(f) }
        RecordingEnv.audioSupported = { true }
        takenSec = 180.0..300.0
        stops += 420.0
        startNight()
        awaitNightEnd()
        com.quietnight.app.audio.NightAudioPref.set(app, false)
        val id = RecordingService.state.value.finishedId!!
        val segs = com.quietnight.app.audio.SegmentIndex.read(app.repository.clipDir(id))
        assertEquals(2, segs.size)
        assertEquals(0L, segs[0].startMs)
        assertTrue("first ${segs[0]}", segs[0].durationMs in 179_000L..181_000L)
        assertTrue("second ${segs[1]}", segs[1].startMs in 300_000L..340_000L)
        assertTrue(segs.all { java.io.File(app.repository.clipDir(id), it.file).readText().contains("|done:") })
        assertNull(RecordingService.state.value.notice)
    }

    @Test fun fullNightAudioTurnsOffWhenStorageRunsLow() {
        com.quietnight.app.audio.NightAudioPref.set(app, true)
        RecordingEnv.encoder = com.quietnight.app.audio.EncoderFactory { f, _ -> FakeEncoder(f) }
        RecordingEnv.audioSupported = { true }
        RecordingEnv.freeBytes = { if (now() < 120) 5_000_000_000L else 10_000_000L }
        stops += 300.0
        startNight()
        awaitNightEnd()
        com.quietnight.app.audio.NightAudioPref.set(app, false)
        val state = RecordingService.state.value
        val s = saved(state.finishedId)
        assertTrue(s.durationSec >= 299)
        val segs = com.quietnight.app.audio.SegmentIndex.read(app.repository.clipDir(s.id))
        assertEquals(1, segs.size)
        assertTrue("seg ${segs[0]}", segs[0].durationMs in 119_000L..123_000L)
        assertTrue(state.notice!!.contains("low on storage"))
    }

    @Test fun fullNightAudioOnAPhoneWithoutAnEncoderIsSkippedWithANote() {
        com.quietnight.app.audio.NightAudioPref.set(app, true)
        RecordingEnv.audioSupported = { false }
        stops += 90.0
        startNight()
        awaitNightEnd()
        com.quietnight.app.audio.NightAudioPref.set(app, false)
        val state = RecordingService.state.value
        assertTrue(saved(state.finishedId).durationSec >= 89)
        assertTrue(com.quietnight.app.audio.SegmentIndex.read(app.repository.clipDir(state.finishedId!!)).isEmpty())
        assertTrue(state.notice!!.contains("Full-night audio"))
    }

    @Test fun audioLostToAnOverrunDoesNotShrinkTheNight() {
        overrunAtSec = 100.0
        snoring = { t -> t in 200.0..260.0 }
        stops += 300.0
        startNight()
        awaitNightEnd()
        val s = saved(RecordingService.state.value.finishedId)
        // Without catching up the night would be ~8 s short and the snores ~8 s early.
        assertTrue("duration ${s.durationSec}", s.durationSec in 297..302)
        val b = s.levels.indices.filter { s.levels[it] > 0 }
        assertTrue("buckets $b", b.isNotEmpty() && b.all { it in 19..27 })
        assertTrue(s.gaps.isEmpty())
    }

    private fun wakeAt(secondsAfterStart: Long): com.quietnight.app.audio.WakeSetting {
        val t = java.time.Instant.ofEpochMilli(wallBase + secondsAfterStart * 1000).atZone(java.time.ZoneId.systemDefault())
        return com.quietnight.app.audio.WakeSetting(enabled = true, hour = t.hour, minute = t.minute)
    }

    @Test fun wakeTimeMovedEarlierDuringTheNightApplies() {
        val alarm = com.quietnight.app.audio.WakeAlarm
        alarm.update(app, wakeAt(3600))
        stops += 3000.0 // never reached
        try {
            startNight()
            while (now() < 300) Thread.sleep(2)
            alarm.update(app, wakeAt(600))
            awaitNightEnd()
        } finally {
            alarm.update(app, com.quietnight.app.audio.WakeSetting())
        }
        val state = RecordingService.state.value
        val s = saved(state.finishedId)
        assertTrue("duration ${s.durationSec}", s.durationSec in 600..603)
        assertTrue(state.notice!!.contains("wake-up time"))
    }

    @Test fun wakeAlarmTurnedOffDuringTheNightLetsItRunOn() {
        val alarm = com.quietnight.app.audio.WakeAlarm
        alarm.update(app, wakeAt(600))
        stops += 900.0
        try {
            startNight()
            while (now() < 300) Thread.sleep(2)
            alarm.update(app, com.quietnight.app.audio.WakeSetting(enabled = false, hour = 7, minute = 0))
            while (RecordingService.state.value.wakeAtMs != 0L && now() < 600) Thread.sleep(2)
            assertEquals(0L, RecordingService.state.value.wakeAtMs)
            awaitNightEnd()
        } finally {
            alarm.update(app, com.quietnight.app.audio.WakeSetting())
        }
        val s = saved(RecordingService.state.value.finishedId)
        assertTrue("duration ${s.durationSec}", s.durationSec in 899..903)
        assertNull(RecordingService.state.value.notice)
    }

    @Test fun noiseGatedMicrophoneKeepsSnoresAndMakesNoGaps() {
        noiseGate = true
        snoring = { true }
        com.quietnight.app.audio.NightAudioPref.set(app, true)
        RecordingEnv.encoder = com.quietnight.app.audio.EncoderFactory { f, _ -> FakeEncoder(f) }
        RecordingEnv.audioSupported = { true }
        stops += 300.0
        try {
            startNight()
            awaitNightEnd()
        } finally {
            com.quietnight.app.audio.NightAudioPref.set(app, false)
        }
        val s = saved(RecordingService.state.value.finishedId)
        assertTrue("gaps ${s.gaps}", s.gaps.isEmpty())
        assertTrue("duration ${s.durationSec}", s.durationSec in 299..302)
        assertTrue("snoring buckets ${s.levels.count { it > 0 }}", s.levels.count { it > 0 } >= 10)
        val segs = com.quietnight.app.audio.SegmentIndex.read(app.repository.clipDir(s.id))
        assertEquals(1, segs.size)
        assertTrue(segs[0].durationMs in 299_000L..302_000L)
    }

    @Test fun oddReadSizesAt44kHzDoNotDriftOrSplitTheNight() {
        supportedRates = listOf(44100)
        readSize = 4096
        com.quietnight.app.audio.NightAudioPref.set(app, true)
        RecordingEnv.encoder = com.quietnight.app.audio.EncoderFactory { f, _ -> FakeEncoder(f) }
        RecordingEnv.audioSupported = { true }
        stops += 1200.0
        try {
            startNight()
            awaitNightEnd()
        } finally {
            com.quietnight.app.audio.NightAudioPref.set(app, false)
        }
        val s = saved(RecordingService.state.value.finishedId)
        assertTrue("duration ${s.durationSec}", s.durationSec in 1199..1201)
        assertTrue(s.gaps.isEmpty())
        // No catch-up fired: it would have ended a segment early. Exactly two 10-minute files.
        val segs = com.quietnight.app.audio.SegmentIndex.read(app.repository.clipDir(s.id))
        assertEquals(listOf(0L, 600_000L), segs.map { it.startMs }.map { it / 100 * 100 })
    }

    @Test fun lowBatteryStopsAndSavesWithANote() {
        battery = 50 to false
        startNight()
        // The battery runs low ten minutes in.
        val watcher = Thread {
            while (now() < 600) Thread.sleep(2)
            battery = 4 to false
        }.apply { start() }
        awaitNightEnd()
        watcher.join()
        val state = RecordingService.state.value
        val s = saved(state.finishedId)
        assertTrue("duration ${s.durationSec}", s.durationSec in 600..610)
        assertNotNull(state.notice)
        assertTrue(state.notice!!.contains("battery"))
        assertEquals(s.id, state.noticeSessionId)
    }

    @Test fun startWhileThePreviousNightIsSavingIsQueuedNotLost() {
        stops += 60.0
        stops += 200.0
        startNight()
        // Wait until night one has finished on its thread but not yet handed back to the main thread.
        val deadline = System.currentTimeMillis() + 60_000
        while (RecordingService.isRecording || RecordingService.state.value.finishedId == null) {
            assertTrue(System.currentTimeMillis() < deadline); Thread.sleep(10)
        }
        val first = RecordingService.state.value.finishedId
        startNight() // a second Start before the service has processed the end of the first
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("service must not stop under the new night", shadowOf(controller.get()).isStoppedBySelf)
        while (RecordingService.state.value.finishedId == first) {
            assertTrue(System.currentTimeMillis() < deadline); Thread.sleep(10)
        }
        awaitNightEnd()
        val second = RecordingService.state.value.finishedId
        assertNotNull(second)
        assertTrue(first != second)
        assertEquals(2, app.repository.sessions.value.count { it.id == first || it.id == second })
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun stopPressedDuringMicrophoneBackoffSavesAndCountsTheGapToTheEnd() {
        takenSec = 120.0..100_000.0
        startNight()
        while (now() < 400) Thread.sleep(2)
        controller.get().onStartCommand(Intent(app, RecordingService::class.java).setAction("com.quietnight.app.STOP"), 0, 5)
        awaitNightEnd()
        val s = saved(RecordingService.state.value.finishedId)
        assertTrue("duration ${s.durationSec}", s.durationSec >= 400)
        assertEquals(120, s.gaps.single().fromSec)
        assertTrue(s.gaps.single().toSec >= 400)
    }

    @Test fun testRunStopsAfterFiveMinutesAndIsLabelled() {
        app.settings.update { it.copy(delayMin = 30) }
        controller.get().onStartCommand(
            Intent(app, RecordingService::class.java).putExtra("testRunMs", 5 * 60_000L), 0, 1,
        )
        awaitNightEnd()
        val state = RecordingService.state.value
        val s = saved(state.finishedId)
        assertTrue("duration ${s.durationSec}", s.durationSec in 300..303)
        assertEquals(RecordingService.TEST_RUN_NOTE, s.note)
        assertNull("no note on the Record tab for a test run", state.notice)
    }

    @Test fun notificationStopAfterProcessDeathKeepsTheSavedNight() {
        // A night saved 40 minutes in, then the process died; the marker is still set.
        val start = wallBase - 3_600_000L
        val partial = Session(
            id = "s$start", startMs = start, endMs = start + 40 * 60_000L, bucketSec = 10,
            levels = ByteArray(240), amps = ByteArray(240), tags = emptyList(), clips = emptyList(),
        )
        app.repository.save(partial)
        app.getSharedPreferences("recording", Context.MODE_PRIVATE).edit().putString("activeId", partial.id).commit()

        controller.get().onStartCommand(Intent(app, RecordingService::class.java).setAction("com.quietnight.app.STOP"), 0, 1)
        assertEquals(partial.endMs, saved(partial.id).interruptedAtMs)
        assertNull(marker())
        assertTrue(RecordingService.state.value.notice!!.contains("cut short"))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun activeIdIsExposedAndEditsDuringTheNightAreKept() {
        stops += 400.0
        startNight()
        // Wait for the first periodic save (every 2 simulated minutes), then edit that night.
        val deadline = System.currentTimeMillis() + 60_000
        var id: String? = null
        while (id == null || app.repository.sessions.value.none { it.id == id }) {
            assertTrue(System.currentTimeMillis() < deadline)
            id = RecordingService.state.value.activeSessionId
            Thread.sleep(2)
        }
        val partial = app.repository.sessions.value.first { it.id == id }
        app.repository.save(partial.withEdits(listOf("r:Nasal strip"), "Blocked nose tonight"))
        awaitNightEnd()
        val s = saved(id)
        assertTrue(s.durationSec >= 399)
        assertEquals(listOf("r:Nasal strip"), s.tags)
        assertEquals("Blocked nose tonight", s.note)
        assertNull(RecordingService.state.value.activeSessionId)
    }

    @Test fun recoveryWorksBeforeTheRepositoryHasLoaded() {
        // The night's file is on disk but not in the in-memory list (loading happens in the background).
        val start = wallBase - 7_200_000L
        val partial = Session(
            id = "s$start", startMs = start, endMs = start + 50 * 60_000L, bucketSec = 10,
            levels = ByteArray(300), amps = ByteArray(300), tags = listOf("f:Alcohol"), clips = emptyList(),
        )
        java.io.File(app.filesDir, "sessions").mkdirs()
        java.io.File(app.filesDir, "sessions/${partial.id}.json").writeText(partial.toJson().toString())
        app.getSharedPreferences("recording", Context.MODE_PRIVATE).edit().putString("activeId", partial.id).commit()
        assertTrue(app.repository.sessions.value.none { it.id == partial.id })

        RecordingService.recoverInterrupted(app)
        val back = Session.fromJson(org.json.JSONObject(java.io.File(app.filesDir, "sessions/${partial.id}.json").readText()))
        assertEquals(partial.endMs, back.interruptedAtMs)
        assertEquals(listOf("f:Alcohol"), back.tags)
        assertNull(marker())
    }

    @Test fun deniedMicrophoneDoesNotStartAndExplains() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        RecordingService.start(app)
        assertFalse(RecordingService.isRecording)
        assertTrue(RecordingService.state.value.error!!.contains("permission"))
        assertNull(shadowOf(app).nextStartedService)
    }
}
