package com.example.meshmessenger.mesh

import android.content.Context
import android.os.Build
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a privacy-preserving diagnostic archive.
 *
 * Manual and automatic reports intentionally have the same contents so a
 * crash report is no less useful than a report sent by the user. The device
 * snapshot argument remains for source compatibility but is never exported.
 * Diagnostic event details are filtered by MeshDiagnostics.readForUpload().
 */
object MeshBugReport {
    fun create(
        context: Context,
        diagnostics: MeshDiagnostics,
        bluetoothSnapshot: String,
        crashTrace: String? = null,
        automatic: Boolean = false
    ): File {
        val dir = context.getExternalFilesDir("Download") ?: context.cacheDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "MeshMessenger-bugreport-${stamp}.zip")

        val meta = buildString {
            appendLine("MeshMessenger bugreport")
            appendLine("version=${com.example.meshmessenger.BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${com.example.meshmessenger.BuildConfig.VERSION_CODE}")
            appendLine("android=${Build.VERSION.RELEASE}")
            appendLine("api=${Build.VERSION.SDK_INT}")
        }

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            put(zip, "report.txt", meta)
            // Use the same sanitized diagnostic stream for manual and automatic reports.
            put(zip, "mesh_diagnostics.log", diagnostics.readForUpload())
            if (!crashTrace.isNullOrBlank()) {
                // Callers must provide a sanitized trace: exception messages and local data
                // must never be persisted in crashTrace.
                put(zip, "crash_trace.txt", crashTrace.take(48_000))
            }
        }
        return file
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}
