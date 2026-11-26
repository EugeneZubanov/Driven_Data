package com.quietnight.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.quietnight.app.data.NightStats
import com.quietnight.app.ui.hourLabel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole app in one pass: first-run guide, sample nights from Settings, Results list, a night,
 * zoom, back, Trends calendar into a night. Waits on visible text rather than timing, so it does
 * not depend on how fast nights load.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-xhdpi")
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val timeout = 10_000L

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeout) {
            compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(timeout) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Bottom navigation item: the clickable node carrying the label. */
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))

    @Test fun wholeAppFlow() {
        val app = compose.activity.application as QuietNightApp

        // 1. First-run guide: skip it.
        waitForText("Skip")
        compose.onNodeWithText("Skip").performClick()

        // 2. Settings: add sample nights.
        tab("Settings").performClick()
        waitForText("Add sample nights")
        compose.onNodeWithText("Add sample nights").performScrollTo().performClick()
        compose.waitUntil(timeout) { app.repository.sessions.value.size > 10 }
        waitForText("Remove sample nights")

        // 3. Results list shows the newest night pinned, and month groups.
        tab("Results").performClick()
        val newest = app.repository.sessions.value.first()
        val title = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(newest.endMs))
        waitForText(title)
        compose.onAllNodesWithText("nights · avg", substring = true).onFirst().assertExists()

        // 4. Open it.
        compose.onNode(hasText(title, substring = true) and hasClickAction()).performClick()
        waitForText("Snore score")
        compose.onNodeWithText(SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(newest.endMs))).assertExists()

        // 5. Zoom to the second hour of the night and back out.
        val hour = NightStats.hourStarts(newest)[1]
        compose.onNodeWithText(hourLabel(hour)).performScrollTo().performClick()
        waitForText("Whole night")
        compose.onNodeWithText("Whole night").performClick()
        compose.waitUntil(timeout) { compose.onAllNodesWithText("Whole night").fetchSemanticsNodes().isEmpty() }

        // 5b. Mark a snoring stretch as not snoring with touch and hold, then undo it.
        val night = app.repository.sessions.value.first { it.id == newest.id }
        val before = night.score
        val bucket = night.levels.indices.first { night.effectiveLevel(it) >= 2 }
        val frac = bucket * night.bucketSec * 1000f / (night.endMs - night.startMs)
        val chart = compose.onNode(hasContentDescription("Timeline from", substring = true))
        chart.performScrollTo().performTouchInput { longClick(androidx.compose.ui.geometry.Offset(width * frac + 2f, height * 0.75f)) }
        waitForText("Not snoring")
        compose.onNode(hasText("Not snoring") and hasClickAction()).performClick()
        waitForText("Undo last")
        compose.waitUntil(timeout) { app.repository.sessions.value.first { it.id == newest.id }.notSnoring.size == 1 }
        assertTrue(app.repository.sessions.value.first { it.id == newest.id }.score < before)
        compose.onNodeWithText("Undo last").performClick()
        compose.waitUntil(timeout) { app.repository.sessions.value.first { it.id == newest.id }.notSnoring.isEmpty() }
        assertTrue(app.repository.sessions.value.first { it.id == newest.id }.score == before)
        compose.waitUntil(timeout) { compose.onAllNodesWithText("Undo last").fetchSemanticsNodes().isEmpty() }

        // 6. Back to the list.
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Results")
        waitForText(title)

        // 7. Trends: tap a recorded day in the calendar and land on that night.
        tab("Trends").performClick()
        val day = hasContentDescription("Day ", substring = true) and hasClickAction()
        waitFor(day)
        val node = compose.onAllNodes(day).onFirst()
        val label = node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
        node.performScrollTo().performClick()
        waitForText("Snore score")
        val score = label.substringAfter("score ").trim()
        assertTrue("calendar label was $label", score.toIntOrNull() != null)
        // The opened night has the score the calendar showed.
        compose.onAllNodesWithText(score).onFirst().assertExists()
        compose.onNodeWithContentDescription("Back").assertExists()
    }
}
