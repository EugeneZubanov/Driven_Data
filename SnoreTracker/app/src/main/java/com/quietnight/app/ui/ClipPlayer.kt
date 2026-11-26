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

    private var player: MediaPlayer? = null

    fun toggle(name: String, file: File) {
        if (playing == name) stop() else play(name, file)
    }

    fun play(name: String, file: File) {
        stop()
        failed = null
        if (!file.isFile) { failed = name; return }
        val p = MediaPlayer()
        try {
            p.setDataSource(file.path)
            p.setOnCompletionListener { if (player === it) stop() }
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
