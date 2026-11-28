package com.quietnight.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietnight.app.audio.MicCheck
import com.quietnight.app.audio.RecordingEnv
import com.quietnight.app.audio.freeSpace
import com.quietnight.app.audio.NightAudioPref
import com.quietnight.app.audio.WakeAlarm
import com.quietnight.app.audio.WakeSetting
import android.os.Build
import com.quietnight.app.audio.RecState
import com.quietnight.app.audio.RecordingPolicy
import com.quietnight.app.audio.RecordingService
import com.quietnight.app.data.Prefs
import com.quietnight.app.data.Session

/** The Record tab with its Android wiring: battery optimisation, pre-start checks, mic check, dimming. */
@Composable
fun RecordTab(
    prefs: Prefs,
    rec: RecState,
    lastSession: Session?,
    onStart: () -> Unit,
    onPrefs: ((Prefs) -> Prefs) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val mic by MicCheck.state.collectAsStateWithLifecycle()

    var notificationsOn by remember { mutableStateOf(true) }
    var exempt by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        exempt = isBatteryExempt(context)
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { MicCheck.stop() }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) MicCheck.start(context)
    }
    val wake by WakeAlarm.setting(context).collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val fullAudio by NightAudioPref.state(context).collectAsStateWithLifecycle()
    val testRunPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.isNotEmpty()) RecordingService.start(context, testRun = true)
    }
    val neededToRecord = {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }

    RecordScreen(
        prefs = prefs,
        rec = rec,
        lastSession = lastSession,
        onStart = onStart,
        onStop = { RecordingService.stop(context) },
        onPrefs = onPrefs,
        onOpenSession = onOpenSession,
        batteryExempt = exempt,
        onFixBattery = { openBatterySettings(context) },
        startWarnings = {
            val (pct, charging) = RecordingService.readBattery(context)
            RecordingPolicy.preflightWarnings(pct, charging, freeSpace(context))
        },
        onDismissNotice = RecordingService::clearNotice,
        mic = mic,
        onMicCheck = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                MicCheck.start(context)
            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        wake = wake ?: WakeSetting(),
        onWake = {
            WakeAlarm.update(context, it)
            // The alarm is a notification: ask for permission when it is turned on (Android 13+).
            if (it.enabled && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        notificationsAllowed = notificationsOn,
        onTestRun = {
            val needed = neededToRecord()
            if (needed.isEmpty()) RecordingService.start(context, testRun = true) else testRunPermissions.launch(needed.toTypedArray())
        },
        manufacturer = Build.MANUFACTURER ?: "",
        fullAudio = fullAudio == true,
        fullAudioSupported = remember { RecordingEnv.audioSupported() },
        onFullAudio = { NightAudioPref.set(context, it) },
        onKeepScreenOn = { on ->
            // MainActivity holds the screen on while recording on this tab; let it go after a long dim.
            context.findActivity()?.window?.let { w ->
                if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        },
        extra = { WaterToday(context.applicationContext as com.quietnight.app.QuietNightApp) },
        onDimmed = { dim ->
            RecordingService.uiDimmed = dim
            context.findActivity()?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = if (dim) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } },
    )

    DisposableEffect(Unit) { onDispose { MicCheck.stop() } }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun isBatteryExempt(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

/**
 * Asks Android to let the app run in the background overnight. The direct prompt is fine for an app
 * that is sideloaded rather than sold in a store; if a phone does not offer it, fall back to the
 * list of apps, and then to the app's own settings page.
 */
@SuppressLint("BatteryLife")
private fun openBatterySettings(context: Context) {
    val tries = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
    )
    for (i in tries) {
        if (runCatching { context.startActivity(i) }.isSuccess) return
    }
}
