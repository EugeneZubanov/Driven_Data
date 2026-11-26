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
) {
    val thresholdDb: Float get() = when (sensitivity) { 0 -> 11f; 2 -> 5.5f; else -> 8f }
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
            .apply()
    }
}
