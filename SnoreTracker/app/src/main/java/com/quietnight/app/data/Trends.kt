package com.quietnight.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class Period { DAYS, WEEKS, MONTHS }

/** Sessions that fall in one day, week or month, with their averaged score. */
class TrendBar(val key: Long, val label: String, val longLabel: String, val sessions: List<Session>) {
    /** Average score contribution per level (mild, loud, heavy). */
    val parts: FloatArray = FloatArray(3) { i -> sessions.map { it.scoreParts[i] }.average().toFloat() }
    val score: Int = parts.sum().roundToInt()
}

class TagEffect(val tag: String, val nights: Int, val withAvg: Float, val withoutAvg: Float) {
    val delta: Float get() = withAvg - withoutAvg
}

object Trends {
    /** The night a session belongs to is the calendar day it ended on. */
    fun dayOf(s: Session): Calendar = Calendar.getInstance().apply {
        timeInMillis = s.endMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    fun bars(sessions: List<Session>, period: Period, maxBars: Int): List<TrendBar> {
        val grouped = sessions.groupBy { s ->
            val c = dayOf(s)
            when (period) {
                Period.DAYS -> {}
                Period.WEEKS -> c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
                Period.MONTHS -> c.set(Calendar.DAY_OF_MONTH, 1)
            }
            c.timeInMillis
        }
        return grouped.toSortedMap().entries.toList().takeLast(maxBars).map { (key, list) ->
            val d = Date(key)
            val (short, long) = when (period) {
                Period.DAYS -> fmt("d MMM", d) to fmt("EEEE d MMMM", d)
                Period.WEEKS -> fmt("d MMM", d) to "Week of ${fmt("d MMMM", d)}"
                Period.MONTHS -> fmt("MMM", d) to fmt("MMMM yyyy", d)
            }
            TrendBar(key, short, long, list)
        }
    }

    fun tagEffects(sessions: List<Session>): List<TagEffect> {
        if (sessions.size < 2) return emptyList()
        return sessions.flatMap { it.tags }.distinct().mapNotNull { tag ->
            val (with, without) = sessions.partition { tag in it.tags }
            if (with.isEmpty() || without.isEmpty()) null
            else TagEffect(tag, with.size, with.map { it.score }.average().toFloat(), without.map { it.score }.average().toFloat())
        }.sortedBy { it.delta }
    }

    private fun fmt(pattern: String, d: Date) = SimpleDateFormat(pattern, Locale.getDefault()).format(d)
}

fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${sec}s"
}
