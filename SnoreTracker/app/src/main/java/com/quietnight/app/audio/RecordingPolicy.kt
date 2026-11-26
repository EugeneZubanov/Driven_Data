package com.quietnight.app.audio

import com.quietnight.app.data.Gap
import com.quietnight.app.data.Session

/**
 * Plain decision logic for an overnight recording, kept free of Android types so it can be unit
 * tested. [RecordingService] feeds it readings from the phone and acts on the answers.
 */
object RecordingPolicy {
    /** A night longer than this is stopped and saved; nobody sleeps 12 hours with the phone listening. */
    const val MAX_NIGHT_MS = 12 * 60 * 60_000L

    /** Wake lock safety timeout: a little longer than the longest night, so it can never outlive one by much. */
    const val WAKE_LOCK_MS = MAX_NIGHT_MS + 30 * 60_000L

    /** Unplugged at or below this battery level, the night is stopped and saved before the phone dies. */
    const val LOW_BATTERY_PCT = 5

    /** Warn before starting if unplugged and below this. */
    const val WARN_BATTERY_PCT = 40

    /** Warn before starting if less free space than this; clips need up to ~5 MB a night. */
    const val LOW_STORAGE_BYTES = 100L * 1024 * 1024

    /** Below this, clips are not written during the night. */
    const val NO_CLIPS_STORAGE_BYTES = 20L * 1024 * 1024

    /** How often progress is written to disk. */
    const val SAVE_EVERY_MS = 2 * 60_000L

    /** Gaps without audio shorter than this are not listed on the night (the time still counts). */
    const val MIN_GAP_MS = 10_000L

    enum class StopReason(val message: String) {
        LOW_BATTERY("Recording stopped because the battery was low. The night so far was saved."),
        MAX_LENGTH("Recording stopped after 12 hours. The night was saved."),
    }

    /** Whether the night should end on its own. [batteryPct] is null when unknown. */
    fun safetyStop(elapsedMs: Long, batteryPct: Int?, charging: Boolean): StopReason? = when {
        elapsedMs >= MAX_NIGHT_MS -> StopReason.MAX_LENGTH
        !charging && batteryPct != null && batteryPct <= LOW_BATTERY_PCT -> StopReason.LOW_BATTERY
        else -> null
    }

    /** Short warnings to show before starting. Empty means all is well. */
    fun preflightWarnings(batteryPct: Int?, charging: Boolean, freeBytes: Long): List<String> = buildList {
        if (!charging) {
            add(
                if (batteryPct != null && batteryPct < WARN_BATTERY_PCT) "The phone is not charging and the battery is at $batteryPct%."
                else "The phone is not charging. A night of recording uses a fair bit of battery."
            )
        }
        if (freeBytes < LOW_STORAGE_BYTES) add("The phone is almost out of storage, so snore clips may not be kept.")
    }

    /**
     * Backoff before trying to reopen the microphone after it failed [attempt] times in a row
     * (1-based). Grows to 30 s so a long phone call does not keep the CPU busy.
     */
    fun reopenDelayMs(attempt: Int): Long = when {
        attempt <= 1 -> 1_000L
        attempt == 2 -> 2_000L
        attempt == 3 -> 5_000L
        attempt <= 6 -> 10_000L
        else -> 30_000L
    }

    /** True when a buffer is exact digital silence, which a real microphone never produces: the input was muted by the system. */
    fun isDigitalSilence(buf: ShortArray, n: Int): Boolean {
        if (n <= 0) return false
        for (i in 0 until n) if (buf[i].toInt() != 0) return false
        return true
    }
}

/**
 * Collects stretches of the night when no audio was available (microphone taken by a call or
 * another app, or read errors), as offsets in seconds from the start of the night.
 */
class GapTracker(private val minGapMs: Long = RecordingPolicy.MIN_GAP_MS) {
    private val gaps = ArrayList<Gap>()
    private var openAtMs = -1L

    val inGap: Boolean get() = openAtMs >= 0

    /** Audio stopped arriving at [offsetMs] into the night. */
    fun begin(offsetMs: Long) {
        if (openAtMs < 0) openAtMs = offsetMs
    }

    /** Audio is back at [offsetMs]; returns the length of the gap that just closed, in ms. */
    fun end(offsetMs: Long): Long {
        if (openAtMs < 0) return 0
        val len = (offsetMs - openAtMs).coerceAtLeast(0)
        if (len >= minGapMs) add(openAtMs, offsetMs)
        openAtMs = -1
        return len
    }

    private fun add(fromMs: Long, toMs: Long) {
        val from = (fromMs / 1000).toInt()
        val to = ((toMs + 999) / 1000).toInt()
        val last = gaps.lastOrNull()
        if (last != null && from <= last.toSec) gaps[gaps.size - 1] = Gap(last.fromSec, maxOf(last.toSec, to))
        else gaps.add(Gap(from, to))
    }

    /** Closed gaps, plus the open one up to [nowOffsetMs] if audio is missing right now. */
    fun snapshot(nowOffsetMs: Long): List<Gap> {
        val out = ArrayList(gaps)
        if (openAtMs >= 0 && nowOffsetMs - openAtMs >= minGapMs) {
            out.add(Gap((openAtMs / 1000).toInt(), ((nowOffsetMs + 999) / 1000).toInt()))
        }
        return out
    }
}

/** A copy of [s] with the recording-health fields set; everything else is unchanged. */
fun Session.withRecordingInfo(interruptedAtMs: Long = this.interruptedAtMs, gaps: List<Gap> = this.gaps) = Session(
    id = id, startMs = startMs, endMs = endMs, bucketSec = bucketSec, levels = levels, amps = amps,
    tags = tags, clips = clips, demo = demo, interruptedAtMs = interruptedAtMs, gaps = gaps, note = note,
)

/**
 * What to do on launch when the last recording never finished cleanly: the app's process was
 * killed mid-night (by the phone, a crash, or the microphone permission being revoked).
 */
object NightRecovery {
    sealed class Outcome {
        /** Part of the night was saved; keep it, marked as cut short at [cutAtMs]. */
        data class Kept(val session: Session, val cutAtMs: Long) : Outcome()
        /** The process died before the first save; nothing to keep. */
        data class Lost(val id: String) : Outcome()
    }

    /**
     * [markerId] is the night that was recording when the process last ran, or null if it ended
     * cleanly. [saved] is that night's last periodic save, if any. [stillRunning] is true when a
     * recording is live in this process (then there is nothing to recover).
     */
    fun check(markerId: String?, saved: Session?, stillRunning: Boolean): Outcome? = when {
        markerId == null || stillRunning -> null
        saved == null || saved.id != markerId -> Outcome.Lost(markerId)
        else -> {
            val cut = if (saved.interruptedAtMs > 0) saved.interruptedAtMs else saved.endMs
            Outcome.Kept(saved.withRecordingInfo(interruptedAtMs = cut), cut)
        }
    }

    fun message(o: Outcome, clock: (Long) -> String): String = when (o) {
        is Outcome.Kept -> "Your last recording was cut short at ${clock(o.cutAtMs)}, probably because the phone closed the app. The night up to then was kept."
        is Outcome.Lost -> "Your last recording stopped unexpectedly before anything could be saved."
    }
}
