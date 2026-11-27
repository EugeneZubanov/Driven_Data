package com.quietnight.app

import com.quietnight.app.data.Clip
import com.quietnight.app.data.Comparison
import com.quietnight.app.data.Level
import com.quietnight.app.data.Gap
import com.quietnight.app.data.NightStats
import com.quietnight.app.data.Session
import com.quietnight.app.data.SessionRepository
import com.quietnight.app.data.Stretch
import com.quietnight.app.data.Span
import com.quietnight.app.data.Backup
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

    @Test fun searchMatchesNotesAndTagNames() {
        val a = night("1", "a", tags = listOf("f:Alcohol")).withEdits(listOf("f:Alcohol"), "Dinner out, late night")
        val b = night("1", "b").withEdits(emptyList(), "Cold, blocked nose")
        val all = listOf(a, b)
        assertEquals(listOf(a), NightStats.search(all, "dinner"))
        assertEquals(listOf(a), NightStats.search(all, "ALCO"))
        assertEquals(listOf(b), NightStats.search(all, " blocked "))
        assertEquals(all, NightStats.search(all, "  "))
        assertTrue(NightStats.search(all, "zzz").isEmpty())
    }

    @Test fun morningSummaryAndBytes() {
        val tonight = scored(10, "t", 0)
        val noisy = listOf(scored(30, "a", 1), scored(30, "b", 2), scored(30, "c", 3))
        assertEquals("You snored for 10m, 10% of the night.", NightStats.morningSummary(tonight, listOf(tonight)))
        assertEquals("You snored for 10m, 10% of the night. Quieter than your average.", NightStats.morningSummary(tonight, noisy + tonight))
        val same = listOf(scored(11, "a", 1), scored(10, "b", 2), scored(9, "c", 3))
        assertTrue(NightStats.morningSummary(tonight, same + tonight).endsWith("About your average."))
        assertTrue(NightStats.morningSummary(scored(60, "n", 0), same).endsWith("Noisier than your average."))
        assertEquals("Hardly any snoring was picked up.", NightStats.morningSummary(scored(0, "q", 0), emptyList()))

        assertEquals("900 B", NightStats.formatBytes(900))
        assertEquals("2 KB", NightStats.formatBytes(2048))
        assertEquals("12.5 MB", NightStats.formatBytes(12L * 1024 * 1024 + 512 * 1024))
    }

    @Test fun otherSoundsAreShownButNeverEraseSnoring() {
        val s = night("..22..2...")
        val others = ByteArray(10).also { it[1] = 1; it[2] = 2; it[3] = 2 } // talking at 1, other sounds over 2-3
        val o = Session(s.id, s.startMs, s.endMs, s.bucketSec, s.levels, s.amps, s.tags, s.clips, others = others)
        // Only minute 1 is "other sound and no snoring"; minutes 2-3 also had confirmed snoring.
        assertEquals(60, o.otherSec)
        assertEquals(s.snoringSec, o.snoringSec)
        assertEquals(s.score, o.score)
        assertEquals(Level.LOUD, o.effectiveLevel(2))
        assertEquals(Level.QUIET, o.effectiveLevel(1))
        val cols = NightStats.columns(o, o.startMs, o.endMs, 10)
        assertArrayEquals(BooleanArray(10) { it in 1..3 }, cols.others)
        assertEquals(2, cols.levels[2])
        // Old nights without the field are unchanged.
        assertEquals(0, s.otherSec)
        assertEquals(Session.fromJson(JSONObject(o.toJson().toString())).otherSec, 60)
    }

    @Test fun damagedNightWithShortArraysStillDraws() {
        val s = Session("d", t0, t0 + 300_000, 60, byteArrayOf(2, 2, 0, 1, 9), byteArrayOf(20), emptyList(), emptyList())
        val c = NightStats.columns(s, s.startMs, s.endMs, 5)
        assertEquals(listOf(2, 2, 0, 1, 3), c.levels.toList()) // out-of-range level clamped, missing amps read as 0
        assertTrue(s.score >= 0)
        NightStats.tip(s); NightStats.longestQuiet(s); NightStats.loudestHour(s, utc); NightStats.shareText(s, listOf(s), utc, Locale.US)
    }

    @Test fun tipPicksOneFact() {
        val q = ".".repeat(20)
        assertEquals("Most snoring came in the last third of the night.", NightStats.tip(night(q + q + "2".repeat(10) + ".".repeat(10))))
        assertEquals("Most snoring came in the first third of the night.", NightStats.tip(night("2".repeat(10) + ".".repeat(50))))
        // Spread evenly: falls through to the level mix.
        val even = ("3...." ).repeat(12)
        assertEquals("Over half of the snoring was heavy.", NightStats.tip(night(even)))
        assertEquals("Most of the snoring was mild.", NightStats.tip(night("1....".repeat(12))))
        val mixed = "1....2....".repeat(6)
        assertNull(NightStats.tip(night(mixed)))
        assertEquals(
            "You tagged Alcohol. Trends shows how nights with it compare once there are a few.",
            NightStats.tip(night(mixed, tags = listOf("r:Nasal strip", "f:Alcohol"))),
        )
        assertNull(NightStats.tip(night("2222" + q))) // under 5 minutes of snoring
        assertNull(NightStats.tip(night("")))
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

    private fun withGaps(s: Session, vararg gaps: Gap) =
        Session(s.id, s.startMs, s.endMs, s.bucketSec, s.levels, s.amps, s.tags, s.clips, demo = s.demo, gaps = gaps.toList())

    @Test fun gapsAreLeftOutOfTheScore() {
        val plain = night("2222......") // 4 of 10 minutes loud
        val gapped = withGaps(plain, Gap(300, 600)) // last 5 minutes had no audio
        assertEquals(40, plain.score)
        assertEquals(300, gapped.gapSec)
        assertEquals(300, gapped.listenedSec)
        assertEquals(80, gapped.score) // 4 of 5 listened minutes
        assertEquals(0.8f, gapped.snoringFraction, 1e-4f)
        assertEquals(60, gapped.secondsByLevel[0]) // only the heard quiet minute
        // No gaps: identical to before.
        assertEquals(0, plain.gapSec)
        assertEquals(600, plain.listenedSec)
    }

    @Test fun gapMaskUsesBucketMiddles() {
        val s = withGaps(night("......"), Gap(70, 150)) // buckets 1 (mid 90) and 2 (mid 150 excluded)
        assertArrayEquals(booleanArrayOf(false, true, false, false, false, false), s.gapMask)
        val all = withGaps(night("11"), Gap(0, 120))
        assertEquals(0, all.score)
        assertEquals(0f, all.snoringFraction)
    }

    @Test fun gapsBreakQuietStretchesAndMarkColumns() {
        val s = withGaps(night("..........1"), Gap(240, 600)) // minutes 4-9 had no audio
        assertEquals(Stretch(0, 240), NightStats.longestQuiet(s))
        val c = NightStats.columns(s, s.startMs, s.startMs + 660_000, 11)
        assertArrayEquals(BooleanArray(11) { it in 4..9 }, c.gaps)
    }

    @Test fun gapsRoundTripAndScoreSurvives() {
        val s = withGaps(night("2222......"), Gap(300, 600))
        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s.gaps, back.gaps)
        assertEquals(s.score, back.score)
    }

    @Test fun sharedWindowsAlignNightsByClockTime() {
        val a = night("1".repeat(60), "a", start = t0) // Thu 23:30 for 1 h
        val b = night("1".repeat(30), "b", start = t0 - 86_400_000L + 3_600_000L) // Wed 00:30 for 30 min
        assertEquals(330, NightStats.eveningMinutes(t0, utc))
        assertEquals(390, NightStats.eveningMinutes(b.startMs, utc))
        val (wa, wb) = NightStats.sharedWindows(listOf(a, b), utc)
        // Both span 23:30 to 01:00 on their own evening.
        assertEquals(t0, wa.first)
        assertEquals(t0 + 90 * 60_000L, wa.second)
        assertEquals(b.startMs - 3_600_000L, wb.first)
        assertEquals(wb.first + 90 * 60_000L, wb.second)
    }

    @Test fun notSnoringIsLeftOutOfSnoringAndUndoIsExact() {
        val s = night("..2222..33..") // 4 loud + 2 heavy minutes of 12
        val marked = s.withNotSnoring(listOf(Span(120, 360))) // the loud stretch
        assertEquals(6 * 60, s.snoringSec)
        assertEquals(2 * 60, marked.snoringSec)
        assertEquals(10 * 60, marked.secondsByLevel[0]) // heard, so it counts as quiet
        assertEquals(240, marked.correctedSec)
        assertEquals(720, marked.listenedSec) // denominator unchanged
        assertTrue(marked.score < s.score)
        assertEquals(Level.QUIET, marked.effectiveLevel(3))
        assertEquals(2, marked.levels[3].toInt()) // detection itself is kept
        // Undo: removing the span gives back exactly the original numbers.
        val undone = marked.withNotSnoring(emptyList())
        assertEquals(s.score, undone.score)
        assertArrayEquals(s.secondsByLevel, undone.secondsByLevel)
        // Stats follow the correction.
        assertEquals(8 * 60, NightStats.snoringStartSec(marked, withinSec = 120))
        val cols = NightStats.columns(marked, marked.startMs, marked.endMs, 12)
        assertEquals(0, cols.levels[3])
        assertTrue(cols.corrected[3] > 0)
        assertEquals(0, cols.corrected[8]) // heavy minutes untouched
        assertEquals(3, cols.levels[8])
    }

    @Test fun clipsInsideAMarkedStretchAreHiddenAndComeBackOnUndo() {
        val s = night("..2222..33..")
        val withClips = Session(
            s.id, s.startMs, s.endMs, s.bucketSec, s.levels, s.amps, s.tags,
            listOf(Clip(150, "a.wav", 2, 20), Clip(500, "b.wav", 3, 30)),
        )
        assertEquals(listOf("a.wav", "b.wav"), withClips.visibleClips.map { it.file })
        val marked = withClips.withNotSnoring(listOf(Span(120, 360)))
        assertEquals(listOf("b.wav"), marked.visibleClips.map { it.file })
        assertEquals(2, marked.clips.size) // the file is kept for undo
        assertEquals(listOf("a.wav", "b.wav"), marked.withNotSnoring(emptyList()).visibleClips.map { it.file })
    }

    @Test fun notSnoringRoundTripsAndOldFilesLoad() {
        val s = night("2222").withNotSnoring(listOf(Span(0, 60), Span(120, 180))).withEdits(listOf("f:Alcohol"), "n")
        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(listOf(Span(0, 60), Span(120, 180)), back.notSnoring) // withEdits keeps it too
        assertEquals(s.score, back.score)
        assertFalse(night("1").toJson().has("notSnoring"))
        assertTrue(Session.fromJson(JSONObject(night("1").toJson().toString())).notSnoring.isEmpty())
    }

    @Test fun backupValidatesNotSnoring() {
        val ok = night("2222", "a").withNotSnoring(listOf(Span(0, 60)))
        assertTrue(Backup.isValid(ok))
        assertFalse(Backup.isValid(night("2222", "a").withNotSnoring(listOf(Span(60, 0)))))
        assertFalse(Backup.isValid(night("2222", "a").withNotSnoring(List(1001) { Span(0, 60) })))
    }

    @Test fun episodeAtJoinsShortPauses() {
        val s = night("....22.2......1.")
        assertEquals(Span(4 * 60, 8 * 60), NightStats.episodeAt(s, 5, pauseSec = 60, reachSec = 0))
        assertEquals(Span(4 * 60, 8 * 60), NightStats.episodeAt(s, 3, pauseSec = 60, reachSec = 60)) // finds it nearby
        assertNull(NightStats.episodeAt(s, 10, pauseSec = 60, reachSec = 60))
        assertEquals(Span(14 * 60, 15 * 60), NightStats.episodeAt(s, 14, pauseSec = 60, reachSec = 0))
        // Marked stretches no longer count as snoring.
        assertNull(NightStats.episodeAt(s.withNotSnoring(listOf(Span(240, 480))), 5, pauseSec = 60, reachSec = 0))
    }

    @Test fun countsForTrends() {
        val hour = night("1".repeat(60))
        assertTrue(hour.countsForTrends)
        assertFalse(night("1".repeat(59)).countsForTrends)
        assertFalse(hour.withEdits(emptyList(), Session.TEST_RUN_NOTE).countsForTrends)
        assertEquals(com.quietnight.app.audio.RecordingService.TEST_RUN_NOTE, Session.TEST_RUN_NOTE)
        // An hour and a half with 40 minutes of no sound has under an hour listened.
        val gapped = night("1".repeat(90)).let {
            Session(it.id, it.startMs, it.endMs, it.bucketSec, it.levels, it.amps, it.tags, it.clips, gaps = listOf(com.quietnight.app.data.Gap(0, 2400)))
        }
        assertFalse(gapped.countsForTrends)
    }

    @Test fun clockFollows12And24Hour() {
        val t = t0 + 5 * 60_000L // 23:35 UTC
        assertEquals("23:35", com.quietnight.app.ui.formatClock(t, true, Locale.US, utc))
        assertEquals("11:35 PM", com.quietnight.app.ui.formatClock(t, false, Locale.US, utc))
        assertEquals("23:00", com.quietnight.app.ui.formatHour(t0 - 30 * 60_000L, true, Locale.US, utc))
        assertEquals("11 PM", com.quietnight.app.ui.formatHour(t0 - 30 * 60_000L, false, Locale.US, utc))
        val text = NightStats.shareText(night("....2222"), emptyList(), utc, Locale.US, is24 = false)
        assertTrue(text, "11:30 PM–11:38 PM" in text)
    }

    @Test fun clipPlayerReportsMissingFileWithoutPlaying() {
        val p = com.quietnight.app.ui.ClipPlayer()
        p.play("c1.wav", File(tmp.root, "missing.wav"))
        assertNull(p.playing)
        assertEquals("c1.wav", p.failed)
        p.stop(); p.stop() // safe to call twice
        assertNull(p.playing)
        assertEquals(0f, p.progress)
    }

    @Test fun clipPlayerStepsThroughQueue() {
        val p = com.quietnight.app.ui.ClipPlayer()
        val files = listOf("a.wav", "b.wav", "c.wav").map { it to File(tmp.root, it) } // missing: no audio needed
        p.setQueue(files)
        p.next()
        assertEquals("a.wav", p.current)
        p.next(); p.next(); p.next() // stops at the end
        assertEquals("c.wav", p.current)
        p.previous()
        assertEquals("b.wav", p.current)
        p.playCurrent()
        assertEquals("b.wav", p.current)
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
