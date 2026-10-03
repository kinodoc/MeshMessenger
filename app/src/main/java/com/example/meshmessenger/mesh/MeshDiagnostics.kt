package com.example.meshmessenger.mesh

import android.content.Context
import android.os.Build
import com.example.meshmessenger.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local technical diagnostics shared by manual and automatic reports.
 * Callers must never pass chat text, contact names, keys, addresses or device identifiers.
 * Upload applies a second allowlist so accidental free-form values are not exported.
 */
class MeshDiagnostics(context: Context) {
    companion object {
        private const val FILE_NAME = "mesh_diagnostics.log"
        private const val MAX_LINES = 800
        private const val MAX_FILE_BYTES = 128 * 1024L

        // Only low-risk structured fields are exported. Values are restricted to simple
        // diagnostic tokens; arbitrary strings are deliberately discarded.
        private val uploadKeys = setOf(
            "state", "from", "to", "transport", "result", "reason", "error",
            "errorCode", "attempt", "retries", "queueSize", "durationMs",
            "packetType", "route", "peerCount", "connected", "enabled",
            "httpStatus", "bytes", "stage", "operation", "serviceState"
        )
        private val safeValue = Regex("[A-Za-z0-9_.:/+-]{1,80}")
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun event(type: String, detail: String = "") {
        val safeType = type.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        val safeDetail = detail.replace(Regex("[\\r\\n|]"), " ").take(500)
        val line = buildString {
            append(formatter.format(Date()))
            append("|v=").append(BuildConfig.VERSION_NAME)
            append("|api=").append(Build.VERSION.SDK_INT)
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

    /**
     * Export event order/timestamps and approved structured diagnostic fields only.
     * Drops arbitrary detail text that could contain personal data or message content.
     */
    fun readForUpload(): String = read().lineSequence().mapNotNull { line ->
        val fields = line.split('|', limit = 5)
        if (fields.size < 4) return@mapNotNull null
        val timestamp = fields[0].takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}")) }
            ?: return@mapNotNull null
        val version = fields[1].removePrefix("v=").takeIf { it.matches(Regex("[A-Za-z0-9.+_-]{1,40}")) }
            ?: return@mapNotNull null
        val api = fields[2].removePrefix("api=").toIntOrNull()?.takeIf { it in 1..100 }
            ?: return@mapNotNull null
        val type = fields[3].takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
            ?: return@mapNotNull null
        val approvedDetails = if (fields.size == 5) {
            fields[4].split(Regex("[,; ]+")).mapNotNull { token ->
                val split = token.split('=', limit = 2)
                if (split.size != 2 || split[0] !in uploadKeys) return@mapNotNull null
                val value = split[1]
                if (!safeValue.matches(value)) return@mapNotNull null
                "${split[0]}=$value"
            }.take(20)
        } else emptyList()
        (listOf(timestamp, "v=$version", "api=$api", type) + approvedDetails).joinToString("|")
    }.joinToString("\n")

    fun crash(thread: Thread, throwable: Throwable) {
        // Thread names and exception messages can contain arbitrary data; do not persist them.
        event("CRASH", "error=${throwable.javaClass.simpleName}")
        event("CRASH_STACK", throwable.stackTrace.take(40).joinToString(",") { it.toString() })
    }

    fun read(): String {
        synchronized(lock) {
            return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        }
    }

    private fun trimIfNeeded() {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        val lines = file.readLines(Charsets.UTF_8)
        val kept = lines.takeLast(MAX_LINES)
        file.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
    }
}
