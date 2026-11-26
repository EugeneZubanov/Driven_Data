package com.quietnight.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietnight.app.audio.MicCheck
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

    var exempt by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        exempt = isBatteryExempt(context)
        onPauseOrDispose { MicCheck.stop() }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) MicCheck.start(context)
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
            RecordingPolicy.preflightWarnings(pct, charging, context.filesDir.usableSpace)
        },
        onDismissNotice = RecordingService::clearNotice,
        mic = mic,
        onMicCheck = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                MicCheck.start(context)
            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        onDimmed = { dim -> context.findActivity()?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = if (dim) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } },
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
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )
    for (i in tries) {
        if (runCatching { context.startActivity(i) }.isSuccess) return
    }
}
