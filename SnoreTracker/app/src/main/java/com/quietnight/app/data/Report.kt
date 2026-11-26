package com.quietnight.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the shareable report shows, worked out from the nights in a window of [days] days. */
class ReportData(
    val fromMs: Long,
    val toMs: Long,
    val days: Int,
    /** Real (non-sample) nights in the window, oldest first. */
    val nights: List<Session>,
    val averageScore: Int,
    /** Seconds snoring at mild, loud and heavy, summed over the window. */
    val snoringSec: IntArray,
    val recordedSec: Long,
    val tags: TagEffects,
    /** Weight readings in the window, oldest first. */
    val weight: List<WeightEntry>,
    val pounds: Boolean,
) {
    val hasNights: Boolean get() = nights.isNotEmpty()

    fun rangeLabel(): String {
        val f = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        return "${f.format(Date(fromMs))} – ${f.format(Date(toMs))}"
    }

    companion object {
        const val DAYS = 30
        const val NOTE = "Sound-based estimate from a phone microphone. Not a medical test or diagnosis."

        fun build(sessions: List<Session>, weight: WeightLog?, nowMs: Long, days: Int = DAYS): ReportData {
            val to = Trends.dayOf(nowMs).timeInMillis
            val from = Trends.dayOf(nowMs).apply { add(java.util.Calendar.DAY_OF_MONTH, -(days - 1)) }.timeInMillis
            val nights = sessions.filter { !it.demo && Trends.dayOf(it).timeInMillis in from..to }.sortedBy { it.startMs }
            val snoring = IntArray(3) { l -> nights.sumOf { it.secondsByLevel[l + 1] } }
            return ReportData(
                fromMs = from,
                toMs = to,
                days = days,
                nights = nights,
                averageScore = if (nights.isEmpty()) 0 else nights.map { it.score }.average().let { Math.round(it).toInt() },
                snoringSec = snoring,
                recordedSec = nights.sumOf { it.levels.size.toLong() * it.bucketSec },
                tags = Trends.tagEffects(nights),
                weight = weight?.entries.orEmpty().filter { it.dayMs in from..to }.sortedBy { it.dayMs },
                pounds = weight?.pounds ?: false,
            )
        }

        fun fileName(nowMs: Long) = "quiet-night-report-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))}.pdf"
    }
}
