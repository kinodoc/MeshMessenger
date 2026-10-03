package com.example.meshmessenger.mesh

import android.content.Context
import android.os.Build
import com.example.meshmessenger.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local technical diagnostics. Never intentionally records chat text, contact names,
 * cryptographic material, or network credentials. Export is sanitized for both report modes.
 */
class MeshDiagnostics(context: Context) {
    companion object {
        private const val FILE_NAME = "mesh_diagnostics.log"
        private const val MAX_LINES = 800
        private const val MAX_FILE_BYTES = 128 * 1024L
        private val sensitiveField = Regex(
            "(?i)(\\b(?:chat(?:Text)?|message(?:Text)?|text|payload|content|contact(?:Name|Id)?|" +
                "peerName|displayName|name|privateKey|publicKey|key|token|secret|password|authorization|" +
                "email|phone|address|ip|mac|bluetoothAddress)\\s*[=:]\\s*)[^,;|\\s]+"
        )
        private val sensitiveAssignment = Regex("(?i)\\b(?:bearer\\s+)[A-Za-z0-9._~+/-]+=*")
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun event(type: String, detail: String = "") {
        val safeType = type.replace(Regex("[\\r\\n|]"), " ").take(80)
        val safeDetail = redact(detail).replace(Regex("[\\r\\n|]"), " ").take(500)
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

    /** Both manual and automatic reports use the same sanitized diagnostic history. */
    fun readForUpload(): String = read().lineSequence().mapNotNull { line ->
        val fields = line.split('|', limit = 5)
        if (fields.size >= 4) redact(fields.take(4).joinToString("|")) else null
    }.joinToString("\n")

    fun crash(thread: Thread, throwable: Throwable) {
        event("CRASH", "thread=${thread.name} error=${throwable.javaClass.simpleName}")
        event("CRASH_STACK", throwable.stackTrace.take(40).joinToString(" <- ") { it.toString() })
    }

    fun read(): String {
        synchronized(lock) {
            return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        }
    }

    private fun redact(value: String): String {
        val masked = sensitiveField.replace(value) { match -> match.groupValues[1] + "[redacted]" }
        return sensitiveAssignment.replace(masked, "Bearer [redacted]")
            .replace(Regex("(?i)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----", RegexOption.DOT_MATCHES_ALL), "[private-key-redacted]")
    }

    private fun trimIfNeeded() {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        val lines = file.readLines(Charsets.UTF_8)
        val kept = lines.takeLast(MAX_LINES)
        file.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
    }
}
