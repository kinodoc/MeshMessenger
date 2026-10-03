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

/** Creates the same privacy-filtered diagnostic archive for manual and automatic reports. */
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

        // Only technical environment information; no serial number, account, contacts,
        // chat contents, network addresses, keys, or raw Bluetooth device snapshot.
        val meta = buildString {
            appendLine("MeshMessenger bugreport")
            appendLine("version=${com.example.meshmessenger.BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${com.example.meshmessenger.BuildConfig.VERSION_CODE}")
            appendLine("android=${Build.VERSION.RELEASE}")
            appendLine("api=${Build.VERSION.SDK_INT}")
            appendLine("manufacturer=${safeMeta(Build.MANUFACTURER)}")
            appendLine("model=${safeMeta(Build.MODEL)}")
        }

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            put(zip, "report.txt", meta)
            // Manual and automatic reports share the same filtered diagnostic stream.
            put(zip, "mesh_diagnostics.log", diagnostics.readForUpload())
            if (!crashTrace.isNullOrBlank()) {
                put(zip, "crash_trace.txt", safeCrashTrace(crashTrace))
            }
        }
        return file
    }

    private fun safeMeta(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_. -]"), "_").take(80)

    /** Accept only exception class names and stack-frame symbols, never message text. */
    private fun safeCrashTrace(trace: String): String = trace.lineSequence()
        .mapNotNull { line ->
            when {
                line.startsWith("exception=") || line.startsWith("cause=") -> {
                    val name = line.substringAfter('=').trim()
                    if (name.matches(Regex("[A-Za-z0-9_.$]{1,160}"))) line else null
                }
                line.trimStart().startsWith("at ") -> {
                    val frame = line.trim().removePrefix("at ").substringBefore('(')
                    if (frame.matches(Regex("[A-Za-z0-9_.$]+"))) " at $frame" else null
                }
                else -> null
            }
        }.take(120).joinToString("\n")

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}
