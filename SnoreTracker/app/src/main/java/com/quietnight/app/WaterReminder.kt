package com.quietnight.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.quietnight.app.audio.RecordingPolicy
import com.quietnight.app.audio.RecordingService
import com.quietnight.app.data.WaterPlan

/**
 * Water reminders through the day. Each reminder is placed by [WaterPlan]: hourly while on track,
 * every 30 minutes with smaller drinks when catching up, none after the cut-off before bed. Inexact,
 * doze-tolerant alarms, like the bedtime reminder, so one can come a few minutes late.
 */
object WaterReminder {
    private const val CHANNEL_ID = "water"
    private const val NOTIFICATION_ID = 7
    const val ACTION_FIRE = "com.quietnight.app.WATER"
    const val ACTION_DRANK = "com.quietnight.app.WATER_DRANK"
    const val EXTRA_ML = "ml"

    private fun app(context: Context) = context.applicationContext as QuietNightApp

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 7,
        Intent(context, WaterReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Arms the next reminder from the current plan, or cancels it. Call after any change. */
    fun sync(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val water = app(context).water
        water.refresh()
        val s = water.settings.value
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        am.cancel(pi)
        if (!s.on) { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID); return }
        val st = WaterPlan.status(nowMs, water.today.value.totalMl, s)
        // Nothing more today: wake up again at tomorrow's start of day.
        val next = if (st.nextReminderMs > 0) st.nextReminderMs
        else WaterPlan.window(nowMs + 24 * 3_600_000L, s).first
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    fun show(context: Context, nowMs: Long = System.currentTimeMillis()) {
        if (!Reminder.canNotify(context)) return
        if (RecordingService.state.value.recording) return // never during a night's recording
        val water = app(context).water
        water.refresh()
        val s = water.settings.value
        val st = WaterPlan.status(nowMs, water.today.value.totalMl, s)
        if (st.state == WaterPlan.State.DONE || st.state == WaterPlan.State.AFTER_CUTOFF) return
        // The drink suggested now: what the plan wants for the coming interval, rounded like a glass.
        val amount = st.nextAmountMl.takeIf { it > 0 } ?: minOf(s.glassMl, st.remainingMl).coerceAtLeast(100)
        val is24 = DateFormat.is24HourFormat(context)
        val text = WaterPlan.message(st) { RecordingPolicy.clockText(it, is24) }

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Water reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 8, Reminder.openRecordIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val drank = PendingIntent.getBroadcast(
            context, 9,
            Intent(context, WaterReceiver::class.java).setAction(ACTION_DRANK).putExtra(EXTRA_ML, amount),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("Time for water: about $amount ml")
            .setContentText("${WaterPlan.liters(st.drankMl)} of ${WaterPlan.liters(st.targetMl)} so far today")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${WaterPlan.liters(st.drankMl)} of ${WaterPlan.liters(st.targetMl)} so far today. $text"))
            .setContentIntent(open)
            .addAction(0, "Drank $amount ml", drank)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the post.
        }
    }

    fun dismiss(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

class WaterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WaterReminder.ACTION_FIRE -> {
                WaterReminder.show(context)
                WaterReminder.sync(context)
            }
            WaterReminder.ACTION_DRANK -> {
                val ml = intent.getIntExtra(WaterReminder.EXTRA_ML, 0)
                if (ml > 0) (context.applicationContext as QuietNightApp).water.add(ml)
                WaterReminder.dismiss(context)
                WaterReminder.sync(context)
            }
        }
    }
}
