package com.quietnight.app.data

import kotlin.math.roundToInt

/** Automatic removal of old audio, and storage sizes in words. */
object StorageCleanup {
    val CHOICES = listOf(0, 30, 90)

    /** Runs the cleanup on a background thread if one is set. Safe to call at every app start. */
    fun runInBackground(repository: SessionRepository, days: Int, onDone: (freedBytes: Long) -> Unit = {}) {
        if (days <= 0) return
        Thread({ onDone(runCatching { repository.deleteClipsOlderThan(days) }.getOrDefault(0L)) }, "clip-cleanup").start()
    }

    /** "0 MB", "312 MB", "1.4 GB". */
    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "${(mb / 1024 * 10).roundToInt() / 10.0} GB" else "${mb.roundToInt()} MB"
    }
}
