package com.quietnight.app

import com.quietnight.app.audio.NightDiagnostics
import com.quietnight.app.data.Backup
import com.quietnight.app.data.BackupException
import com.quietnight.app.data.DetectionCheck
import com.quietnight.app.data.RestoreSpace
import com.quietnight.app.data.Session
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class RoundSixTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_795_700_000_000L

    private fun night(id: String = "n1", endMs: Long = now, minutes: Int = 480, demo: Boolean = false, note: String = "") = Session(
        id = id, startMs = endMs - minutes * 60_000L, endMs = endMs, bucketSec = 60,
        levels = ByteArray(minutes) { if (it < 48) 2 else 0 }, amps = ByteArray(minutes), tags = listOf("f:Alcohol"),
        clips = emptyList(), demo = demo, note = note,
    )

    private val diag = NightDiagnostics(
        16000, 8f, 8 * 3600L, 0f, 120, 10, 5, 7, 3, 2, 60, 48, 4, 9, -62f, -55f, -40f,
    )

    @Test fun shareTextHasPhoneAppRateAndDiagnostics() {
        val t = DetectionCheck.shareText(night(), diag, 1, "1.2", "14", 34, "Acme Phone 3")
        assertTrue(t.startsWith("Quiet Night 1.2 detection details"))
        assertTrue(t.contains("Phone: Acme Phone 3, Android 14 (API 34)"))
        assertTrue(t.contains("Capture rate: 16000 Hz"))
        assertTrue(t.contains("Sensitivity setting: Normal"))
        assertTrue(t.contains(diag.format()))
        assertFalse(t.contains("Alcohol")) // tags and notes are not shared
    }

    @Test fun shareTextWithoutDiagnosticsSaysSo() {
        val t = DetectionCheck.shareText(night(), null, 2, "1.2", "9", 28, "X")
        assertTrue(t.contains("No detector details were saved"))
        assertTrue(t.contains("Sensitivity setting: High"))
    }

    @Test fun latestRealNightSkipsSamplesTestRunsAndShortNights() {
        val real = night("real", now - 86_400_000L)
        val all = listOf(real, night("demo", now, demo = true), night("test", now, note = Session.TEST_RUN_NOTE), night("short", now, minutes = 30))
        assertEquals("real", DetectionCheck.latestRealNight(all)!!.id)
        assertNull(DetectionCheck.latestRealNight(emptyList()))
        assertEquals("Low", DetectionCheck.levelName(-3))
    }

    @Test fun reportNeedsARealCountingNightInTheWindow() {
        assertFalse(com.quietnight.app.data.ReportData.available(listOf(night("d", demo = true)), now))
        assertFalse(com.quietnight.app.data.ReportData.available(listOf(night("t", note = Session.TEST_RUN_NOTE), night("s", minutes = 30)), now))
        assertFalse(com.quietnight.app.data.ReportData.available(listOf(night("old", endMs = now - 40 * 86_400_000L)), now))
        assertTrue(com.quietnight.app.data.ReportData.available(listOf(night("d", demo = true), night("real")), now))
    }

    @Test fun restoreSpaceDecision() {
        val mb = 1024L * 1024
        assertNull(RestoreSpace.problem(null, 0, 0)) // size unknown: try anyway
        assertNull(RestoreSpace.problem(100 * mb, 300 * mb, 250 * mb))
        val msg = RestoreSpace.problem(100 * mb, 150 * mb, 500 * mb)
        assertNotNull(msg)
        assertTrue(msg!!.contains("200 MB") && msg.contains("150 MB") && msg.contains("Nothing was changed"))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun index(s: Session) = JSONObject().put("format", Backup.FORMAT).put("version", 1)
        .put("sessions", JSONArray().put(s.toJson())).toString().toByteArray()

    @Test fun detectorDetailsRoundTripThroughBackup() {
        val src = tmp.newFolder()
        File(src, "n1").mkdirs()
        File(src, "n1/${NightDiagnostics.FILE_NAME}").writeText(diag.toJson().toString())
        val out = ByteArrayOutputStream()
        Backup.export(listOf(night()), { id, name -> File(File(src, id), name) }, out)
        val dst = tmp.newFolder()
        Backup.restore(ByteArrayInputStream(out.toByteArray()), emptySet(), tmp.newFolder(), { File(dst, it) }, save = {})
        assertEquals(diag.captureRate, NightDiagnostics.read(File(dst, "n1"))!!.captureRate)
    }

    @Test fun damagedDetectorDetailsAreDroppedAndOversizedRefused() {
        val dst = tmp.newFolder()
        Backup.restore(
            ByteArrayInputStream(zipOf(Backup.INDEX to index(night()), "clips/n1/${NightDiagnostics.FILE_NAME}" to "{nope".toByteArray())),
            emptySet(), tmp.newFolder(), { File(dst, it) }, save = {},
        )
        assertFalse(File(dst, "n1/${NightDiagnostics.FILE_NAME}").exists())
        try {
            Backup.restore(
                ByteArrayInputStream(zipOf(Backup.INDEX to index(night()), "clips/n1/${NightDiagnostics.FILE_NAME}" to ByteArray((Backup.MAX_SMALL_JSON_BYTES + 1).toInt()))),
                emptySet(), tmp.newFolder(), { File(tmp.newFolder(), it) }, save = {},
            )
            fail("accepted an oversized detector.json")
        } catch (_: BackupException) {}
    }
}
