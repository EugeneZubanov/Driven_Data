package com.quietnight.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import com.quietnight.app.audio.SegmentIndex
import java.io.File

/** Stores each night as one JSON file plus a folder of WAV clips. Newest session first. */
private fun Session.withoutClips() = Session(
    id, startMs, endMs, bucketSec, levels, amps, tags, emptyList(), demo = demo,
    interruptedAtMs = interruptedAtMs, gaps = gaps, note = note, notSnoring = notSnoring, others = others, conf = conf,
)

class SessionRepository(private val root: File) {
    private val sessionsDir = File(root, "sessions").apply { mkdirs() }
    private val clipsDir = File(root, "clips").apply { mkdirs() }
    private val trashDir = File(root, "trash")

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    private val _loaded = MutableStateFlow(false)

    /** False until the saved nights have been read; screens show a loading state meanwhile. */
    val loaded: StateFlow<Boolean> = _loaded

    /**
     * Reads every saved night. Files that cannot be parsed (corrupt, or cut off mid-write) are
     * skipped without affecting the others; leftover temp files from an interrupted save are removed.
     * Holds the lock throughout, so a [save] from the recording thread is never lost: it either
     * lands on disk before the files are listed or is applied after.
     */
    @Synchronized
    fun load() {
        purgeTrash()
        sessionsDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        _sessions.value = (sessionsDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { Session.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.startMs }
        _loaded.value = true
    }

    /** Loads now on the calling thread unless already loaded. For code that needs the full list. */
    fun ensureLoaded() {
        if (_loaded.value) return
        synchronized(this) { if (!_loaded.value) load() }
    }

    /** Starts loading on a background thread so app start does not wait for it. */
    fun loadInBackground() {
        Thread({ ensureLoaded() }, "load-nights").start()
    }

    @Synchronized
    fun save(session: Session) {
        val tmp = File(sessionsDir, "${session.id}.tmp")
        tmp.writeText(session.toJson().toString())
        tmp.renameTo(File(sessionsDir, "${session.id}.json"))
        _sessions.value = (_sessions.value.filter { it.id != session.id } + session).sortedByDescending { it.startMs }
    }

    @Synchronized
    fun delete(id: String) {
        File(sessionsDir, "$id.json").delete()
        clipDir(id).deleteRecursively()
        _sessions.value = _sessions.value.filter { it.id != id }
    }

    /**
     * Removes a night but keeps its files in a trash folder so [restore] can undo it. Only the
     * most recent trashed night is kept; older ones, and any left at the next [load], are deleted.
     */
    @Synchronized
    fun trash(id: String) {
        purgeTrash()
        val json = File(sessionsDir, "$id.json")
        if (json.exists()) {
            trashDir.mkdirs()
            if (!json.renameTo(File(trashDir, "$id.json"))) json.delete()
            val clips = clipDir(id)
            if (clips.exists() && !clips.renameTo(File(trashDir, "$id-clips"))) clips.deleteRecursively()
        }
        _sessions.value = _sessions.value.filter { it.id != id }
    }

    /** Brings back a night removed with [trash]. Returns false if it is no longer there. */
    @Synchronized
    fun restore(id: String): Boolean {
        val json = File(trashDir, "$id.json")
        val session = runCatching { Session.fromJson(JSONObject(json.readText())) }.getOrNull() ?: return false
        json.renameTo(File(sessionsDir, "$id.json"))
        File(trashDir, "$id-clips").let { if (it.exists()) it.renameTo(clipDir(id)) }
        _sessions.value = (_sessions.value.filter { it.id != id } + session).sortedByDescending { it.startMs }
        return true
    }

    @Synchronized
    fun purgeTrash() {
        trashDir.deleteRecursively()
    }

    fun deleteWhere(predicate: (Session) -> Boolean) {
        ensureLoaded()
        _sessions.value.filter(predicate).forEach { delete(it.id) }
    }

    /** Whole-night audio files (segments and their index), as opposed to the ten-second clips. */
    private fun isNightAudio(f: File) = f.name == SegmentIndex.FILE || f.name.startsWith("audio-") || f.name.startsWith(SegmentIndex.FILE)

    /** Bytes used by one night's ten-second clips (not its whole-night audio). */
    fun clipBytes(sessionId: String): Long =
        clipDir(sessionId).listFiles()?.filter { it.isFile && !isNightAudio(it) }?.sumOf { it.length() } ?: 0L

    /** Bytes used by one night's whole-night audio. */
    fun audioBytes(sessionId: String): Long =
        clipDir(sessionId).listFiles()?.filter { it.isFile && isNightAudio(it) }?.sumOf { it.length() } ?: 0L

    /** Bytes used by all saved audio, clips and whole-night recordings (not counting a night in the trash). */
    fun clipBytes(): Long = clipsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes a night's ten-second clips but keeps the night and any whole-night audio. Returns the bytes freed. */
    @Synchronized
    fun deleteClips(sessionId: String): Long {
        val files = clipDir(sessionId).listFiles()?.filter { it.isFile && !isNightAudio(it) }.orEmpty()
        val freed = files.sumOf { it.length() }
        files.forEach { it.delete() }
        clipDir(sessionId).delete() // only removed if now empty
        _sessions.value.firstOrNull { it.id == sessionId }?.let { s ->
            if (s.clips.isNotEmpty()) save(s.withoutClips())
        }
        return freed
    }

    /** Deletes a night's whole-night audio but keeps the night and its clips. Returns the bytes freed. */
    @Synchronized
    fun deleteNightAudio(sessionId: String): Long {
        val files = clipDir(sessionId).listFiles()?.filter { it.isFile && isNightAudio(it) }.orEmpty()
        val freed = files.sumOf { it.length() }
        files.forEach { it.delete() }
        clipDir(sessionId).delete() // only removed if now empty
        return freed
    }

    /** Deletes the clips of nights that ended more than [days] days before [nowMs]. Returns the bytes freed. */
    fun deleteClipsOlderThan(days: Int, nowMs: Long = System.currentTimeMillis()): Long {
        ensureLoaded()
        val cutoff = nowMs - days * 86_400_000L
        return _sessions.value.filter { it.endMs < cutoff && (it.clips.isNotEmpty() || clipBytes(it.id) > 0) }
            .sumOf { deleteClips(it.id) }
    }

    /** Deletes the whole-night audio of nights that ended more than [days] days before [nowMs]. Returns the bytes freed. */
    fun deleteNightAudioOlderThan(days: Int, nowMs: Long = System.currentTimeMillis()): Long {
        ensureLoaded()
        val cutoff = nowMs - days * 86_400_000L
        return _sessions.value.filter { it.endMs < cutoff }.sumOf { deleteNightAudio(it.id) }
    }

    fun clipDir(sessionId: String) = File(clipsDir, sessionId)

    fun clipFile(sessionId: String, name: String) = File(clipDir(sessionId), name)
}
