package com.quietnight.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.quietnight.app.ui.NightCalendar
import com.quietnight.app.ui.IntroScreen
import com.quietnight.app.ui.WeightCard
import com.quietnight.app.data.WeightEntry
import com.quietnight.app.data.WeightLog
import com.github.takahirom.roborazzi.captureRoboImage
import com.quietnight.app.audio.RecState
import com.quietnight.app.data.DemoData
import com.quietnight.app.data.Prefs
import com.quietnight.app.ui.Palette
import com.quietnight.app.ui.QuietNightTheme
import com.quietnight.app.ui.RecordScreen
import com.quietnight.app.ui.ResultsListScreen
import com.quietnight.app.ui.SessionDetailScreen
import com.quietnight.app.ui.SettingsScreen
import com.quietnight.app.ui.TrendsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders each screen with sample data to PNGs under build/shots for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_795_700_000_000L // late Nov 2026
    private val sessions = DemoData.generate(now).sortedByDescending { it.startMs }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            QuietNightTheme { Box(Modifier.fillMaxSize().background(Palette.Background)) { content() } }
        }
        compose.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Test fun record() = shot("record") {
        RecordScreen(Prefs(selectedTags = setOf("r:Side sleeping")), RecState(), sessions.first(), {}, {}, {}, {})
    }

    @Test fun recording() = shot("recording") {
        RecordScreen(
            Prefs(), RecState(recording = true, startMs = now - 3 * 3600_000L, delayMs = 0, listening = true, dbAboveFloor = 14f, eventCount = 212),
            null, {}, {}, {}, {}, nowMs = { now },
        )
    }

    @Test fun results() = shot("results") { ResultsListScreen(sessions) {} }

    @Test fun detail() = shot("detail") {
        SessionDetailScreen(sessions.maxBy { it.score }, {}, {}, null, {}, { File(it) })
    }

    @Test fun trends() = shot("trends") { TrendsScreen(sessions) }

    @Test fun trendsMonths() {
        shot("trends-months") { TrendsScreen(sessions) }
        compose.onNodeWithText("Months").performClick()
        compose.onRoot().captureRoboImage("build/shots/trends-months.png")
    }

    @Test fun settings() = shot("settings") { SettingsScreen(Prefs(), false, true, {}, {}, {}, {}) }

    @Test fun settingsData() {
        shot("settings-data") { SettingsScreen(Prefs(), false, true, {}, {}, {}, {}, {}, {}, {}, "Added 12 nights. 3 already here.") }
        compose.onNodeWithText("Your data").performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/settings-data.png")
    }

    /** The Trends screen is long; capture it whole at a tall size so every section can be checked. */
    @Test @Config(qualifiers = "w411dp-h3200dp-xhdpi")
    fun trendsFull() = shot("trends-full") { TrendsScreen(sessions) }

    @Test fun trendsOneNight() = shot("trends-one-night") { TrendsScreen(sessions.take(1)) }

    @Test fun trendsWeeks() {
        shot("trends-weeks") { TrendsScreen(sessions) }
        compose.onNodeWithText("Weeks").performClick()
        compose.onRoot().captureRoboImage("build/shots/trends-weeks.png")
    }

    @Test fun intro1() = shot("intro-1") { IntroScreen({}) }
    @Test fun intro3() = shot("intro-3") { IntroScreen({}, startPage = 2) }

    @Test @Config(qualifiers = "w411dp-h2000dp-xhdpi")
    fun settingsReminderWeight() {
        val log = WeightLog(listOf(WeightEntry(now - 86_400_000L, 82.3f)), pounds = false)
        shot("settings-reminder") {
            SettingsScreen(
                Prefs(reminderOn = true), false, true, {}, {}, {}, {}, {}, {}, {}, "Restoring… 40 files read", dataBusy = true,
                onShowIntro = {}, onReminder = { _, _ -> }, weight = log, onWeight = {},
            )
        }
    }

    @Test fun weightCard() = shot("weight") {
        val day = 86_400_000L
        val log = WeightLog((0 until 12).map { WeightEntry(now - (80 - it * 7) * day, 84f - it * 0.35f + (it % 3) * 0.2f) })
        Column(Modifier.padding(20.dp)) {
            WeightCard(sessions, log, {}, todayMs = now)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
            WeightCard(sessions, WeightLog(), {}, todayMs = now)
        }
    }

    @Test fun calendar() = shot("calendar") {
        Column(Modifier.padding(20.dp)) { NightCalendar(sessions, {}, todayMs = now) }
    }
}
