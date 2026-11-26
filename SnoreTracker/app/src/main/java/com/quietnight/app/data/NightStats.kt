package com.quietnight.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Timeline columns for one window of a night: strongest level per column and its loudness. */
class Columns(val levels: IntArray, val amps: IntArray, val firstCovered: Int, val lastCovered: Int) {
    /** Columns where every bucket had no audio. */
    var gaps: BooleanArray = BooleanArray(levels.size)
        internal set

    /** Loudness of snoring the user marked as not snoring, per column; 0 where there is none. */
    var corrected: IntArray = IntArray(levels.size)
        internal set
}

/** A run of time within a night, as seconds from its start. */
data class Stretch(val startSec: Int, val lengthSec: Int)

/** How one night's score compares with the nights before it. */
data class Comparison(val average: Int, val nights: Int) {
    fun diff(score: Int) = score - average
}

/** Plain calculations behind the Results tab. No Android types, so they are unit-tested directly. */
object NightStats {

    /**
     * Merges the buckets that fall into [winStartMs, winEndMs) into [count] columns, keeping the
     * strongest level (then loudest) per column. Columns outside the recording stay quiet; the
     * covered column range is returned so callers can leave the rest blank.
     */
    fun columns(session: Session, winStartMs: Long, winEndMs: Long, count: Int): Columns {
        val lv = IntArray(count)
        val amp = IntArray(count)
        val n = session.levels.size
        val span = winEndMs - winStartMs
        if (n == 0 || count <= 0 || span <= 0) return Columns(lv, amp, 0, -1)
        val bucketMs = session.bucketSec * 1000L
        val endMs = minOf(session.endMs, session.startMs + n * bucketMs)
        val from = ((winStartMs - session.startMs) / bucketMs).coerceIn(0, n.toLong()).toInt()
        val to = ((winEndMs - session.startMs + bucketMs - 1) / bucketMs).coerceIn(0, n.toLong()).toInt()
        var first = Int.MAX_VALUE
        var last = -1
        val heard = BooleanArray(count)
        val any = BooleanArray(count)
        val mask = session.gapMask
        val corr = IntArray(count)
        val notSnoring = session.notSnoringMask
        for (b in from until to) {
            val t = session.startMs + b * bucketMs
            if (t < winStartMs || t >= winEndMs || t >= endMs) continue
            val c = ((t - winStartMs) * count / span).toInt().coerceIn(0, count - 1)
            if (c < first) first = c
            if (c > last) last = c
            any[c] = true
            if (!mask[b]) heard[c] = true
            val l = session.effectiveLevel(b)
            val a = session.amps[b].toInt()
            if (notSnoring[b] && !mask[b] && session.levels[b] > 0) corr[c] = maxOf(corr[c], a)
            if (l > lv[c] || (l == lv[c] && a > amp[c])) { lv[c] = l; amp[c] = a }
        }
        return Columns(lv, amp, if (last < 0) 0 else first, last).also { cols ->
            cols.gaps = BooleanArray(count) { any[it] && !heard[it] }
            cols.corrected = corr
        }
    }

