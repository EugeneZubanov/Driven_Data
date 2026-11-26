package com.quietnight.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.quietnight.app.QuietNightApp
import com.quietnight.app.Reminder

/**
 * Turns the bedtime reminder on or off and re-arms the alarm. On Android 13+ turning it on first asks for
 * the notification permission; if that is refused the reminder stays off.
 */
@Composable
fun rememberReminderToggle(app: QuietNightApp): (Boolean, Int) -> Unit {
    val context = LocalContext.current
    // Saveable so the choice survives the activity being recreated while the permission dialog is up.
    var pendingMinute by rememberSaveable { mutableIntStateOf(-1) }
    fun apply(on: Boolean, minute: Int) {
        app.settings.update { it.copy(reminderOn = on, reminderMinute = minute) }
        Reminder.sync(context)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingMinute >= 0) apply(true, pendingMinute)
        pendingMinute = -1
    }
    return { on, minute ->
        val needsPermission = on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingMinute = minute
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            apply(on, minute)
        }
    }
}
