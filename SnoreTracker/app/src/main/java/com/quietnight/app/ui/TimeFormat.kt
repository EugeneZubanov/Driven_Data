package com.quietnight.app.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Whether the device is set to 24-hour time. Read on each composition so a settings change shows on return. */
@Composable
fun rememberIs24Hour(): Boolean = DateFormat.is24HourFormat(LocalContext.current)
