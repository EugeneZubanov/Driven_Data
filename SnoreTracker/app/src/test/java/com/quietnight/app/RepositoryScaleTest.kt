package com.quietnight.app

import com.quietnight.app.data.Clip
import com.quietnight.app.data.DemoData
import com.quietnight.app.data.Session
import com.quietnight.app.data.SessionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class RepositoryScaleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_795_700_000_000L

    /** A year of ~8.5 h nights at 10 s buckets (~3,000 buckets each); every 7th has 15 clips. */
    private fun year(root: File): List<Session> {
        val repo = SessionRepository(root)
        val rnd = Random(3)
        val out = (1..365).map { n ->
            val s = DemoData.night("n$n", now - n * 86_400_000L, 8.5f, 0.3f + rnd.nextFloat(), listOf("f:Alcohol"), rnd)
            if (n % 7 != 0) s else Session(
                s.id, s.startMs, s.endMs, s.bucketSec, s.levels, s.amps, s.tags,
                (0 until 15).map { Clip(600 + it * 1200, "c$it.wav", 2, 25) }, demo = false,
            )
        }
        out.forEach(repo::save)
        return out
    }

    @Test fun aYearLoadsQuickly() {
        val nights = year(tmp.root)
        assertEquals(3060, nights.first().levels.size)
        // Warm up class loading once, then time a cold read of all files.
        SessionRepository(tmp.root).load()
        val repo = SessionRepository(tmp.root)
        val t0 = System.nanoTime()
        repo.load()
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(365, repo.sessions.value.size)
        assertTrue(repo.loaded.value)
        // Generous budget for a shared CI machine; on a laptop this takes well under 1 s.
        assertTrue("loading a year took $loadMs ms", loadMs < 4_000)

        // What the list needs per row: score and per-level seconds, computed once per night.
        val t1 = System.nanoTime()
        repo.sessions.value.forEach { it.score; it.secondsByLevel }
        val statsMs = (System.nanoTime() - t1) / 1_000_000
        assertTrue("scoring a year took $statsMs ms", statsMs < 1_000)
        val bytes = File(tmp.root, "sessions").listFiles()!!.sumOf { it.length() }
        println("year: load $loadMs ms, stats $statsMs ms, ${bytes / 1024} KiB of JSON")
    }

    @Test fun corruptAndHalfWrittenFilesAreSkipped() {
        val repo = SessionRepository(tmp.root)
        val good = DemoData.night("good", now, 1f, 1f, emptyList(), Random(1))
        val other = DemoData.night("other", now - 86_400_000L, 1f, 1f, emptyList(), Random(2))
        repo.save(good); repo.save(other)
        val dir = File(tmp.root, "sessions")
        val full = File(dir, "good.json").readText()
        File(dir, "cut.json").writeText(full.substring(0, full.length / 2)) // cut off mid-write
        File(dir, "junk.json").writeText("not json at all")
        File(dir, "empty.json").writeText("")
        File(dir, "missing-fields.json").writeText("{\"id\":\"x\"}")
        File(dir, "good2.tmp").writeText(full) // left over from an interrupted save

        val fresh = SessionRepository(tmp.root)
        fresh.load()
        assertEquals(listOf("good", "other"), fresh.sessions.value.map { it.id })
        assertFalse(File(dir, "good2.tmp").exists())
        assertTrue(File(dir, "cut.json").exists()) // left alone, not deleted
    }

    @Test fun saveDuringBackgroundLoadIsKept() {
        year(tmp.root)
        val repo = SessionRepository(tmp.root)
        repo.loadInBackground()
        val tonight = DemoData.night("tonight", now, 1f, 1f, emptyList(), Random(9))
        repo.save(tonight) // from another thread while loading
        repo.ensureLoaded()
        assertEquals(366, repo.sessions.value.size)
        assertEquals("tonight", repo.sessions.value.first().id)
    }

    @Test fun clipStorage() {
        val repo = SessionRepository(tmp.root)
        fun night(id: String, daysAgo: Int): Session {
            val s = Session(id, now - daysAgo * 86_400_000L, now - daysAgo * 86_400_000L + 60_000, 10, ByteArray(6), ByteArray(6), emptyList(),
                listOf(Clip(0, "a.wav", 2, 20), Clip(30, "b.wav", 2, 20)), note = "kept")
            repo.save(s)
            repo.clipDir(id).mkdirs()
            repo.clipFile(id, "a.wav").writeBytes(ByteArray(1000))
            repo.clipFile(id, "b.wav").writeBytes(ByteArray(500))
            return s
        }
        night("old", 40); night("new", 2)
        assertEquals(1500L, repo.clipBytes("old"))
        assertEquals(3000L, repo.clipBytes())

        assertEquals(1500L, repo.deleteClipsOlderThan(30, now))
        assertEquals(0L, repo.clipBytes("old"))
        assertEquals(1500L, repo.clipBytes())
        val old = SessionRepository(tmp.root).apply { load() }.sessions.value.first { it.id == "old" }
        assertTrue(old.clips.isEmpty())
        assertEquals("kept", old.note) // the night itself stays

        assertEquals(1500L, repo.deleteClips("new"))
        assertEquals(0L, repo.clipBytes())
        assertEquals(0L, repo.deleteClipsOlderThan(30, now)) // nothing left
    }
}
