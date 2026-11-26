package com.quietnight.app

import com.quietnight.app.data.Clip
import com.quietnight.app.data.Comparison
import com.quietnight.app.data.NightStats
import com.quietnight.app.data.Session
import com.quietnight.app.data.SessionRepository
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class ResultsLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")
    // Thu 2026-11-26 23:30 UTC
    private val t0 = 1_795_735_800_000L

    /** Night with 60 s buckets built from a string: '.' quiet, '1'..'3' levels. */
    private fun night(pattern: String, id: String = "n", start: Long = t0, tags: List<String> = emptyList(), demo: Boolean = false): Session {
        val levels = ByteArray(pattern.length) { if (pattern[it] == '.') 0 else (pattern[it] - '0').toByte() }
        val amps = ByteArray(pattern.length) { if (levels[it] > 0) (10 + levels[it] * 8).toByte() else 0 }
        return Session(id, start, start + pattern.length * 60_000L, 60, levels, amps, tags, emptyList(), demo)
    }

    /** Night with a given score: [snoreMin] loud minutes out of 100. */
    private fun scored(snoreMin: Int, id: String, daysAgo: Int, demo: Boolean = false) =
        night("2".repeat(snoreMin) + ".".repeat(100 - snoreMin), id, t0 - daysAgo * 86_400_000L, demo = demo)

    @Test fun columnsKeepStrongestLevelAndCoverage() {
        val s = night("..1.3...2.")
        val c = NightStats.columns(s, s.startMs, s.endMs, 5)
        assertArrayEquals(intArrayOf(0, 1, 3, 0, 2), c.levels)
        assertEquals(0, c.firstCovered)
        assertEquals(4, c.lastCovered)
    }

    @Test fun columnsForWindowOutsideRecordingStayBlank() {
        val s = night("3333") // 4 minutes
        // Window of 8 minutes starting 2 minutes before the night.
        val c = NightStats.columns(s, s.startMs - 120_000, s.startMs + 360_000, 8)
        assertArrayEquals(intArrayOf(0, 0, 3, 3, 3, 3, 0, 0), c.levels)
        assertEquals(2, c.firstCovered)
        assertEquals(5, c.lastCovered)
    }

    @Test fun columnsOfEmptyNight() {
        val s = night("")
        val c = NightStats.columns(s, s.startMs, s.startMs + 1000, 4)
        assertEquals(-1, c.lastCovered)
    }

    @Test fun longestQuietStretch() {
        val s = night("..1....2...")
        assertEquals(3 * 60, NightStats.longestQuiet(s)!!.startSec)
        assertEquals(4 * 60, NightStats.longestQuiet(s)!!.lengthSec)
        assertNull(NightStats.longestQuiet(night("1212")))
    }

    @Test fun snoringStartIgnoresIsolatedSounds() {
        // A lone sound at minute 1, then sustained snoring from minute 5.
        val s = night(".1...11.1......")
        assertEquals(5 * 60, NightStats.snoringStartSec(s, withinSec = 240))
        assertNull(NightStats.snoringStartSec(night(".1....1....")))
    }

    @Test fun loudestHourUsesClockHours() {
        // Starts 23:30. 23:30-00:00 has 20 mild minutes; 00:00-01:00 has 10 heavy minutes.
        val s = night("1".repeat(20) + ".".repeat(10) + "3".repeat(10) + ".".repeat(60)) // ends 01:10
        val hours = NightStats.hourStarts(s, utc)
        assertEquals(3, hours.size) // 23:00, 00:00, 01:00
        assertEquals(hours[1], NightStats.loudestHour(s, utc))
        assertNull(NightStats.loudestHour(night("....."), utc))
    }

    @Test fun compareWithRecentUsesOnlyEarlierRealNights() {
        val tonight = scored(10, "t", 0)
        val all = listOf(
            tonight,
            scored(20, "a", 1), scored(30, "b", 2), scored(40, "c", 3),
            scored(90, "later", -1), // after tonight: ignored
            scored(90, "demo", 4, demo = true), // sample: ignored for a real night
        )
        val c = NightStats.compareWithRecent(tonight, all)!!
        assertEquals(3, c.nights)
        assertEquals(listOf(20, 30, 40).map { scored(it, "x", 0).score }.average().toInt(), c.average)
        assertNull(NightStats.compareWithRecent(tonight, all.take(3)))
    }

    @Test fun compareWindowTakesMostRecent() {
        val tonight = scored(0, "t", 0)
        val all = listOf(tonight) + (1..20).map { scored(if (it <= 14) 10 else 90, "n$it", it) }
        val c = NightStats.compareWithRecent(tonight, all)!!
        assertEquals(14, c.nights)
        assertEquals(scored(10, "x", 0).score, c.average)
    }

    @Test fun comparisonSentence() {
        assertEquals("7 below your 14-night average", NightStats.comparisonText(13, Comparison(20, 14)))
        assertEquals("5 above your 3-night average", NightStats.comparisonText(25, Comparison(20, 3)))
        assertEquals("Same as your 5-night average", NightStats.comparisonText(20, Comparison(20, 5)))
    }

    @Test fun tagsAndFilter() {
        val a = night("1", "a", tags = listOf("r:Side sleeping", "f:Alcohol"))
        val b = night("1", "b", tags = listOf("f:Caffeine"))
        assertEquals(listOf("f:Alcohol", "f:Caffeine", "r:Side sleeping"), NightStats.usedTags(listOf(a, b)))
        assertEquals(listOf(b), NightStats.filterByTag(listOf(a, b), "f:Caffeine"))
        assertEquals(listOf(a, b), NightStats.filterByTag(listOf(a, b), null))
        assertEquals(0, NightStats.averageScore(emptyList()))
    }

    @Test fun shareTextHasTheEssentials() {
        val s = night("....222222....", tags = listOf("f:Alcohol", "r:Nasal strip")).withEdits(listOf("f:Alcohol", "r:Nasal strip"), "Slept late ")
        val text = NightStats.shareText(s, listOf(s), utc, Locale.US)
        assertTrue(text, text.startsWith("Night of Thu 26 Nov, 23:30–23:44"))
        assertTrue(text, "Snore score: ${s.score}" in text)
        assertTrue(text, "Snoring started: 23:34" in text)
        assertTrue(text, "Factors: Alcohol" in text)
        assertTrue(text, "Remedies: Nasal strip" in text)
        assertTrue(text, "Note: Slept late" in text)
        assertFalse(text, "average" in text) // no history
    }

    @Test fun noteRoundTripsAndOldFilesStillLoad() {
        val s = night("12.3").withEdits(listOf("f:Alcohol"), "Blocked nose after a cold")
        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals("Blocked nose after a cold", back.note)
        assertEquals(listOf("f:Alcohol"), back.tags)
        assertArrayEquals(s.levels, back.levels)

        val old = JSONObject(night("1").toJson().toString()).apply { remove("note") }
        assertEquals("", Session.fromJson(old).note)
        assertFalse(night("1").toJson().has("note")) // nothing written when empty
    }

    @Test fun trashAndRestore() {
        val repo = SessionRepository(tmp.root)
        val s = Session("x", t0, t0 + 60_000, 60, byteArrayOf(1), byteArrayOf(20), emptyList(), listOf(Clip(0, "c.wav", 1, 20)))
        repo.save(s)
        repo.clipDir("x").mkdirs()
        repo.clipFile("x", "c.wav").writeText("wav")

        repo.trash("x")
        assertTrue(repo.sessions.value.isEmpty())
        assertFalse(repo.clipFile("x", "c.wav").exists())

        assertTrue(repo.restore("x"))
        assertEquals(listOf("x"), repo.sessions.value.map { it.id })
        assertEquals("wav", repo.clipFile("x", "c.wav").readText())

        // After a reload the trash is emptied and the night stays.
        repo.trash("x")
        SessionRepository(tmp.root).load()
        assertFalse(repo.restore("x"))
        assertFalse(File(tmp.root, "sessions/x.json").exists())
        assertFalse(repo.clipDir("x").exists())
    }

    @Test fun trashKeepsOnlyLatest() {
        val repo = SessionRepository(tmp.root)
        repo.save(night("1", "a")); repo.save(night("1", "b", start = t0 + 1))
        repo.trash("a")
        repo.trash("b")
        assertFalse(repo.restore("a"))
        assertTrue(repo.restore("b"))
    }

    @Test fun editedSessionReplacesOnSave() {
        val repo = SessionRepository(tmp.root)
        val s = night("1", "a")
        repo.save(s)
        repo.save(s.withEdits(listOf("r:Humidifier"), "ok"))
        val fresh = SessionRepository(tmp.root).apply { load() }
        assertEquals(listOf("r:Humidifier"), fresh.sessions.value.single().tags)
        assertEquals("ok", fresh.sessions.value.single().note)
    }
}
