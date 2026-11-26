package com.quietnight.app.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

object Palette {
    val Background = Color(0xFF0E1120)
    val Surface = Color(0xFF171B30)
    val SurfaceHigh = Color(0xFF222848)
    val Outline = Color(0xFF343B60)
    val Text = Color(0xFFE8EAF4)
    val Muted = Color(0xFF9AA0BD)
    val Moon = Color(0xFFF3E9C6)
    val Good = Color(0xFF4CC38A)

    val Quiet = Color(0xFF2A3152)
    val Mild = Color(0xFF3B8EEA)
    val Loud = Color(0xFFBC961C)
    val Heavy = Color(0xFFE0457B)

    /** Indexed by [com.quietnight.app.data.Level]. */
    val levels = listOf(Quiet, Mild, Loud, Heavy)
}

@Composable
fun QuietNightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Moon,
            onPrimary = Color(0xFF1B1F36),
            primaryContainer = Palette.SurfaceHigh,
            onPrimaryContainer = Palette.Text,
            secondary = Palette.Mild,
            secondaryContainer = Palette.SurfaceHigh,
            onSecondaryContainer = Palette.Text,
            background = Palette.Background,
            onBackground = Palette.Text,
            surface = Palette.Background,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Surface,
            onSurfaceVariant = Palette.Muted,
            surfaceContainer = Palette.Surface,
            surfaceContainerHigh = Palette.SurfaceHigh,
            surfaceContainerHighest = Palette.SurfaceHigh,
            surfaceContainerLow = Palette.Surface,
            outline = Palette.Outline,
            outlineVariant = Palette.Outline,
            error = Palette.Heavy,
        ),
    ) {
        // Text outside a Material surface would otherwise default to black.
        CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
    }
}
