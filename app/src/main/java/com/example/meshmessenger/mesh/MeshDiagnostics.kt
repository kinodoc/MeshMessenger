package com.example.meshmessenger.mesh

import android.content.Context
import android.os.Build
import com.example.meshmessenger.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Bounded technical event log. Callers must log state/counters/error codes, never chat data. */
class MeshDiagnostics(context: Context) {
    companion object {
        private const val FILE_NAME = "mesh_diagnostics.log"
        private const val MAX_LINES = 800
        private const val MAX_FILE_BYTES = 128 * 1024L
        // One process-wide session id is shared by the foreground service and the activity.\n        // Previously this was an instance field, so MeshBugReport created a second\n        // MeshDiagnostics instance with a different sid and filtered out every event.\n            private val macAddress = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
        private val uuid = Regex("(?i)\\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\b")
        private val ipv4 = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
        private val longToken = Regex("(?i)\\b[0-9a-f]{24,}\\b")
        private val sensitiveValue = Regex("(?i)\\b(name|contact|peer_id|node_id|source_id|destination_id|src|dst|key|token|payload|message|text|address|ssid)=([^ |,;]+)")
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val sessionId = UUID.randomUUID().toString().replace("-", "").take(8)

    fun event(type: String, detail: String = "") {
        val safeType = type.replace(Regex("[\\r\\n|]"), " ").take(80)
        val safeDetail = sanitize(detail).take(500)
        val line = buildString {
            append(formatter.format(Date()))
            append("|v=").append(BuildConfig.VERSION_NAME)
            append("|api=").append(Build.VERSION.SDK_INT)
            append("|sid=").append(sessionId)
            append("|").append(safeType)
            if (safeDetail.isNotBlank()) append("|").append(safeDetail)
        }
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n", Charsets.UTF_8)
                trimIfNeeded()
            }
        }
    }

    fun crash(thread: Thread, throwable: Throwable) {
        event("CRASH", "thread_category=${if (thread.name == "main") "main" else "worker"} error_type=${throwable.javaClass.simpleName}")
        event("CRASH_STACK", throwable.stackTrace.take(80).joinToString(" <- ") { it.className + "." + it.methodName + ":" + it.lineNumber })
    }

    /**
     * The same bounded, privacy-filtered event stream is used by manual and automatic reports.
     * Preserve diagnostic details (error codes, counters, state transitions), but sanitize
     * identifiers and sensitive key/value fields before anything leaves the device.
     */
    fun readForUpload(): String = synchronized(lock) {
        runCatching {
            file.readLines(Charsets.UTF_8)
                .filter { it.contains("|sid=$sessionId|") }
                .takeLast(MAX_LINES)
                .joinToString("\n") { line ->
                val fields = line.split('|', limit = 6)
                if (fields.size < 4) {
                    sanitize(line)
                } else {
                    val header = fields.take(5).joinToString("|")
                    val detail = fields.getOrNull(5)?.let { sanitize(it) }.orEmpty()
                    if (detail.isBlank()) header else "$header|$detail"
                }
            }
        }.getOrDefault("")
    }

    private fun sanitize(value: String): String {
        var result = value.replace(Regex("[\\r\\n|]"), " ").take(2000)
        result = sensitiveValue.replace(result) { "${it.groupValues[1]}=[redacted]" }
        result = macAddress.replace(result, "[address]")
        result = uuid.replace(result, "[id]")
        result = ipv4.replace(result, "[ip]")
        result = longToken.replace(result, "[token]")
        return result
    }

    fun read(): String = readForUpload()

    private fun trimIfNeeded() {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        val lines = file.readLines(Charsets.UTF_8)
        val start = (lines.size - MAX_LINES).coerceAtLeast(0)
        file.writeText(lines.subList(start, lines.size).joinToString("\n") + "\n", Charsets.UTF_8)
    }
}
