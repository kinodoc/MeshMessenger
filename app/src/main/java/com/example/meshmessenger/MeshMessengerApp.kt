package com.example.meshmessenger

import android.app.Application
import android.util.Log
import com.example.meshmessenger.mesh.MeshBugReport
import com.example.meshmessenger.mesh.MeshDiagnostics
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Crash reports are persisted locally by the uncaught handler and uploaded on the next
 * app launch. Reports include the shared privacy-filtered diagnostics, not chat data.
 */
class MeshMessengerApp : Application() {
    private val crashFile by lazy { File(noBackupFilesDir, "pending-crash.txt") }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordCriticalFailure("uncaught", error)
            previous?.uncaughtException(thread, error)
        }
        if (crashFile.isFile && crashFile.length() in 1..48_000) {
            Thread({ uploadPendingCrash() }, "mesh-crash-report").apply { isDaemon = true; start() }
        }
    }

    /** Save critical startup/mesh failures for automatic upload on the next launch. */
    fun recordCriticalFailure(label: String, error: Throwable) {
        runCatching {
            crashFile.writeText(buildString {
                appendLine("event=" + label.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80))
                appendLine("exception=${error.javaClass.name}")
                error.stackTrace.take(80).forEach { appendLine(" at $it") }
                error.cause?.let { cause ->
                    appendLine("cause=${cause.javaClass.name}")
                    cause.stackTrace.take(40).forEach { appendLine(" at $it") }
                }
            }.take(48_000))
        }.onFailure { Log.e("MeshMessenger", "Could not persist crash report") }
    }

    private fun uploadPendingCrash() {
        val trace = runCatching { crashFile.readText().take(48_000) }.getOrNull() ?: return
        val report = runCatching {
            MeshBugReport.create(this, MeshDiagnostics(this), "", trace, automatic = true)
        }.getOrNull() ?: return
        try {
            val connection = (URL(REPORT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/zip")
                setRequestProperty("X-Mesh-Version", BuildConfig.VERSION_NAME)
                setFixedLengthStreamingMode(report.length().toInt())
            }
            try {
                report.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
                if (connection.responseCode in 200..299) {
                    crashFile.delete()
                    report.delete()
                } else {
                    Log.w("MeshMessenger", "Automatic crash report deferred: HTTP ${connection.responseCode}")
                    report.delete()
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w("MeshMessenger", "Automatic crash report deferred until next launch")
            report.delete()
        }
    }

    companion object {
        private const val REPORT_URL = "https://194.87.186.159/api/bugreports"
    }
}
