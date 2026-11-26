package com.quietnight.app

import com.quietnight.app.data.Direction
import com.quietnight.app.data.Insights
import com.quietnight.app.data.Period
import com.quietnight.app.data.Session
import com.quietnight.app.data.Trends
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TrendsTest {
    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone

    @Before fun setUp() {
        savedLocale = Locale.getDefault(); savedZone = TimeZone.getDefault()
        Locale.setDefault(Locale.UK) // weeks start on Monday
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    }

    @After fun tearDown() {
        Locale.setDefault(savedLocale); TimeZone.setDefault(savedZone)
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 7, min: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    /** A night ending at [endMs] lasting [hours], with [snoreBuckets] buckets at [level] from the start. */
    private fun night(
        endMs: Long, hours: Int = 8, snoreBuckets: Int = 0, level: Int = 2, tags: List<String> = emptyList(), id: String = "s$endMs",
    ): Session {
        val bucket = 60
        val n = hours * 60
        val levels = ByteArray(n) { if (it < snoreBuckets) level.toByte() else 0 }
        return Session(id, endMs - n * bucket * 1000L, endMs, bucket, levels, ByteArray(n), tags, emptyList())
    }

    @Test fun weeksAcrossYearEndShareOneBucketMondayFirst() {
        // Mon 28 Dec 2026 … Sun 3 Jan 2027 is one week in the UK.
        val bars = Trends.bars(listOf(night(at(2026, 12, 29)), night(at(2027, 1, 3)), night(at(2027, 1, 4))), Period.WEEKS, 8)
        assertEquals(2, bars.size)
        assertEquals(2, bars[0].sessions.size)
        assertEquals(at(2026, 12, 28, 0), bars[0].key)
        assertEquals(at(2027, 1, 4, 0), bars[1].key)
    }

    @Test fun weeksStartOnSundayInUsLocale() {
        Locale.setDefault(Locale.US)
        val bars = Trends.bars(listOf(night(at(2027, 1, 2)), night(at(2027, 1, 3))), Period.WEEKS, 8)
        assertEquals(2, bars.size)
        assertEquals(at(2026, 12, 27, 0), bars[0].key) // Sunday before New Year
        assertEquals(at(2027, 1, 3, 0), bars[1].key)
    }

    @Test fun periodStartWorksForEveryDayOfAYear() {
        val c = Calendar.getInstance().apply { timeInMillis = at(2026, 1, 1, 0) }
        repeat(400) {
            val start = Trends.periodStart(c, Period.WEEKS)
            assertEquals(Calendar.MONDAY, start.get(Calendar.DAY_OF_WEEK))
            val diff = (c.timeInMillis - start.timeInMillis) / 3600_000.0
            assertTrue("gap $diff at ${c.time}", diff in 0.0..(6 * 24 + 1.0))
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    @Test fun daysIncludeEmptyGapsAndTrimToMax() {
        val bars = Trends.bars(listOf(night(at(2026, 11, 1)), night(at(2026, 11, 4), snoreBuckets = 48)), Period.DAYS, 14)
        assertEquals(4, bars.size)
        assertTrue(bars[1].isEmpty && bars[2].isEmpty)
        assertEquals(0, bars[1].score) // no NaN from an empty average
        assertEquals(10, bars[3].score) // 48 of 480 minutes loud = 10
        // The last two days are the 3rd (empty, dropped as leading) and the 4th.
        assertEquals(1, Trends.bars(listOf(night(at(2026, 11, 1)), night(at(2026, 11, 4))), Period.DAYS, 2).size)
    }

    @Test fun leadingEmptyPeriodsAreDropped() {
        val bars = Trends.bars(listOf(night(at(2026, 11, 1)), night(at(2026, 11, 20))), Period.DAYS, 5)
        // The last five days are 16..20; only the 20th has data, so the bars start there.
        assertEquals(1, bars.size)
        assertFalse(bars[0].isEmpty)
    }

    @Test fun singleNightGivesOneBar() {
        assertEquals(1, Trends.bars(listOf(night(at(2026, 11, 1))), Period.MONTHS, 6).size)
        assertTrue(Trends.bars(emptyList(), Period.DAYS, 6).isEmpty())
    }

    @Test fun monthsSpanYearEnd() {
        val bars = Trends.bars(listOf(night(at(2026, 11, 30)), night(at(2027, 2, 1))), Period.MONTHS, 6)
        assertEquals(listOf(at(2026, 11, 1, 0), at(2026, 12, 1, 0), at(2027, 1, 1, 0), at(2027, 2, 1, 0)), bars.map { it.key })
    }

    @Test fun tagEffectNeedsEnoughNightsBothWays() {
        val few = (1..4).map { night(at(2026, 11, it), snoreBuckets = 100, tags = listOf("f:Alcohol")) } +
            (5..20).map { night(at(2026, 11, it), snoreBuckets = 10) }
        val r = Trends.tagEffects(few)
        assertTrue(r.effects.isEmpty())
        assertEquals(1, r.pending.size)
        assertEquals(4, r.pending[0].nights)
    }

    @Test fun tagEffectRangeAndConsistency() {
        // Clearly louder with the tag, little spread.
        val clear = (1..6).map { night(at(2026, 11, it), snoreBuckets = 120 + it, tags = listOf("f:Alcohol")) } +
            (7..14).map { night(at(2026, 11, it), snoreBuckets = 20 + it) }
        val e = Trends.tagEffects(clear).effects.single()
        assertTrue(e.delta > 15f)
        assertTrue(e.low > 0f && e.high > e.low)
        assertTrue(e.consistent)

        // Same averages on both sides with lots of spread: not consistent.
        val noisy = (1..12).map { i -> night(at(2026, 11, i), snoreBuckets = if (i % 2 == 0) 200 else 0, tags = if (i % 4 < 2) listOf("r:Humidifier") else emptyList()) }
        val n = Trends.tagEffects(noisy).effects.single()
        assertFalse(n.consistent)
        assertTrue(n.low < 0f && n.high > 0f)
    }

    @Test fun varianceIsSampleVariance() {
        assertEquals(2.5, Trends.variance(listOf(1.0, 2.0, 3.0, 4.0, 5.0)), 1e-9)
        assertEquals(0.0, Trends.variance(listOf(3.0)), 0.0)
    }

    @Test fun hourlyOrdersFromEveningAndNormalises() {
        // 22:00–06:00; first hour fully loud.
        val s = night(at(2026, 11, 2, 6), hours = 8, snoreBuckets = 60, level = 3)
        val slots = Insights.hourly(listOf(s))
        assertEquals(listOf(22, 23, 0, 1, 2, 3, 4, 5), slots.map { it.hour })
        assertEquals(1f, slots[0].share(2), 1e-6f)
        assertEquals(0f, slots[1].totalShare, 1e-6f)
        assertTrue(Insights.hourly(emptyList()).isEmpty())
    }

    @Test fun weekdayUsesTheEveningTheNightStarted() {
        // Started 00:30 Saturday 7 Nov 2026 → Friday night.
        val s = night(at(2026, 11, 7, 8, 30), hours = 8)
        assertEquals(Calendar.FRIDAY, Insights.eveningOf(s).get(Calendar.DAY_OF_WEEK))
        val stats = Insights.byWeekday(listOf(s))
        assertEquals(Calendar.MONDAY, stats.first().dayOfWeek)
        assertEquals(1, stats.first { it.dayOfWeek == Calendar.FRIDAY }.nights)
        assertEquals(7, stats.size)
    }

    @Test fun quietRunBestWorstAndRolling() {
        val scores = listOf(30, 5, 0, 40, 8, 2, 9) // oldest first; the last three are quiet
        val list = scores.mapIndexed { i, sc -> night(at(2026, 11, i + 1), snoreBuckets = sc * 480 / 100) }
        assertEquals(3, Insights.quietRun(list))
        assertEquals(0, Insights.best(list)!!.score)
        assertEquals(40, Insights.worst(list)!!.score)
        val r = Insights.rolling(list)!!
        assertEquals(7, r.nights)
        assertNull(r.direction) // needs 14 nights

        val fourteen = (1..14).map { night(at(2026, 10, it), snoreBuckets = if (it <= 7) 192 else 48) } // 40 then 10
        val r2 = Insights.rolling(fourteen)!!
        assertEquals(10f, r2.average, 0.01f)
        assertEquals(Direction.DOWN, r2.direction)
        assertNull(Insights.rolling(emptyList()))
    }

    @Test fun scoreBands() {
        assertEquals(listOf(0, 0, 1, 2, 2, 3, 4, 4), listOf(0, 9, 10, 20, 34, 35, 50, 100).map(Insights::band))
    }

    @Test fun byDayKeysOnEndDay() {
        val s = night(at(2026, 11, 3, 6))
        assertEquals(s.id, Insights.byDay(listOf(s))[at(2026, 11, 3, 0)]!!.id)
    }
}
