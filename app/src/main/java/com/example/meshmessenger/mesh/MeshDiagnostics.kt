package com.example.meshmessenger.mesh

import android.content.Context
import android.os.Build
import com.example.meshmessenger.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local technical diagnostics. Callers must pass structured, non-sensitive event details only.
 * Never pass chat text, keys, contact names, device addresses or message payloads.
 */
class MeshDiagnostics(context: Context) {
    companion object {
        private const val FILE_NAME = "mesh_diagnostics.log"
        private const val MAX_LINES = 800
        private const val MAX_FILE_BYTES = 128 * 1024L
        private val safeDetailKeys = setOf(
            "state", "result", "code", "duration_ms", "count", "queue_size",
            "transport", "operation", "error_type", "retry", "status", "reason",
            "bytes", "attempt", "stage", "enabled"
        )
        private val safeValue = Regex("^[A-Za-z0-9_.:-]{1,48}$")
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun event(type: String, detail: String = "") {
        val safeType = type.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        // Persist only structured key=value diagnostics. Never log arbitrary exception messages,
        // user-provided strings, chat content, keys, names, or addresses.
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

    /** Redact arbitrary details while retaining allowlisted, bounded technical fields. */
    fun readForUpload(): String = read().lineSequence().mapNotNull { line ->
        val fields = line.split('|', limit = 5)
        if (fields.size < 4) return@mapNotNull null
        val type = fields[3].replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        val detail = if (fields.size >= 5) sanitizeDetails(fields[4]) else ""
        buildString {
            append(fields[0].take(23))
            append("|").append(fields[1].take(32))
            append("|").append(fields[2].take(16))
            append("|").append(type)
            if (detail.isNotBlank()) append("|").append(detail)
        }
    }.joinToString("\n")

    fun crash(thread: Thread, throwable: Throwable) {
        // Thread names and exception messages can contain private data; keep only exception type.
        event("CRASH", "error_type=${throwable.javaClass.simpleName}")
        event("CRASH_STACK", throwable.stackTrace.take(40).joinToString(",") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        })
    }

    fun read(): String {
        synchronized(lock) {
            return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        }
    }

    private fun sanitizeDetails(detail: String): String = detail
        .split(Regex("[\\s,;|]+"))
        .mapNotNull { token ->
            val split = token.split('=', limit = 2)
            if (split.size != 2) return@mapNotNull null
            val key = split[0].lowercase(Locale.US)
            val value = split[1]
            if (key !in safeDetailKeys || !safeValue.matches(value)) return@mapNotNull null
            "$key=$value"
        }
        .take(12)
        .joinToString(",")

    private fun trimIfNeeded() {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        val lines = file.readLines(Charsets.UTF_8)
        val kept = lines.takeLast(MAX_LINES)
        file.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
    }
}
