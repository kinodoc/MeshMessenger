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
 * Creates the same privacy-preserving diagnostic archive for manual and automatic reports.
 * Report delivery differs; diagnostic contents do not.
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

        // Keep metadata useful for debugging but avoid hardware identifiers and wall-clock
        // creation time. Event timestamps in the diagnostic log provide the timeline.
        val meta = buildString {
            appendLine("MeshMessenger bugreport")
            appendLine("version=${com.example.meshmessenger.BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${com.example.meshmessenger.BuildConfig.VERSION_CODE}")
            appendLine("android=${Build.VERSION.RELEASE}")
            appendLine("api=${Build.VERSION.SDK_INT}")
        }

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            put(zip, "report.txt", meta)
            // Bluetooth snapshots may contain device names/addresses. Use structured,
            // allowlisted BLE events from diagnostics instead of raw snapshots.
            put(zip, "mesh_diagnostics.log", diagnostics.readForUpload())
            if (!crashTrace.isNullOrBlank()) {
                // Callers must pass a sanitized trace: exception type and stack frames only.
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