    /** Minutes after 18:00 on the evening a night began, so nights can share one clock axis. */
    fun eveningMinutes(ms: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = ms }
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (m >= 18 * 60) m - 18 * 60 else m + 6 * 60
    }

    /**
     * Shared window for comparing nights by clock time: for each night, the ms window that covers
     * the same evening-relative span (earliest start to latest end across all of them).
     */
    fun sharedWindows(nights: List<Session>, tz: TimeZone = TimeZone.getDefault()): List<Pair<Long, Long>> {
        if (nights.isEmpty()) return emptyList()
        val starts = nights.map { eveningMinutes(it.startMs, tz) }
        val from = starts.min()
        val to = nights.indices.maxOf { starts[it] + ((nights[it].endMs - nights[it].startMs + 59_999) / 60_000).toInt() }
        return nights.mapIndexed { i, n ->
            // 18:00 on this night's evening, to the minute.
            val zero = n.startMs - n.startMs % 60_000L - starts[i] * 60_000L
            (zero + from * 60_000L) to (zero + to * 60_000L)
        }
    }

    /** Average score of up to [window] nights that started before [session]; null with fewer than [minNights]. */
    fun compareWithRecent(session: Session, all: List<Session>, window: Int = 14, minNights: Int = 3): Comparison? {
        val prior = all.asSequence()
            .filter { it.id != session.id && it.startMs < session.startMs && it.demo == session.demo }
            .sortedByDescending { it.startMs }
            .take(window)
            .toList()
        if (prior.size < minNights) return null
        return Comparison(prior.map { it.score }.average().roundToInt(), prior.size)
    }

    /** Longest run of quiet buckets. */
    fun longestQuiet(session: Session): Stretch? {
        var best = -1; var bestLen = 0; var run = 0
        for (i in session.levels.indices) {
            if (session.effectiveLevel(i) == Level.QUIET && !session.gapMask[i]) {
                run++
                if (run > bestLen) { bestLen = run; best = i - run + 1 }
            } else run = 0
        }
        return if (bestLen == 0) null else Stretch(best * session.bucketSec, bestLen * session.bucketSec)
    }

    /**
     * When snoring started: the first snoring bucket followed by at least [minSnores] snoring
     * buckets within [withinSec], so a single cough does not count. Seconds from the start.
     */
    fun snoringStartSec(session: Session, withinSec: Int = 120, minSnores: Int = 3): Int? {
        val n = session.levels.size
        val win = (withinSec / session.bucketSec).coerceAtLeast(1)
        val need = minSnores.coerceAtMost(win)
        for (i in 0 until n) {
            if (session.effectiveLevel(i) == Level.QUIET) continue
            var count = 0
            for (j in i until minOf(n, i + win)) if (session.effectiveLevel(j) != Level.QUIET) count++
            if (count >= need) return i * session.bucketSec
        }
        return null
    }

    /** Clock-hour starts (ms) that overlap the night, in order. */
    fun hourStarts(session: Session, tz: TimeZone = TimeZone.getDefault()): List<Long> {
        val cal = Calendar.getInstance(tz).apply {
            timeInMillis = session.startMs
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val out = ArrayList<Long>()
        while (cal.timeInMillis < session.endMs && out.size < 48) {
            out += cal.timeInMillis
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }
        return out
    }

    /** Start (ms) of the clock hour with the most weighted snoring, or null for a quiet night. */
    fun loudestHour(session: Session, tz: TimeZone = TimeZone.getDefault()): Long? {
        val hours = hourStarts(session, tz)
        if (hours.isEmpty()) return null
        val weight = FloatArray(hours.size)
        val bucketMs = session.bucketSec * 1000L
        var h = 0
        for (b in session.levels.indices) {
            val l = session.effectiveLevel(b)
            if (l == Level.QUIET) continue
            val t = session.startMs + b * bucketMs
            while (h + 1 < hours.size && t >= hours[h + 1]) h++
            weight[h] += Session.WEIGHTS[l - 1]
        }
        var best = -1
        for (i in weight.indices) if (weight[i] > 0f && (best < 0 || weight[i] > weight[best])) best = i
        return if (best < 0) null else hours[best]
    }

    /**
     * The snoring episode around bucket [bucket]: the run of snoring buckets that contains it (or
     * starts within [reachSec] of it), joined across quiet pauses of up to [pauseSec]. In seconds.
     */
    fun episodeAt(session: Session, bucket: Int, pauseSec: Int = 30, reachSec: Int = 30): Span? {
        val n = session.levels.size
        if (n == 0) return null
        val bs = session.bucketSec
        fun snoring(i: Int) = i in 0 until n && session.effectiveLevel(i) != Level.QUIET
        val reach = reachSec / bs
        val seed = (0..reach).asSequence().flatMap { d -> sequenceOf(bucket - d, bucket + d) }.firstOrNull { snoring(it) } ?: return null
        val pause = pauseSec / bs
        var lo = seed
        while (true) { val p = (1..pause + 1).firstOrNull { snoring(lo - it) } ?: break; lo -= p }
        var hi = seed
        while (true) { val p = (1..pause + 1).firstOrNull { snoring(hi + it) } ?: break; hi += p }
        return Span(lo * bs, (hi + 1) * bs)
    }

    /** Rounded average score, or 0 for no nights. */
    fun averageScore(sessions: List<Session>): Int =
        if (sessions.isEmpty()) 0 else sessions.map { it.score }.average().roundToInt()

    /** Tags used on any of the nights, factors first, then by name. */
    fun usedTags(sessions: List<Session>): List<String> =
        sessions.flatMap { it.tags }.distinct().sortedWith(compareBy({ Tags.isRemedy(it) }, { Tags.label(it).lowercase() }))

    /** Nights whose note or tag names contain [query], ignoring case. A blank query keeps all. */
    fun search(sessions: List<Session>, query: String): List<Session> {
        val q = query.trim()
        if (q.isEmpty()) return sessions
        return sessions.filter { s -> s.note.contains(q, ignoreCase = true) || s.tags.any { Tags.label(it).contains(q, ignoreCase = true) } }
    }

    fun filterByTag(sessions: List<Session>, tag: String?): List<Session> =
        if (tag == null) sessions else sessions.filter { tag in it.tags }

    /** "7 below your 14-night average" style sentence. */
    fun comparisonText(score: Int, c: Comparison): String {
        val d = c.diff(score)
        val label = "your ${c.nights}-night average"
        return when {
            d == 0 -> "Same as $label"
            d < 0 -> "${-d} below $label"
            else -> "$d above $label"
        }
    }

    /** Short morning-after summary, e.g. "You snored for 1h 34m, 18% of the night. Quieter than your average." */
    fun morningSummary(session: Session, all: List<Session>): String {
        val pct = (session.snoringFraction * 100).roundToInt()
        val first = if (session.snoringSec < 60) "Hardly any snoring was picked up."
        else "You snored for ${formatDuration(session.snoringSec)}, $pct% of the night."
        val c = compareWithRecent(session, all) ?: return first
        val d = c.diff(session.score)
        val second = when {
            d <= -3 -> "Quieter than your average."
            d >= 3 -> "Noisier than your average."
            else -> "About your average."
        }
        return "$first $second"
    }

    /**
     * At most one plain observation about the night, from its own facts: where in the night the
     * snoring sat, how loud it mostly was, or a tagged factor. Null when there is nothing useful.
     */
    fun tip(session: Session): String? {
        val n = session.levels.size
        if (n == 0 || session.snoringSec < 5 * 60) return null
        val thirds = IntArray(3)
        for (i in 0 until n) if (session.effectiveLevel(i) != Level.QUIET) thirds[(i * 3L / n).toInt()]++
        val total = thirds.sum().toFloat()
        val byLevel = session.secondsByLevel
        val snoring = session.snoringSec.toFloat()
        val factor = session.tags.firstOrNull { !Tags.isRemedy(it) }
        return when {
            thirds[2] / total >= 0.5f -> "Most snoring came in the last third of the night."
            thirds[0] / total >= 0.5f -> "Most snoring came in the first third of the night."
            byLevel[Level.HEAVY] / snoring >= 0.5f -> "Over half of the snoring was heavy."
            byLevel[Level.MILD] / snoring >= 0.7f -> "Most of the snoring was mild."
            factor != null -> "You tagged ${Tags.label(factor)}. Trends shows how nights with it compare once there are a few."
            else -> null
        }
    }

    /** "830 KB", "12.4 MB". */
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Plain-text summary of a night for the share sheet. */
    fun shareText(
        session: Session, all: List<Session>, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault(), is24: Boolean = true,
    ): String {
        fun f(p: String, ms: Long) = SimpleDateFormat(if (p == "HH:mm" && !is24) "h:mm a" else p, locale).apply { timeZone = tz }.format(Date(ms))
        val lines = ArrayList<String>()
        lines += "Night of ${f("EEE d MMM", session.startMs)}, ${f("HH:mm", session.startMs)}–${f("HH:mm", session.endMs)}"
        lines += "Snore score: ${session.score}"
        compareWithRecent(session, all)?.let { lines += comparisonText(session.score, it) }
        lines += "Time recorded: ${formatDuration(session.durationSec)}"
        lines += "Time snoring: ${formatDuration(session.snoringSec)} (${(session.snoringFraction * 100).roundToInt()}%)"
        snoringStartSec(session)?.let { lines += "Snoring started: ${f("HH:mm", session.startMs + it * 1000L)}" }
        longestQuiet(session)?.let { lines += "Longest quiet stretch: ${formatDuration(it.lengthSec)}" }
        loudestHour(session, tz)?.let { lines += "Loudest hour: ${f("HH:mm", it)}–${f("HH:mm", it + 3_600_000L)}" }
        val factors = session.tags.filter { !Tags.isRemedy(it) }.map(Tags::label)
        val remedies = session.tags.filter(Tags::isRemedy).map(Tags::label)
        if (factors.isNotEmpty()) lines += "Factors: ${factors.joinToString(", ")}"
        if (remedies.isNotEmpty()) lines += "Remedies: ${remedies.joinToString(", ")}"
        if (session.note.isNotBlank()) lines += "Note: ${session.note.trim()}"
        if (session.demo) lines += "(Sample night, not a real recording)"
        return lines.joinToString("\n")
    }
}
