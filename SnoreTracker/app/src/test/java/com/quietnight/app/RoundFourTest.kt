package com.quietnight.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.quietnight.app.data.ReportData
import com.quietnight.app.data.Session
import com.quietnight.app.data.Trends
import com.quietnight.app.data.WeightEntry
import com.quietnight.app.data.WeightLog
import com.quietnight.app.ui.ReportRenderer
import com.quietnight.app.ui.clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoundFourTest {
    private val day = 86_400_000L
    private val now = 1_795_700_000_000L

    private fun night(endMs: Long, snore: Int, tags: List<String> = emptyList(), demo: Boolean = false): Session {
        val n = 480
        return Session(
            id = "s$endMs", startMs = endMs - n * 60_000L, endMs = endMs, bucketSec = 60,
            levels = ByteArray(n) { if (it < snore) 2 else 0 }, amps = ByteArray(n), tags = tags, clips = emptyList(), demo = demo,
        )
    }

    @Test fun requestedTabReadsOnlyValidExtras() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(0, Reminder.requestedTab(Reminder.openRecordIntent(ctx)))
        assertNull(Reminder.requestedTab(Intent()))
        assertNull(Reminder.requestedTab(null))
        assertNull(Reminder.requestedTab(Intent().putExtra(Reminder.EXTRA_OPEN_TAB, 9)))
    }

    @Test fun reminderNotificationOpensRecordTab() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Reminder.show(app)
        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(Reminder.TAB_RECORD, Reminder.requestedTab(intent))
    }

    @Test fun reminderNotShownWithoutPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Reminder.show(app)
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun reportCoversThirtyDaysOfRealNights() {
        val nights = (0 until 40).map { night(now - it * day, snore = 48) } + night(now, 400, demo = true)
        val r = ReportData.build(nights, WeightLog(listOf(WeightEntry(Trends.dayOf(now).timeInMillis, 80f), WeightEntry(now - 90 * day, 85f))), now)
        assertEquals(30, r.nights.size)
        assertTrue(r.nights.none { it.demo })
        assertEquals(10, r.averageScore)
        assertEquals(30 * 48 * 60, r.snoringSec[1])
        assertEquals(1, r.weight.size) // the 90-day-old reading is outside the window
        // Window starts on the local midnight 29 days before today (DST may shift it by an hour).
        assertTrue(abs(Trends.dayOf(now).timeInMillis - 29 * day - r.fromMs) <= 3_600_000L)
        val empty = ReportData.build(emptyList(), null, now)
        assertFalse(empty.hasNights)
        assertEquals(0, empty.averageScore)
    }

    @Test fun reportTagComparisonKeepsTheMinimumNightsRule() {
        val few = (0 until 10).map { night(now - it * day, 48, if (it < 3) listOf("f:Alcohol") else emptyList()) }
        val r = ReportData.build(few, null, now)
        assertTrue(r.tags.effects.isEmpty())
        assertEquals(1, r.tags.pending.size)
    }

    @Test fun widgetContentUsesLatestRealNight() {
        assertEquals("–", WidgetContent.of(emptyList()).score)
        val c = WidgetContent.of(listOf(night(now - day, 48), night(now, 96), night(now + day, 400, demo = true)))
        assertEquals("20", c.score)
        assertTrue(c.detail.startsWith("Snoring 1h 36m"))
    }

    @Test fun clockFollowsSetting() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val t = java.util.Calendar.getInstance().apply { clear(); set(2026, 10, 26, 22, 5) }.timeInMillis
            assertEquals("22:05", clock(t, true))
            assertEquals("10:05 PM", clock(t, false))
        } finally { Locale.setDefault(saved) }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun reportRendersToPng() {
        // Sample nights, marked as real so the report includes them.
        val nights = com.quietnight.app.data.DemoData.generate(now).map {
            Session(id = it.id, startMs = it.startMs, endMs = it.endMs, bucketSec = it.bucketSec, levels = it.levels, amps = it.amps, tags = it.tags, clips = it.clips)
        }
        val weight = WeightLog((0 until 6).map { WeightEntry(Trends.dayOf(now - it * 5 * day).timeInMillis, 82f - it * 0.4f) })
        val data = ReportData.build(nights, weight, now)
        val scale = 2
        val bmp = Bitmap.createBitmap(ReportRenderer.PAGE_W * scale, ReportRenderer.PAGE_H * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { scale(scale.toFloat(), scale.toFloat()) }
        ReportRenderer.draw(canvas, data)
        File("build/shots").mkdirs()
        FileOutputStream("build/shots/report.png").use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File("build/shots/report.png").length() > 1000)
        assertTrue(data.weight.zipWithNext().all { (a, b) -> a.dayMs < b.dayMs }) // oldest first, whatever the input order
    }

    @Test fun reportFileNameIsDated() {
        assertTrue(ReportData.fileName(now).matches(Regex("quiet-night-report-\\d{4}-\\d{2}-\\d{2}\\.pdf")))
    }
}
