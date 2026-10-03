package com.example.meshmessenger.mesh

import android.content.Context
import android.os.Build
import com.example.meshmessenger.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local technical diagnostics. Event details must be machine-generated enums,
 * durations, counts, or error codes. Never pass message text, contact data, keys,
 * tokens, peer identifiers, or raw packets.
 */
class MeshDiagnostics(context: Context) {
    companion object {
        private const val FILE_NAME = "mesh_diagnostics.log"
        private const val MAX_LINES = 1200
        private const val MAX_FILE_BYTES = 192 * 1024L
        private val SAFE_DETAIL_KEYS = setOf(
            "stage", "state", "result", "reason", "error", "code", "status",
            "transport", "operation", "attempt", "retry", "count", "queue",
            "duration_ms", "elapsed_ms", "api", "version", "connected",
            "enabled", "permission", "mtu", "bytes", "service", "exception",
            "component", "event", "phase", "direction", "packet_type",
            "route_state", "hop_count", "peer_count", "neighbor_count",
            "queue_depth", "dropped_count", "timeout_ms", "latency_ms",
            "http_status", "failure_kind", "scan_state", "advertise_state",
            "connection_state", "delivery_state", "ack_state", "retry_count",
            "foreground", "battery_optimization", "network_type", "validated"
        )
        private val SAFE_VALUE = Regex("^[A-Za-z0-9_.:/-]{1,80}$")
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun event(type: String, detail: String = "") {
        val safeType = type.filter { it.isLetterOrDigit() || it in "._-" }.take(80).ifBlank { "EVENT" }
        val safeDetail = sanitizeDetails(detail)
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

    /** Shared, redacted event history used by both manual and automatic reports. */
    fun readForUpload(): String = read().lineSequence().mapNotNull { line ->
        val fields = line.split('|', limit = 5)
        if (fields.size < 4) return@mapNotNull null
        val timestamp = fields[0].takeIf {
            it.matches(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}$"))
        } ?: return@mapNotNull null
        val version = fields[1].takeIf { it.matches(Regex("^v=[A-Za-z0-9._+-]{1,40}$")) }
            ?: return@mapNotNull null
        val api = fields[2].takeIf { it.matches(Regex("^api=\\d{1,3}$")) }
            ?: return@mapNotNull null
        val type = fields[3].filter { it.isLetterOrDigit() || it in "._-" }.take(80).ifBlank { "EVENT" }
        val details = if (fields.size == 5) sanitizeDetails(fields[4]) else ""
        listOf(timestamp, version, api, type, details)
            .filterIndexed { index, value -> index < 4 || value.isNotBlank() }
            .joinToString("|")
    }.takeLast(MAX_LINES).joinToString("\n")

    private fun sanitizeDetails(raw: String): String =
        raw.split('|', ',', ';', ' ')
            .mapNotNull { token ->
                val split = token.split('=', limit = 2)
                if (split.size != 2) return@mapNotNull null
                val key = split[0].lowercase(Locale.US)
                val value = split[1]
                if (key !in SAFE_DETAIL_KEYS || !SAFE_VALUE.matches(value)) return@mapNotNull null
                "$key=$value"
            }
            .distinct()
            .take(30)
            .joinToString(",")
            .take(900)

    fun crash(thread: Thread, throwable: Throwable) {
        // Thread names and exception messages can contain arbitrary application/user data.
        event("CRASH", "exception=" + throwable.javaClass.simpleName.filter { it.isLetterOrDigit() || it == '_' }.take(80))
        // Store stack frames as allowlisted structured events; omit file paths and messages.
        (throwable.stackTrace.asSequence().take(40) +
            (throwable.cause?.stackTrace?.asSequence()?.take(20) ?: emptySequence()))
            .forEach { frame ->
                val component = frame.className.filter { c -> c.isLetterOrDigit() || c in "._" }
                    .take(80).ifBlank { "unknown" }
                val operation = frame.methodName.filter { c -> c.isLetterOrDigit() || c in "_." }
                    .take(60).ifBlank { "unknown" }
                event("CRASH_FRAME", "component=$component operation=$operation code=\${frame.lineNumber.coerceAtLeast(0)}")
            }
    }

    fun read(): String = synchronized(lock) {
        runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
    }

    private fun trimIfNeeded() {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        val kept = file.readLines(Charsets.UTF_8).takeLast(MAX_LINES)
        file.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
    }
}
