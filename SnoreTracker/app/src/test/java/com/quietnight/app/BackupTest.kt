package com.quietnight.app

import com.quietnight.app.data.Backup
import com.quietnight.app.data.BackupException
import com.quietnight.app.data.Clip
import com.quietnight.app.data.Csv
import com.quietnight.app.data.Session
import com.quietnight.app.data.Gap
import com.quietnight.app.data.Weight
import com.quietnight.app.data.WeightEntry
import com.quietnight.app.data.WeightLog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun session(id: String, start: Long = 1_790_000_000_000L, clips: List<Clip> = emptyList(), demo: Boolean = false, tags: List<String> = listOf("f:Alcohol")): Session {
        val n = 6 * 360
        val levels = ByteArray(n) { (it % 4).toByte() }
        return Session(id, start, start + n * 10_000L, 10, levels, ByteArray(n) { 20 }, tags, clips, demo)
    }

    private class Store(root: File) {
        val clips = File(root, "clips")
        val saved = ArrayList<Session>()
        fun clipDir(id: String) = File(clips, id)
        fun clipFile(id: String, name: String) = File(clipDir(id), name)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun index(vararg sessions: JSONObject) =
        JSONObject().put("format", Backup.FORMAT).put("version", 1).put("sessions", JSONArray(sessions.toList())).toString().toByteArray()

    private fun restore(bytes: ByteArray, store: Store, existing: Set<String> = emptySet()) =
        Backup.restore(ByteArrayInputStream(bytes), existing, tmp.newFolder(), store::clipDir, save = { store.saved += it })

    @Test fun roundTripKeepsNightsAndClipsAndSkipsSamples() {
        val src = Store(tmp.newFolder())
        val a = session("s1", clips = listOf(Clip(30, "c3.wav", 2, 22)))
        src.clipDir("s1").mkdirs()
        src.clipFile("s1", "c3.wav").writeBytes(byteArrayOf(1, 2, 3, 4))
        val out = ByteArrayOutputStream()
        assertEquals(2, Backup.export(listOf(a, session("s2"), session("demo-1", demo = true)), src::clipFile, out))

        val dst = Store(tmp.newFolder())
        val r = restore(out.toByteArray(), dst, existing = setOf("s2"))
        assertEquals(1, r.added); assertEquals(1, r.skipped); assertEquals(0, r.invalid)
        val got = dst.saved.single()
        assertEquals("s1", got.id)
        assertArrayEquals(a.levels, got.levels)
        assertEquals(a.tags, got.tags)
        assertEquals(1, got.clips.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), dst.clipFile("s1", "c3.wav").readBytes())
    }

    @Test fun restoreReportsProgress() {
        val seen = ArrayList<Int>()
        val bytes = zip(Backup.INDEX to index(session("a").toJson()), "clips/a/c0.wav" to byteArrayOf(1), "clips/a/c1.wav" to byteArrayOf(1))
        val store = Store(tmp.newFolder())
        Backup.restore(ByteArrayInputStream(bytes), emptySet(), tmp.newFolder(), store::clipDir, save = { store.saved += it }, onProgress = { seen += it })
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test fun clipsMissingFromZipAreDroppedFromTheNight() {
        val dst = Store(tmp.newFolder())
        restore(zip(Backup.INDEX to index(session("s1", clips = listOf(Clip(0, "c0.wav", 1, 10))).toJson())), dst)
        assertTrue(dst.saved.single().clips.isEmpty())
    }

    @Test fun zipSlipEntriesAreIgnored() {
        val root = tmp.newFolder()
        val dst = Store(File(root, "inner"))
        val bytes = zip(
            Backup.INDEX to index(session("s1", clips = listOf(Clip(0, "c0.wav", 1, 10))).toJson()),
            "clips/s1/../../../evil.wav" to byteArrayOf(9),
            "../evil2.wav" to byteArrayOf(9),
            "/abs.wav" to byteArrayOf(9),
            "clips/s1/c0.wav" to byteArrayOf(7),
        )
        restore(bytes, dst)
        assertEquals(listOf("c0.wav"), dst.saved.single().clips.map { it.file })
        assertFalse(root.walkTopDown().any { it.name.startsWith("evil") || it.name == "abs.wav" })
        assertFalse(File(root.parentFile, "evil.wav").exists())
    }

    @Test fun malformedInputThrowsBeforeSavingAnything() {
        val dst = Store(tmp.newFolder())
        for (bad in listOf(
            "not a zip".toByteArray(),
            zip("other.txt" to byteArrayOf(1)),
            zip(Backup.INDEX to "{oops".toByteArray()),
            zip(Backup.INDEX to JSONObject().put("format", "something-else").toString().toByteArray()),
            zip(Backup.INDEX to JSONObject().put("format", Backup.FORMAT).put("version", 99).put("sessions", JSONArray()).toString().toByteArray()),
        )) {
            try { restore(bad, dst); fail("accepted bad input") } catch (_: BackupException) {}
        }
        assertTrue(dst.saved.isEmpty())
    }

    @Test fun invalidNightsAreCountedNotSaved() {
        val good = session("ok").toJson()
        val badId = session("x").toJson().put("id", "../../etc")
        val badTimes = session("t").toJson().put("end", 5)
        val badLevels = session("l").toJson().put("levels", "!!!not base64")
        val missing = JSONObject().put("id", "m")
        val dst = Store(tmp.newFolder())
        val r = restore(zip(Backup.INDEX to index(good, badId, badTimes, badLevels, missing)), dst)
        assertEquals(1, r.added); assertEquals(4, r.invalid)
        assertEquals("ok", dst.saved.single().id)
    }

    @Test fun duplicateIdsInsideBackupAreAddedOnce() {
        val dst = Store(tmp.newFolder())
        val r = restore(zip(Backup.INDEX to index(session("a").toJson(), session("a").toJson())), dst)
        assertEquals(1, r.added); assertEquals(1, r.skipped)
    }

    @Test fun oversizedClipIsRejected() {
        val dst = Store(tmp.newFolder())
        val big = ByteArray((Backup.MAX_CLIP_BYTES + 1).toInt())
        try {
            restore(zip(Backup.INDEX to index(session("a").toJson()), "clips/a/c0.wav" to big), dst)
            fail("accepted oversized clip")
        } catch (_: BackupException) {}
        assertTrue(dst.saved.isEmpty())
    }

    @Test fun csvHasOneRowPerRealNight() {
        val csv = Csv.export(listOf(session("b", start = 1_790_100_000_000L), session("a"), session("d", demo = true)))
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(Csv.HEADER, lines[0])
        assertEquals(3, lines.size)
        val cols = lines[1].split(',')
        assertEquals(10, cols.size)
        assertEquals("", cols[9]) // no weight logged
        assertEquals("360", cols[3]) // 6 h
        assertEquals("90", cols[4]); assertEquals("90", cols[5]); assertEquals("90", cols[6])
        assertEquals("Alcohol", cols[8])
    }

    @Test fun csvFillsWeightForTheNightsDate() {
        val s = session("a")
        val day = com.quietnight.app.data.Trends.dayOf(s).timeInMillis
        val csv = Csv.export(listOf(s), WeightLog(listOf(WeightEntry(day, 81.25f))))
        assertEquals("81.3", csv.trimEnd().split("\r\n")[1].split(',')[9])
    }

    @Test fun weightTravelsWithBackupAndMergesByDay() {
        val out = ByteArrayOutputStream()
        val sent = WeightLog(listOf(WeightEntry(100, 80f), WeightEntry(200, 79f)), pounds = true)
        Backup.export(listOf(session("s1")), { _, _ -> File("none") }, out, sent)
        var local = WeightLog(listOf(WeightEntry(200, 70f)))
        val r = Backup.restore(
            ByteArrayInputStream(out.toByteArray()), emptySet(), tmp.newFolder(), Store(tmp.newFolder())::clipDir, save = {},
            mergeWeight = { incoming -> Weight.merge(local, incoming).let { (log, n) -> local = log; n } },
        )
        assertEquals(1, r.weightsAdded)
        assertEquals(listOf(100L to 80f, 200L to 70f), local.entries.map { it.dayMs to it.kg }) // local day kept
        assertFalse(local.pounds) // unit preference stays local
    }

    @Test fun backupWithoutWeightStillRestores() {
        var called = false
        val r = Backup.restore(
            ByteArrayInputStream(zip(Backup.INDEX to index(session("a").toJson()))), emptySet(), tmp.newFolder(),
            Store(tmp.newFolder())::clipDir, save = {}, mergeWeight = { called = true; 0 },
        )
        assertEquals(1, r.added); assertEquals(0, r.weightsAdded); assertFalse(called)
    }

    @Test fun gapsNoteAndInterruptionSurviveRoundTrip() {
        val base = session("g")
        val s = Session(
            id = base.id, startMs = base.startMs, endMs = base.endMs, bucketSec = base.bucketSec, levels = base.levels,
            amps = base.amps, tags = base.tags, clips = emptyList(), interruptedAtMs = base.endMs,
            gaps = listOf(Gap(60, 120)), note = "Blocked nose, slept badly",
        )
        val out = ByteArrayOutputStream()
        Backup.export(listOf(s), { _, _ -> File("none") }, out)
        val dst = Store(tmp.newFolder())
        restore(out.toByteArray(), dst)
        val got = dst.saved.single()
        assertEquals(s.interruptedAtMs, got.interruptedAtMs)
        assertEquals(s.gaps, got.gaps)
        assertEquals(s.note, got.note)
        val badGap = s.toJson().put("gaps", org.json.JSONArray().put(org.json.JSONArray().put(50).put(10)))
        assertEquals(1, restore(zip(Backup.INDEX to index(badGap)), Store(tmp.newFolder())).invalid)
    }

    @Test fun csvFieldsAreQuotedAndFormulasDefused() {
        assertEquals("plain", Csv.field("plain"))
        assertEquals("\"a, b\"", Csv.field("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
        assertEquals("'=1+1", Csv.field("=1+1"))
        assertEquals("", Csv.field(""))
    }
}
