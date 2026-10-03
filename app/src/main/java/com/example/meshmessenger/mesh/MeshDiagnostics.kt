package com.example.meshmessenger.mesh

import android.content.Context
import android.os.Build
import com.example.meshmessenger.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local technical diagnostics. Do not pass message text, contact data, keys, device IDs,
 * network addresses, or arbitrary exception messages to event().
 */
class MeshDiagnostics(context: Context) {
    companion object {
        private const val FILE_NAME = "mesh_diagnostics.log"
        private const val MAX_LINES = 800
        private const val MAX_FILE_BYTES = 128 * 1024L
        private val safeDetailKeys = setOf(
            "state", "status", "result", "code", "bytes", "count", "size",
            "duration", "elapsed", "attempt", "retry", "ttl", "fragments",
            "queued", "delivered", "connected", "enabled", "reason", "error",
            "exception", "phase", "transport", "operation", "profile", "source"
        )
        private val safeValue = Regex("^[A-Za-z0-9_.:-]{1,64}$")
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
     * Shared privacy filter for manual and automatic reports.
     * Keeps event timing/category and allowlisted technical key-value fields only.
     */
    fun readForUpload(): String = read().lineSequence().mapNotNull lineFilter@{ line ->
        val fields = line.split('|', limit = 5)
        if (fields.size < 4) return@lineFilter null
        val details = fields.getOrNull(4).orEmpty().split(Regex("[,; ]+"))
            .mapNotNull tokenFilter@{ token ->
                val separator = token.indexOf('=')
                if (separator <= 0) return@tokenFilter null
                val key = token.substring(0, separator).lowercase(Locale.US)
                val value = token.substring(separator + 1)
                if (key in safeDetailKeys && safeValue.matches(value)) "$key=$value" else null
            }.take(12)
        (fields.take(4) + if (details.isNotEmpty()) listOf(details.joinToString(",")) else emptyList())
            .joinToString("|")
    }.takeLast(MAX_LINES).joinToString("\n")

    fun crash(thread: Thread, throwable: Throwable) {
        // Thread names and exception messages can contain user-controlled data.
        event("CRASH", "exception=" + throwable.javaClass.simpleName)
        event("CRASH_CAUSE", "count=" + throwable.stackTrace.size)
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
