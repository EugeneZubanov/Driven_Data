package com.quietnight.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** Stores each night as one JSON file plus a folder of WAV clips. Newest session first. */
class SessionRepository(private val root: File) {
    private val sessionsDir = File(root, "sessions").apply { mkdirs() }
    private val clipsDir = File(root, "clips").apply { mkdirs() }

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    @Synchronized
    fun load() {
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

    fun deleteWhere(predicate: (Session) -> Boolean) {
        _sessions.value.filter(predicate).forEach { delete(it.id) }
    }

    fun clipDir(sessionId: String) = File(clipsDir, sessionId)

    fun clipFile(sessionId: String, name: String) = File(clipDir(sessionId), name)
}
