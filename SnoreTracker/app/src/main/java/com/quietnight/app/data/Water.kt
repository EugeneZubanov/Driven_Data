package com.quietnight.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Water-reminder settings. Times are minutes after local midnight. */
data class WaterSettings(
    val on: Boolean = false,
    /** Daily target for drinks, in ml (food adds roughly a fifth on top, so this is drinks only). */
    val targetMl: Int = 2000,
    val wakeMinute: Int = 7 * 60 + 30,
    val bedMinute: Int = 23 * 60,
    /** No reminders this long before bed, so the night is not broken by trips to the toilet. */
    val stopBeforeBedMin: Int = 120,
    val glassMl: Int = 250,
    /** Size of the bottle the user sometimes drinks from over several hours. */
    val bottleSizeMl: Int = 1500,
)

/**
 * The plan for drinking the rest of today's target, kept free of Android types so it can be tested.
 *
 * Rules, from public guidance:
 * - Spread drinks over the waking day rather than drinking a lot at once. Healthy kidneys clear
 *   roughly 0.8–1 L an hour, and drinking faster than that risks low blood sodium (hyponatremia),
 *   so catching up is capped at [MAX_ML_PER_HOUR] = 0.5 L an hour, half that limit.
 * - Stop [WaterSettings.stopBeforeBedMin] before bed (2–3 h is the usual advice for fewer night-time
 *   toilet trips); after that, only sips if thirsty.
 * - If the rest of the target cannot be reached within that cap, the plan says so and aims for what
 *   can be reached, rather than pushing the user to gulp it down. Missing a target on one day is fine.
 */
object WaterPlan {
    /** Highest pace the plan will ever ask for: half the ~1 L/h a healthy adult's kidneys can clear. */
    const val MAX_ML_PER_HOUR = 500
    /** Largest single drink the plan suggests. */
    const val MAX_DRINK_ML = 400
    /** Normal gap between reminders; halved when catching up so each drink stays small. */
    const val NORMAL_INTERVAL_MIN = 60
    const val CATCH_UP_INTERVAL_MIN = 30

    enum class State { BEFORE_DAY, ON_TRACK, BEHIND, CANNOT_CATCH_UP, DONE, AFTER_CUTOFF }

    data class Status(
        val state: State,
        val drankMl: Int,
        val targetMl: Int,
        /** How much the plan expected by now if drinks were spread evenly over the day. */
        val expectedMl: Int,
        /** Where the day will end if the plan is followed (equals the target unless it cannot be reached). */
        val reachableMl: Int,
        /** Pace needed from now to the cut-off, ml per hour (0 when done or outside the day). */
        val perHourMl: Int,
        /** Start and end of today's drinking window. */
        val dayStartMs: Long,
        val cutoffMs: Long,
        /** When the next reminder is due, and how much it suggests; 0 if none today. */
        val nextReminderMs: Long,
        val nextAmountMl: Int,
    ) {
        val remainingMl: Int get() = (targetMl - drankMl).coerceAtLeast(0)
        val fraction: Float get() = if (targetMl <= 0) 1f else (drankMl.toFloat() / targetMl).coerceIn(0f, 1f)
    }

    /** Today's window: from wake-up to [WaterSettings.stopBeforeBedMin] before bed, kept within today. */
    fun window(nowMs: Long, s: WaterSettings, zone: TimeZone = TimeZone.getDefault()): Pair<Long, Long> {
        val start = at(nowMs, s.wakeMinute, zone)
        // A bedtime at or before wake-up time means after midnight; the drinking day still ends by midnight.
        val bed = if (s.bedMinute > s.wakeMinute) at(nowMs, s.bedMinute, zone) else at(nowMs, 24 * 60, zone)
        val cutoff = (bed - s.stopBeforeBedMin * 60_000L).coerceAtLeast(start + 60 * 60_000L)
        return start to cutoff
    }

