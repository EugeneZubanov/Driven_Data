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

    /** "22:30" style label for a minute of the day. */
    fun label(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}
