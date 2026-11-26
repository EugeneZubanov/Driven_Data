package com.quietnight.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Prefs(
    /** Minutes to wait after pressing Start before listening for snoring. */
    val delayMin: Int = 20,
    /** 0 = low, 1 = normal, 2 = high. */
    val sensitivity: Int = 1,
    val saveClips: Boolean = true,
    /** Tags that will be attached to the next recording. */
    val selectedTags: Set<String> = emptySet(),
    val customTags: Set<String> = emptySet(),
    /** The first-run introduction has been seen or skipped. */
    val introSeen: Boolean = false,
    /** Daily bedtime reminder, at [reminderMinute] minutes after local midnight. */
    val reminderOn: Boolean = false,
    val reminderMinute: Int = 22 * 60 + 30,
    /** Delete clips and whole-night audio of nights older than this many days; 0 = keep. */
    val clipCleanupDays: Int = 0,
) {
    val thresholdDb: Float get() = Level.thresholdDb(sensitivity)
}

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(
        Prefs(
            delayMin = sp.getInt("delayMin", 20),
            sensitivity = sp.getInt("sensitivity", 1),
            saveClips = sp.getBoolean("saveClips", true),
            selectedTags = sp.getStringSet("selectedTags", emptySet())!!.toSet(),
            customTags = sp.getStringSet("customTags", emptySet())!!.toSet(),
            introSeen = sp.getBoolean("introSeen", false),
            reminderOn = sp.getBoolean("reminderOn", false),
            reminderMinute = sp.getInt("reminderMinute", 22 * 60 + 30),
            clipCleanupDays = sp.getInt("clipCleanupDays", 0),
        )
    )
    val prefs: StateFlow<Prefs> = _prefs

    fun update(transform: (Prefs) -> Prefs) {
        val p = transform(_prefs.value)
        _prefs.value = p
        sp.edit()
            .putInt("delayMin", p.delayMin)
            .putInt("sensitivity", p.sensitivity)
            .putBoolean("saveClips", p.saveClips)
            .putStringSet("selectedTags", p.selectedTags)
            .putStringSet("customTags", p.customTags)
            .putBoolean("introSeen", p.introSeen)
            .putBoolean("reminderOn", p.reminderOn)
            .putInt("reminderMinute", p.reminderMinute)
            .putInt("clipCleanupDays", p.clipCleanupDays)
            .apply()
    }
}
