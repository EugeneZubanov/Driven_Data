package com.quietnight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private class IntroPage(val icon: ImageVector, val title: String, val body: String)

private val pages = listOf(
    IntroPage(
        Icons.Filled.Bed, "Put the phone near your head",
        "Place it on a bedside table or the floor beside the bed, within about an arm's length, " +
            "microphone facing you. Keep the same spot each night so nights compare fairly.",
    ),
    IntroPage(
        Icons.Filled.BatteryChargingFull, "Keep it plugged in",
        "Recording runs all night with the screen off. Plug in the charger so the battery lasts until " +
            "morning. Audio is analysed on the phone and never leaves it.",
    ),
    IntroPage(
        Icons.Filled.BarChart, "What the score means",
        "The snore score is the share of the night spent snoring, with louder snoring counting more. " +
            "Lower is quieter. It is an estimate from sound, not a medical measurement. If you are worried " +
            "about pauses in breathing or daytime sleepiness, talk to a doctor.",
    ),
)

/** Short, skippable first-run introduction. [startPage] is for previews and tests. */
@Composable
fun IntroScreen(onDone: () -> Unit, startPage: Int = 0) {
    var page by rememberSaveable { mutableIntStateOf(startPage.coerceIn(0, pages.lastIndex)) }
    val p = pages[page]
    val last = page == pages.lastIndex
    Column(Modifier.fillMaxSize().background(Palette.Background).systemBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Quiet Night", color = Palette.Muted, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (!last) TextButton(onDone) { Text("Skip", color = Palette.Muted) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(Palette.Surface), contentAlignment = Alignment.Center) {
                Icon(p.icon, null, tint = Palette.Moon, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text("Step ${page + 1} of ${pages.size}", color = Palette.Muted, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(p.body, color = Palette.Muted, style = MaterialTheme.typography.bodyLarge)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { i ->
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == page) Palette.Moon else Palette.Outline))
                }
            }
            if (page > 0) TextButton({ page-- }) { Text("Back") }
            Spacer(Modifier.size(8.dp))
            Button({ if (last) onDone() else page++ }) { Text(if (last) "Start using Quiet Night" else "Next") }
        }
    }
}
