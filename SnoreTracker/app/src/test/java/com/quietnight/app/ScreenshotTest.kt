package com.quietnight.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.quietnight.app.ui.NightCalendar
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.compose.ui.test.performTouchInput
import com.quietnight.app.audio.MicCheckState
import com.quietnight.app.audio.RecState
import com.quietnight.app.audio.RecordingPolicy
import com.quietnight.app.data.Clip
import com.quietnight.app.data.DemoData
import com.quietnight.app.data.NightStats
import com.quietnight.app.data.Session
import com.quietnight.app.ui.ClipPlayer
import com.quietnight.app.ui.Card
import com.quietnight.app.ui.EditNightFields
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.quietnight.app.ui.clock
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

    @Test fun recordChecks() = shot("record-checks") {
        RecordScreen(
            Prefs(), RecState(notice = "Your last recording was cut short at 03:12, probably because the phone closed the app. The night up to then was kept.", noticeSessionId = "s1"),
            null, {}, {}, {}, {}, nowMs = { now },
            batteryExempt = false,
            mic = MicCheckState(running = true, level = 0.7f, heard = true),
        )
    }

    @Test fun recordWarnings() {
        shot("record-warnings") {
            RecordScreen(
                Prefs(), RecState(), null, {}, {}, {}, {}, nowMs = { now },
                startWarnings = { RecordingPolicy.preflightWarnings(23, charging = false, freeBytes = 10_000_000) },
                mic = MicCheckState(done = true, heard = false),
            )
        }
        compose.onNodeWithText("Start").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start anyway").assertExists()
        // The dialog is its own window, so capture the whole screen.
        captureScreenRoboImage("build/shots/record-warnings.png")
    }

    @Test fun recordingPaused() {
        shot("recording-paused") {
            RecordScreen(
                Prefs(), RecState(recording = true, startMs = now - 5 * 3600_000L, listening = true, micPaused = true, gapSec = 840, eventCount = 97),
                null, {}, {}, {}, {}, nowMs = { now },
            )
        }
        // A short tap on Stop only shows the hint; the night keeps recording.
        compose.onNodeWithText("Hold to stop").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/shots/recording-paused.png")
    }

    @Test fun recordingDimmed() = shot("recording-dimmed") {
        RecordScreen(
            Prefs(), RecState(recording = true, startMs = now - 3 * 3600_000L, listening = true),
            null, {}, {}, {}, {}, nowMs = { now }, dimAfterMs = 0,
        )
    }

    @Test fun results() = shot("results") { ResultsListScreen(sessions, nowMs = now) {} }

    @Test fun detail() = shot("detail") {
        SessionDetailScreen(sessions.maxBy { it.score }, {}, {}, null, {}, { File(it) }, history = sessions)
    }

    /** Loudest demo night with a few clips added, the second one shown playing. */
    private fun withClips(): Session {
        val s = sessions.maxBy { it.score }
        val loud = s.levels.indices.filter { s.levels[it] >= 2 }
        val picks = listOf(loud[loud.size / 8], loud[loud.size / 2], loud[loud.size * 7 / 8])
        val clips = picks.map { Clip(it * s.bucketSec, "c$it.wav", s.levels[it].toInt(), s.amps[it].toInt()) }
        return Session(s.id, s.startMs, s.endMs, s.bucketSec, s.levels, s.amps, s.tags, clips, s.demo, note = "Went to bed late after a dinner out.")
    }

    /** Same as [shot] with text 30% larger, to check for clipping. */
    private fun bigShot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1.3f),
            ) {
                QuietNightTheme { Box(Modifier.fillMaxSize().background(Palette.Background)) { content() } }
            }
        }
        compose.onRoot().captureRoboImage("build/shots/$name.png")
    }

    /** A night with two stretches of no sound that was cut short. */
    private fun withProblems(): Session {
        val s = withClips()
        return Session(
            s.id, s.startMs, s.endMs, s.bucketSec, s.levels, s.amps, s.tags, s.clips, s.demo,
            interruptedAtMs = s.endMs,
            gaps = listOf(com.quietnight.app.data.Gap(5400, 7200), com.quietnight.app.data.Gap(20000, 20900)),
            note = s.note,
        )
    }

    @Test fun detailProblems() {
        val s = withProblems()
        val other = sessions.first { it.id != s.id }
        shot("detail-problems") {
            SessionDetailScreen(s, {}, {}, null, {}, { File(it) }, history = sessions, onSave = {}, initialCompareId = other.id)
        }
        compose.onNodeWithText("Longest quiet").performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/detail-compare.png")
    }

    @Test fun detailLargeText() {
        bigShot("detail-large-text") { SessionDetailScreen(withProblems(), {}, {}, null, {}, { File(it) }, history = sessions, onSave = {}) }
        compose.onAllNodesWithContentDescription("Play clip from", substring = true, useUnmergedTree = true).onLast().performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/detail-large-text-clips.png")
    }

    @Test fun resultsSearch() {
        val noted = sessions.mapIndexed { i, s -> if (i % 4 == 1) s.withEdits(s.tags, "Dinner out with friends") else s }
        shot("results-search") { ResultsListScreen(noted, nowMs = now) {} }
        compose.onNodeWithText("Search notes").performTextInput("dinner")
        compose.onRoot().captureRoboImage("build/shots/results-search.png")
    }

    @Test fun resultsLargeText() = bigShot("results-large-text") { ResultsListScreen(sessions, nowMs = now) {} }

    @Test fun detailClips() {
        val s = withClips()
        val player = ClipPlayer().apply { showForPreview(s.clips[1].file, 0.4f) }
        shot("detail-clips") { SessionDetailScreen(s, {}, {}, null, {}, { File(it) }, history = sessions, onSave = {}, player = player) }
        compose.onAllNodesWithContentDescription("Play clip from", substring = true, useUnmergedTree = true).onLast().performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/detail-clips-list.png")
    }

    @Test fun detailZoomed() {
        val s = withClips()
        val hour = NightStats.hourStarts(s)[3]
        shot("detail-zoomed") { SessionDetailScreen(s, {}, {}, null, {}, { File(it) }, history = sessions, onSave = {}) }
        compose.onNodeWithText(clock(hour)).performClick()
        compose.onRoot().captureRoboImage("build/shots/detail-zoomed.png")
    }

    /** The edit dialog's fields (a real dialog window does not render under Robolectric). */
    @Test fun editNight() = shot("edit-night") {
        val s = withClips()
        Card(Modifier.padding(20.dp)) {
            EditNightFields(s, setOf("f:Late shift"), s.tags.toSet() + "f:Late shift", {}, s.note, {})
        }
    }

    @Test fun resultsFiltered() {
        shot("results-filtered") { ResultsListScreen(sessions, nowMs = now) {} }
        compose.onNodeWithText("Alcohol").performClick()
        compose.onRoot().captureRoboImage("build/shots/results-filtered.png")
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

    @Test fun calendar() = shot("calendar") {
        Column(Modifier.padding(20.dp)) { NightCalendar(sessions, {}, todayMs = now) }
    }
}
