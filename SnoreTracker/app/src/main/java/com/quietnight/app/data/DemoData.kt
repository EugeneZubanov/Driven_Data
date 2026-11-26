package com.quietnight.app.data

import java.util.Calendar
import kotlin.random.Random

/** Generates plausible sample nights so the charts can be explored before any real recording. */
object DemoData {
    fun generate(nowMs: Long, nights: Int = 45, seed: Int = 7): List<Session> {
        val rnd = Random(seed)
        val out = ArrayList<Session>()
        for (n in 1..nights) {
            if (rnd.nextFloat() < 0.25f) continue
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, -n)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, rnd.nextInt(60))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis + rnd.nextInt(90) * 60_000L
            val tags = buildList {
                if (rnd.nextFloat() < 0.3f) add("f:Alcohol")
                if (rnd.nextFloat() < 0.2f) add("f:Blocked nose")
                if (rnd.nextFloat() < 0.5f) add("r:Side sleeping")
                if (rnd.nextFloat() < 0.35f) add("r:Humidifier")
            }
            var severity = 0.5f + rnd.nextFloat() * 0.8f
            if ("f:Alcohol" in tags) severity += 0.7f
            if ("f:Blocked nose" in tags) severity += 0.3f
            if ("r:Side sleeping" in tags) severity -= 0.35f
            if ("r:Humidifier" in tags) severity -= 0.1f
            out += night("demo-$n", start, (6.5f + rnd.nextFloat() * 2.5f), severity.coerceAtLeast(0.1f), tags, rnd)
        }
        return out
    }

    fun night(id: String, startMs: Long, hours: Float, severity: Float, tags: List<String>, rnd: Random): Session {
        val bucketSec = 10
        val count = (hours * 3600 / bucketSec).toInt()
        val levels = ByteArray(count)
        val amps = ByteArray(count)
        var i = 180 // nothing while falling asleep
        while (i < count) {
            i += (rnd.nextInt(40, 420) / severity.coerceAtLeast(0.3f)).toInt()
            val episode = (rnd.nextInt(12, 110) * severity).toInt().coerceAtLeast(3)
            val base = 9f + rnd.nextFloat() * 12f * severity
            for (j in i until minOf(count, i + episode)) {
                if (rnd.nextFloat() < 0.25f) continue
                val db = (base + rnd.nextFloat() * 9f - 3f).coerceIn(8f, 45f)
                levels[j] = Level.fromDb(db).toByte()
                amps[j] = db.toInt().toByte()
            }
            i += episode
        }
        return Session(id, startMs, startMs + count * bucketSec * 1000L, bucketSec, levels, amps, tags, emptyList(), demo = true)
    }
}
