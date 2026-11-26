package com.quietnight.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import com.quietnight.app.audio.RecState
import com.quietnight.app.data.Clip
import com.quietnight.app.data.DemoData
import com.quietnight.app.data.Prefs
import com.quietnight.app.data.Session
import com.quietnight.app.ui.Palette
import com.quietnight.app.ui.QuietNightTheme
import com.quietnight.app.ui.RecordScreen
import com.quietnight.app.ui.ResultsListScreen
import com.quietnight.app.ui.SessionDetailScreen
import com.quietnight.app.ui.SettingsScreen
import com.quietnight.app.ui.TrendsScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What TalkBack would find on each main screen, read from the merged semantics tree: every
 * clickable thing has a label, every toggle has a label and a state, each screen has headings,
 * and charts carry a spoken summary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi") // tall, so whole screens are composed
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_795_700_000_000L
    private val sessions = DemoData.generate(now).sortedByDescending { it.startMs }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { QuietNightTheme { Box(Modifier.fillMaxSize().background(Palette.Background)) { content() } } }
        compose.waitForIdle()
    }

    private fun nodes(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes()

    private fun SemanticsNode.label(): String {
        val c = config
        return listOfNotNull(
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
            c.getOrNull(SemanticsProperties.EditableText)?.text,
        ).joinToString(" ").trim()
    }

    /** Checks shared by every screen. Returns the labels of clickable things, for screen-specific checks. */
    private fun checkScreen(name: String, minHeadings: Int = 1): List<String> {
        val clickable = nodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        val unlabeled = clickable.filter { it.label().isEmpty() }
        assertTrue("$name: ${unlabeled.size} clickable nodes without a label: ${unlabeled.map { it.config }}", unlabeled.isEmpty())

        val toggles = nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        val badToggles = toggles.filter { it.label().isEmpty() }
        assertTrue("$name: toggles without a label: ${badToggles.map { it.config }}", badToggles.isEmpty())

        val headings = nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertTrue("$name: expected at least $minHeadings headings, found ${headings.map { it.label() }}", headings.size >= minHeadings)
        return clickable.map { it.label() }
    }

    @Test fun resultsList() {
        show { ResultsListScreen(sessions, nowMs = now) {} }
        val labels = checkScreen("Results")
        assertTrue(labels.any { it.contains("All nights") })
        // Swipe-to-delete has a screen-reader alternative.
        assertTrue(nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).any { it.label() == "Results" })
        assertTrue(
            nodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
                .any { n -> n.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].any { it.label == "Delete night" } },
        )
    }

    @Test fun nightDetail() {
        val s0 = sessions.maxBy { it.score }
        val loud = s0.levels.indices.filter { s0.levels[it] >= 2 }
        val s = Session(
            s0.id, s0.startMs, s0.endMs, s0.bucketSec, s0.levels, s0.amps, s0.tags,
            listOf(Clip(loud[3] * 10, "a.wav", 2, 30), Clip(loud[40] * 10, "b.wav", 3, 35)), s0.demo,
        )
        show { SessionDetailScreen(s, {}, {}, null, {}, { File(it) }, history = sessions, onSave = {}, onDeleteClips = {}) }
        val labels = checkScreen("Detail", minHeadings = 3)
        for (needed in listOf("Back", "Older night", "Newer night", "Share night as text", "Delete night", "Previous clip", "Next clip")) {
            assertTrue("Detail: no button labelled $needed in $labels", labels.any { it.contains(needed) })
        }
        assertTrue(labels.any { it.startsWith("Play clip from") })
        // The timeline is a drawing; it must carry a spoken summary.
        assertTrue(nodes(hasContentDescription("Timeline from", substring = true)).isNotEmpty())
        // Auto-play is one switch with its label.
        val autoPlay = nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).filter { it.label().contains("Play clips one after another") }
        assertTrue(autoPlay.size == 1)
        assertTrue(autoPlay[0].config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Switch)
    }

    @Test fun trends() {
        show { TrendsScreen(sessions) }
        checkScreen("Trends", minHeadings = 2)
    }

    @Test fun settings() {
        show { SettingsScreen(Prefs(), false, true, {}, {}, {}, {}) }
        checkScreen("Settings", minHeadings = 2)
        assertTrue(nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).isNotEmpty())
    }

    @Test fun record() {
        show { RecordScreen(Prefs(), RecState(), sessions.first(), {}, {}, {}, {}) }
        checkScreen("Record", minHeadings = 0)
    }
}
