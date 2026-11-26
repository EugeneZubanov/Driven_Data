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

    /**
     * [gated]: counted by the regression gates; false for known weak spots that are only reported.
     * [maxFalseSec]: allowed false-alarm time for a distractor; above 0 only for partial weak spots.
     */
    class Scenario(
        val name: String, val snoring: Boolean, val gain: Double = 1.0, val gated: Boolean = true,
        val maxFalseSec: Double = 0.0, val build: (rate: Int) -> Scene,
    )

    /** [levels]: by dbAboveFloor (night baseline); [localLevels]: by the floor at that moment, as before. */
    data class Score(val truth: Int, val hits: Int, val events: Int, val falseSec: Double, val levels: IntArray, val localLevels: IntArray) {
        val hitRate get() = if (truth == 0) Double.NaN else hits.toDouble() / truth
    }

    private val snoreScenarios = listOf(
        Scenario("snoring, quiet room", true) { r -> Scene(180.0, 11, r).roomNoise().snoring(5.0, 180.0, 350.0) },
        Scenario("snoring, period 3->6 s", true) { r -> Scene(240.0, 12, r).roomNoise().snoring(5.0, 240.0, 350.0, 3.0, 6.0) },
        Scenario("snoring, slow pulses 20/s", true) { r -> Scene(180.0, 13, r).roomNoise().snoring(5.0, 180.0, 350.0, 4.5, pulseHz = 20.0) },
        Scenario("snoring, fast pulses 60/s", true) { r -> Scene(180.0, 14, r).roomNoise().snoring(5.0, 180.0, 350.0, 3.5, pulseHz = 60.0) },
        Scenario("snoring, noise-like", true) { r -> Scene(180.0, 15, r).roomNoise().snoring(5.0, 180.0, 350.0, noisy = true) },
        Scenario("irregular breathing (+-25%)", true) { r -> Scene(240.0, 24, r).roomNoise().snoring(5.0, 240.0, 350.0, periodJitter = 0.25) },
        Scenario("short snores 0.4-0.7 s", true) { r -> Scene(180.0, 25, r).roomNoise().snoring(5.0, 180.0, 350.0, 3.5, minDur = 0.4, maxDur = 0.7) },
        Scenario("near-threshold snoring ~9 dB", true) { r -> Scene(180.0, 26, r).roomNoise().snoring(5.0, 180.0, 100.0, jitterDb = 1.5) },
        Scenario("quiet snoring ~11 dB", true) { r -> Scene(180.0, 16, r).roomNoise().snoring(5.0, 180.0, 125.0, jitterDb = 1.5) },
        Scenario("loud snoring", true) { r -> Scene(180.0, 17, r).roomNoise().snoring(5.0, 180.0, 3000.0) },
        Scenario("clipping snoring", true) { r -> Scene(180.0, 18, r).roomNoise().snoring(5.0, 180.0, 40000.0) },
        Scenario("very quiet phone (1/40)", true, gain = 1.0 / 40) { r -> Scene(180.0, 19, r).roomNoise().snoring(5.0, 180.0, 350.0) },
        Scenario("snoring over fan", true) { r -> Scene(180.0, 20, r).roomNoise().fan(400.0).snoring(5.0, 180.0, 1600.0) },
        Scenario("snoring over rain", true) { r -> Scene(180.0, 21, r).roomNoise().rain(300.0).snoring(5.0, 180.0, 1400.0) },
        Scenario("fan turns on mid-snoring", true) { r -> Scene(240.0, 22, r).roomNoise().fan(400.0, 100.0).snoring(5.0, 240.0, 1600.0) },
        Scenario("two snorers interleaved", true) { r -> Scene(240.0, 27, r).roomNoise().snoring(5.0, 240.0, 450.0, 4.0).snoring(7.0, 240.0, 250.0, 5.3, pulseHz = 28.0) },
        Scenario("snoring + partner talking", true) { r -> Scene(240.0, 23, r).roomNoise().snoring(5.0, 240.0, 500.0).speech(60.0, 150.0, 400.0, 200.0, 1.0, 6.0) },
    )

    private val distractors = listOf(
        Scenario("room noise only", false) { r -> Scene(180.0, 31, r).roomNoise() },
        Scenario("monologue (speech)", false) { r -> Scene(240.0, 32, r).roomNoise().speech(3.0, 240.0, 600.0) },
        Scenario("partner talking", false) { r -> Scene(240.0, 33, r).roomNoise().speech(3.0, 240.0, 400.0, 200.0, 1.0, 8.0) },
        Scenario("TV (speech + music)", false) { r -> Scene(240.0, 34, r).roomNoise().music(0.0, 240.0, 200.0).speech(3.0, 240.0, 500.0, 140.0) },
        Scenario("music", false) { r -> Scene(180.0, 35, r).roomNoise().music(0.0, 180.0, 600.0) },
        Scenario("traffic rumble", false) { r -> Scene(240.0, 36, r).roomNoise().traffic(0.0, 240.0, 1500.0) },
        Scenario("fan on at 60 s, off at 150 s", false) { r -> Scene(240.0, 37, r).roomNoise().fan(500.0, 60.0, 150.0) },
        Scenario("coughing fits", false) { r -> Scene(240.0, 38, r).roomNoise().coughing(0.0, 240.0, 1500.0) },
        Scenario("bed rustling", false) { r -> Scene(180.0, 39, r).roomNoise().rustling(0.0, 180.0, 600.0) },
        Scenario("dog barking", false) { r -> Scene(180.0, 40, r).roomNoise().barking(0.0, 180.0, 1500.0) },
        Scenario("dog barking, slow (2.5 s)", false) { r -> Scene(180.0, 41, r).roomNoise().barking(0.0, 180.0, 1500.0, 2.5) },
        Scenario("ticking clock", false) { r -> Scene(120.0, 42, r).roomNoise().clock(800.0) },
        Scenario("phone notifications", false) { r -> Scene(240.0, 43, r).roomNoise().notifications(0.0, 240.0, 1200.0) },
        Scenario("rain", false) { r -> Scene(180.0, 44, r).roomNoise().rain(400.0) },
        Scenario("heavy breathing, no snore", false) { r -> Scene(180.0, 45, r).roomNoise().breathing(0.0, 180.0, 400.0) },
        // Weak spot: creaky talking is rough and low like a snore; 4 minutes of it still gives ~6 s.
        Scenario("creaky voice (vocal fry) *", false, maxFalseSec = 10.0) { r -> Scene(240.0, 47, r).roomNoise().speech(3.0, 240.0, 500.0, 45.0, pulseJitter = 0.2) },
        Scenario("dual-tone phone ringer x8", false) { r -> Scene(60.0, 46, r).roomNoise().dualToneRinger(10.0, 8, 1500.0) },
    )

    private fun run(pcm: ShortArray, threshold: Float, legacy: Boolean, rate: Int = this.rate): List<SnoreEvent> {
        val events = ArrayList<SnoreEvent>()
        val feed: (ShortArray, Int, Int) -> Unit = if (legacy) {
            val d = LegacySnoreDetector(rate, threshold) { events += it }
            val tmp = ShortArray(rate / 10)
            val f: (ShortArray, Int, Int) -> Unit = { a, off, n -> System.arraycopy(a, off, tmp, 0, n); d.feed(tmp, n) }
            f
        } else {
            val d = SnoreDetector(rate, threshold) { events += it }
            val f: (ShortArray, Int, Int) -> Unit = { a, off, n -> d.feed(a, off, n) }
            f
        }
        var off = 0
        val chunk = rate / 10 // 100 ms reads, as the service does
        while (off < pcm.size) { val n = minOf(chunk, pcm.size - off); feed(pcm, off, n); off += n }
        return events
    }

    private fun score(scene: Scene, events: List<SnoreEvent>): Score {
        val pad = 150L
        val hit = BooleanArray(scene.snores.size)
        var falseMs = 0L
        val levels = IntArray(4)
        val localLevels = IntArray(4)
        for (e in events) {
            var matched = false
            scene.snores.forEachIndexed { i, s ->
                if (e.startMs < s[1] + pad && e.startMs + e.durationMs > s[0] - pad) { hit[i] = true; matched = true }
            }
            if (!matched) falseMs += e.durationMs
            levels[Level.fromDb(e.dbAboveFloor)]++
            localLevels[Level.fromDb(e.dbAboveLocalFloor)]++
        }
        return Score(scene.snores.size, hit.count { it }, events.size, falseMs / 1000.0, levels, localLevels)
    }

    private val thresholds = floatArrayOf(11f, 8f, 5.5f)

    /** Scores for one scenario: index 0..2 = new detector at [thresholds], 3 = old detector at 8 dB. */
    private fun evaluate(s: Scenario): List<Score> {
        val scene = s.build(rate)
        val pcm = scene.pcm(s.gain)
        return thresholds.map { score(scene, run(pcm, it, legacy = false)) } + score(scene, run(pcm, 8f, legacy = true))
    }

    /**
     * Scenarios are independent, so they run on up to 3 threads (results kept in order). Three, not
     * more, because a 4-minute scene at 48 kHz holds ~90 MB while it is generated.
     */
    private fun <T> parallel(items: List<Scenario>, f: (Scenario) -> T): List<T> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(3, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
        try {
            return items.map { s -> pool.submit<T> { f(s) } }.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun fmt(sc: Score) =
        if (sc.truth > 0) "%3d/%-3d %4.0f%%  fa %5.1fs".format(sc.hits, sc.truth, 100 * sc.hitRate, sc.falseSec)
        else "ev %3d     fa %5.1fs".format(sc.events, sc.falseSec)

    @Test fun corpusTable() {
        val out = StringBuilder()
        out.appendLine("Snore detector on the synthetic corpus (threshold 8 dB = normal sensitivity)")
        out.appendLine("%-30s | %-23s | %-23s | %-13s | %s".format("scenario", "new: hits  rate  false", "old: hits  rate  false", "M/L/H local", "M/L/H baseline"))
        val all = parallel(snoreScenarios + distractors) { evaluate(it) }.let { r -> (snoreScenarios + distractors).map { it.name }.zip(r).toMap() }
        val results = all.mapValues { it.value[1] }
        for (s in snoreScenarios + distractors) {
            val new = all.getValue(s.name)[1]
            val old = all.getValue(s.name)[3]
            val loc = new.localLevels
            out.appendLine("%-30s | %-23s | %-23s | %-13s | %d/%d/%d".format(s.name + if (s.gated) "" else " *", fmt(new), fmt(old),
                "${loc[1]}/${loc[2]}/${loc[3]}", new.levels[1], new.levels[2], new.levels[3]))
        }
        out.appendLine("* known weak spot: not gated, or gated at a non-zero false-alarm ceiling")
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
        for (s in snoreScenarios.filter { it.gated }) {
            val sc = results.getValue(s.name)
            val minRate = 0.8
            if (sc.hitRate < minRate) problems += "${s.name}: hit rate ${sc.hitRate}"
            if (sc.falseSec > 3.0) problems += "${s.name}: ${sc.falseSec}s false alarms"
        }
        for (s in distractors.filter { it.gated }) {
            val sc = results.getValue(s.name)
            if (sc.falseSec > s.maxFalseSec) problems += "${s.name}: ${sc.falseSec}s false alarms"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Problems against the gates for one scenario at normal sensitivity. */
    private fun gateProblems(s: Scenario, sc: Score): List<String> = when {
        !s.gated -> emptyList()
        s.snoring -> listOfNotNull(
            if (sc.hitRate < 0.8) "${s.name}: hit rate ${sc.hitRate}" else null,
            if (sc.falseSec > 3.0) "${s.name}: ${sc.falseSec}s false alarms" else null,
        )
        else -> listOfNotNull(if (sc.falseSec > s.maxFalseSec) "${s.name}: ${sc.falseSec}s false alarms" else null)
    }

    /**
     * Android only guarantees 44.1 kHz capture, so the service may run at 44.1 or 48 kHz. The same
     * scenes are generated natively at those rates (not resampled) and must pass the same gates.
     */
    @Test fun corpusAtCaptureRates() {
        val out = StringBuilder("Snore detector at other capture rates (8 dB)\n")
        val problems = ArrayList<String>()
        for (r in intArrayOf(44100, 48000)) {
            var hits = 0; var truth = 0; var fa = 0.0
            val scores = parallel(snoreScenarios + distractors) { s ->
                val scene = s.build(r)
                val pcm = scene.pcm(s.gain)
                val events = run(pcm, 8f, legacy = false, rate = r)
                score(scene, events)
            }
            for ((s, sc) in (snoreScenarios + distractors).zip(scores)) {
                hits += sc.hits; truth += sc.truth; if (!s.snoring) fa += sc.falseSec
                problems += gateProblems(s, sc).map { "$r Hz $it" }
                if (sc.hitRate < 0.95 || sc.falseSec > 0) out.appendLine("  $r Hz %-30s %s".format(s.name, fmt(sc)))
            }
            out.appendLine("%d Hz: hit %.1f%%, distractor false alarms %.1fs".format(r, 100.0 * hits / truth, fa))
        }
        println(out)
        File("build/reports").mkdirs()
        File("build/reports/detector-corpus-rates.txt").writeText(out.toString())
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
