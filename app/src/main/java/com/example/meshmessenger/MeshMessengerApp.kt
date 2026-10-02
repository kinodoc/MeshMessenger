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
 * app launch. Uploads contain diagnostics only; chat stores and identity keys are not read.
 */
class MeshMessengerApp : Application() {
    private val crashFile by lazy { File(noBackupFilesDir, "pending-crash.txt") }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile.writeText(buildString {
                    appendLine("thread=${thread.name.take(80)}")
                    appendLine("exception=${error.javaClass.name}")
                    appendLine("message=${error.message.orEmpty().take(500)}")
                    error.stackTrace.take(80).forEach { appendLine(" at $it") }
                    error.cause?.let { appendLine("cause=${it.javaClass.name}: ${it.message.orEmpty().take(300)}") }
                })
            }.onFailure { Log.e("MeshMessenger", "Could not persist crash report", it) }
            previous?.uncaughtException(thread, error)
        }
        if (crashFile.isFile && crashFile.length() in 1..48_000) {
            Thread({ uploadPendingCrash() }, "mesh-crash-report").apply { isDaemon = true; start() }
        }
    }

    private fun uploadPendingCrash() {
        val trace = runCatching { crashFile.readText().take(48_000) }.getOrNull() ?: return
        val report = runCatching {
            MeshBugReport.create(this, MeshDiagnostics(this), "automatic crash report", trace)
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
            Log.w("MeshMessenger", "Automatic crash report deferred until next launch", e)
            report.delete()
        }
    }

    companion object {
        private const val REPORT_URL = "https://194.87.186.159/api/bugreports"
    }
}
