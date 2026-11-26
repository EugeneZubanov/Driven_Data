package com.quietnight.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.quietnight.app.data.ReminderTime
import com.quietnight.app.data.Settings

/**
 * Daily bedtime reminder. Uses an inexact, doze-tolerant alarm (no exact-alarm permission), so it can
 * arrive a few minutes late. Each firing schedules the next one; boot, app update and clock changes re-arm it.
 */
object Reminder {
    private const val CHANNEL_ID = "reminder"
    private const val NOTIFICATION_ID = 2
    const val ACTION_FIRE = "com.quietnight.app.REMINDER"

    /** Extra on the intent that opens [MainActivity]: the tab to show (0 = Record). */
    const val EXTRA_OPEN_TAB = "com.quietnight.app.OPEN_TAB"
    const val TAB_RECORD = 0

    /** The tab an incoming intent asks for, or null if it asks for none. */
    fun requestedTab(intent: Intent?): Int? =
        intent?.takeIf { it.hasExtra(EXTRA_OPEN_TAB) }?.getIntExtra(EXTRA_OPEN_TAB, -1)?.takeIf { it in 0..3 }

    /** Intent that opens the app on the Record tab. */
    fun openRecordIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_TAB, TAB_RECORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Arms or cancels the alarm to match the saved settings. */
    fun sync(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = Settings(context).prefs.value
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        am.cancel(pi)
        if (prefs.reminderOn) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ReminderTime.next(nowMs, prefs.reminderMinute), pi)
        }
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun show(context: Context) {
        if (!canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Bedtime reminder", NotificationManager.IMPORTANCE_DEFAULT))
        // Own request code so this never overwrites another notification's PendingIntent to MainActivity.
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            openRecordIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("Time to start recording")
            .setContentText("Put the phone by your bed and press Start.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the post.
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminder.ACTION_FIRE -> {
                if (Settings(context).prefs.value.reminderOn) Reminder.show(context)
                Reminder.sync(context)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Reminder.sync(context)
        }
    }
}
