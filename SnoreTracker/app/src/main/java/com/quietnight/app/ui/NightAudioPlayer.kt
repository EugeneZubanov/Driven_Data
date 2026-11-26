package com.quietnight.app.ui

import android.media.MediaPlayer
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.quietnight.app.audio.AudioSegment
import com.quietnight.app.data.NightSeek
import com.quietnight.app.data.SeekPos
import kotlinx.coroutines.delay
import java.io.File

/**
 * Plays a night's whole-night audio segments as one recording from a chosen time, moving on to
 * the next segment at the end of each (skipping gaps) and stopping after the last. Same rules as
 * [ClipPlayer]: one MediaPlayer at a time, released on every path.
 */
@Stable
class NightAudioPlayer(segments: List<AudioSegment>, private val dir: File) {
    val segments: List<AudioSegment> = NightSeek.ordered(segments)

    var playing by mutableStateOf(false)
        private set

    /** Night time (ms from the start) of the playhead, or of the chosen start point when paused. */
    var positionMs by mutableLongStateOf(this.segments.firstOrNull()?.startMs ?: 0L)
        private set

    var failed by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null
    private var index = -1

    /** Chooses where playback starts (also moves it while playing). */
    fun cue(atMs: Long) {
        val wasPlaying = playing
        positionMs = atMs
        if (wasPlaying) play()
    }

    fun toggle() = if (playing) pause() else play()

    fun play() {
        val pos = NightSeek.locate(segments, positionMs) ?: NightSeek.locate(segments, 0) ?: return
        start(pos)
    }

    private fun start(pos: SeekPos) {
        release()
        failed = false
        val seg = segments[pos.index]
        val p = MediaPlayer()
        try {
            p.setDataSource(File(dir, seg.file).path)
            p.setOnCompletionListener { if (player === it) advance() }
            p.setOnErrorListener { mp, _, _ -> if (player === mp) { pause(); failed = true }; true }
            p.prepare()
            if (pos.offsetMs > 0) p.seekTo(pos.offsetMs.toInt())
            p.start()
            player = p
            index = pos.index
            positionMs = NightSeek.nightMs(segments, pos)
            playing = true
        } catch (e: Exception) {
            p.release()
            failed = true
            playing = false
        }
    }

    private fun advance() {
        val next = NightSeek.next(segments, index)
        if (next == null) {
            pause()
            positionMs = segments.first().startMs // back to the start for next time
        } else start(SeekPos(next, 0))
    }

    /** Stops sound and keeps the position. */
    fun pause() {
        release()
        playing = false
    }

    private fun release() {
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
    }

    internal fun tick() {
        val p = player ?: return
        val seg = segments.getOrNull(index) ?: return
        runCatching { positionMs = seg.startMs + p.currentPosition }
    }

    @VisibleForTesting
    fun showForPreview(atMs: Long, isPlaying: Boolean) {
        positionMs = atMs
        playing = isPlaying
    }
}

/** A [NightAudioPlayer] for one night: stops when the night changes, the screen leaves, or the app goes to the background. */
@Composable
fun rememberNightAudioPlayer(sessionId: String, segments: List<AudioSegment>, dir: File): NightAudioPlayer {
    val player = remember(sessionId, segments) { NightAudioPlayer(segments, dir) }
    DisposableEffect(player) { onDispose { player.pause() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    val playing = player.playing
    LaunchedEffect(player, playing) {
        while (playing) {
            player.tick()
            delay(200)
        }
    }
    return player
}
