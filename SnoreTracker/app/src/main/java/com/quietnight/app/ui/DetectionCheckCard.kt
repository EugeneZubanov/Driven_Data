package com.quietnight.app.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietnight.app.BuildConfig
import com.quietnight.app.QuietNightApp
import com.quietnight.app.audio.NightDiagnostics
import com.quietnight.app.audio.SensitivityAdvice
import com.quietnight.app.data.DetectionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Detection check card shows; [details] and [shareText] are null when there is no real night yet. */
class DetectionCheckState(val advice: SensitivityAdvice.Advice?, val details: String?, val shareText: String?)

/** Loads the suggestion and last night's diagnostics on an IO thread whenever nights or the setting change. */
@Composable
fun rememberDetectionCheck(app: QuietNightApp, sensitivity: Int): DetectionCheckState? {
    val sessions by app.repository.sessions.collectAsStateWithLifecycle()
    val state by produceState<DetectionCheckState?>(null, sessions, sensitivity) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val recent = sessions.filter { !it.demo }.sortedByDescending { it.startMs }.take(SensitivityAdvice.MAX_NIGHTS)
                    .map { it to NightDiagnostics.read(app.repository.clipDir(it.id)) }
                val advice = SensitivityAdvice.suggest(sensitivity, recent)
                val latest = DetectionCheck.latestRealNight(sessions)
                val diag = latest?.let { s -> recent.firstOrNull { it.first.id == s.id }?.second ?: NightDiagnostics.read(app.repository.clipDir(s.id)) }
                DetectionCheckState(
                    advice,
                    latest?.let { diag?.format() ?: "No detector details were saved for this night. They are kept when audio clips are on." },
                    latest?.let {
                        DetectionCheck.shareText(it, diag, sensitivity, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE ?: "?", Build.VERSION.SDK_INT, "${Build.MANUFACTURER} ${Build.MODEL}")
                    },
                )
            }.getOrNull()
        }
    }
    return state
}

/** Sensitivity suggestion (never applied automatically) and last night's detector details to share. */
@Composable
fun DetectionCheckCard(state: DetectionCheckState, current: Int, initiallyOpen: Boolean = false, onUseSensitivity: (Int) -> Unit) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Card {
        Column {
            SectionTitle("Detection check")
            val advice = state.advice
            if (advice != null && advice.sensitivity != current) {
                Text(
                    "Suggestion: ${DetectionCheck.levelName(advice.sensitivity)} sensitivity",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
                Text(advice.reason, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ onUseSensitivity(advice.sensitivity) }) { Text("Use ${DetectionCheck.levelName(advice.sensitivity)}") }
            } else {
                Text(
                    "No change suggested. The app looks at your last nights and any stretches you marked as not snoring.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.details != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Last night's detection details", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text(if (open) "Hide" else "Show", color = Palette.Muted)
                }
                if (open) {
                    Text(state.details, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Quiet Night detection details")
                                .putExtra(Intent.EXTRA_TEXT, state.shareText)
                            try {
                                context.startActivity(Intent.createChooser(send, "Share details"))
                            } catch (_: Exception) {
                                // No app to share with; nothing else to do.
                            }
                        }) { Text("Share details") }
                    }
                    Text(
                        "Shares text only: phone model, Android and app version, and the numbers above. No audio.",
                        color = Palette.Muted, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
