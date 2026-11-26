package com.quietnight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quietnight.app.audio.RecState
import com.quietnight.app.data.Prefs
import com.quietnight.app.data.Session
import com.quietnight.app.data.Tags
import com.quietnight.app.data.formatDuration
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.quietnight.app.audio.MicCheckState
import android.text.format.DateFormat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.quietnight.app.audio.NightAudioWriter
import com.quietnight.app.audio.RecordingPolicy
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import com.quietnight.app.audio.WakeSetting
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import kotlinx.coroutines.launch

@Composable
fun RecordScreen(
    prefs: Prefs,
    rec: RecState,
    lastSession: Session?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPrefs: ((Prefs) -> Prefs) -> Unit,
    onOpenSession: (String) -> Unit,
    nowMs: () -> Long = System::currentTimeMillis,
    /** False when Android may pause the app overnight to save battery. */
    batteryExempt: Boolean = true,
    onFixBattery: () -> Unit = {},
    /** Reasons to think twice before starting (not charging, low storage); asked when Start is pressed. */
    startWarnings: () -> List<String> = { emptyList() },
    onDismissNotice: () -> Unit = {},
    mic: MicCheckState = MicCheckState(),
    onMicCheck: () -> Unit = {},
    /** Called when the recording view dims or wakes, so the window brightness can follow. */
    onDimmed: (Boolean) -> Unit = {},
    /** Called with false once the night view has been dimmed long enough for the screen to turn off. */
    onKeepScreenOn: (Boolean) -> Unit = {},
    dimAfterMs: Long = 30_000,
    wake: WakeSetting = WakeSetting(),
    onWake: (WakeSetting) -> Unit = {},
    /** Starts a 5-minute recording that opens its result, to check detection before a whole night. */
    onTestRun: () -> Unit = {},
    /** Build.MANUFACTURER, for phone-specific battery steps. */
    manufacturer: String = "",
    /** Keep the whole night's audio, not just the loudest clips. */
    fullAudio: Boolean = false,
    onFullAudio: (Boolean) -> Unit = {},
    /** False when the phone has no suitable audio encoder; the full-audio row is then left out. */
    fullAudioSupported: Boolean = true,
    /** The phone's 12/24-hour setting; null reads it from the context. */
    is24Hour: Boolean? = null,
) {
    val h24 = is24Hour ?: DateFormat.is24HourFormat(LocalContext.current)
    CompositionLocalProvider(LocalIs24Hour provides h24) { RecordScreenBody(
        prefs, rec, lastSession, onStart, onStop, onPrefs, onOpenSession, nowMs, batteryExempt, onFixBattery, startWarnings,
        onDismissNotice, mic, onMicCheck, onDimmed, onKeepScreenOn, dimAfterMs, wake, onWake, onTestRun, manufacturer,
        fullAudio && fullAudioSupported, onFullAudio, fullAudioSupported,
    ) }
}

/** 12/24-hour setting for the times shown on the Record tab. */
private val LocalIs24Hour = staticCompositionLocalOf { true }

@Composable
private fun timeText(ms: Long) = RecordingPolicy.clockText(ms, LocalIs24Hour.current)