    fun status(nowMs: Long, drankMl: Int, s: WaterSettings, zone: TimeZone = TimeZone.getDefault()): Status {
        val (start, cutoff) = window(nowMs, s, zone)
        val target = s.targetMl.coerceAtLeast(0)
        val dayMs = (cutoff - start).toDouble()
        val elapsed = ((nowMs - start) / dayMs).coerceIn(0.0, 1.0)
        val expected = (target * elapsed).roundToInt()
        val remaining = (target - drankMl).coerceAtLeast(0)
        fun st(state: State, reachable: Int, perHour: Int, next: Long, amount: Int) =
            Status(state, drankMl, target, expected, reachable, perHour, start, cutoff, next, amount)

        if (remaining == 0) return st(State.DONE, drankMl, 0, 0, 0)
        if (nowMs >= cutoff) return st(State.AFTER_CUTOFF, drankMl, 0, 0, 0)
        if (nowMs < start) {
            val perHour = ceil(target / (dayMs / 3_600_000.0)).toInt()
            return st(State.BEFORE_DAY, target, perHour, start, roundDrink(perHour * NORMAL_INTERVAL_MIN / 60, s.glassMl))
        }

        val hoursLeft = (cutoff - nowMs) / 3_600_000.0
        val capacity = (MAX_ML_PER_HOUR * hoursLeft).toInt()
        val reachable = drankMl + minOf(remaining, capacity)
        val perHour = minOf(ceil(remaining / hoursLeft).toInt(), MAX_ML_PER_HOUR)
        val state = when {
            remaining > capacity -> State.CANNOT_CATCH_UP
            drankMl < expected - s.glassMl / 2 -> State.BEHIND
            else -> State.ON_TRACK
        }
        // Catching up: remind more often with smaller drinks, instead of one big one.
        val interval = if (perHour > 300) CATCH_UP_INTERVAL_MIN else NORMAL_INTERVAL_MIN
        val next = nowMs + interval * 60_000L
        val amount = roundDrink(perHour * interval / 60, s.glassMl).coerceAtMost(reachable - drankMl)
        return if (next >= cutoff) st(state, reachable, perHour, 0, 0) else st(state, reachable, perHour, next, amount)
    }

    /** The plan in one or two plain sentences, for the card and the notification. */
    fun message(st: Status, clock: (Long) -> String): String = when (st.state) {
        State.DONE -> "You've reached today's ${liters(st.targetMl)}. Drink more if you're thirsty."
        State.BEFORE_DAY -> "Your water day starts at ${clock(st.dayStartMs)}: about ${pace(st.perHourMl)} ml an hour until ${clock(st.cutoffMs)}."
        State.AFTER_CUTOFF ->
            "It's close to bedtime, so no more reminders today; just sip if you're thirsty. " +
                "Tomorrow, try to drink more in the first half of the day."
        State.ON_TRACK -> "On track. About ${pace(st.perHourMl)} ml an hour until ${clock(st.cutoffMs)} gets you there."
        State.BEHIND ->
            "A little behind, so the rest is spread out: about ${pace(st.perHourMl)} ml an hour until ${clock(st.cutoffMs)}. " +
                "No need to drink it all at once."
        State.CANNOT_CATCH_UP ->
            "Too much is left to drink safely before ${clock(st.cutoffMs)}. Aim for ${liters(st.reachableMl)} today " +
                "(about ${pace(st.perHourMl)} ml an hour, never more than a glass at a time) and start earlier tomorrow."
    }

    fun liters(ml: Int): String = when {
        ml in 1..999 -> "$ml ml"
        ml % 1000 == 0 -> "${ml / 1000} L"
        else -> "%.1f L".format(ml / 1000.0)
    }

    /** Pace for people to read: nearest 10 ml. */
    private fun pace(ml: Int) = ((ml + 5) / 10) * 10

    /** A sensible drink: nearest 50 ml, at least 100 ml, at most [MAX_DRINK_ML], near the user's glass. */
    private fun roundDrink(ml: Int, glassMl: Int): Int {
        val r = ((ml + 25) / 50) * 50
        return r.coerceIn(100, minOf(MAX_DRINK_ML, maxOf(glassMl, 100) * 2))
    }

    private fun at(nowMs: Long, minuteOfDay: Int, zone: TimeZone): Long = Calendar.getInstance(zone).run {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.MINUTE, minuteOfDay)
        timeInMillis
    }

    /** "2026-11-28": the calendar day a drink counts towards. */
    fun dayKey(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String = Calendar.getInstance(zone).run {
        timeInMillis = nowMs
        "%04d-%02d-%02d".format(get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH))
    }
}

