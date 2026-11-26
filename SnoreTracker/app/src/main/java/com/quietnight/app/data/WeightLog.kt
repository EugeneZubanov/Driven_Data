package com.quietnight.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/** One weight reading for a calendar day (local midnight millis), always stored in kilograms. */
data class WeightEntry(val dayMs: Long, val kg: Float)

data class WeightLog(val entries: List<WeightEntry> = emptyList(), val pounds: Boolean = false)

object Weight {
    const val LB_PER_KG = 2.2046226f
    const val MIN_KG = 20f
    const val MAX_KG = 350f

    fun toDisplay(kg: Float, pounds: Boolean): Float = if (pounds) kg * LB_PER_KG else kg
    fun toKg(value: Float, pounds: Boolean): Float = if (pounds) value / LB_PER_KG else value
    fun unit(pounds: Boolean) = if (pounds) "lb" else "kg"

    /** "72.4 kg" with one decimal. */
    fun format(kg: Float, pounds: Boolean): String {
        val v = (toDisplay(kg, pounds) * 10).roundToInt() / 10f
        return "${if (v % 1f == 0f) v.toInt().toString() else v.toString()} ${unit(pounds)}"
    }

    /** Parses user input such as "72,4" or "160"; null if it is not a plausible weight. */
    fun parseKg(text: String, pounds: Boolean): Float? {
        val v = text.trim().replace(',', '.').toFloatOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        val kg = toKg(v, pounds)
        return if (kg in MIN_KG..MAX_KG) kg else null
    }

    /** Adds or replaces the reading for [dayMs], keeping entries sorted by day. */
    fun put(log: WeightLog, dayMs: Long, kg: Float): WeightLog =
        log.copy(entries = (log.entries.filter { it.dayMs != dayMs } + WeightEntry(dayMs, kg)).sortedBy { it.dayMs })

    /** Adds readings from [incoming] for days that [local] has none for. Returns the new log and how many were added. */
    fun merge(local: WeightLog, incoming: WeightLog): Pair<WeightLog, Int> {
        val have = local.entries.map { it.dayMs }.toSet()
        val add = incoming.entries.filter { it.dayMs !in have }
        return local.copy(entries = (local.entries + add).sortedBy { it.dayMs }) to add.size
    }

    fun remove(log: WeightLog, dayMs: Long): WeightLog = log.copy(entries = log.entries.filter { it.dayMs != dayMs })

    fun toJson(log: WeightLog): JSONObject = JSONObject()
        .put("pounds", log.pounds)
        .put("entries", JSONArray().also { a -> log.entries.forEach { a.put(JSONObject().put("day", it.dayMs).put("kg", it.kg.toDouble())) } })

    /** Lenient: skips entries that are missing fields or out of range. */
    fun fromJson(o: JSONObject): WeightLog {
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val day = e.optLong("day", -1)
            val kg = e.optDouble("kg", Double.NaN).toFloat()
            if (day <= 0 || kg.isNaN() || kg !in MIN_KG..MAX_KG) null else WeightEntry(day, kg)
        }.associateBy { it.dayMs }.values.sortedBy { it.dayMs }
        return WeightLog(entries, o.optBoolean("pounds", false))
    }
}

/** Stores the weight log as one small JSON file in the app's private storage. */
class WeightStore(private val file: File) {
    private val _log = MutableStateFlow(load())
    val log: StateFlow<WeightLog> = _log

    private fun load(): WeightLog =
        runCatching { Weight.fromJson(JSONObject(file.readText())) }.getOrDefault(WeightLog())

    @Synchronized
    fun update(transform: (WeightLog) -> WeightLog) {
        val next = transform(_log.value)
        _log.value = next
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Weight.toJson(next).toString())
        tmp.renameTo(file)
    }

    /** Merges a restored log into this one; returns the number of days added. */
    fun mergeFrom(incoming: WeightLog): Int {
        var added = 0
        update { local -> Weight.merge(local, incoming).also { added = it.second }.first }
        return added
    }

    companion object {
        @Volatile private var instance: WeightStore? = null

        fun get(filesDir: File): WeightStore =
            instance ?: synchronized(this) { instance ?: WeightStore(File(filesDir, "weight.json")).also { instance = it } }
    }
}