@Composable
private fun RecordScreenBody(
    prefs: Prefs, rec: RecState, lastSession: Session?, onStart: () -> Unit, onStop: () -> Unit,
    onPrefs: ((Prefs) -> Prefs) -> Unit, onOpenSession: (String) -> Unit, nowMs: () -> Long, batteryExempt: Boolean,
    onFixBattery: () -> Unit, startWarnings: () -> List<String>, onDismissNotice: () -> Unit, mic: MicCheckState,
    onMicCheck: () -> Unit, onDimmed: (Boolean) -> Unit, onKeepScreenOn: (Boolean) -> Unit, dimAfterMs: Long,
    wake: WakeSetting, onWake: (WakeSetting) -> Unit, onTestRun: () -> Unit, manufacturer: String,
    fullAudio: Boolean, onFullAudio: (Boolean) -> Unit, fullAudioSupported: Boolean,
) {
    if (rec.recording) RecordingView(rec, onStop, nowMs, onDimmed, dimAfterMs, onKeepScreenOn)
    else IdleView(
        prefs, rec, lastSession, onStart, onPrefs, onOpenSession,
        batteryExempt, onFixBattery, startWarnings, onDismissNotice, mic, onMicCheck,
        wake, onWake, onTestRun, manufacturer, fullAudio, onFullAudio, fullAudioSupported,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleView(
    prefs: Prefs,
    rec: RecState,
    lastSession: Session?,
    onStart: () -> Unit,
    onPrefs: ((Prefs) -> Prefs) -> Unit,
    onOpenSession: (String) -> Unit,
    batteryExempt: Boolean,
    onFixBattery: () -> Unit,
    startWarnings: () -> List<String>,
    onDismissNotice: () -> Unit,
    mic: MicCheckState,
    onMicCheck: () -> Unit,
    wake: WakeSetting,
    onWake: (WakeSetting) -> Unit,
    onTestRun: () -> Unit,
    manufacturer: String,
    fullAudio: Boolean,
    onFullAudio: (Boolean) -> Unit,
    fullAudioSupported: Boolean,
) {
    var addKind by remember { mutableStateOf<String?>(null) }
    var oemHelp by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    val tryStart = {
        val w = startWarnings()
        if (w.isEmpty()) onStart() else warnings = w
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bedtime, null, tint = Palette.Moon, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Quiet Night", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(148.dp).clip(CircleShape).background(Palette.Moon).clickable(onClick = tryStart),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mic, null, tint = Palette.Background, modifier = Modifier.size(40.dp))
                Text("Start", color = Palette.Background, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Plug the phone in and leave it face up near your bed.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
        TextButton(onTestRun) { Text("Try a 5-minute test run") }
        rec.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Palette.Heavy, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        rec.notice?.let { notice ->
            Spacer(Modifier.height(16.dp))
            NoticeCard(notice, rec.noticeSessionId?.let { id -> { onOpenSession(id); onDismissNotice() } }, onDismissNotice)
        }
        if (!batteryExempt) {
            Spacer(Modifier.height(16.dp))
            BatteryCard(onFixBattery) { oemHelp = true }
        }
        Spacer(Modifier.height(16.dp))
        TonightCard(
            prefs = prefs, onPrefs = onPrefs, wake = wake, onWake = onWake, mic = mic, onMicCheck = onMicCheck,
            onAddTag = { addKind = it }, fullAudio = fullAudio, onFullAudio = onFullAudio, fullAudioSupported = fullAudioSupported,
        )

        if (lastSession != null) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.clickable { onOpenSession(lastSession.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Last night")
                        Text(
                            "${formatDuration(lastSession.durationSec)} recorded · ${formatDuration(lastSession.snoringSec)} snoring",
                            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ScoreDial(lastSession, 56.dp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (oemHelp) {
        val help = OemHelp.stepsFor(manufacturer)
        AlertDialog(
            onDismissRequest = { oemHelp = false },
            title = { Text("Keep it running on ${help.brand}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    help.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", color = Palette.Muted) }
                }
            },
            confirmButton = { TextButton({ oemHelp = false; onFixBattery() }) { Text("Open setting") } },
            dismissButton = { TextButton({ oemHelp = false }) { Text("Close") } },
        )
    }

    if (warnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { warnings = emptyList() },
            title = { Text("Before you start") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    warnings.forEach { Text(it, color = Palette.Muted) }
                }
            },
            confirmButton = { TextButton({ warnings = emptyList(); onStart() }) { Text("Start anyway") } },
            dismissButton = { TextButton({ warnings = emptyList() }) { Text("Cancel") } },
        )
    }

    addKind?.let { kind ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addKind = null },
            title = { Text(if (kind == "f") "New factor" else "New remedy") },
            text = { OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val tag = "$kind:${name.trim()}"
                        onPrefs { it.copy(customTags = it.customTags + tag, selectedTags = it.selectedTags + tag) }
                        addKind = null
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton({ addKind = null }) { Text("Cancel") } },
        )
    }
}

private data class TagGroup(val kind: String, val title: String, val hint: String, val builtIn: List<String>)

@Composable
private fun NoticeCard(text: String, onOpen: (() -> Unit)?, onDismiss: () -> Unit) {
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, tint = Palette.Moon, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                SectionTitle("Last recording")
            }
            Spacer(Modifier.height(4.dp))
            Text(text, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onOpen != null) TextButton(onOpen) { Text("Open night") }
                TextButton(onDismiss) { Text("OK") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatteryCard(onFix: () -> Unit, onHelp: () -> Unit) {
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatteryAlert, null, tint = Palette.Loud, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                SectionTitle("Let it run overnight")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "To save battery, Android can stop apps in the night, which would cut the recording short. " +
                    "Turn off battery optimisation for Quiet Night to prevent this.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onHelp) { Text("Steps for my phone") }
                TextButton(onFix) { Text("Open setting") }
            }
        }
    }
}

