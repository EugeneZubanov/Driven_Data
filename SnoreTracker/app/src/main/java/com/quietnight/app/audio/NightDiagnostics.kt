package com.quietnight.app.audio

import org.json.JSONObject
import java.io.File

/**
 * What the detector saw during one night, for tuning against real recordings. Saved next to the
 * night's clips as [FILE_NAME]; not part of the Session, but included in the app's own backups.
 */
data class NightDiagnostics(
    val captureRate: Int,
    val thresholdDb: Float,
    val listenedSec: Long,
    val clippedShare: Float,
    val bursts: Int,
    val tooShort: Int,
    val tooLong: Int,
    val lowShare: Int,
    val speechLike: Int,
    val machineLike: Int,
    val candidates: Int,
    val reported: Int,
    val weak: Int,
    val nearMisses: Int,
    /** Noise floor in dBFS (NaN if nothing was heard). */
    val floorMinDb: Float,
    val floorMedianDb: Float,
    val floorMaxDb: Float,
) {
    /** Candidates that never lined up into a breathing rhythm. */
    val noRhythm: Int get() = (candidates - reported).coerceAtLeast(0)

    /** A few plain lines for a settings or debug screen. */
    fun format(): String {
        val hours = listenedSec / 3600f
        fun f(x: Float) = if (x.isNaN()) "–" else "%.0f".format(x)
        return listOf(
            (if (listenedSec >= 3600) "Listened %.1f h".format(hours) else "Listened ${listenedSec / 60} min ${listenedSec % 60} s") +
                " at %d Hz, sensitivity %.1f dB.".format(captureRate, thresholdDb),
            "Low sounds heard: $bursts. Counted as snoring: $reported.",
            "Set aside: $tooShort too short, $tooLong too long, $lowShare not low enough, " +
                "$speechLike like talking, $machineLike too regular, $noRhythm without a breathing rhythm.",
            "Near misses: $nearMisses just below the threshold, $weak counted only just above it.",
            "Room noise floor: ${f(floorMinDb)} / ${f(floorMedianDb)} / ${f(floorMaxDb)} dBFS (min / median / max)." +
                if (clippedShare > 0.001f) " Clipping in %.1f%% of the time.".format(clippedShare * 100) else "",
        ).joinToString("\n")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("rate", captureRate).put("threshold", thresholdDb.toDouble()).put("listenedSec", listenedSec)
        .put("clipped", clippedShare.toDouble()).put("bursts", bursts).put("tooShort", tooShort).put("tooLong", tooLong)
        .put("lowShare", lowShare).put("speechLike", speechLike).put("machineLike", machineLike)
        .put("candidates", candidates).put("reported", reported).put("weak", weak).put("nearMisses", nearMisses)
        .apply {
            if (!floorMinDb.isNaN()) put("floorMin", floorMinDb.toDouble()).put("floorMedian", floorMedianDb.toDouble()).put("floorMax", floorMaxDb.toDouble())
        }

    companion object {
        const val FILE_NAME = "detector.json"

        fun from(stats: DetectorStats, captureRate: Int, thresholdDb: Float): NightDiagnostics {
            val h = stats.floorHistogram
            val total = h.sum()
            var min = Float.NaN; var max = Float.NaN; var median = Float.NaN
            var seen = 0
            for (i in h.indices) {
                if (h[i] == 0) continue
                if (min.isNaN()) min = i - 100f
                max = i - 100f
                if (median.isNaN() && (seen + h[i]) * 2 >= total) median = i - 100f
                seen += h[i]
            }
            return NightDiagnostics(
                captureRate, thresholdDb, stats.frames / 20,
                if (stats.frames == 0L) 0f else stats.clippedFrames.toFloat() / stats.frames,
                stats.bursts, stats.tooShort, stats.tooLong, stats.lowShare, stats.speechLike, stats.machineLike,
                stats.candidates, stats.reported, stats.weak, stats.nearMisses, min, median, max,
            )
        }

        fun fromJson(o: JSONObject) = NightDiagnostics(
            o.optInt("rate"), o.optDouble("threshold", 8.0).toFloat(), o.optLong("listenedSec"), o.optDouble("clipped", 0.0).toFloat(),
            o.optInt("bursts"), o.optInt("tooShort"), o.optInt("tooLong"), o.optInt("lowShare"), o.optInt("speechLike"),
            o.optInt("machineLike"), o.optInt("candidates"), o.optInt("reported"), o.optInt("weak"), o.optInt("nearMisses"),
            o.optDouble("floorMin", Double.NaN).toFloat(), o.optDouble("floorMedian", Double.NaN).toFloat(), o.optDouble("floorMax", Double.NaN).toFloat(),
        )

        /** The diagnostics saved in a night's clip folder, or null if there are none or they are unreadable. */
        fun read(clipDir: File): NightDiagnostics? =
            runCatching { fromJson(JSONObject(File(clipDir, FILE_NAME).readText())) }.getOrNull()
    }
}
