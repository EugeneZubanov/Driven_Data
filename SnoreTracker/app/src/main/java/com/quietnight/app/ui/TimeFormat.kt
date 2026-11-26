package com.quietnight.app.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Whether the device is set to 24-hour time. Read on each composition so a settings change shows on return. */
@Composable
fun rememberIs24Hour(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

/** Clock time following [is24]: "22:30" or "10:30 PM". Overload of [clock] for callers that know the setting. */
fun clock(ms: Long, is24: Boolean): String =
    SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ms))

/** A clock formatter that follows the device's 12/24-hour setting, for use inside composables. */
@Composable
fun rememberClock(): (Long) -> String {
    val is24 = rememberIs24Hour()
    return { ms -> clock(ms, is24) }
}
