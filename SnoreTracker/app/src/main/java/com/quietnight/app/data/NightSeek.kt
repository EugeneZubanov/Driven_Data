package com.quietnight.app.data

import com.quietnight.app.audio.AudioSegment

/** A place in the whole-night audio: segment [index], [offsetMs] into it. */
data class SeekPos(val index: Int, val offsetMs: Long)

/**
 * Maths for playing a night's audio segments as one recording. Times are ms from the start of the
 * night. Gaps between segments are real gaps (no audio) and are skipped.
 */
object NightSeek {
    /** Segments in time order, dropping empty ones. */
    fun ordered(segments: List<AudioSegment>): List<AudioSegment> = segments.filter { it.durationMs > 0 }.sortedBy { it.startMs }

    /**
     * Where to start playing for night time [atMs]: inside the segment that covers it, or the
     * start of the next segment when [atMs] falls in a gap or before the first. Null after the end.
     */
    fun locate(segments: List<AudioSegment>, atMs: Long): SeekPos? {
        for ((i, s) in segments.withIndex()) {
            if (atMs < s.startMs) return SeekPos(i, 0)
            if (atMs < s.startMs + s.durationMs) return SeekPos(i, atMs - s.startMs)
        }
        return null
    }

    /** Night time of a position. */
    fun nightMs(segments: List<AudioSegment>, pos: SeekPos): Long = segments[pos.index].startMs + pos.offsetMs

    /** The segment after [index], or null at the end. */
    fun next(segments: List<AudioSegment>, index: Int): Int? = (index + 1).takeIf { it < segments.size }

    /** Whether night time [atMs] has audio. */
    fun covered(segments: List<AudioSegment>, atMs: Long): Boolean =
        segments.any { atMs >= it.startMs && atMs < it.startMs + it.durationMs }

    /** Total length of audio, gaps excluded. */
    fun totalMs(segments: List<AudioSegment>): Long = segments.sumOf { it.durationMs }
}
