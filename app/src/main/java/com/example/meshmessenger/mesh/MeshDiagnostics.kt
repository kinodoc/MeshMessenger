package com.example.meshmessenger.mesh

import android.content.Context
import android.os.Build
import com.example.meshmessenger.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local technical diagnostics. Do not pass message bodies, contact identifiers, keys,
 * network addresses, device identifiers, or arbitrary exception messages to event().
 */
class MeshDiagnostics(context: Context) {
    companion object {
        private const val FILE_NAME = "mesh_diagnostics.log"
        private const val MAX_LINES = 800
        private const val MAX_FILE_BYTES = 128 * 1024L

        // Only structured, low-risk operational fields are eligible for upload.
        private val uploadKeys = setOf(
            "stage", "state", "status", "transport", "result", "code", "error_code",
            "reason_code", "attempt", "retry", "count", "packets", "bytes", "queue",
            "queue_size", "duration_ms", "elapsed_ms", "rssi", "mtu", "api", "version"
        )
        private val sensitiveKey = Regex(
            "(?i)(message|body|text|chat|contact|peer.?id|node.?id|device.?id|address|ip|mac|uuid|key|token|secret|password|phone|email|name|path|uri|url|exception|stack|cause)"
        )
        private val safeValue = Regex("[A-Za-z0-9_.:-]{1,64}")
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun event(type: String, detail: String = "") {
        val safeType = type.replace(Regex("[\\r\\n|]"), " ").take(80)
        // Keep local details for troubleshooting, but upload only allowlisted structured fields.
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
     * Retain useful structured operational values while dropping arbitrary/free-form
     * details. Unknown fields and any field that may contain personal or message data
     * are omitted rather than attempting to redact their contents.
     */
    fun readForUpload(): String = read().lineSequence().mapNotNull { line ->
        val fields = line.split('|', limit = 5)
        if (fields.size < 4) return@mapNotNull null
        val safeDetails = fields.drop(4).flatMap { segment ->
            segment.split(Regex("[,; ]+")).mapNotNull { item ->
                val idx = item.indexOf('=')
                if (idx <= 0 || idx == item.lastIndex) return@mapNotNull null
                val key = item.substring(0, idx).lowercase(Locale.US)
                val value = item.substring(idx + 1)
                if (key !in uploadKeys || sensitiveKey.containsMatchIn(key) ||
                    !safeValue.matches(value)) return@mapNotNull null
                "$key=$value"
            }
        }
        (fields.take(4) + safeDetails).joinToString("|")
    }.joinToString("\n")

    fun crash(thread: Thread, throwable: Throwable) {
        // Thread names and exception messages can contain user-controlled data.
        event("CRASH", "error_type=" + throwable.javaClass.simpleName)
        event("CRASH_FRAMES", throwable.stackTrace.take(40).joinToString(" <- ") { it.toString() })
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
