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
}
