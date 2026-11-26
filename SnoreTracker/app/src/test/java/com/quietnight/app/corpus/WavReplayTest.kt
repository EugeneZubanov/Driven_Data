package com.quietnight.app.corpus

import com.quietnight.app.audio.NightRecorder
import com.quietnight.app.audio.SnoreDetector
import com.quietnight.app.audio.SnoreEvent
import com.quietnight.app.data.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replays WAV files through the detector so shared recordings can become regression cases:
 *   ./gradlew :app:testDebugUnitTest --tests '*WavReplayTest*' -Pwavs=/path/to/folder
 * (or set QN_WAVS). Skipped otherwise. Report: app/build/reports/wav-replay.txt
 */
class WavReplayTest {
    /** 16-bit PCM WAV (any rate, channels averaged to mono). */
    fun readWav(f: File): Pair<Int, ShortArray> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 12 && b.getInt(0) == 0x46464952 && b.getInt(8) == 0x45564157) { "not a WAV file" }
        var pos = 12
        var rate = 0; var channels = 0; var bits = 0
        while (pos + 8 <= b.limit()) {
            val id = b.getInt(pos); val len = b.getInt(pos + 4)
            val body = pos + 8
            if (id == 0x20746d66) { // "fmt "
                channels = b.getShort(body + 2).toInt(); rate = b.getInt(body + 4); bits = b.getShort(body + 14).toInt()
            } else if (id == 0x61746164) { // "data"
                require(bits == 16 && channels >= 1) { "only 16-bit PCM is supported (bits=$bits)" }
                val frames = minOf(len, b.limit() - body) / (2 * channels)
                return rate to ShortArray(frames) { i ->
                    var sum = 0
                    for (c in 0 until channels) sum += b.getShort(body + (i * channels + c) * 2)
                    (sum / channels).toShort()
                }
            }
            pos = body + len + (len and 1)
        }
        error("no data chunk")
    }

    fun report(f: File, thresholdDb: Float = 8f): String {
        val (rate, pcm) = readWav(f)
        val events = ArrayList<SnoreEvent>()
        val det = SnoreDetector(rate, thresholdDb) { events += it }
        det.feed(pcm, 0, pcm.size)
        val rec = NightRecorder("replay", 0L, rate, thresholdDb, delayMs = 0, tags = emptyList(), clipDir = null)
        rec.feed(pcm, pcm.size)
        val s = rec.snapshot()
        return buildString {
            appendLine("== ${f.name}: %.1f s at %d Hz".format(pcm.size.toDouble() / rate, rate))
            for (e in events) appendLine("  snore %6.1f s  %4.1f s  %5.1f dB (local %5.1f)  %s  conf %.1f".format(
                e.startMs / 1000.0, e.durationMs / 1000.0, e.dbAboveFloor, e.dbAboveLocalFloor, Level.names[Level.fromDb(e.dbAboveFloor)], e.confidence))
            appendLine("  levels per 10 s: " + s.levels.joinToString("") { it.toString() })
            appendLine(rec.diagnostics().format().prependIndent("  "))
        }
    }

    @Test fun replayFolder() {
        val dir = (System.getProperty("wavs") ?: System.getenv("QN_WAVS"))?.takeIf { it.isNotBlank() }
        assumeTrue("set -Pwavs=<dir> or QN_WAVS to replay recordings", dir != null)
        val files = File(dir!!).walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }.sorted().toList()
        val out = StringBuilder("Replayed ${files.size} WAV files from $dir\n")
        for (f in files) out.append(runCatching { report(f) }.getOrElse { "== ${f.name}: ${it.message}\n" })
        println(out)
        File("build/reports").mkdirs()
        File("build/reports/wav-replay.txt").writeText(out.toString())
    }

    @Test fun harnessReadsWhatTheAppWrites() {
        for (rate in intArrayOf(16000, 44100)) {
            val scene = Scene(40.0, 95, rate).roomNoise().snoring(1.0, 40.0, 800.0, 3.5)
            val f = File("build/tmp/replay-$rate.wav").apply { parentFile.mkdirs() }
            NightRecorder.writeWav(f, scene.pcm(), rate)
            val (r, pcm) = readWav(f)
            assertEquals(rate, r)
            assertEquals(scene.n, pcm.size)
            val text = report(f)
            assertTrue(text, text.lines().count { it.trim().startsWith("snore") } >= 5)
            assertTrue(text, "Counted as snoring" in text && "Room noise floor" in text)
        }
    }
}
