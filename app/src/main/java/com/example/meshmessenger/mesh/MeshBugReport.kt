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
            // The trace is independently allowlisted; never archive arbitrary caller input.
            if (!crashTrace.isNullOrBlank()) {
                val safeTrace = sanitizeCrashTrace(crashTrace)
                if (safeTrace.isNotBlank()) put(zip, "crash_trace.txt", safeTrace)
            }
        }
        return file
    }

    private fun sanitizeCrashTrace(raw: String): String = raw.lineSequence().mapNotNull { line ->
        when {
            line.matches(Regex("^event=[A-Za-z0-9_.-]{1,80}$")) -> line
            line.matches(Regex("^(exception|cause)=[A-Za-z0-9_.$]{1,180}$")) -> line
            line.matches(Regex("^ at [A-Za-z0-9_.$]+\\.[A-Za-z0-9_$<>]+\\([^)]{0,180}\\)$")) -> line
            else -> null
        }
    }.take(120).joinToString("\n")

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}
