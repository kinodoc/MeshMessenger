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

        // Structured, low-risk diagnostic fields. Identifiers and free-form text are never exported.
        private val uploadKeys = setOf(
            "state", "transport", "result", "reason", "error", "errorCode", "error_type",
            "attempt", "retries", "queueSize", "durationMs", "packetType", "peerCount",
            "connected", "enabled", "httpStatus", "bytes", "stage", "operation",
            "serviceState", "component", "method", "line", "ble_ready", "relay_ready",
            "nodeReady", "nodeHealthy", "relayReady", "messageType", "status", "count",
            "timeoutMs", "mtu", "gattStatus", "profile", "permission", "available",
            "rssi", "batteryPct", "charging", "networkType", "permissionState", "bleState",
            "relayState", "lastSeenAgeMs", "queueAgeMs", "packetBytes", "failureCount",
            "foreground", "dozeMode", "bluetoothState", "gattOperation", "retryDelayMs",
            "threadState", "scanState", "advertisingState", "connectionState", "deliveryState",
            "ackState", "routeState", "transportState", "httpMethod", "tlsStatus", "dnsStatus",
            "storageState", "batteryOptimized", "processState", "elapsedMs", "eventCount"
        )
        private val safeValue = Regex("[A-Za-z][A-Za-z0-9_.$:/+-]{0,79}|[0-9]{1,8}")
        private val privateIdentifier = Regex(
            "(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}|(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9a-f]{8}-[0-9a-f-]{27,}|[0-9]{10,}"
        )
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun event(type: String, detail: String = "") {
        val safeType = type.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80).ifBlank { "EVENT" }
        // Keep each event on one line and prevent callers from injecting extra fields.
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

    /** Preserve event order and approved diagnostic values; discard arbitrary/private values. */
    fun readForUpload(): String = read().lineSequence().mapNotNull { line ->
        val fields = line.split('|', limit = 5)
        if (fields.size < 4) return@mapNotNull null
        val timestamp = fields[0].takeIf {
            it.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"))
        } ?: return@mapNotNull null
        val version = fields[1].removePrefix("v=").takeIf {
            it.matches(Regex("[A-Za-z0-9.+_-]{1,40}"))
        } ?: return@mapNotNull null
        val api = fields[2].removePrefix("api=").toIntOrNull()?.takeIf { it in 1..100 }
            ?: return@mapNotNull null
        val type = fields[3].takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
            ?: return@mapNotNull null
        val approvedDetails = if (fields.size == 5) {
            fields[4].split(Regex("[,; ]+")).mapNotNull { token ->
                val split = token.split('=', limit = 2)
                if (split.size != 2 || split[0] !in uploadKeys) return@mapNotNull null
                val value = split[1]
                if (!safeValue.matches(value) || privateIdentifier.containsMatchIn(value)) return@mapNotNull null
                "${split[0]}=$value"
            }.take(30)
        } else emptyList()
        (listOf(timestamp, "v=$version", "api=$api", type) + approvedDetails).joinToString("|")
    }.joinToString("\n")

    fun crash(thread: Thread, throwable: Throwable) {
        // Exception messages and thread names may contain private data; keep type and safe stack frames.
        val exceptionType = throwable.javaClass.simpleName
            .filter { it.isLetterOrDigit() || it == '_' }
            .take(80)
            .ifBlank { "UnknownException" }
        event("CRASH", "error=$exceptionType")
        throwable.stackTrace.take(40).forEach { frame ->
            val component = frame.className
                .filter { it.isLetterOrDigit() || it in "._$" }
                .take(80)
                .ifBlank { "unknown" }
            val method = frame.methodName
                .filter { it.isLetterOrDigit() || it in "_$" }
                .take(60)
                .ifBlank { "unknown" }
            event("CRASH_FRAME", "component=$component,method=$method,line=${frame.lineNumber.coerceAtLeast(0)}")
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
