package com.quietnight.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.quietnight.app.MainActivity
import com.quietnight.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WakeSetting(val enabled: Boolean = false, val hour: Int = 7, val minute: Int = 0)

/**
 * Optional wake-up time. The recording service is already awake all night, so it checks the clock
 * itself: no exact-alarm permission is needed on any Android version. At that time it saves the
 * night and posts an alarm notification that rings until it is opened or dismissed. If the app has
 * been closed by the phone in the night there is no alarm, which the Record tab says.
 */
object WakeAlarm {
    private const val PREFS = "wake"
    private const val CHANNEL_ID = "wake"
    private const val NOTIFICATION_ID = 6 // 2 is the bedtime reminder's

    private val _setting = MutableStateFlow<WakeSetting?>(null)

    fun setting(context: Context): StateFlow<WakeSetting?> {
        if (_setting.value == null) {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _setting.value = WakeSetting(sp.getBoolean("on", false), sp.getInt("h", 7), sp.getInt("m", 0))
        }
        return _setting
    }

    fun update(context: Context, s: WakeSetting) {
        _setting.value = s
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("on", s.enabled).putInt("h", s.hour).putInt("m", s.minute)
        }
    }

    /** Wall-clock time to stop at for a night starting at [nowMs], or 0 for none. */
    fun wakeAtFor(context: Context, nowMs: Long): Long {
        val s = setting(context).value ?: return 0
        return if (s.enabled) RecordingPolicy.nextWakeTime(nowMs, s.hour, s.minute) else 0
    }

    fun ring(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                enableVibration(true)
            },
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("Good morning")
            .setContentText("Your night has been saved. Tap to see it.")
            .setCategory(NotificationCompat.CATEGORY_ALARM) // allowed through Do Not Disturb when alarms are
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Snooze 5 min", snoozeIntent(context, 5))
            .addAction(0, "Snooze 10 min", snoozeIntent(context, 10))
            .addAction(0, "Turn off", PendingIntent.getBroadcast(context, 3, Intent(context, AlarmOffReceiver::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        // Rings again and again until the notification is opened or dismissed.
        n.flags = n.flags or Notification.FLAG_INSISTENT
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private fun snoozeIntent(context: Context, minutes: Int) = PendingIntent.getBroadcast(
        context, 10 + minutes,
        Intent(context, AlarmOffReceiver::class.java).putExtra(EXTRA_SNOOZE_MIN, minutes),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** Which AlarmManager call can ring on time on this phone. */
    enum class SnoozeMode { ALARM_CLOCK, INEXACT_WHILE_IDLE }

    /**
     * setAlarmClock needs no permission before Android 12. On 12 and later it needs exact-alarm
     * access: granted by default on 12L and below for SCHEDULE_EXACT_ALARM, and on 13+ through
     * USE_EXACT_ALARM (granted at install). If access was revoked anyway, fall back to an inexact
     * alarm that may ring a few minutes late in Doze.
     */
    fun snoozeMode(sdk: Int, canScheduleExact: Boolean): SnoozeMode =
        if (sdk < 31 || canScheduleExact) SnoozeMode.ALARM_CLOCK else SnoozeMode.INEXACT_WHILE_IDLE

    fun snooze(context: Context, minutes: Int, nowMs: Long = System.currentTimeMillis()) {
        silence(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val at = nowMs + minutes * 60_000L
        val ring = ringIntent(context)
        val canExact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            when (snoozeMode(android.os.Build.VERSION.SDK_INT, canExact)) {
                SnoozeMode.ALARM_CLOCK -> {
                    val show = PendingIntent.getActivity(context, 5, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(at, show), ring)
                }
                SnoozeMode.INEXACT_WHILE_IDLE -> am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, ring)
            }
        }.onFailure { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, ring) }
    }

    const val EXTRA_SNOOZE_MIN = "snoozeMin"
    const val ACTION_RING = "com.quietnight.app.RING"

    private fun ringIntent(context: Context) = PendingIntent.getBroadcast(
        context, 4, Intent(context, AlarmOffReceiver::class.java).setAction(ACTION_RING), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Stops the ringing and any pending snooze. */
    fun silence(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        runCatching { (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(ringIntent(context)) }
    }
}

/** Turn off, snooze, and the snoozed ring itself. */
class AlarmOffReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val snooze = intent?.getIntExtra(WakeAlarm.EXTRA_SNOOZE_MIN, 0) ?: 0
        when {
            intent?.action == WakeAlarm.ACTION_RING -> WakeAlarm.ring(context)
            snooze > 0 -> WakeAlarm.snooze(context, snooze)
            else -> WakeAlarm.silence(context)
        }
    }
}
