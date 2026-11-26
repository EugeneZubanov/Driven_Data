package com.quietnight.app.ui

import android.media.MediaPlayer
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.io.File

/**
 * Plays one saved clip at a time. Shared by the timeline markers and the clip list so both show
 * the same playing clip and progress. Always releases the MediaPlayer it created, including when
 * a file is missing or fails to prepare.
 */
@Stable
class ClipPlayer {
    /** File name of the clip that is playing, or null. */
    var playing by mutableStateOf<String?>(null)
        private set

    /** 0..1 through the playing clip. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** File name of the last clip that could not be played. */
    var failed by mutableStateOf<String?>(null)
        private set

    /** Play the next clip when one finishes. */
    var autoAdvance by mutableStateOf(false)

    private var player: MediaPlayer? = null
    private var queue: List<Pair<String, File>> = emptyList()
    /** Clip that was playing most recently, so next/previous work after it stops. */
    var current by mutableStateOf<String?>(null)
        private set

    /** The night's clips in order, for next/previous and auto-advance. */
    fun setQueue(clips: List<Pair<String, File>>) {
        queue = clips
    }

    /** Plays the last clip played, or the first one. */
    fun playCurrent() {
        val c = queue.firstOrNull { it.first == current } ?: queue.firstOrNull() ?: return
        play(c.first, c.second)
    }

    fun next() = step(+1)
    fun previous() = step(-1)

    private fun step(by: Int) {
        if (queue.isEmpty()) return
        val i = queue.indexOfFirst { it.first == (playing ?: current) }
        val j = if (i < 0) 0 else i + by
        if (j in queue.indices) play(queue[j].first, queue[j].second)
    }

    private fun finished() {
        val i = queue.indexOfFirst { it.first == playing }
        if (autoAdvance && i >= 0 && i + 1 < queue.size) play(queue[i + 1].first, queue[i + 1].second) else stop()
    }

    fun toggle(name: String, file: File) {
        if (playing == name) stop() else play(name, file)
    }

    fun play(name: String, file: File) {
        stop()
        failed = null
        current = name
        if (!file.isFile) { failed = name; return }
        val p = MediaPlayer()
        try {
            p.setDataSource(file.path)
            p.setOnCompletionListener { if (player === it) finished() }
            p.setOnErrorListener { mp, _, _ -> if (player === mp) { stop(); failed = name }; true }
            p.prepare() // local 10 s WAV: quick enough to prepare synchronously
            p.start()
            player = p
            playing = name
            progress = 0f
        } catch (e: Exception) {
            p.release()
            failed = name
        }
    }

    fun stop() {
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
        playing = null
        progress = 0f
    }

    internal fun tick() {
        val p = player ?: return
        runCatching {
            val d = p.duration
            if (d > 0) progress = (p.currentPosition.toFloat() / d).coerceIn(0f, 1f)
        }
    }

    /** Shows a clip as playing without any audio, for screen renders. */
    @VisibleForTesting
    fun showForPreview(name: String?, progress: Float) {
        playing = name
        current = name
        this.progress = progress
    }
}

/** A [ClipPlayer] tied to one night: stops when the night changes, the screen leaves, or the app goes to the background. */
@Composable
fun rememberClipPlayer(sessionId: String): ClipPlayer {
    val player = remember(sessionId) { ClipPlayer() }
    DisposableEffect(player) { onDispose { player.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.stop() }
    val playing = player.playing
    LaunchedEffect(player, playing) {
        while (playing != null) {
            player.tick()
            delay(50)
        }
    }
    return player
}