@Composable
private fun MicCheckCard(mic: MicCheckState, onCheck: () -> Unit, framed: Boolean = true) {
    Frame(framed) {
        Column {
            if (framed) SectionTitle("Microphone check")
            Text(
                when {
                    mic.running && mic.heard -> "The phone hears you."
                    mic.running -> "Talk or breathe loudly from where you sleep."
                    mic.error != null -> mic.error
                    mic.done && mic.heard -> "The phone heard you. You are all set."
                    mic.done -> "Could not hear much. Move the phone closer and try again."
                    else -> "See that the phone can hear you from your pillow."
                },
                color = if (mic.running && mic.heard || mic.done && mic.heard && mic.error == null) Palette.Good else Palette.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Quiet)) {
                    if (mic.running) {
                        Box(
                            Modifier.fillMaxWidth(mic.level.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (mic.heard) Palette.Good else Palette.Mild),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onCheck, enabled = !mic.running) { Text(if (mic.running) "Listening…" else if (mic.done) "Again" else "Check") }
            }
        }
    }
}

private const val HOLD_TO_STOP_MS = 1500

@Composable
private fun RecordingView(
    rec: RecState,
    onStop: () -> Unit,
    nowMs: () -> Long,
    onDimmed: (Boolean) -> Unit,
    dimAfterMs: Long,
    onKeepScreenOn: (Boolean) -> Unit = {},
) {
    var now by remember { mutableLongStateOf(nowMs()) }
    var lastTouch by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = nowMs() }
    }
    val dimmed = now - lastTouch >= dimAfterMs
    LaunchedEffect(dimmed) { onDimmed(dimmed) }
    val keepOn = RecordingPolicy.keepScreenOn(dimmed, now - lastTouch - dimAfterMs)
    LaunchedEffect(keepOn) { onKeepScreenOn(keepOn) }
    DisposableEffect(Unit) { onDispose { onDimmed(false) } }

    val elapsed = ((now - rec.startMs) / 1000).toInt().coerceAtLeast(0)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Any touch keeps the screen awake; seen before the children so it never blocks them.
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch = nowMs(); now = lastTouch
                }
            }
        },
    ) {
        if (dimmed) DimmedView(now, elapsed, rec)
        else AwakeView(rec, now, elapsed, onStop)
    }
}

