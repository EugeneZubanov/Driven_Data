package com.quietnight.app

import com.quietnight.app.data.Insights
import com.quietnight.app.data.ReminderTime
import com.quietnight.app.data.Session
import com.quietnight.app.data.Weight
import com.quietnight.app.data.WeightLog
import com.quietnight.app.data.WeightStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class RoundTwoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val london = TimeZone.getTimeZone("Europe/London")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, zone: TimeZone = london): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    @Test fun reminderLaterTodayOrTomorrow() {
        assertEquals(at(2026, 11, 26, 22, 30), ReminderTime.next(at(2026, 11, 26, 20, 0), 22 * 60 + 30, london))
        assertEquals(at(2026, 11, 27, 22, 30), ReminderTime.next(at(2026, 11, 26, 22, 30), 22 * 60 + 30, london)) // exactly now → tomorrow
        assertEquals(at(2026, 11, 27, 22, 30), ReminderTime.next(at(2026, 11, 26, 23, 0), 22 * 60 + 30, london))
        assertEquals(at(2027, 1, 1, 0, 15), ReminderTime.next(at(2026, 12, 31, 23, 59), 15, london)) // year end
    }

    @Test fun reminderAcrossDstKeepsWallClock() {
        // Clocks go back on 25 Oct 2026 in the UK; 22:30 should stay 22:30 local.
        val next = ReminderTime.next(at(2026, 10, 24, 23, 0), 22 * 60 + 30, london)
        val c = Calendar.getInstance(london).apply { timeInMillis = next }
        assertEquals(25, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(22, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))
        // Spring forward 29 Mar 2026: 01:30 does not exist; result is still after now and on the 29th.
        val now = at(2026, 3, 28, 23, 0)
        val spring = ReminderTime.next(now, 90, london)
        assertTrue(spring > now && spring - now < 4 * 3600_000L)
    }

    @Test fun reminderClampsBadMinutesAndLabels() {
        val now = at(2026, 11, 26, 12, 0)
        assertTrue(ReminderTime.next(now, 5000, london) > now)
        assertEquals("07:05", ReminderTime.label(7 * 60 + 5))
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            assertEquals("10:30 PM", ReminderTime.label(22 * 60 + 30, is24 = false))
            assertEquals("12:00 AM", ReminderTime.label(0, is24 = false))
            assertEquals("22", ReminderTime.hourLabel(22, is24 = true))
            assertEquals("7 AM", ReminderTime.hourLabel(7, is24 = false))
        } finally { java.util.Locale.setDefault(saved) }
    }

    @Test fun weightParsingAndUnits() {
        assertEquals(72.4f, Weight.parseKg("72,4", false)!!, 1e-4f)
        assertEquals(72.57f, Weight.parseKg("160", true)!!, 0.01f)
        assertNull(Weight.parseKg("abc", false))
        assertNull(Weight.parseKg("5", false))
        assertNull(Weight.parseKg("900", false))
        assertNull(Weight.parseKg("NaN", false))
        assertEquals("72.4 kg", Weight.format(72.4f, false))
        assertEquals("160 lb", Weight.format(72.5748f, true))
    }

    @Test fun weightPutReplacesSameDayAndSorts() {
        var log = WeightLog()
        log = Weight.put(log, 200, 80f)
        log = Weight.put(log, 100, 81f)
        log = Weight.put(log, 200, 79f)
        assertEquals(listOf(100L to 81f, 200L to 79f), log.entries.map { it.dayMs to it.kg })
        assertEquals(1, Weight.remove(log, 100).entries.size)
    }

    @Test fun weightJsonIsLenient() {
        val o = JSONObject("""{"pounds":true,"entries":[{"day":5,"kg":70},{"day":6},{"kg":70},{"day":7,"kg":9999},"x",{"day":5,"kg":71}]}""")
        val log = Weight.fromJson(o)
        assertTrue(log.pounds)
        assertEquals(1, log.entries.size)
        assertEquals(Weight.fromJson(Weight.toJson(log)), log)
    }

    @Test fun weightStorePersists() {
        val f = File(tmp.root, "w.json")
        WeightStore(f).update { Weight.put(it, 1000, 70f).copy(pounds = true) }
        val again = WeightStore(f).log.value
        assertEquals(70f, again.entries.single().kg)
        assertTrue(again.pounds)
        f.writeText("garbage")
        assertTrue(WeightStore(f).log.value.entries.isEmpty())
    }

    @Test fun rollingSeriesAveragesFullWindows() {
        val start = 1_790_000_000_000L
        val list = (0 until 9).map { i ->
            val n = 480
            val snore = if (i < 7) 48 else 96 // scores 10 then 20
            Session("s$i", start + i * 86_400_000L, start + i * 86_400_000L + n * 60_000L, 60,
                ByteArray(n) { if (it < snore) 2 else 0 }, ByteArray(n), emptyList(), emptyList())
        }
        val series = Insights.rollingSeries(list)
        assertEquals(3, series.size)
        assertEquals(10f, series[0].second, 0.01f)
        assertEquals((10f * 5 + 20f * 2) / 7, series[2].second, 0.01f)
        assertTrue(Insights.rollingSeries(list.take(6)).isEmpty())
    }
}
