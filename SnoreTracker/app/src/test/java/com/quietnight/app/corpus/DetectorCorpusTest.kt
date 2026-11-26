package com.quietnight.app.corpus

import com.quietnight.app.audio.SnoreDetector
import com.quietnight.app.audio.SnoreEvent
import com.quietnight.app.data.Level
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the detector over a synthetic corpus (see [Scene] for what that can and cannot show) and
 * prints a table of hit rate and false-alarm time per scenario, next to the original detector, so
 * that regressions are visible. Written to app/build/reports/detector-corpus.txt as well.
 */
class DetectorCorpusTest {
    private val rate = 16000

    class Scenario(val name: String, val snoring: Boolean, val gain: Double = 1.0, val build: () -> Scene)

    data class Score(val truth: Int, val hits: Int, val events: Int, val falseSec: Double, val levels: IntArray) {
        val hitRate get() = if (truth == 0) Double.NaN else hits.toDouble() / truth
    }

    private val snoreScenarios = listOf(
        Scenario("snoring, quiet room", true) { Scene(180.0, 11).roomNoise().snoring(5.0, 180.0, 350.0) },
        Scenario("snoring, period 3->6 s", true) { Scene(240.0, 12).roomNoise().snoring(5.0, 240.0, 350.0, 3.0, 6.0) },
        Scenario("snoring, slow pulses 20/s", true) { Scene(180.0, 13).roomNoise().snoring(5.0, 180.0, 350.0, 4.5, pulseHz = 20.0) },
        Scenario("snoring, fast pulses 60/s", true) { Scene(180.0, 14).roomNoise().snoring(5.0, 180.0, 350.0, 3.5, pulseHz = 60.0) },
        Scenario("snoring, noise-like", true) { Scene(180.0, 15).roomNoise().snoring(5.0, 180.0, 350.0, noisy = true) },
        Scenario("quiet snoring ~11 dB", true) { Scene(180.0, 16).roomNoise().snoring(5.0, 180.0, 125.0, jitterDb = 1.5) },
        Scenario("loud snoring", true) { Scene(180.0, 17).roomNoise().snoring(5.0, 180.0, 3000.0) },
        Scenario("clipping snoring", true) { Scene(180.0, 18).roomNoise().snoring(5.0, 180.0, 40000.0) },
        Scenario("very quiet phone (1/40)", true, gain = 1.0 / 40) { Scene(180.0, 19).roomNoise().snoring(5.0, 180.0, 350.0) },
        Scenario("snoring over fan", true) { Scene(180.0, 20).roomNoise().fan(400.0).snoring(5.0, 180.0, 1600.0) },
        Scenario("snoring over rain", true) { Scene(180.0, 21).roomNoise().rain(300.0).snoring(5.0, 180.0, 1400.0) },
        Scenario("fan turns on mid-snoring", true) { Scene(240.0, 22).roomNoise().fan(400.0, 100.0).snoring(5.0, 240.0, 1600.0) },
        Scenario("snoring + partner talking", true) { Scene(240.0, 23).roomNoise().snoring(5.0, 240.0, 500.0).speech(60.0, 150.0, 400.0, 200.0, 1.0, 6.0) },
    )

    private val distractors = listOf(
        Scenario("room noise only", false) { Scene(180.0, 31).roomNoise() },
        Scenario("monologue (speech)", false) { Scene(240.0, 32).roomNoise().speech(3.0, 240.0, 600.0) },
        Scenario("partner talking", false) { Scene(240.0, 33).roomNoise().speech(3.0, 240.0, 400.0, 200.0, 1.0, 8.0) },
        Scenario("TV (speech + music)", false) { Scene(240.0, 34).roomNoise().music(0.0, 240.0, 200.0).speech(3.0, 240.0, 500.0, 140.0) },
        Scenario("music", false) { Scene(180.0, 35).roomNoise().music(0.0, 180.0, 600.0) },
        Scenario("traffic rumble", false) { Scene(240.0, 36).roomNoise().traffic(0.0, 240.0, 1500.0) },
        Scenario("fan on at 60 s, off at 150 s", false) { Scene(240.0, 37).roomNoise().fan(500.0, 60.0, 150.0) },
        Scenario("coughing fits", false) { Scene(240.0, 38).roomNoise().coughing(0.0, 240.0, 1500.0) },
        Scenario("bed rustling", false) { Scene(180.0, 39).roomNoise().rustling(0.0, 180.0, 600.0) },
        Scenario("dog barking", false) { Scene(180.0, 40).roomNoise().barking(0.0, 180.0, 1500.0) },
        Scenario("dog barking, slow (2.5 s)", false) { Scene(180.0, 41).roomNoise().barking(0.0, 180.0, 1500.0, 2.5) },
        Scenario("ticking clock", false) { Scene(120.0, 42).roomNoise().clock(800.0) },
        Scenario("phone notifications", false) { Scene(240.0, 43).roomNoise().notifications(0.0, 240.0, 1200.0) },
        Scenario("rain", false) { Scene(180.0, 44).roomNoise().rain(400.0) },
    )

