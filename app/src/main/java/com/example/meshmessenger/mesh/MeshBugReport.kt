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
 * Creates the same privacy-filtered diagnostic archive for manual and automatic reports.
 * The only optional entry is a locally recorded crash trace when one is available.
 */
object MeshBugReport {
    fun create(
        context: Context,
        diagnostics: MeshDiagnostics,
        bluetoothSnapshot: String = "",
        crashTrace: String? = null,
        automatic: Boolean = false
    ): File {
        val dir = context.getExternalFilesDir("Download") ?: context.cacheDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "MeshMessenger-bugreport-${stamp}.zip")

        // Keep diagnostic metadata consistent across manual and automatic submissions.
        // Exclude manufacturer/model, serials, Android ID, MAC/IP addresses, names and secrets.
        val meta = buildString {
            appendLine("MeshMessenger bugreport")
            appendLine("version=${com.example.meshmessenger.BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${com.example.meshmessenger.BuildConfig.VERSION_CODE}")
            appendLine("android=${Build.VERSION.RELEASE}")
            appendLine("api=${Build.VERSION.SDK_INT}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.take(4).joinToString(",").take(160)}")
        }

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            put(zip, "report.txt", meta)
            put(zip, "mesh_diagnostics.log", diagnostics.readForUpload())
            // A crash trace is included only if present. The app stores exception class and
            // stack frames, never exception messages, chat content, contacts, or keys.
            if (!crashTrace.isNullOrBlank()) {
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
