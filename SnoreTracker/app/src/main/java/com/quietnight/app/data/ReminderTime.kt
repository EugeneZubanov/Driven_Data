package com.quietnight.app.data

import java.util.Calendar
import java.util.TimeZone

/** Scheduling maths for the daily bedtime reminder, kept free of Android types so it can be unit tested. */
object ReminderTime {
    /**
     * The next moment strictly after [nowMs] whose local wall-clock time is [minuteOfDay]. On a DST
     * change day a time that does not exist (spring forward) resolves to the hour after.
     */
    fun next(nowMs: Long, minuteOfDay: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= nowMs) {
            c.add(Calendar.DAY_OF_MONTH, 1)
            c.set(Calendar.HOUR_OF_DAY, m / 60)
            c.set(Calendar.MINUTE, m % 60)
        }
        return c.timeInMillis
    }

    /** "22:30", or "10:30 PM" (in the locale's words) when [is24] is false. */
    fun label(minuteOfDay: Int, is24: Boolean = true): String = format(minuteOfDay, if (is24) "HH:mm" else "h:mm a")

    /** Clock-hour label for chart axes: "22", or "10 PM" when [is24] is false. */
    fun hourLabel(hour: Int, is24: Boolean): String = format(hour * 60, if (is24) "HH" else "h a")

    private fun format(minuteOfDay: Int, pattern: String): String {
        val utc = TimeZone.getTimeZone("UTC")
        val f = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).apply { timeZone = utc }
        return f.format(java.util.Date(minuteOfDay.coerceIn(0, 24 * 60 - 1) * 60_000L))
    }
}
