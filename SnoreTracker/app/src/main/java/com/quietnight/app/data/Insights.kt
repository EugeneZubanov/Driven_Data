package com.quietnight.app.data

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Snoring by clock hour. For each hour, [recordedSec] is how long nights were being recorded in it and
 * [snoringSec] how much of that was snoring at each level (mild, loud, heavy).
 */
class HourSlot(val hour: Int, val recordedSec: Long, val snoringSec: LongArray) {
    /** Share of recorded time in this hour spent snoring, per level, 0..1. */
    fun share(level: Int): Float = if (recordedSec == 0L) 0f else snoringSec[level].toFloat() / recordedSec
    val totalShare: Float get() = share(0) + share(1) + share(2)
}

class WeekdayStat(val dayOfWeek: Int, val nights: Int, val avgScore: Float)

enum class Direction { DOWN, STEADY, UP }

/** Average of the last [window] nights against the [window] nights before them. */
class Rolling(val average: Float, val previous: Float?, val nights: Int) {
    val direction: Direction?
        get() = previous?.let {
            val d = average - it
            when {
                d <= -Insights.STEADY_BAND -> Direction.DOWN
                d >= Insights.STEADY_BAND -> Direction.UP
                else -> Direction.STEADY
            }
        }
}

object Insights {
    /** A quiet night scores at or below this. */
    const val QUIET_SCORE = 10

    /** Rolling averages closer than this count as steady. */
    const val STEADY_BAND = 3f

    /**
     * Snoring by clock hour over the most recent [nights] nights. Returns the run of hours that were
     * recorded in, in night order (e.g. 22, 23, 0, 1 … 7), so the chart starts in the evening.
     */
    fun hourly(sessions: List<Session>, nights: Int = 30): List<HourSlot> {
        val recent = sessions.sortedByDescending { it.startMs }.take(nights)
        val rec = LongArray(24)
        val snore = Array(24) { LongArray(3) }
        val c = Calendar.getInstance()
        for (s in recent) {
            if (s.bucketSec <= 0) continue
            for (i in s.levels.indices) {
                c.timeInMillis = s.startMs + i * s.bucketSec * 1000L
                val h = c.get(Calendar.HOUR_OF_DAY)
                rec[h] += s.bucketSec.toLong()
                val l = s.levels[i].toInt()
                if (l in 1..3) snore[h][l - 1] += s.bucketSec.toLong()
            }
        }
        // Order hours from noon so a night reads left to right, then trim unrecorded hours at both ends.
        val order = (12 until 24) + (0 until 12)
        val first = order.indexOfFirst { rec[it] > 0 }
        if (first < 0) return emptyList()
        val last = order.indexOfLast { rec[it] > 0 }
        return order.subList(first, last + 1).map { HourSlot(it, rec[it], snore[it]) }
    }

    /**
     * The evening a night started on: a session that starts before noon belongs to the previous evening,
     * so "Friday night" includes a recording started at 00:30 on Saturday.
     */
    fun eveningOf(s: Session): Calendar = Trends.dayOf(s.startMs - 12 * 3600_000L)

    /** Average score per weekday (by the evening the night started), in the locale's week order. */
    fun byWeekday(sessions: List<Session>): List<WeekdayStat> {
        val grouped = sessions.groupBy { eveningOf(it).get(Calendar.DAY_OF_WEEK) }
        val first = Calendar.getInstance().firstDayOfWeek
        return (0 until 7).map { i ->
            val dow = (first - 1 + i) % 7 + 1
            val list = grouped[dow].orEmpty()
            WeekdayStat(dow, list.size, if (list.isEmpty()) 0f else list.map { it.score }.average().toFloat())
        }
    }

    fun best(sessions: List<Session>): Session? = sessions.minWithOrNull(compareBy<Session> { it.score }.thenByDescending { it.startMs })
    fun worst(sessions: List<Session>): Session? = sessions.maxWithOrNull(compareBy<Session> { it.score }.thenBy { it.startMs })

    /** Recorded nights in a row, counting back from the latest, that scored [QUIET_SCORE] or less. */
    fun quietRun(sessions: List<Session>): Int =
        sessions.sortedByDescending { it.startMs }.takeWhile { it.score <= QUIET_SCORE }.size

    fun rolling(sessions: List<Session>, window: Int = 7): Rolling? {
        val sorted = sessions.sortedByDescending { it.startMs }
        if (sorted.isEmpty()) return null
        val cur = sorted.take(window)
        val prev = sorted.drop(window).take(window)
        return Rolling(
            cur.map { it.score }.average().toFloat(),
            if (prev.size == window) prev.map { it.score }.average().toFloat() else null,
            cur.size,
        )
    }

    /** Score band 0..4 used to shade the calendar: 0–9, 10–19, 20–34, 35–49, 50+. */
    val bandFloors = intArrayOf(0, 10, 20, 35, 50)

    fun band(score: Int): Int = bandFloors.indexOfLast { score >= it }.coerceAtLeast(0)

    /** Sessions keyed by the calendar day they ended on (local midnight millis); latest session wins. */
    fun byDay(sessions: List<Session>): Map<Long, Session> =
        sessions.sortedBy { it.startMs }.associateBy { Trends.dayOf(it).timeInMillis }

    fun Float.pct(): Int = (this * 100).roundToInt()
}
