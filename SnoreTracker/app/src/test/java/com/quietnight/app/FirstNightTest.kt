package com.quietnight.app

import android.Manifest
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.quietnight.app.audio.MicInput
import com.quietnight.app.audio.RecordingEnv
import com.quietnight.app.audio.RecordingService
import com.quietnight.app.data.Session
import com.quietnight.app.data.formatDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * A first real night from the user's side: fresh install, skip the guide, press Start, a simulated
 * night with snoring in the middle, stop it the way the screen does, and check what the user then
 * sees on every tab and on the widget. The real RecordingService runs with a fake microphone and
 * clock (as in RecordingServiceTest), so an hour passes in about a second.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-xhdpi")
class FirstNightTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<QuietNightApp>()
    private val clockMs = AtomicLong(0)
    private val wallBase = System.currentTimeMillis() - 3 * 3_600_000L
    private val rnd = Random(11)
    private val timeout = 20_000L
    private var controller: ServiceController<RecordingService>? = null

    /** Simulated seconds after which the fake night slows to real time, waiting for the user to stop it. */
    @Volatile private var holdAtSec = Double.MAX_VALUE
    private val snoring: (Double) -> Boolean = { t -> t in 900.0..2400.0 }

    private inner class FakeMic(override val sampleRate: Int) : MicInput {
        override fun read(buf: ShortArray, size: Int): Int {
            val t = clockMs.get() / 1000.0
            if (t >= holdAtSec) Thread.sleep(20) // the night is over; wait for Stop at about real speed
            TestAudio.chunk(t, size, rnd, snoring, sampleRate).copyInto(buf)
            clockMs.addAndGet(size * 1000L / sampleRate)
            return size
        }
        override fun silenced() = false
        override fun close() {}
    }

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        RecordingEnv.openMic = { _, rates -> FakeMic(rates.first()) }
        RecordingEnv.elapsedMs = { clockMs.get() }
        RecordingEnv.wallMs = { wallBase + clockMs.get() }
        RecordingEnv.sleep = { clockMs.addAndGet(it); Thread.sleep(1) }
        RecordingEnv.battery = { 90 to true }
        RecordingEnv.audioSupported = { false }
        RecordingEnv.audioSink = { it } // simulated time runs far faster than the async encoder queue
        RecordingService.clearFinished()
        RecordingService.clearNotice()
    }

    @After fun tearDown() {
        RecordingEnv.reset()
    }

    private fun waitForText(text: String, substring: Boolean = false) = compose.waitUntil(timeout) {
        compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))

    /** Hands intents the app sent to the service (Robolectric only records them) to a real service instance. */
    private fun deliverServiceIntents() {
        while (true) {
            val i: Intent = shadowOf(app).nextStartedService ?: return
            val c = controller ?: Robolectric.buildService(RecordingService::class.java, i).create().also { controller = it }
            c.get().onStartCommand(i, 0, 1)
        }
    }

    private fun skipGuideAndSetUp() {
        waitForText("Skip")
        compose.onNodeWithText("Skip").performClick()
        compose.waitUntil(timeout) { app.repository.loaded.value }
        // Fall-asleep delay: none, so listening starts at once.
        app.settings.update { it.copy(delayMin = 0, saveClips = true) }
        waitForText("Start")
    }

    /** Taps Start; accepts the "Before you start" warnings if this phone shows any. */
    private fun pressStart() {
        compose.onNodeWithText("Start").performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Start anyway").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Start anyway").performClick()
        compose.waitForIdle()
        deliverServiceIntents()
        compose.waitUntil(timeout) { RecordingService.state.value.recording }
    }

    private fun awaitSaved(): Session {
        // The app clears finishedId once it has opened the night, so wait for the saved, finished night.
        compose.waitUntil(60_000) {
            deliverServiceIntents()
            !RecordingService.state.value.recording && app.repository.sessions.value.isNotEmpty()
        }
        return app.repository.sessions.value.first()
    }

    /** What the user sees after a night is saved: the detail screen opened for it, with the summary. */
    private fun checkDetail(night: Session) {
        waitForText("Good morning")
        waitForText("Snore score")
        val desc = compose.onAllNodes(hasContentDescription("Timeline from", substring = true)).fetchSemanticsNodes()
            .single().config[SemanticsProperties.ContentDescription].first()
        assertTrue(desc, desc.contains("Snoring ${formatDuration(night.snoringSec)}"))
    }

    @Test fun firstRealNight() {
        skipGuideAndSetUp()
        pressStart()

        // About 65 minutes with snoring from minute 15 to 40, then the user stops it.
        holdAtSec = 65 * 60.0
        compose.waitUntil(60_000) { clockMs.get() >= 65 * 60_000L }
        val stop = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button) and
            SemanticsMatcher("stop action") { n -> n.config.getOrNull(SemanticsActions.OnClick)?.label == "Stop recording" })
        stop.performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Stop recording?")
        compose.onNode(hasText("Stop") and hasClickAction()).performClick()
        compose.waitForIdle()
        deliverServiceIntents()
        val night = awaitSaved()

        assertTrue("snoring ${night.snoringSec}", night.snoringSec > 0)
        assertTrue("duration ${night.durationSec}", night.durationSec in 64 * 60..70 * 60)
        assertTrue("clips ${night.clips}", night.clips.isNotEmpty())
        checkDetail(night)
        compose.onAllNodesWithContentDescription("Play clip from", substring = true, useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue("clip rows", it.isNotEmpty())
        }

        // Results: exactly one night.
        compose.onNode(hasContentDescription("Back")).performClick()
        waitForText("Last night")
        assertEquals(1, app.repository.sessions.value.size)
        waitForText("1 night · avg", substring = true)

        // Record tab: the last-night card.
        tab("Record").performClick()
        waitForText("Last night")
        waitForText("${formatDuration(night.durationSec)} recorded", substring = true)

        // Trends: one counted night.
        tab("Trends").performClick()
        waitForText("1 night recorded")

        // Widget content has it.
        val w = WidgetContent.of(app.repository.sessions.value)
        assertEquals(night.score.toString(), w.score)
        assertTrue(w.title, w.title.startsWith("Last night"))
    }

    @Test fun fiveMinuteTestRun() {
        skipGuideAndSetUp()
        holdAtSec = 6 * 60.0 // the test run ends on its own at 5 minutes
        compose.onNodeWithText("Try a 5-minute test run").performScrollTo().performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Start anyway").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Start anyway").performClick()
        deliverServiceIntents()
        val night = awaitSaved()

        assertEquals(Session.TEST_RUN_NOTE, night.note)
        assertTrue("duration ${night.durationSec}", night.durationSec in 290..320)
        assertTrue(!night.countsForTrends)
        waitForText("Snore score")
        compose.onAllNodes(hasContentDescription("Timeline from", substring = true)).fetchSemanticsNodes().single()

        compose.onNode(hasContentDescription("Back")).performClick()
        waitForText("Test run", substring = true)
        tab("Trends").performClick()
        waitForText("Trends appear once you have a night with at least an hour recorded. Test runs are left out.")
        assertEquals("No nights yet. Tap to record.", WidgetContent.of(app.repository.sessions.value).detail)
    }
}
