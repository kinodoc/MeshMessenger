package com.example.meshmessenger.mesh

import android.content.Context
import android.os.Build
import com.example.meshmessenger.BuildConfig
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates the same diagnostic payload for manual and automatic reports. */
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
        val file = File(dir, "MeshMessenger-bugreport-" + stamp + ".zip")

        val meta = buildString {
            appendLine("MeshMessenger bugreport")
            appendLine("version=" + BuildConfig.VERSION_NAME)
            appendLine("versionCode=" + BuildConfig.VERSION_CODE)
            appendLine("android=" + Build.VERSION.RELEASE)
            appendLine("api=" + Build.VERSION.SDK_INT)
            appendLine("deviceManufacturer=" + Build.MANUFACTURER)
            appendLine("deviceModel=" + Build.MODEL)
            appendLine("abi=" + Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        }

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            put(zip, "report.txt", meta)
            // Bluetooth snapshots may contain nearby device names and addresses.
            put(zip, "mesh_diagnostics.log", diagnostics.readForUpload())
            if (!crashTrace.isNullOrBlank()) {
                put(zip, "crash_trace.txt", sanitizeTrace(crashTrace).take(48_000))
            }
        }
        return file
    }

    private fun sanitizeTrace(trace: String): String = trace.lineSequence().filter { line ->
        line.startsWith("event=") || line.startsWith("exception=") ||
            line.startsWith(" at ") || line.startsWith("cause=")
    }.map { line ->
        line.replace(Regex("(?i)(message|text|payload|token|secret|password|key|address|email|phone)=\\S+"), "$1=[redacted]")
    }.joinToString("\n")

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}
