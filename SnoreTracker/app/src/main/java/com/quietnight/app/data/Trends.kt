package com.quietnight.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Period { DAYS, WEEKS, MONTHS }

/** Sessions that fall in one day, week or month, with their averaged score. Empty for a period with no recording. */
class TrendBar(val key: Long, val label: String, val longLabel: String, val sessions: List<Session>) {
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** Average score contribution per level (mild, loud, heavy); zeros when [isEmpty]. */
    val parts: FloatArray = FloatArray(3) { i -> if (sessions.isEmpty()) 0f else sessions.map { it.scoreParts[i] }.average().toFloat() }
    val score: Int = parts.sum().roundToInt()
}

/**
 * Average score on nights with a tag against nights without it. [low]..[high] is a rough 95% range for the
 * difference (Welch's normal approximation); it only describes what went together, not what caused what.
 */
class TagEffect(
    val tag: String,
    val nights: Int,
    val withoutNights: Int,
    val withAvg: Float,
    val withoutAvg: Float,
    val low: Float,
    val high: Float,
) {
    val delta: Float get() = withAvg - withoutAvg

    /** True when the whole range sits on one side of zero. */
    val consistent: Boolean get() = low > 0f || high < 0f
}

/** A tag that has been used, but not on enough nights (or not enough nights without it) to compare. */
class TagPending(val tag: String, val nights: Int, val withoutNights: Int)

class TagEffects(val effects: List<TagEffect>, val pending: List<TagPending>)

object Trends {
    /** Nights needed both with and without a tag before a comparison is shown. */
    const val MIN_NIGHTS = 5

    /** The night a session belongs to is the calendar day it ended on. */
    fun dayOf(s: Session): Calendar = dayOf(s.endMs)

    fun dayOf(ms: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** Start of the period containing [day] (a local midnight). Weeks start on the locale's first day of week. */
    fun periodStart(day: Calendar, period: Period): Calendar {
        val c = day.clone() as Calendar
        when (period) {
            Period.DAYS -> {}
            // Step back day by day rather than set(DAY_OF_WEEK), whose field resolution misbehaves across
            // month and year boundaries.
            Period.WEEKS -> c.add(Calendar.DAY_OF_MONTH, -((c.get(Calendar.DAY_OF_WEEK) - c.firstDayOfWeek + 7) % 7))
            Period.MONTHS -> c.set(Calendar.DAY_OF_MONTH, 1)
        }
        return c
    }

    private fun next(c: Calendar, period: Period) = c.add(
        when (period) { Period.DAYS -> Calendar.DAY_OF_MONTH; Period.WEEKS -> Calendar.WEEK_OF_YEAR; Period.MONTHS -> Calendar.MONTH },
        1,
    )

    /**
     * The last [maxBars] consecutive periods ending at the most recent recorded one, including periods with no
     * recording (as empty bars) so gaps stay visible. Leading empty periods are dropped.
     */
    fun bars(sessions: List<Session>, period: Period, maxBars: Int): List<TrendBar> {
        if (sessions.isEmpty()) return emptyList()
        val grouped = sessions.groupBy { periodStart(dayOf(it), period).timeInMillis }
        val keys = grouped.keys.sorted()
        val last = keys.last()
        // Walk forward from the first period so DST changes never knock keys off local midnight.
        val all = ArrayList<Long>()
        val c = Calendar.getInstance().apply { timeInMillis = keys.first() }
        while (c.timeInMillis <= last) {
            all += c.timeInMillis
            next(c, period)
            c.timeInMillis = periodStart(dayOf(c.timeInMillis), period).timeInMillis
        }
        val now = Calendar.getInstance()
        return all.takeLast(maxBars).map { key ->
            val d = Date(key)
            val thisYear = Calendar.getInstance().apply { timeInMillis = key }.get(Calendar.YEAR) == now.get(Calendar.YEAR)
            val (short, long) = when (period) {
                Period.DAYS -> fmt("d MMM", d) to fmt(if (thisYear) "EEEE d MMMM" else "EEEE d MMMM yyyy", d)
                Period.WEEKS -> fmt("d MMM", d) to "Week of ${fmt(if (thisYear) "d MMMM" else "d MMMM yyyy", d)}"
                Period.MONTHS -> fmt("MMM", d) to fmt("MMMM yyyy", d)
            }
            TrendBar(key, short, long, grouped[key].orEmpty())
        }.dropWhile { it.isEmpty }
    }

    fun tagEffects(sessions: List<Session>, minNights: Int = MIN_NIGHTS): TagEffects {
        val effects = ArrayList<TagEffect>()
        val pending = ArrayList<TagPending>()
        for (tag in sessions.flatMap { it.tags }.distinct()) {
            val (with, without) = sessions.partition { tag in it.tags }
            if (with.size < minNights || without.size < minNights) {
                pending += TagPending(tag, with.size, without.size)
                continue
            }
            val a = with.map { it.score.toDouble() }
            val b = without.map { it.score.toDouble() }
            val diff = a.average() - b.average()
            val se = sqrt(variance(a) / a.size + variance(b) / b.size)
            effects += TagEffect(
                tag, with.size, without.size, a.average().toFloat(), b.average().toFloat(),
                (diff - 1.96 * se).toFloat(), (diff + 1.96 * se).toFloat(),
            )
        }
        return TagEffects(effects.sortedBy { it.delta }, pending.sortedByDescending { it.nights })
    }

    /** Sample variance (n - 1). */
    internal fun variance(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return x.sumOf { (it - m) * (it - m) } / (x.size - 1)
    }

    private fun fmt(pattern: String, d: Date) = SimpleDateFormat(pattern, Locale.getDefault()).format(d)
}

fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${sec}s"
}