/** Near-black night view for OLED screens: just the time, drifting slowly to avoid burn-in. */
@Composable
private fun DimmedView(now: Long, elapsedSec: Int, rec: RecState) {
    val step = ((now / 60_000) % 6).toInt()
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            Modifier.offset(x = 14.dp * ((step % 3) - 1), y = 24.dp * ((step / 3) * 2 - 1)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(timeText(now), fontSize = 56.sp, fontWeight = FontWeight.Light, color = Palette.Muted.copy(alpha = 0.45f))
            Text(
                if (rec.micPaused) "Paused · microphone in use" else "Recording · ${formatDuration(elapsedSec)}",
                color = Palette.Muted.copy(alpha = 0.4f), style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(32.dp))
            Text("Tap to wake", color = Palette.Muted.copy(alpha = 0.3f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AwakeView(rec: RecState, now: Long, elapsed: Int, onStop: () -> Unit) {
    val delayLeft = ((rec.startMs + rec.delayMs - now) / 1000).toInt()
    var confirm by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Bedtime, null, tint = Palette.Moon.copy(alpha = 0.7f), modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(16.dp))
        Text(timeText(now), fontSize = 64.sp, fontWeight = FontWeight.Light, color = Palette.Text.copy(alpha = 0.85f))
        Text(if (rec.testRun) "Test run · ${formatDuration(elapsed)} of 5m" else "Recording for ${formatDuration(elapsed)}", color = Palette.Muted)
        if (rec.wakeAtMs > 0) Text("Wake-up at ${timeText(rec.wakeAtMs)}", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(28.dp))

        if (rec.micPaused) {
            Text(
                "Another app or a call is using the microphone. Recording carries on when it is free.",
                color = Palette.Loud, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        } else if (!rec.listening && delayLeft > 0) {
            Text("Listening starts in ${formatDuration(delayLeft + 59)}", color = Palette.Muted)
        } else {
            Text("Listening", color = Palette.Muted)
            Spacer(Modifier.height(10.dp))
            // live loudness above the room's noise floor
            val frac = (rec.dbAboveFloor / 35f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(0.7f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Quiet)) {
                Box(
                    Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (rec.dbAboveFloor >= 28) Palette.Heavy else if (rec.dbAboveFloor >= 18) Palette.Loud else Palette.Mild),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("${rec.eventCount} snores detected", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (rec.gapSec >= 60) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No sound for ${formatDuration(rec.gapSec)} so far",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(48.dp))
        HoldToStopButton(onStop = onStop, onAccessibleStop = { confirm = true })
        Spacer(Modifier.height(16.dp))
        Text(
            "You can turn the screen off. Recording continues until you stop it.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Stop recording?") },
            confirmButton = { TextButton({ confirm = false; onStop() }) { Text("Stop") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Keep recording") } },
        )
    }
}

/**
 * Stop needs a 1.5 s press, so a sleepy brush against the screen cannot end the night. A quick tap
 * explains what to do. Screen readers get a normal button with a confirm dialog instead.
 */
@Composable
private fun HoldToStopButton(onStop: () -> Unit, onAccessibleStop: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(240.dp).height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(Palette.SurfaceHigh)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    onClick(label = "Stop recording") { onAccessibleStop(); true }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        hint = false
                        val fill = scope.launch {
                            progress.animateTo(1f, tween(HOLD_TO_STOP_MS, easing = LinearEasing))
                            onStop()
                        }
                        val released = tryAwaitRelease()
                        if (fill.isActive) {
                            fill.cancel()
                            if (released) hint = true
                            scope.launch { progress.animateTo(0f, tween(200)) }
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(progress.value).height(52.dp).background(Palette.Moon.copy(alpha = 0.35f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Stop, null, tint = Palette.Text)
                Spacer(Modifier.width(8.dp))
                Text("Hold to stop", color = Palette.Text, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (hint) "Keep holding to stop and see results" else " ",
            color = Palette.Muted, style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeCard(wake: WakeSetting, onWake: (WakeSetting) -> Unit, framed: Boolean = true) {
    var picking by remember { mutableStateOf(false) }
    Frame(framed) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (framed) SectionTitle("Wake-up alarm")
                    Text(
                        if (wake.enabled) "Stops recording and rings at this time." else "Off",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = wake.enabled, onCheckedChange = { onWake(wake.copy(enabled = it)) }, modifier = Modifier.semantics { contentDescription = "Wake-up alarm" })
            }
            if (wake.enabled) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ picking = true }) {
                        Text(RecordingPolicy.timeOfDay(wake.hour, wake.minute, LocalIs24Hour.current), fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Keep your usual alarm too, in case the phone closes the app.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    if (picking) {
        val state = rememberTimePickerState(wake.hour, wake.minute, is24Hour = LocalIs24Hour.current)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Wake-up time") },
            text = { TimeInput(state) },
            confirmButton = { TextButton({ picking = false; onWake(wake.copy(hour = state.hour, minute = state.minute)) }) { Text("Set") } },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Frame(framed: Boolean, content: @Composable () -> Unit) {
    if (framed) Card { content() } else content()
}

/** Short summary of the chosen tags for a collapsed row. */
internal fun tagSummary(selected: Set<String>, max: Int = 2): String {
    if (selected.isEmpty()) return "None"
    val names = selected.sortedWith(compareBy({ !Tags.isRemedy(it) }, { Tags.label(it) })).map(Tags::label)
    return if (names.size <= max) names.joinToString(", ") else names.take(max).joinToString(", ") + " +${names.size - max}"
}

/**
 * Everything to set up for tonight in one card: each row shows its current value and opens in
 * place, so the first screenful stays Start plus anything that needs attention.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TonightCard(
    prefs: Prefs,
    onPrefs: ((Prefs) -> Prefs) -> Unit,
    wake: WakeSetting,
    onWake: (WakeSetting) -> Unit,
    mic: MicCheckState,
    onMicCheck: () -> Unit,
    onAddTag: (String) -> Unit,
    fullAudio: Boolean = false,
    onFullAudio: (Boolean) -> Unit = {},
    fullAudioSupported: Boolean = true,
) {
    var open by remember { mutableStateOf<String?>(null) }
    @Composable
    fun SettingRow(key: String, label: String, value: String, content: @Composable () -> Unit) {
        val expanded = open == key
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = if (expanded) null else key }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(
                value, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp).padding(start = 12.dp),
            )
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.Muted, modifier = Modifier.padding(start = 4.dp))
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 12.dp)) { content() }
        }
        HorizontalDivider(color = Palette.Outline.copy(alpha = 0.5f))
    }

    Card {
        Column {
            SectionTitle("Tonight")
            Spacer(Modifier.height(4.dp))
            SettingRow("delay", "Fall-asleep delay", if (prefs.delayMin == 0) "None" else "${prefs.delayMin} min") {
                Text("Listening starts after this delay.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(0, 10, 20, 30, 45)) {
                        FilterChip(
                            selected = prefs.delayMin == m,
                            onClick = { onPrefs { it.copy(delayMin = m) } },
                            label = { Text(if (m == 0) "None" else "$m min") },
                        )
                    }
                }
            }
            SettingRow("wake", "Wake-up alarm", if (wake.enabled) RecordingPolicy.timeOfDay(wake.hour, wake.minute, LocalIs24Hour.current) else "Off") {
                WakeCard(wake, onWake, framed = false)
            }
            SettingRow("tags", "Factors and remedies", tagSummary(prefs.selectedTags)) {
                for ((kind, title, hint, builtIn) in listOf(
                    TagGroup("f", "Factors", "Things that might make snoring worse.", Tags.factors),
                    TagGroup("r", "Remedies", "Things you are trying to snore less.", Tags.remedies),
                )) {
                    Text(title, fontWeight = FontWeight.Medium)
                    Text(hint, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    val all = builtIn.map { "$kind:$it" } + prefs.customTags.filter { it.startsWith("$kind:") }.sorted()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tag in all) {
                            val on = tag in prefs.selectedTags
                            FilterChip(
                                selected = on,
                                onClick = { onPrefs { it.copy(selectedTags = if (on) it.selectedTags - tag else it.selectedTags + tag) } },
                                label = { Text(Tags.label(tag)) },
                            )
                        }
                        AssistChip(
                            onClick = { onAddTag(kind) },
                            label = { Text("Add") },
                            leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (fullAudioSupported) SettingRow("audio", "Full-night audio", if (fullAudio) "On" else "Off") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Keep all of tonight's sound, not just the loudest clips. " +
                            "About ${NightAudioWriter.estimateBytes(8.0) / 1_000_000} MB for an 8-hour night; " +
                            "it turns itself off if the phone runs low on space.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = fullAudio, onCheckedChange = onFullAudio)
                }
            }
            SettingRow(
                "mic", "Microphone check",
                when { mic.running -> "Listening…"; mic.done && mic.heard -> "Heard you"; mic.done -> "Try again"; else -> "Not checked" },
            ) {
                MicCheckCard(mic, onMicCheck, framed = false)
            }
        }
    }
}
