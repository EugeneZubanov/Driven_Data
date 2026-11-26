package com.quietnight.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** Stores each night as one JSON file plus a folder of WAV clips. Newest session first. */
class SessionRepository(private val root: File) {
    private val sessionsDir = File(root, "sessions").apply { mkdirs() }
    private val clipsDir = File(root, "clips").apply { mkdirs() }
    private val trashDir = File(root, "trash")

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    @Synchronized
    fun load() {
        purgeTrash()
        _sessions.value = (sessionsDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { Session.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.startMs }
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
        _sessions.value.filter(predicate).forEach { delete(it.id) }
    }

    fun clipDir(sessionId: String) = File(clipsDir, sessionId)

    fun clipFile(sessionId: String, name: String) = File(clipDir(sessionId), name)
}
