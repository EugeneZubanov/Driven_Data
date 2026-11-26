package com.quietnight.app

import com.quietnight.app.audio.AudioSegment
import com.quietnight.app.audio.SegmentIndex
import com.quietnight.app.data.Backup
import com.quietnight.app.data.Csv
import com.quietnight.app.data.Gap
import com.quietnight.app.data.Insights
import com.quietnight.app.data.ReportData
import com.quietnight.app.data.Session
import com.quietnight.app.data.Span
import com.quietnight.app.data.StorageCleanup
import com.quietnight.app.data.Trends
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class RoundFiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_795_700_000_000L
    private val day = 86_400_000L

    /** [minutes]-long night ending at [endMs] with the first [snore] minutes loud. */
    private fun night(
        endMs: Long, minutes: Int = 480, snore: Int = 48, note: String = "",
        notSnoring: List<Span> = emptyList(), gaps: List<Gap> = emptyList(), id: String = "s$endMs",
    ) = Session(
        id = id, startMs = endMs - minutes * 60_000L, endMs = endMs, bucketSec = 60,
        levels = ByteArray(minutes) { if (it < snore) 2 else 0 }, amps = ByteArray(minutes), tags = emptyList(), clips = emptyList(),
        note = note, notSnoring = notSnoring, gaps = gaps,
    )

    private val testRun get() = night(now, note = Session.TEST_RUN_NOTE, id = "test")
    private val short get() = night(now - day, minutes = 50, snore = 40, id = "short")
    private val real get() = night(now - 2 * day, id = "real")

    @Test fun trendsReportCsvAndWidgetIgnoreNightsThatDoNotCount() {
        val all = listOf(testRun, short, real)
        assertEquals(listOf("real"), Trends.counted(all).map { it.id })
        assertEquals(listOf("real"), ReportData.build(all, null, now).nights.map { it.id })
        assertEquals(2, Csv.export(all).trimEnd().split("\r\n").size) // header + the real night
        assertTrue(WidgetContent.of(all).title.contains(java.text.SimpleDateFormat("d MMM").format(java.util.Date(real.endMs))))
        assertEquals("–", WidgetContent.of(listOf(testRun, short)).score)
    }

    @Test fun hourlyUsesCorrectionsAndSkipsGaps() {
        // First hour loud, but the user marked 0–30 min as not snoring, and 30–60 min has no audio.
        // Ends at 07:00 so the night starts on the hour (23:00) and its first hour is one clock hour.
        val end = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 7); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val s = night(end, snore = 60, notSnoring = listOf(Span(0, 1800)), gaps = listOf(Gap(1800, 3600)))
        val first = Insights.hourly(listOf(s)).first { it.recordedSec > 0 }
        assertEquals(23, first.hour)
        assertEquals(1800L, first.recordedSec)
        assertEquals(0f, first.totalShare, 0f)
    }

    @Test fun reportUsesListenedTimeAndCorrectedSnoring() {
        val s = night(now, snore = 120, notSnoring = listOf(Span(0, 3600)), gaps = listOf(Gap(7200, 10800)))
        val r = ReportData.build(listOf(s), null, now)
        assertEquals(s.listenedSec.toLong(), r.recordedSec)
        assertEquals(3600, r.snoringSec[1]) // only the uncorrected hour of loud snoring counts
    }

    private fun withAudio(store: File, s: Session, segments: Map<String, ByteArray>) {
        val dir = File(store, s.id).apply { mkdirs() }
        segments.forEach { (n, b) -> File(dir, n).writeBytes(b) }
        SegmentIndex.write(dir, segments.keys.mapIndexed { i, n -> AudioSegment(n, i * 600_000L, 600_000L) })
    }

    @Test fun wholeNightAudioRoundTrips() {
        val src = tmp.newFolder()
        val s = real
        withAudio(src, s, mapOf("audio-001.m4a" to byteArrayOf(1, 2), "audio-002.m4a" to byteArrayOf(3)))
        val out = ByteArrayOutputStream()
        Backup.export(listOf(s), { id, name -> File(File(src, id), name) }, out)
        val names = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue(names.containsAll(listOf("clips/real/night-audio.json", "clips/real/audio-001.m4a", "clips/real/audio-002.m4a")))

        val dst = tmp.newFolder()
        Backup.restore(ByteArrayInputStream(out.toByteArray()), emptySet(), tmp.newFolder(), { File(dst, it) }, save = {})
        val dir = File(dst, "real")
        assertEquals(listOf("audio-001.m4a", "audio-002.m4a"), SegmentIndex.read(dir).map { it.file })
        assertEquals(listOf<Byte>(3), File(dir, "audio-002.m4a").readBytes().toList())
    }

    @Test fun restoredSegmentIndexKeepsOnlySafePresentInRangeSegments() {
        val s = real
        val index = SegmentIndex.toJson(listOf(
            AudioSegment("audio-001.m4a", 0, 600_000),
            AudioSegment("missing.m4a", 600_000, 600_000), // not in zip
            AudioSegment("../evil.m4a", 0, 1), // unsafe
            AudioSegment("notes.txt", 0, 1), // not audio
            AudioSegment("audio-003.m4a", 99 * 3600_000L, 1), // starts after the night
        ))
        val zipBytes = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z ->
                fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
                put(Backup.INDEX, JSONObject().put("format", Backup.FORMAT).put("version", 1)
                    .put("sessions", org.json.JSONArray().put(s.toJson())).toString().toByteArray())
                put("clips/real/night-audio.json", index.toByteArray())
                put("clips/real/audio-001.m4a", byteArrayOf(1))
                put("clips/real/audio-003.m4a", byteArrayOf(1))
                put("clips/real/notes.txt", byteArrayOf(1))
            }
        }.toByteArray()
        val dst = tmp.newFolder()
        Backup.restore(ByteArrayInputStream(zipBytes), emptySet(), tmp.newFolder(), { File(dst, it) }, save = {})
        assertEquals(listOf("audio-001.m4a"), SegmentIndex.read(File(dst, "real")).map { it.file })
        assertFalse(File(dst, "real/audio-003.m4a").exists())
    }

    @Test fun backupWithoutAudioCreatesNoIndex() {
        val out = ByteArrayOutputStream()
        Backup.export(listOf(real), { id, name -> File(tmp.root, "$id/$name") }, out)
        val dst = tmp.newFolder()
        Backup.restore(ByteArrayInputStream(out.toByteArray()), emptySet(), tmp.newFolder(), { File(dst, it) }, save = {})
        assertFalse(File(dst, "real/${SegmentIndex.FILE}").exists())
    }

    @Test fun storageSizesInWords() {
        assertEquals("0 MB", StorageCleanup.formatBytes(0))
        assertEquals("312 MB", StorageCleanup.formatBytes(312L * 1024 * 1024))
        assertEquals("1.5 GB", StorageCleanup.formatBytes(1536L * 1024 * 1024))
    }
}
