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

    /*
     * All detection thresholds live here, in dB above the night's quiet reference: the lowest
     * noise floor that held for a whole minute (see SnoreEvent.dbAboveFloor). The phone's
     * microphone is not calibrated, so these are relative levels, not sound pressure.
     */
    /** Snoring at least this far above the quiet reference is Loud; below it, Mild. */
    const val LOUD_DB = 18f
    /** Snoring at least this far above the quiet reference is Heavy. */
    const val HEAVY_DB = 28f
    /** How far above the current noise floor a sound must rise to count, by sensitivity 0 low, 1 normal, 2 high. */
    val SENSITIVITY_DB = floatArrayOf(11f, 8f, 5.5f)

    fun thresholdDb(sensitivity: Int): Float = SENSITIVITY_DB[sensitivity.coerceIn(0, SENSITIVITY_DB.size - 1)]

    fun fromDb(dbAboveFloor: Float): Int = when {
        dbAboveFloor >= HEAVY_DB -> HEAVY
        dbAboveFloor >= LOUD_DB -> LOUD
        else -> MILD
    }
}

data class Clip(val offsetSec: Int, val file: String, val level: Int, val amp: Int)

/** A stretch marked by the user as not snoring, in seconds from the start. */
data class Span(val fromSec: Int, val toSec: Int)

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
    /**
     * Stretches the user marked as not snoring, in the order they were marked. Detection in
     * [levels] is kept as it was, so removing a span undoes the correction exactly.
     */
    val notSnoring: List<Span> = emptyList(),
    /** Per bucket: OtherSound kind bits (1 talking, 2 other loud sound). Empty if not recorded. */
    val others: ByteArray = ByteArray(0),
    /** Per bucket: highest snore confidence 0–100 (a rough heuristic). Empty if not recorded. */
    val conf: ByteArray = ByteArray(0),
) {
    val durationSec: Int get() = ((endMs - startMs) / 1000).toInt()

    /** True for buckets whose middle falls in a [gaps] stretch (no audio was heard). */
    val gapMask: BooleanArray by lazy {
        val mask = BooleanArray(levels.size)
        for (g in gaps) {
            val from = ((g.fromSec - bucketSec / 2f) / bucketSec).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(0)
            for (b in from until levels.size) {
                val mid = b * bucketSec + bucketSec / 2f
                if (mid >= g.toSec) break
                if (mid >= g.fromSec) mask[b] = true
            }
        }
        mask
    }

    /** True for buckets whose middle falls in a [notSnoring] span. */
    val notSnoringMask: BooleanArray by lazy { spanMask(notSnoring.map { it.fromSec to it.toSec }) }

    /**
     * Level after corrections: quiet for gaps and for stretches marked not snoring. Other sounds do
     * not change it: a cough in a bucket must not erase snoring the detector confirmed there.
     */
    fun effectiveLevel(i: Int): Int =
        if (gapMask[i] || notSnoringMask[i]) Level.QUIET else levels[i].toInt().coerceIn(0, 3)

    /** True when the detector heard talking or another non-snore sound in bucket [i]. */
    fun hasOther(i: Int): Boolean = i < others.size && others[i].toInt() != 0

    /** Seconds with talking or other sounds and no snoring, outside gaps. */
    val otherSec: Int by lazy { levels.indices.count { hasOther(it) && !gapMask[it] && effectiveLevel(it) == Level.QUIET } * bucketSec }

    /** Seconds that were detected as snoring but marked not snoring. */
    val correctedSec: Int by lazy {
        levels.indices.count { notSnoringMask[it] && !gapMask[it] && levels[it] > 0 } * bucketSec
    }

    private fun spanMask(spans: List<Pair<Int, Int>>): BooleanArray {
        val mask = BooleanArray(levels.size)
        for ((fromSec, toSec) in spans) {
            val from = kotlin.math.ceil((fromSec - bucketSec / 2f) / bucketSec).toInt().coerceAtLeast(0)
            for (b in from until levels.size) {
                val mid = b * bucketSec + bucketSec / 2f
                if (mid >= toSec) break
                if (mid >= fromSec) mask[b] = true
            }
        }
        return mask
    }

    /** Seconds of the timeline with no audio. */
    val gapSec: Int by lazy { gapMask.count { it } * bucketSec }

    /** Seconds of the timeline actually listened to; the score is a share of this. */
    val listenedSec: Int get() = levels.size * bucketSec - gapSec

    /** Seconds spent at each level, indexed by [Level]. Gaps are not counted as quiet. */
    val secondsByLevel: IntArray by lazy {
        val out = IntArray(4)
        for (i in levels.indices) if (!gapMask[i]) out[effectiveLevel(i)] += bucketSec
        out
    }

    val snoringSec: Int get() = secondsByLevel[1] + secondsByLevel[2] + secondsByLevel[3]

    val snoringFraction: Float
        get() = if (listenedSec <= 0) 0f else snoringSec.toFloat() / listenedSec

    /** Score contribution of each snoring level; they add up to [score]. */
    val scoreParts: FloatArray by lazy {
        val total = listenedSec.toFloat()
        if (total <= 0f) FloatArray(3) else {
            val raw = FloatArray(3) { 100f * WEIGHTS[it] * secondsByLevel[it + 1] / total }
            val sum = raw.sum()
            if (sum > 100f) FloatArray(3) { raw[it] * 100f / sum } else raw
        }
    }

    /** 0–100. Share of the listened time spent snoring, with louder snoring counting for more. */
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
        if (notSnoring.isNotEmpty()) put("notSnoring", JSONArray().also { arr -> notSnoring.forEach { arr.put(JSONArray().put(it.fromSec).put(it.toSec)) } })
        if (others.any { it.toInt() != 0 }) put("others", Base64.getEncoder().encodeToString(others))
        if (conf.any { it.toInt() != 0 }) put("conf", Base64.getEncoder().encodeToString(conf))
    }

    /** Same night with tags and note changed after the fact. */
    fun withEdits(tags: List<String>, note: String): Session =
        Session(id, startMs, endMs, bucketSec, levels, amps, tags, clips, demo, interruptedAtMs, gaps, note, notSnoring, others = others, conf = conf)

    /** Same night with a different set of not-snoring corrections. */
    fun withNotSnoring(spans: List<Span>): Session = Session(
        id, startMs, endMs, bucketSec, levels, amps, tags, clips, demo = demo,
        interruptedAtMs = interruptedAtMs, gaps = gaps, note = note, notSnoring = spans, others = others, conf = conf,
    )

    /** False for test runs and nights with under an hour actually listened to. */
    val countsForTrends: Boolean get() = note != TEST_RUN_NOTE && listenedSec >= 3600

    companion object {
        val WEIGHTS = floatArrayOf(0.4f, 1.0f, 2.0f)

        /** Note the recorder gives a short test recording; same text as RecordingService.TEST_RUN_NOTE. */
        const val TEST_RUN_NOTE = "Test run"

        fun fromJson(o: JSONObject): Session {
            val tags = o.optJSONArray("tags") ?: JSONArray()
            val clips = o.optJSONArray("clips") ?: JSONArray()
            val gaps = o.optJSONArray("gaps") ?: JSONArray()
            val notSnoring = o.optJSONArray("notSnoring") ?: JSONArray()
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
                notSnoring = List(notSnoring.length()) { notSnoring.getJSONArray(it).let { g -> Span(g.getInt(0), g.getInt(1)) } },
                others = o.optString("others", "").let { if (it.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(it) },
                conf = o.optString("conf", "").let { if (it.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(it) },
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