/** Today's drinks and the water settings, kept in SharedPreferences. Resets at local midnight. */
class WaterStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val sp = context.getSharedPreferences("water", Context.MODE_PRIVATE)

    /**
     * Today's logged drinks, plus an open bottle if any. While a bottle is open its intake is unknown,
     * so the sips the reminders suggested count as an estimate (never more than the bottle) until the
     * bottle is marked finished or put away, when the real amount replaces the estimate.
     */
    data class Today(val day: String, val drinks: List<Int>, val bottleMl: Int = 0, val bottleEstimateMl: Int = 0) {
        val bottleOpen: Boolean get() = bottleMl > 0
        /** Estimated intake from the open bottle so far. */
        val bottleSoFarMl: Int get() = if (bottleOpen) minOf(bottleEstimateMl, bottleMl) else 0
        /** What the plan works from: logged drinks plus the open bottle's estimate. */
        val totalMl: Int get() = drinks.sum() + bottleSoFarMl
        val isEstimate: Boolean get() = bottleSoFarMl > 0
    }

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<WaterSettings> = _settings
    private val _today = MutableStateFlow(readToday())
    val today: StateFlow<Today> = _today

    private fun readSettings() = WaterSettings(
        on = sp.getBoolean("on", false),
        targetMl = sp.getInt("targetMl", 2000),
        wakeMinute = sp.getInt("wakeMinute", 7 * 60 + 30),
        bedMinute = sp.getInt("bedMinute", 23 * 60),
        stopBeforeBedMin = sp.getInt("stopBeforeBedMin", 120),
        glassMl = sp.getInt("glassMl", 250),
        bottleSizeMl = sp.getInt("bottleSizeMl", 1500),
    )

    private fun readToday(): Today {
        val key = WaterPlan.dayKey(clock())
        // A new day starts empty; a bottle left open overnight is closed without logging a guess.
        if (sp.getString("day", null) != key) return Today(key, emptyList())
        val drinks = sp.getString("drinks", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        return Today(key, drinks, sp.getInt("bottleMl", 0), sp.getInt("bottleEstimateMl", 0))
    }

    /** Re-reads today's total, e.g. after midnight or after a drink logged from the notification. */
    fun refresh() { _today.value = readToday() }

    fun updateSettings(transform: (WaterSettings) -> WaterSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        sp.edit().putBoolean("on", s.on).putInt("targetMl", s.targetMl).putInt("wakeMinute", s.wakeMinute)
            .putInt("bedMinute", s.bedMinute).putInt("stopBeforeBedMin", s.stopBeforeBedMin).putInt("glassMl", s.glassMl)
            .putInt("bottleSizeMl", s.bottleSizeMl).apply()
    }

    @Synchronized
    fun add(ml: Int) {
        val t = readToday()
        save(t.copy(drinks = t.drinks + ml.coerceIn(1, 5000)))
    }

    @Synchronized
    fun undo() {
        val t = readToday()
        if (t.drinks.isNotEmpty()) save(t.copy(drinks = t.drinks.dropLast(1)))
    }

    @Synchronized
    fun openBottle(sizeMl: Int) {
        val t = readToday()
        save(t.copy(bottleMl = sizeMl.coerceIn(100, 5000), bottleEstimateMl = 0))
    }

    /** A reminder suggested [ml] from the open bottle: counted as an estimate until the bottle is closed. */
    @Synchronized
    fun noteBottleSips(ml: Int) {
        val t = readToday()
        if (t.bottleOpen) save(t.copy(bottleEstimateMl = minOf(t.bottleMl, t.bottleEstimateMl + ml.coerceAtLeast(0))))
    }

    /** The bottle is empty: log all of it, replacing the estimate. */
    @Synchronized
    fun finishBottle() {
        val t = readToday()
        if (t.bottleOpen) save(Today(t.day, t.drinks + t.bottleMl))
    }

    /** Stopped before the end: log the part drunk ([drunkFraction] of the bottle), replacing the estimate. */
    @Synchronized
    fun putBottleAway(drunkFraction: Float) {
        val t = readToday()
        if (!t.bottleOpen) return
        val ml = (t.bottleMl * drunkFraction.coerceIn(0f, 1f)).toInt()
        save(Today(t.day, if (ml > 0) t.drinks + ml else t.drinks))
    }

    private fun save(t: Today) {
        // commit: the notification actions may log from a short-lived receiver.
        sp.edit().putString("day", t.day).putString("drinks", t.drinks.joinToString(","))
            .putInt("bottleMl", t.bottleMl).putInt("bottleEstimateMl", t.bottleEstimateMl).commit()
        _today.value = t
    }
}
