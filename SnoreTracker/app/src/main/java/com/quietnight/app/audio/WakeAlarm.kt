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
    private const val NOTIFICATION_ID = 2

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("on", s.enabled).putInt("h", s.hour).putInt("m", s.minute).apply()
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
            context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("Good morning")
            .setContentText("Your night has been saved. Tap to see it.")
            .setCategory(NotificationCompat.CATEGORY_ALARM) // allowed through Do Not Disturb when alarms are
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Turn off", PendingIntent.getBroadcast(context, 3, Intent(context, AlarmOffReceiver::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        // Rings again and again until the notification is opened or dismissed.
        n.flags = n.flags or Notification.FLAG_INSISTENT
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    fun silence(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }
}

class AlarmOffReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = WakeAlarm.silence(context)
}