    private fun run(pcm: ShortArray, threshold: Float, legacy: Boolean): List<SnoreEvent> {
        val events = ArrayList<SnoreEvent>()
        val feed: (ShortArray, Int, Int) -> Unit = if (legacy) {
            val d = LegacySnoreDetector(rate, threshold) { events += it }
            val tmp = ShortArray(1600)
            val f: (ShortArray, Int, Int) -> Unit = { a, off, n -> System.arraycopy(a, off, tmp, 0, n); d.feed(tmp, n) }
            f
        } else {
            val d = SnoreDetector(rate, threshold) { events += it }
            val f: (ShortArray, Int, Int) -> Unit = { a, off, n -> d.feed(a, off, n) }
            f
        }
        var off = 0
        while (off < pcm.size) { val n = minOf(1600, pcm.size - off); feed(pcm, off, n); off += n }
        return events
    }

    private fun score(scene: Scene, events: List<SnoreEvent>): Score {
        val pad = 150L
        val hit = BooleanArray(scene.snores.size)
        var falseMs = 0L
        val levels = IntArray(4)
        for (e in events) {
            var matched = false
            scene.snores.forEachIndexed { i, s ->
                if (e.startMs < s[1] + pad && e.startMs + e.durationMs > s[0] - pad) { hit[i] = true; matched = true }
            }
            if (!matched) falseMs += e.durationMs
            levels[Level.fromDb(e.dbAboveFloor)]++
        }
        return Score(scene.snores.size, hit.count { it }, events.size, falseMs / 1000.0, levels)
    }

    private val thresholds = floatArrayOf(11f, 8f, 5.5f)

    /** Scores for one scenario: index 0..2 = new detector at [thresholds], 3 = old detector at 8 dB. */
    private fun evaluate(s: Scenario): List<Score> {
        val scene = s.build()
        val pcm = scene.pcm(s.gain)
        return thresholds.map { score(scene, run(pcm, it, legacy = false)) } + score(scene, run(pcm, 8f, legacy = true))
    }

    private fun fmt(sc: Score) =
        if (sc.truth > 0) "%3d/%-3d %4.0f%%  fa %5.1fs".format(sc.hits, sc.truth, 100 * sc.hitRate, sc.falseSec)
        else "ev %3d     fa %5.1fs".format(sc.events, sc.falseSec)

    @Test fun corpusTable() {
        val out = StringBuilder()
        out.appendLine("Snore detector on the synthetic corpus (threshold 8 dB = normal sensitivity)")
        out.appendLine("%-30s | %-23s | %-23s | %s".format("scenario", "new: hits  rate  false", "old: hits  rate  false", "new levels M/L/H"))
        val all = (snoreScenarios + distractors).associate { it.name to evaluate(it) }
        val results = all.mapValues { it.value[1] }
        for (s in snoreScenarios + distractors) {
            val new = all.getValue(s.name)[1]
            val old = all.getValue(s.name)[3]
            out.appendLine("%-30s | %-23s | %-23s | %d/%d/%d".format(s.name, fmt(new), fmt(old), new.levels[1], new.levels[2], new.levels[3]))
        }
        out.appendLine()
        out.appendLine("Sensitivity sweep (new detector): snore hit rate / distractor false-alarm seconds")
        for ((ti, label) in listOf("low 11 dB", "normal 8 dB", "high 5.5 dB").withIndex()) {
            val hits = snoreScenarios.map { all.getValue(it.name)[ti] }
            val fa = distractors.sumOf { all.getValue(it.name)[ti].falseSec }
            val faSnore = hits.sumOf { it.falseSec }
            val worst = hits.indices.minBy { hits[it].hitRate }
            out.appendLine("%-12s hit %5.1f%% (min %4.0f%% '%s')  distractor fa %6.1fs  fa inside snore scenes %5.1fs".format(
                label, 100.0 * hits.sumOf { it.hits } / hits.sumOf { it.truth },
                100 * hits[worst].hitRate, snoreScenarios[worst].name, fa, faSnore))
        }
        println(out)
        File("build/reports").mkdirs()
        File("build/reports/detector-corpus.txt").writeText(out.toString())

        // Regression gates at normal sensitivity. Synthetic, so these only catch regressions.
        val problems = ArrayList<String>()
        for (s in snoreScenarios) {
            val sc = results.getValue(s.name)
            val minRate = 0.8
            if (sc.hitRate < minRate) problems += "${s.name}: hit rate ${sc.hitRate}"
            if (sc.falseSec > 3.0) problems += "${s.name}: ${sc.falseSec}s false alarms"
        }
        for (s in distractors) {
            val sc = results.getValue(s.name)
            if (sc.falseSec > 0.0) problems += "${s.name}: ${sc.falseSec}s false alarms"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
