package com.quietnight.app.audio

import com.quietnight.app.data.Session

/**
 * Suggests a detection sensitivity from recent nights: the user's "not snoring" corrections, the
 * other sounds heard, and the detector's near misses. Pure; plain advice, the user decides.
 * Sensitivity: 0 = low, 1 = normal, 2 = high (as in Prefs).
 */
object SensitivityAdvice {
    data class Advice(val sensitivity: Int, val reason: String)

    /** Nights considered: the most recent [MAX_NIGHTS] real ones. */
    const val MAX_NIGHTS = 7

    /**
     * [nights] most recent first, each with its diagnostics if saved. Returns null when the
     * current setting looks right or there is too little to go on.
     */
    fun suggest(current: Int, nights: List<Pair<Session, NightDiagnostics?>>): Advice? {
        val recent = nights.filter { !it.first.demo }.take(MAX_NIGHTS)
        if (recent.isEmpty()) return null
        var detectedSec = 0L
        var correctedSec = 0L
        var correctedNights = 0
        var correctedTalking = 0
        var correctedBuckets = 0
        for ((s, _) in recent) {
            detectedSec += s.levels.indices.count { s.levels[it] > 0 && !s.gapMask[it] } * s.bucketSec
            correctedSec += s.correctedSec
            if (s.correctedSec > 0) correctedNights++
            for (i in s.levels.indices) {
                if (!s.notSnoringMask[i] || s.levels[i].toInt() == 0) continue
                correctedBuckets++
                if (i < s.others.size && s.others[i].toInt() and OtherSound.TALKING != 0) correctedTalking++
            }
        }
        if (current > 0 && correctedNights >= 2 && correctedSec >= 20 * 60 && correctedSec * 4 >= detectedSec) {
            val min = correctedSec / 60
            val why = if (correctedTalking * 2 >= correctedBuckets) " Much of it was during talking." else ""
            return Advice(current - 1, "You marked $min min as not snoring over the last ${recent.size} nights.$why Lower sensitivity may pick up fewer other sounds.")
        }
        val withDiag = recent.filter { it.second != null }
        if (current < 2 && correctedSec == 0L && withDiag.size >= 3) {
            val quiet = withDiag.all { (s, _) ->
                val listened = s.listenedSec.coerceAtLeast(1)
                s.levels.indices.count { s.levels[it] > 0 && !s.gapMask[it] } * s.bucketSec * 50 < listened
            }
            val hours = withDiag.sumOf { it.second!!.listenedSec } / 3600.0
            val near = withDiag.sumOf { it.second!!.nearMisses + it.second!!.weak }
            if (quiet && hours >= 3 && near / hours >= 10) {
                return Advice(current + 1, "Little snoring was picked up, but many low sounds fell just short of it. Higher sensitivity may catch quieter snoring.")
            }
        }
        return null
    }
}
