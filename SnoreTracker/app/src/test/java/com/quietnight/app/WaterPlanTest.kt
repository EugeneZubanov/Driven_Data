package com.quietnight.app

import androidx.test.core.app.ApplicationProvider
import com.quietnight.app.data.WaterPlan
import com.quietnight.app.data.WaterPlan.State
import com.quietnight.app.data.WaterSettings
import com.quietnight.app.data.WaterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class WaterPlanTest {
    private val utc = TimeZone.getTimeZone("UTC")
    // Up 07:30, bed 23:00, stop 2 h before: the drinking day is 07:30-21:00 (13.5 h).
    private val s = WaterSettings(on = true, targetMl = 2000, wakeMinute = 7 * 60 + 30, bedMinute = 23 * 60, stopBeforeBedMin = 120, glassMl = 250)

    private fun at(h: Int, m: Int = 0, dayOffset: Int = 0): Long = Calendar.getInstance(utc).run {
        clear(); set(2026, Calendar.NOVEMBER, 28 + dayOffset, h, m); timeInMillis
    }
    private fun status(h: Int, m: Int, drank: Int, settings: WaterSettings = s) = WaterPlan.status(at(h, m), drank, settings, utc)

    @Test fun windowEndsBeforeBed() {
        val (start, end) = WaterPlan.window(at(12), s, utc)
        assertEquals(at(7, 30), start)
        assertEquals(at(21), end)
    }

    @Test fun onTrackWhenDrinksFollowTheDay() {
        // Halfway through the day (14:15) with half the target drunk.
        val st = status(14, 15, 1000)
        assertEquals(State.ON_TRACK, st.state)
        assertEquals(1000, st.expectedMl)
        assertEquals(2000, st.reachableMl)
        assertEquals(149, st.perHourMl) // 1000 ml over 6.75 h
        assertEquals(at(15, 15), st.nextReminderMs)
    }

    @Test fun forgotTheFirstHalfSpreadsTheRestOverTheHoursLeft() {
        // Nothing by 14:15: 2 L is still reachable within 0.5 L/h, so it is spread, not gulped.
        val st = status(14, 15, 0)
        assertEquals(State.BEHIND, st.state)
        assertEquals(2000, st.reachableMl)
        assertEquals(297, st.perHourMl)
        assertTrue(st.perHourMl <= WaterPlan.MAX_ML_PER_HOUR)
        assertEquals(300, st.nextAmountMl) // one drink per hour of about 300 ml
        // Following the plan gets there by the cut-off.
        var t = at(14, 15); var drank = 0
        while (true) {
            val p = WaterPlan.status(t, drank, s, utc)
            if (p.nextReminderMs == 0L) break
            drank += p.nextAmountMl; t = p.nextReminderMs
        }
        assertTrue("drank $drank", drank >= 1700)
    }

    @Test fun tooLateNeverAsksForMoreThanHalfALitreAnHour() {
        // Nothing by 18:00: 3 h left, at most 1.5 L can be had safely.
        val st = status(18, 0, 0)
        assertEquals(State.CANNOT_CATCH_UP, st.state)
        assertEquals(WaterPlan.MAX_ML_PER_HOUR, st.perHourMl)
        assertEquals(1500, st.reachableMl)
        assertEquals(at(18, 30), st.nextReminderMs) // every 30 min with smaller drinks
        assertEquals(250, st.nextAmountMl)
        assertTrue(st.nextAmountMl <= WaterPlan.MAX_DRINK_ML)
        val msg = WaterPlan.message(st) { "21:00" }
        assertTrue(msg, msg.contains("1.5 L") && msg.contains("start earlier tomorrow"))
    }

    @Test fun noRemindersAfterTheCutOffOrWhenDone() {
        assertEquals(State.AFTER_CUTOFF, status(21, 30, 800).state)
        assertEquals(0L, status(21, 30, 800).nextReminderMs)
        assertEquals(State.DONE, status(15, 0, 2000).state)
        assertEquals(0L, status(15, 0, 2100).nextReminderMs)
        // The last reminder of the day is never after the cut-off.
        assertEquals(0L, status(20, 30, 1500).nextReminderMs)
    }

    @Test fun beforeTheDayTheFirstReminderIsAtWakeUp() {
        val st = status(6, 0, 0)
        assertEquals(State.BEFORE_DAY, st.state)
        assertEquals(at(7, 30), st.nextReminderMs)
        assertEquals(149, st.perHourMl) // 2 L over 13.5 h
    }

    @Test fun bedAfterMidnightStillEndsTheDayByMidnight() {
        val late = s.copy(wakeMinute = 9 * 60, bedMinute = 60) // bed at 01:00
        val (_, end) = WaterPlan.window(at(12), late, utc)
        assertEquals(at(22), end) // midnight minus 2 h
    }

    @Test fun bottleCountsSuggestedSipsUntilFinished() {
        val now = at(12)
        val store = WaterStore(ApplicationProvider.getApplicationContext(), clock = { now })
        store.add(250)
        store.openBottle(1500)
        store.noteBottleSips(300); store.noteBottleSips(300)
        assertEquals(850, store.today.value.totalMl) // 250 logged + 600 estimated
        assertTrue(store.today.value.isEstimate)
        repeat(10) { store.noteBottleSips(300) }
        assertEquals(250 + 1500, store.today.value.totalMl) // never more than the bottle
        store.finishBottle()
        assertEquals(listOf(250, 1500), store.today.value.drinks) // the real amount replaces the estimate
        assertEquals(false, store.today.value.bottleOpen)
        assertEquals(false, store.today.value.isEstimate)
    }

    @Test fun bottlePutAwayCountsWhatWasDrunk() {
        val now = at(12)
        val store = WaterStore(ApplicationProvider.getApplicationContext(), clock = { now })
        store.openBottle(1500)
        store.noteBottleSips(1200)
        store.putBottleAway(0.5f)
        assertEquals(750, store.today.value.totalMl)
        assertEquals(false, store.today.value.bottleOpen)
    }

    @Test fun storeResetsAtMidnightAndUndoes() {
        var now = at(12)
        val store = WaterStore(ApplicationProvider.getApplicationContext(), clock = { now })
        store.add(250); store.add(300)
        assertEquals(550, store.today.value.totalMl)
        store.undo()
        assertEquals(250, store.today.value.totalMl)
        now = at(12, dayOffset = 1) // the next day, whatever the test machine's time zone
        store.refresh()
        assertEquals(0, store.today.value.totalMl)
    }
}
