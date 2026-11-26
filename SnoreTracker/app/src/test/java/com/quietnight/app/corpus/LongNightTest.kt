package com.quietnight.app.corpus

import com.quietnight.app.audio.NightRecorder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A simulated 8-hour night at 16 kHz through [NightRecorder], streamed minute by minute (the night
 * is never held in memory). Each hour: 15 min quiet, 20 min snoring, 5 min quiet, 5 min talking,
 * 15 min quiet. Minutes are drawn from six pre-generated one-minute segments.
 * Prints CPU time per hour of audio for this JVM; a phone will be slower, but not by orders of
 * magnitude.
 */
class LongNightTest {
    private val rate = 16000

    @Test fun eightHourNight() {
        val quiet = listOf(Scene(60.0, 101).roomNoise(), Scene(60.0, 102).roomNoise()).map { it.pcm() }
        val snoring = listOf(
            Scene(60.0, 103).roomNoise().snoring(0.5, 60.0, 400.0, 4.0),
            Scene(60.0, 104).roomNoise().snoring(0.3, 60.0, 600.0, 3.8, noisy = true),
            Scene(60.0, 105).roomNoise().snoring(1.0, 60.0, 300.0, 4.4, pulseHz = 25.0),
        ).map { it.pcm() }
        val talking = Scene(60.0, 106).roomNoise().speech(0.5, 60.0, 500.0, 190.0, 1.0, 5.0).pcm()

        val dir = File("build/tmp/long-night").apply { deleteRecursively() }
        val rt = Runtime.getRuntime()
        System.gc()
        val heapBefore = rt.totalMemory() - rt.freeMemory()

        val rec = NightRecorder("night", 0L, rate, 8f, delayMs = 0, tags = emptyList(), clipDir = dir)
        val buf = ShortArray(1600) // ~100 ms, like the service's read size
        // java.lang.management is not on the Android compile classpath, but the test JVM has it.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val cpuTime = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
        fun cpu() = cpuTime.invoke(bean) as Long
        var cpuNs = 0L
        val minutes = 8 * 60
        var snoringMinutes = 0
        val quietMinuteIdx = ArrayList<Int>()
        for (m in 0 until minutes) {
            val mm = m % 60
            val pcm = when (mm) {
                in 15..34 -> { snoringMinutes++; snoring[m % snoring.size] }
                in 40..44 -> talking
                else -> { if (mm in 1..13 || mm in 46..58) quietMinuteIdx += m; quiet[m % 2] }
            }
            var off = 0
            val t0 = cpu()
            while (off < pcm.size) {
                val n = minOf(buf.size, pcm.size - off)
                System.arraycopy(pcm, off, buf, 0, n)
                rec.feed(buf, n)
                off += n
            }
            cpuNs += cpu() - t0
        }
        rec.finish()
        val s = rec.snapshot()
        System.gc()
        val heapAfter = rt.totalMemory() - rt.freeMemory()

        val cpuPerHourMs = cpuNs / 1e6 / 8
        val falseBuckets = quietMinuteIdx.sumOf { m -> (0 until 6).count { s.levels[m * 6 + it] > 0 } }
        val report = """
            |8-hour simulated night: ${s.durationSec / 3600.0} h, ${s.levels.size} buckets, ${rec.eventCount} events
            |snoring ${s.snoringSec / 60} min (truth $snoringMinutes min), score ${s.score}, clips ${s.clips.size}
            |snoring buckets in quiet-only minutes: $falseBuckets of ${quietMinuteIdx.size * 6}
            |CPU: %.0f ms per hour of audio (%.4f of real time) on this JVM
            |heap growth after GC: %.1f MB
        """.trimMargin().format(cpuPerHourMs, cpuPerHourMs / 3_600_000, (heapAfter - heapBefore) / 1e6)
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/detector-longrun.txt").writeText(report + "\n")

        assertTrue(report, s.durationSec == 8 * 3600)
        assertTrue(report, s.levels.size == 8 * 360)
        // Snoring minutes are mostly marked; a 10 s bucket either side of each run may be too.
        assertTrue(report, s.snoringSec in (snoringMinutes * 60 * 0.85).toInt()..(snoringMinutes * 60 * 1.1).toInt())
        assertTrue(report, falseBuckets == 0)
        assertTrue(report, s.clips.size == 15 && s.clips.all { File(dir, it.file).exists() })
        assertTrue(report, dir.listFiles()!!.size == 15)
        assertTrue(report, heapAfter - heapBefore < 30_000_000)
        // Generous: real time on the phone is 3.6e6 ms per hour; this flags a gross slowdown.
        assertTrue(report, cpuPerHourMs < 60_000)
    }
}
