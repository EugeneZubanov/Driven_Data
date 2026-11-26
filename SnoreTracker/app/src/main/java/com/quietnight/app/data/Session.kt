package com.quietnight.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.math.roundToInt

/** Snoring intensity of one timeline bucket. */
object Level {
    const val QUIET = 0
    const val MILD = 1
    const val LOUD = 2
    const val HEAVY = 3

    val names = listOf("Quiet", "Mild", "Loud", "Heavy")

    fun fromDb(dbAboveFloor: Float): Int = when {
        dbAboveFloor >= 28f -> HEAVY
        dbAboveFloor >= 18f -> LOUD
        else -> MILD
    }
}

data class Clip(val offsetSec: Int, val file: String, val level: Int, val amp: Int)

/** A stretch of the night with no audio (microphone in use elsewhere), in seconds from the start. */
data class Gap(val fromSec: Int, val toSec: Int)

/**
 * One recorded night. The timeline is a list of fixed-length buckets, each holding the strongest
 * snoring level heard in it and its loudness in dB above the room's noise floor.
 */
class Session(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val bucketSec: Int,
    val levels: ByteArray,
    val amps: ByteArray,
    val tags: List<String>,
    val clips: List<Clip>,
    val demo: Boolean = false,
    /** When the recording ended unexpectedly (app closed by the phone), or 0 if it was stopped normally. */
    val interruptedAtMs: Long = 0L,
    /** Stretches without audio; they count as quiet on the timeline. */
    val gaps: List<Gap> = emptyList(),
    /** Short free-text note added after the night; empty when none. */
    val note: String = "",
) {
    val durationSec: Int get() = ((endMs - startMs) / 1000).toInt()

    /** Seconds spent at each level, indexed by [Level]. */
    val secondsByLevel: IntArray by lazy {
        val out = IntArray(4)
        for (l in levels) out[l.toInt().coerceIn(0, 3)] += bucketSec
        out
    }

    val snoringSec: Int get() = secondsByLevel[1] + secondsByLevel[2] + secondsByLevel[3]

    val snoringFraction: Float
        get() = if (levels.isEmpty()) 0f else snoringSec.toFloat() / (levels.size * bucketSec)

    /** Score contribution of each snoring level; they add up to [score]. */
    val scoreParts: FloatArray by lazy {
        val total = (levels.size * bucketSec).toFloat()
        if (total == 0f) FloatArray(3) else {
            val raw = FloatArray(3) { 100f * WEIGHTS[it] * secondsByLevel[it + 1] / total }
            val sum = raw.sum()
            if (sum > 100f) FloatArray(3) { raw[it] * 100f / sum } else raw
        }
    }

    /** 0–100. Share of the night spent snoring, with louder snoring counting for more. */
    val score: Int get() = scoreParts.sum().roundToInt()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("start", startMs)
        put("end", endMs)
        put("bucketSec", bucketSec)
        put("levels", Base64.getEncoder().encodeToString(levels))
        put("amps", Base64.getEncoder().encodeToString(amps))
        put("tags", JSONArray(tags))
        put("clips", JSONArray().also { arr ->
            clips.forEach {
                arr.put(JSONObject().put("t", it.offsetSec).put("f", it.file).put("l", it.level).put("a", it.amp))
            }
        })
        put("demo", demo)
        if (interruptedAtMs > 0) put("interruptedAt", interruptedAtMs)
        if (gaps.isNotEmpty()) put("gaps", JSONArray().also { arr -> gaps.forEach { arr.put(JSONArray().put(it.fromSec).put(it.toSec)) } })
        if (note.isNotEmpty()) put("note", note)
    }

    /** Same night with tags and note changed after the fact. */
    fun withEdits(tags: List<String>, note: String): Session =
        Session(id, startMs, endMs, bucketSec, levels, amps, tags, clips, demo, interruptedAtMs, gaps, note)

    companion object {
        val WEIGHTS = floatArrayOf(0.4f, 1.0f, 2.0f)

        fun fromJson(o: JSONObject): Session {
            val tags = o.optJSONArray("tags") ?: JSONArray()
            val clips = o.optJSONArray("clips") ?: JSONArray()
            val gaps = o.optJSONArray("gaps") ?: JSONArray()
            return Session(
                id = o.getString("id"),
                startMs = o.getLong("start"),
                endMs = o.getLong("end"),
                bucketSec = o.getInt("bucketSec"),
                levels = Base64.getDecoder().decode(o.getString("levels")),
                amps = Base64.getDecoder().decode(o.getString("amps")),
                tags = List(tags.length()) { tags.getString(it) },
                clips = List(clips.length()) {
                    val c = clips.getJSONObject(it)
                    Clip(c.getInt("t"), c.getString("f"), c.getInt("l"), c.getInt("a"))
                },
                demo = o.optBoolean("demo", false),
                interruptedAtMs = o.optLong("interruptedAt", 0L),
                gaps = List(gaps.length()) { gaps.getJSONArray(it).let { g -> Gap(g.getInt(0), g.getInt(1)) } },
                note = o.optString("note", ""),
            )
        }
    }
}

/** Built-in and custom tags. Stored on a session as "f:Name" (factor) or "r:Name" (remedy). */
object Tags {
    val factors = listOf("Alcohol", "Late meal", "Caffeine", "Blocked nose", "Exhausted", "Unwell")
    val remedies = listOf("Side sleeping", "Nasal strip", "Humidifier", "Mouthpiece", "Extra pillow", "Nasal spray")

    fun label(tag: String) = tag.substringAfter(':')
    fun isRemedy(tag: String) = tag.startsWith("r:")
}
