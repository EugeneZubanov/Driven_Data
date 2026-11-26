package com.quietnight.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RestoreResult(val added: Int, val skipped: Int, val invalid: Int)

class BackupException(message: String) : IOException(message)

/**
 * Backup zip layout:
 *   nights.json                     {"format":"quietnight-backup","version":1,"sessions":[...]}
 *   clips/<sessionId>/<clipFile>    audio clips, as stored on the phone
 * Sample nights are never exported.
 */
object Backup {
    const val FORMAT = "quietnight-backup"
    const val VERSION = 1
    const val INDEX = "nights.json"

    const val MAX_INDEX_BYTES = 32L * 1024 * 1024
    const val MAX_CLIP_BYTES = 16L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    const val MAX_SESSIONS = 5_000
    private const val MAX_NIGHT_MS = 36 * 3600_000L

    private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")

    fun isSafeName(name: String) = SAFE_NAME.matches(name) && ".." !in name

    fun fileName(nowMs: Long) = "quiet-night-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))}.zip"

    /** Writes every non-sample session and its clips. Returns the number of nights written. */
    fun export(sessions: List<Session>, clipFile: (String, String) -> File, out: OutputStream): Int {
        val real = sessions.filter { !it.demo }
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(INDEX))
            val index = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("sessions", JSONArray().also { arr -> real.forEach { arr.put(it.toJson()) } })
            zip.write(index.toString().toByteArray())
            zip.closeEntry()
            for (s in real) for (c in s.clips) {
                val f = clipFile(s.id, c.file)
                if (!isSafeName(s.id) || !isSafeName(c.file) || !f.isFile) continue
                zip.putNextEntry(ZipEntry("clips/${s.id}/${c.file}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return real.size
    }

    /**
     * Reads a backup zip and adds the nights whose id is not already in [existingIds]. Clips are first
     * unpacked to [staging] (emptied before and after) and only moved into [clipDir] for nights that are
     * added. Nights that fail validation are counted as invalid; a broken or oversized file throws
     * [BackupException] before anything is saved.
     */
    fun restore(
        input: InputStream,
        existingIds: Set<String>,
        staging: File,
        clipDir: (String) -> File,
        save: (Session) -> Unit,
        onProgress: (filesRead: Int) -> Unit = {},
    ): RestoreResult {
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            var index: ByteArray? = null
            var total = 0L
            var entries = 0
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw BackupException("Too many files in backup")
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name == INDEX) {
                        if (index != null) throw BackupException("Backup has two index files")
                        index = readLimited(zip, MAX_INDEX_BYTES)
                        total += index!!.size
                    } else {
                        val parts = name.split('/')
                        // Anything that is not clips/<id>/<file> with plain names is ignored (zip-slip guard).
                        if (parts.size != 3 || parts[0] != "clips" || !isSafeName(parts[1]) || !isSafeName(parts[2])) continue
                        val dir = File(staging, parts[1])
                        val target = File(dir, parts[2])
                        if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
                        dir.mkdirs()
                        total += copyLimited(zip, target, MAX_CLIP_BYTES)
                    }
                    if (total > MAX_TOTAL_BYTES) throw BackupException("Backup is too large")
                    onProgress(entries)
                }
            }
            val bytes = index ?: throw BackupException("Not a Quiet Night backup")
            val root = try { JSONObject(String(bytes)) } catch (e: Exception) { throw BackupException("Backup index is damaged") }
            if (root.optString("format") != FORMAT) throw BackupException("Not a Quiet Night backup")
            if (root.optInt("version", 0) > VERSION) throw BackupException("Backup is from a newer version of the app")
            val arr = root.optJSONArray("sessions") ?: throw BackupException("Backup index is damaged")
            if (arr.length() > MAX_SESSIONS) throw BackupException("Backup has too many nights")

            var added = 0; var skipped = 0; var invalid = 0
            val seen = HashSet(existingIds)
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i)?.let { o -> runCatching { Session.fromJson(o) }.getOrNull() }
                if (s == null || !isValid(s)) { invalid++; continue }
                if (s.id in seen) { skipped++; continue }
                seen += s.id
                val staged = File(staging, s.id)
                val dest = clipDir(s.id)
                val clips = s.clips.filter { isSafeName(it.file) && File(staged, it.file).isFile }
                if (clips.isNotEmpty()) {
                    dest.mkdirs()
                    for (c in clips) {
                        val from = File(staged, c.file)
                        val to = File(dest, c.file)
                        if (!from.renameTo(to)) from.copyTo(to, overwrite = true)
                    }
                }
                save(Session(s.id, s.startMs, s.endMs, s.bucketSec, s.levels, s.amps, s.tags, clips, demo = false, interruptedAtMs = s.interruptedAtMs, gaps = s.gaps, note = s.note))
                added++
            }
            return RestoreResult(added, skipped, invalid)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun isValid(s: Session): Boolean =
        isSafeName(s.id) &&
            s.bucketSec in 1..600 &&
            s.startMs > 0 && s.endMs > s.startMs && s.endMs - s.startMs <= MAX_NIGHT_MS &&
            s.levels.size == s.amps.size &&
            s.levels.size.toLong() * s.bucketSec <= MAX_NIGHT_MS / 1000 &&
            s.levels.all { it in 0..3 } &&
            s.tags.size <= 50 && s.tags.all { it.length <= 60 } &&
            s.clips.size <= 100

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            n += r
            if (n > limit) throw BackupException("Backup index is too large")
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun copyLimited(input: InputStream, target: File, limit: Long): Long {
        val buf = ByteArray(16 * 1024)
        var n = 0L
        target.outputStream().use { out ->
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                n += r
                if (n > limit) throw BackupException("A clip in the backup is too large")
                out.write(buf, 0, r)
            }
        }
        return n
    }
}

/** One row per night for spreadsheets. Sample nights are left out. */
object Csv {
    const val HEADER = "date,start,end,duration_min,mild_min,loud_min,heavy_min,score,tags"

    fun fileName(nowMs: Long) = "quiet-night-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))}.csv"

    fun export(sessions: List<Session>): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder(HEADER).append("\r\n")
        for (s in sessions.filter { !it.demo }.sortedBy { it.startMs }) {
            sb.append(date.format(Date(s.endMs))).append(',')
                .append(time.format(Date(s.startMs))).append(',')
                .append(time.format(Date(s.endMs))).append(',')
                .append(s.durationSec / 60).append(',')
                .append(s.secondsByLevel[Level.MILD] / 60).append(',')
                .append(s.secondsByLevel[Level.LOUD] / 60).append(',')
                .append(s.secondsByLevel[Level.HEAVY] / 60).append(',')
                .append(s.score).append(',')
                .append(field(s.tags.joinToString("; ") { Tags.label(it) }))
                .append("\r\n")
        }
        return sb.toString()
    }

    /** Quotes a field when needed, and defuses values a spreadsheet would run as a formula. */
    fun field(v: String): String {
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
