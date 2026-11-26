package com.quietnight.app.data

import com.quietnight.app.audio.NightDiagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain-text pieces for the Settings "Detection check" card. */
object DetectionCheck {
    val LEVELS = listOf("Low", "Normal", "High")

    fun levelName(sensitivity: Int): String = LEVELS[sensitivity.coerceIn(0, LEVELS.lastIndex)]

    /** The latest real night that counts for trends, from a newest-first or unordered list. */
    fun latestRealNight(sessions: List<Session>): Session? =
        sessions.filter { !it.demo && it.countsForTrends }.maxByOrNull { it.startMs }

    /**
     * Text the owner can send back for tuning: app and phone details, the night's own numbers, and the
     * detector's diagnostics. No audio and no tags or notes.
     */
    fun shareText(
        night: Session,
        diagnostics: NightDiagnostics?,
        sensitivity: Int,
        appVersion: String,
        androidRelease: String,
        sdkInt: Int,
        model: String,
    ): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return buildString {
            appendLine("Quiet Night $appVersion detection details")
            appendLine("Phone: $model, Android $androidRelease (API $sdkInt)")
            appendLine("Night: ${date.format(Date(night.startMs))} to ${date.format(Date(night.endMs))}")
            appendLine("Sensitivity setting: ${levelName(sensitivity)}")
            appendLine(
                "Score ${night.score}; snoring ${night.snoringSec / 60} min of ${night.listenedSec / 60} min listened " +
                    "(mild ${night.secondsByLevel[Level.MILD] / 60}, loud ${night.secondsByLevel[Level.LOUD] / 60}, " +
                    "heavy ${night.secondsByLevel[Level.HEAVY] / 60}); marked not snoring ${night.correctedSec / 60} min.",
            )
            if (diagnostics != null) {
                appendLine("Capture rate: ${diagnostics.captureRate} Hz")
                appendLine()
                append(diagnostics.format())
            } else {
                append("No detector details were saved for this night (they are kept when audio clips are on).")
            }
        }.trimEnd()
    }
}

/** Whether there is room to restore a backup of [zipBytes], given free space in cache and app storage. */
object RestoreSpace {
    /** Returns a message to show instead of restoring, or null if there is room (or the size is unknown). */
    fun problem(zipBytes: Long?, cacheFree: Long, filesFree: Long): String? {
        if (zipBytes == null || zipBytes <= 0) return null
        val need = zipBytes * 2
        val free = minOf(cacheFree, filesFree)
        if (free >= need) return null
        return "Not enough free space to restore this backup. It needs about ${StorageCleanup.formatBytes(need)} free; " +
            "the phone has ${StorageCleanup.formatBytes(free)}. Free some space and try again. Nothing was changed."
    }
}
