package com.quietnight.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietnight.app.QuietNightApp
import com.quietnight.app.WaterReminder
import com.quietnight.app.data.ReminderTime
import com.quietnight.app.data.WaterPlan
import com.quietnight.app.data.WaterSettings
import com.quietnight.app.data.WaterStore
import kotlinx.coroutines.delay

/** "Water today" on the Record tab, wired to the app's water store. Shows nothing while reminders are off. */
@Composable
fun WaterToday(app: QuietNightApp) {
    val context = LocalContext.current
    val settings by app.water.settings.collectAsStateWithLifecycle()
    val today by app.water.today.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Pick up drinks logged from the notification and the day changing at midnight.
    LifecycleResumeEffect(Unit) { app.water.refresh(); now = System.currentTimeMillis(); onPauseOrDispose {} }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis(); app.water.refresh() } }
    if (!settings.on) return
    Spacer(Modifier.height(12.dp))
    val changed = { WaterReminder.dismiss(context); WaterReminder.sync(context) }
    WaterTodayCard(
        settings, today, now, rememberIs24Hour(),
        onAdd = { app.water.add(it); changed() },
        onUndo = { app.water.undo(); WaterReminder.sync(context) },
        onOpenBottle = { app.water.openBottle(settings.bottleSizeMl); WaterReminder.sync(context) },
        onFinishBottle = { app.water.finishBottle(); changed() },
        onPutBottleAway = { app.water.putBottleAway(it); changed() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaterTodayCard(
    settings: WaterSettings, today: WaterStore.Today, nowMs: Long, is24: Boolean,
    onAdd: (Int) -> Unit, onUndo: () -> Unit,
    onOpenBottle: () -> Unit = {}, onFinishBottle: () -> Unit = {}, onPutBottleAway: (Float) -> Unit = {},
) {
    val st = WaterPlan.status(nowMs, today.totalMl, settings)
    var askingLeft by remember { mutableStateOf(false) }
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Water today", Modifier.weight(1f))
                Text(
                    "${if (today.isEstimate) "About " else ""}${WaterPlan.liters(today.totalMl)} of ${WaterPlan.liters(settings.targetMl)}",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { st.fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Palette.Mild, trackColor = Palette.Quiet, drawStopIndicator = {},
            )
            Spacer(Modifier.height(8.dp))
            Text(WaterPlan.message(st) { ReminderTime.label(minuteOf(it), is24) }, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            if (st.nextReminderMs > 0 && st.nextAmountMl > 0) {
                Text(
                    "Next reminder ${ReminderTime.label(minuteOf(st.nextReminderMs), is24)}: about ${st.nextAmountMl} ml" +
                        if (today.bottleOpen) " from your bottle." else ".",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (today.bottleOpen) {
                Spacer(Modifier.height(10.dp))
                Text("${WaterPlan.liters(today.bottleMl)} bottle on the go", fontWeight = FontWeight.Medium)
                Text(
                    "Reminders ask for a few sips and count them as an estimate (about ${WaterPlan.liters(today.bottleSoFarMl)} so far). " +
                        "Mark it when it's empty and the whole bottle is counted instead.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (today.bottleOpen) {
                    OutlinedButton(onFinishBottle) { Text("Finished the bottle") }
                    TextButton({ askingLeft = true }) { Text("Put it away") }
                } else {
                    OutlinedButton({ onAdd(settings.glassMl) }) { Text("+ Glass (${settings.glassMl} ml)") }
                    OutlinedButton({ onAdd(maxOf(100, settings.glassMl / 2)) }) { Text("+ ${maxOf(100, settings.glassMl / 2)} ml") }
                    OutlinedButton(onOpenBottle) { Text("Started a ${WaterPlan.liters(settings.bottleSizeMl)} bottle") }
                    if (today.drinks.isNotEmpty()) TextButton(onUndo) { Text("Undo") }
                }
            }
        }
    }
    if (askingLeft) {
        AlertDialog(
            onDismissRequest = { askingLeft = false },
            title = { Text("How much is left?") },
            text = {
                Column {
                    Text("Roughly is fine. What you drank is counted and the estimate is dropped.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    listOf("Most of it" to 0.25f, "About half" to 0.5f, "A little" to 0.75f).forEach { (label, drunk) ->
                        TextButton({ askingLeft = false; onPutBottleAway(drunk) }) {
                            Text("$label (counts ${WaterPlan.liters((today.bottleMl * drunk).toInt())})")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ askingLeft = false }) { Text("Cancel") } },
        )
    }
}

/** Minute of the local day for [ms], to reuse the 12/24-hour label of the reminder settings. */
private fun minuteOf(ms: Long): Int = java.util.Calendar.getInstance().run {
    timeInMillis = ms
    get(java.util.Calendar.HOUR_OF_DAY) * 60 + get(java.util.Calendar.MINUTE)
}

/** Settings card for water reminders. Turning them on asks for the notification permission on Android 13+. */
@Composable
fun WaterSettings(app: QuietNightApp) {
    val context = LocalContext.current
    val s by app.water.settings.collectAsStateWithLifecycle()
    fun apply(t: (WaterSettings) -> WaterSettings) { app.water.updateSettings(t); WaterReminder.sync(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) apply { it.copy(on = true) }
    }
    WaterSettingsCard(s, rememberIs24Hour()) { t ->
        val next = t(s)
        val needsPermission = next.on && !s.on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            app.water.updateSettings { next.copy(on = false) } // keep the other choices; on once allowed
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else apply { next }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WaterSettingsCard(s: WaterSettings, is24: Boolean, onChange: ((WaterSettings) -> WaterSettings) -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) }
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Water reminders")
                    Text(
                        "Reminders spread your drinks over the day. If you fall behind, the rest is spread over the " +
                            "hours left, never more than about half a litre an hour.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(s.on, { on -> onChange { it.copy(on = on) } }, Modifier.semantics { contentDescription = "Water reminders" })
            }
            Spacer(Modifier.height(10.dp))
            Text("Daily target from drinks", fontWeight = FontWeight.Medium)
            Text(
                "A common guide for drinks is about 1.6 L for women and 2.0 L for men; food adds roughly a fifth on top. " +
                    "Drink more in heat or when active.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1500, 1600, 2000, 2500, 3000).forEach { ml ->
                    FilterChip(s.targetMl == ml, { onChange { it.copy(targetMl = ml) } }, label = { Text(WaterPlan.liters(ml)) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Your glass", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(150, 200, 250, 330, 500).forEach { ml ->
                    FilterChip(s.glassMl == ml, { onChange { it.copy(glassMl = ml) } }, label = { Text("$ml ml") })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Your bottle", fontWeight = FontWeight.Medium)
            Text(
                "For a bottle you sip from over several hours: start it on the Water today card and mark it when it's empty.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(500, 750, 1000, 1500, 2000).forEach { ml ->
                    FilterChip(s.bottleSizeMl == ml, { onChange { it.copy(bottleSizeMl = ml) } }, label = { Text(WaterPlan.liters(ml)) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Your day", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ picking = "wake" }) { Text("Up at ${ReminderTime.label(s.wakeMinute, is24)}") }
                OutlinedButton({ picking = "bed" }) { Text("Bed at ${ReminderTime.label(s.bedMinute, is24)}") }
            }
            Spacer(Modifier.height(10.dp))
            Text("Stop reminders before bed", fontWeight = FontWeight.Medium)
            Text(
                "Fewer trips to the toilet at night, so your sleep and the night's recording are less disturbed.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 120, 180).forEach { m ->
                    FilterChip(s.stopBeforeBedMin == m, { onChange { it.copy(stopBeforeBedMin = m) } }, label = { Text("${m / 60} h") })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "General guidance, not medical advice. If a doctor has told you to limit fluids (for example for heart " +
                    "or kidney problems), follow that instead.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    picking?.let { which ->
        val minute = if (which == "wake") s.wakeMinute else s.bedMinute
        val state = rememberTimePickerState(minute / 60, minute % 60, is24Hour = is24)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (which == "wake") "When you get up" else "When you go to bed") },
            text = { TimeInput(state) },
            confirmButton = {
                TextButton({
                    val m = state.hour * 60 + state.minute
                    onChange { if (which == "wake") it.copy(wakeMinute = m) else it.copy(bedMinute = m) }
                    picking = null
                }) { Text("Set") }
            },
            dismissButton = { TextButton({ picking = null }) { Text("Cancel") } },
        )
    }
}
