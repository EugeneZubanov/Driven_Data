package com.quietnight.app

import com.quietnight.app.audio.GapTracker
import com.quietnight.app.audio.NightRecorder
import com.quietnight.app.audio.NightRecovery
import com.quietnight.app.audio.RecordingPolicy
import com.quietnight.app.audio.RecordingPolicy.StopReason
import com.quietnight.app.audio.ServiceLifecycle
import com.quietnight.app.audio.withRecordingInfo
import com.quietnight.app.data.Gap
import com.quietnight.app.data.Session
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordingPolicyTest {
    private val hour = 3600_000L

    @Test fun safetyStopAtMaxLength() {
        assertNull(RecordingPolicy.safetyStop(8 * hour, 80, charging = true))
        assertEquals(StopReason.MAX_LENGTH, RecordingPolicy.safetyStop(12 * hour, 80, charging = true))
    }

    @Test fun safetyStopAtLowBatteryOnlyWhenUnplugged() {
        assertEquals(StopReason.LOW_BATTERY, RecordingPolicy.safetyStop(hour, 5, charging = false))
        assertNull(RecordingPolicy.safetyStop(hour, 6, charging = false))
        assertNull(RecordingPolicy.safetyStop(hour, 2, charging = true))
        assertNull(RecordingPolicy.safetyStop(hour, null, charging = false))
    }

    @Test fun wakeLockOutlivesLongestNight() {
        assertTrue(RecordingPolicy.WAKE_LOCK_MS > RecordingPolicy.MAX_NIGHT_MS)
    }

    @Test fun preflightWarnings() {
        assertTrue(RecordingPolicy.preflightWarnings(90, charging = true, freeBytes = 5_000_000_000).isEmpty())
        val unplugged = RecordingPolicy.preflightWarnings(25, charging = false, freeBytes = 5_000_000_000)
        assertEquals(1, unplugged.size)
        assertTrue(unplugged[0].contains("25%"))
        assertFalse(RecordingPolicy.preflightWarnings(90, charging = false, freeBytes = 5_000_000_000)[0].contains("%"))
        assertEquals(1, RecordingPolicy.preflightWarnings(90, charging = true, freeBytes = 10_000_000).size)
    }

    @Test fun reopenBackoffGrowsAndCaps() {
        val delays = (1..20).map(RecordingPolicy::reopenDelayMs)
        assertEquals(delays, delays.sorted())
        assertEquals(1_000L, delays.first())
        assertEquals(30_000L, delays.last())
    }

    @Test fun digitalSilence() {
        assertTrue(RecordingPolicy.isDigitalSilence(ShortArray(100), 100))
        assertFalse(RecordingPolicy.isDigitalSilence(ShortArray(100).also { it[50] = 1 }, 100))
        assertTrue(RecordingPolicy.isDigitalSilence(ShortArray(100).also { it[50] = 1 }, 50))
        assertFalse(RecordingPolicy.isDigitalSilence(ShortArray(100), 0))
    }

    @Test fun gapTrackerIgnoresBlipsAndMergesNeighbours() {
        val g = GapTracker(minGapMs = 10_000)
        g.begin(60_000); assertEquals(3_000L, g.end(63_000)) // too short to list
        assertTrue(g.snapshot(70_000).isEmpty())
        g.begin(100_000); g.begin(105_000) // second begin is ignored while open
        assertTrue(g.inGap)
        assertEquals(listOf(Gap(100, 130)), g.snapshot(130_000)) // open gap shows up to now
        g.end(160_000)
        assertFalse(g.inGap)
        g.begin(160_500); g.end(200_000)
        assertEquals(listOf(Gap(100, 200)), g.snapshot(300_000))
        assertEquals(0L, g.end(400_000)) // closing when nothing is open
    }

    private fun session(id: String = "s1", start: Long = 1_000_000L, end: Long = start + 2 * hour) =
        Session(id, start, end, 10, ByteArray(720), ByteArray(720), listOf("f:Alcohol"), emptyList())

    @Test fun recoveryKeepsSavedPartOfNight() {
        val s = session()
        val o = NightRecovery.check("s1", s, stillRunning = false)
        o as NightRecovery.Outcome.Kept
        assertEquals(s.endMs, o.cutAtMs)
        assertEquals(s.endMs, o.session.interruptedAtMs)
        assertEquals(s.levels.size, o.session.levels.size)
        assertEquals(s.tags, o.session.tags)
        assertTrue(NightRecovery.message(o) { "03:12" }.contains("03:12"))
    }

    @Test fun recoveryWhenNothingWasSaved() {
        assertEquals(NightRecovery.Outcome.Lost("s1"), NightRecovery.check("s1", null, stillRunning = false))
    }

    @Test fun noRecoveryWhenCleanOrStillRecording() {
        assertNull(NightRecovery.check(null, null, stillRunning = false))
        assertNull(NightRecovery.check("s1", session(), stillRunning = true))
    }

    @Test fun sessionJsonRoundTripsNewFieldsAndStaysCompatible() {
        val s = session().withRecordingInfo(interruptedAtMs = 5_000_000L, gaps = listOf(Gap(60, 300), Gap(900, 960)))
        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(5_000_000L, back.interruptedAtMs)
        assertEquals(listOf(Gap(60, 300), Gap(900, 960)), back.gaps)

        // Files written before these fields existed still load, and plain nights do not gain keys.
        val plain = session().toJson()
        assertFalse(plain.has("interruptedAt"))
        assertFalse(plain.has("gaps"))
        val old = Session.fromJson(plain)
        assertEquals(0L, old.interruptedAtMs)
        assertTrue(old.gaps.isEmpty())
    }

    @Test fun plannedStops() {
        assertNull(RecordingPolicy.plannedStop(4 * 60_000L, 1_000L, RecordingPolicy.TEST_RUN_MS, 0))
        assertEquals(StopReason.TEST_DONE, RecordingPolicy.plannedStop(5 * 60_000L, 1_000L, RecordingPolicy.TEST_RUN_MS, 0))
        assertNull(RecordingPolicy.plannedStop(hour, 999L, 0, 1_000L))
        assertEquals(StopReason.WAKE_TIME, RecordingPolicy.plannedStop(hour, 1_000L, 0, 1_000L))
        assertNull(RecordingPolicy.plannedStop(20 * hour, 5_000L, 0, 0))
    }

    @Test fun nextWakeTimeIsTomorrowMorningAndHandlesDaylightSaving() {
        val zone = java.time.ZoneId.of("Europe/London")
        fun ms(s: String) = java.time.ZonedDateTime.of(java.time.LocalDateTime.parse(s), zone).toInstant().toEpochMilli()
        assertEquals(ms("2026-11-27T07:00"), RecordingPolicy.nextWakeTime(ms("2026-11-26T23:10"), 7, 0, zone))
        assertEquals(ms("2026-11-27T07:00"), RecordingPolicy.nextWakeTime(ms("2026-11-27T01:30"), 7, 0, zone))
        assertEquals(ms("2026-11-28T07:00"), RecordingPolicy.nextWakeTime(ms("2026-11-27T07:00"), 7, 0, zone))
        // Clocks go back on 25 Oct 2026: 23:00 to 07:00 is 9 real hours that night.
        assertEquals(9 * hour, RecordingPolicy.nextWakeTime(ms("2026-10-24T23:00"), 7, 0, zone) - ms("2026-10-24T23:00"))
    }

    @Test fun uiUpdatesSlowDownWhenNobodyIsLooking() {
        val awake = RecordingPolicy.uiUpdateEveryMs(screenOn = true, dimmed = false)
        val dim = RecordingPolicy.uiUpdateEveryMs(screenOn = true, dimmed = true)
        val off = RecordingPolicy.uiUpdateEveryMs(screenOn = false, dimmed = false)
        assertTrue(awake <= 250 && dim > awake && off > dim)
        assertEquals(off, RecordingPolicy.uiUpdateEveryMs(screenOn = false, dimmed = true))
    }

    @Test fun snoozeUsesAlarmClockWhenAllowed() {
        assertEquals(com.quietnight.app.audio.WakeAlarm.SnoozeMode.ALARM_CLOCK, com.quietnight.app.audio.WakeAlarm.snoozeMode(26, canScheduleExact = false))
        assertEquals(com.quietnight.app.audio.WakeAlarm.SnoozeMode.ALARM_CLOCK, com.quietnight.app.audio.WakeAlarm.snoozeMode(30, canScheduleExact = false))
        assertEquals(com.quietnight.app.audio.WakeAlarm.SnoozeMode.ALARM_CLOCK, com.quietnight.app.audio.WakeAlarm.snoozeMode(34, canScheduleExact = true))
        assertEquals(com.quietnight.app.audio.WakeAlarm.SnoozeMode.INEXACT_WHILE_IDLE, com.quietnight.app.audio.WakeAlarm.snoozeMode(33, canScheduleExact = false))
    }

    @Test fun tagSummaryIsShort() {
        assertEquals("None", com.quietnight.app.ui.tagSummary(emptySet()))
        assertEquals("Side sleeping, Alcohol", com.quietnight.app.ui.tagSummary(setOf("f:Alcohol", "r:Side sleeping")))
        assertEquals("Nasal strip, Side sleeping +1", com.quietnight.app.ui.tagSummary(setOf("f:Alcohol", "r:Side sleeping", "r:Nasal strip")))
    }

    @Test fun captureRateFallsBackInOrder() {
        assertEquals(listOf(16000, 48000, 44100), com.quietnight.app.audio.CAPTURE_RATES)
        val tried = ArrayList<Int>()
        val got = com.quietnight.app.audio.firstWorkingRate(com.quietnight.app.audio.CAPTURE_RATES) { r -> tried += r; if (r == 44100) "mic@$r" else null }
        assertEquals("mic@44100", got)
        assertEquals(listOf(16000, 48000, 44100), tried)
        assertNull(com.quietnight.app.audio.firstWorkingRate(listOf(16000)) { null })
    }

    @Test fun screenMayTurnOffAfterTenMinutesDimmed() {
        assertTrue(RecordingPolicy.keepScreenOn(dimmed = false, dimmedForMs = 99 * 60_000L))
        assertTrue(RecordingPolicy.keepScreenOn(dimmed = true, dimmedForMs = 9 * 60_000L))
        assertFalse(RecordingPolicy.keepScreenOn(dimmed = true, dimmedForMs = 10 * 60_000L))
    }

    @Test fun timesFollowThe12Or24HourSetting() {
        val zone = java.time.ZoneId.of("Europe/London")
        val ms = java.time.ZonedDateTime.of(2026, 11, 27, 23, 5, 0, 0, zone).toInstant().toEpochMilli()
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.UK)
        try {
            assertEquals("23:05", RecordingPolicy.clockText(ms, is24Hour = true, zone = zone))
            assertEquals("11:05 pm", RecordingPolicy.clockText(ms, is24Hour = false, zone = zone).lowercase())
            assertEquals("06:45", RecordingPolicy.timeOfDay(6, 45, is24Hour = true))
            assertEquals("6:45 am", RecordingPolicy.timeOfDay(6, 45, is24Hour = false).lowercase())
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test fun catchUpOnlyBeyondNormalBufferingLag() {
        // A stall the mic buffer still covers must not be counted twice.
        assertEquals(0L, RecordingPolicy.catchUpMs(10_000, 10_000 - RecordingPolicy.MIC_BUFFER_SECONDS * 1_000L))
        assertTrue(RecordingPolicy.MAX_LAG_MS > RecordingPolicy.MIC_BUFFER_SECONDS * 1_000L)
        assertEquals(0L, RecordingPolicy.catchUpMs(10_000, 9_000))
        assertEquals(0L, RecordingPolicy.catchUpMs(10_000, 7_000))
        assertEquals(7_000L, RecordingPolicy.catchUpMs(20_000, 12_000))
    }

    @Test fun wakeChangeNeverLandsInThePast() {
        val zone = java.time.ZoneId.of("Europe/London")
        fun ms(s: String) = java.time.ZonedDateTime.of(java.time.LocalDateTime.parse(s), zone).toInstant().toEpochMilli()
        // At 03:00 the alarm is moved to 02:30: that means tomorrow, not "already due".
        assertEquals(ms("2026-11-28T02:30"), RecordingPolicy.wakeAtAfterChange(true, 2, 30, ms("2026-11-27T03:00"), zone))
        assertEquals(ms("2026-11-27T06:15"), RecordingPolicy.wakeAtAfterChange(true, 6, 15, ms("2026-11-27T03:00"), zone))
        assertEquals(0L, RecordingPolicy.wakeAtAfterChange(false, 6, 15, ms("2026-11-27T03:00"), zone))
    }

    @Test fun digitalSilenceIsAGapOnlyAfterSeconds() {
        assertFalse(RecordingPolicy.digitalSilenceIsGap(100))
        assertFalse(RecordingPolicy.digitalSilenceIsGap(3_900))
        assertTrue(RecordingPolicy.digitalSilenceIsGap(RecordingPolicy.DIGITAL_SILENCE_GAP_MS))
    }

    @Test fun captureSourcesFallBackFromUnprocessed() {
        val unprocessed = android.media.MediaRecorder.AudioSource.UNPROCESSED
        val voice = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
        val mic = android.media.MediaRecorder.AudioSource.MIC
        assertEquals(listOf(unprocessed, voice, mic), com.quietnight.app.audio.captureSources(true))
        assertEquals(listOf(voice, mic), com.quietnight.app.audio.captureSources(false))
        // UNPROCESSED advertised but refused at this rate: the next source is used.
        val tried = ArrayList<Int>()
        val got = com.quietnight.app.audio.firstWorkingSource(com.quietnight.app.audio.captureSources(true)) { s ->
            tried += s; if (s == unprocessed) null else "src$s"
        }
        assertEquals("src$voice", got)
        assertEquals(listOf(unprocessed, voice), tried)
    }

    @Test fun serviceLifecycle() {
        assertEquals(ServiceLifecycle.OnStart.LAUNCH, ServiceLifecycle.onStart(workerAlive = false, workerStopping = false))
        assertEquals(ServiceLifecycle.OnStart.LAUNCH, ServiceLifecycle.onStart(workerAlive = false, workerStopping = true))
        assertEquals(ServiceLifecycle.OnStart.QUEUE_AFTER_CURRENT, ServiceLifecycle.onStart(workerAlive = true, workerStopping = true))
        assertEquals(ServiceLifecycle.OnStart.IGNORE_DUPLICATE, ServiceLifecycle.onStart(workerAlive = true, workerStopping = false))
        assertEquals(ServiceLifecycle.OnWorkerEnd.RELAUNCH, ServiceLifecycle.onWorkerEnd(startQueued = true))
        assertEquals(ServiceLifecycle.OnWorkerEnd.STOP_SERVICE, ServiceLifecycle.onWorkerEnd(startQueued = false))
    }

    @Test fun skipKeepsTimelineOnWallClock() {
        val rate = 16000
        val r = NightRecorder("s", 0L, rate, 8f, delayMs = 0, tags = emptyList(), clipDir = null)
        val noise = ShortArray(rate) { ((it * 7919) % 200 - 100).toShort() }
        repeat(60) { r.feed(noise, noise.size) }
        r.skip(10 * 60_000L)
        repeat(60) { r.feed(noise, noise.size) }
        val s = r.snapshot()
        assertEquals(12 * 60, s.durationSec)
        assertEquals(72, s.levels.size)
    }

    @Test fun skipDuringFallAsleepDelayCountsTowardsIt() {
        val rate = 16000
        val r = NightRecorder("s", 0L, rate, 8f, delayMs = 60_000, tags = emptyList(), clipDir = null)
        val noise = ShortArray(rate) { ((it * 7919) % 200 - 100).toShort() }
        repeat(30) { r.feed(noise, noise.size) }
        assertFalse(r.listening)
        r.skip(45_000)
        assertTrue(r.listening)
    }
}

class MicCheckTest {
    @Test fun levelOfSilenceAndFullScale() {
        org.junit.Assert.assertEquals(-100f, com.quietnight.app.audio.MicCheck.dbfs(ShortArray(100), 100), 0.01f)
        val loud = ShortArray(100) { if (it % 2 == 0) 32767 else -32767 }
        org.junit.Assert.assertEquals(0f, com.quietnight.app.audio.MicCheck.dbfs(loud, 100), 0.1f)
        org.junit.Assert.assertEquals(0f, com.quietnight.app.audio.MicCheck.barFraction(-90f), 0f)
        org.junit.Assert.assertEquals(1f, com.quietnight.app.audio.MicCheck.barFraction(-10f), 0f)
    }

    @Test fun heardNeedsAClearRiseAboveTheRoom() {
        val room = List(10) { -62f }
        org.junit.Assert.assertFalse(com.quietnight.app.audio.MicCheck.heard(room + List(20) { -55f }))
        org.junit.Assert.assertTrue(com.quietnight.app.audio.MicCheck.heard(room + List(20) { -62f } + listOf(-45f)))
        org.junit.Assert.assertFalse(com.quietnight.app.audio.MicCheck.heard(room))
        // A loud first second sets a high baseline rather than counting as heard.
        org.junit.Assert.assertFalse(com.quietnight.app.audio.MicCheck.heard(List(10) { -40f } + List(10) { -45f }))
    }
}
