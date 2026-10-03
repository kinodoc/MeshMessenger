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

/** Creates a diagnostic archive. Automatic reports use a minimized, redacted payload. */
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
            // Hardware model helps reproduce vendor-specific BLE and background-service failures.
            // Do not include serial numbers, Android ID, MAC addresses, or other device identifiers.
            appendLine("deviceManufacturer=${Build.MANUFACTURER.take(80)}")
            appendLine("deviceModel=${Build.MODEL.take(100)}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.take(4).joinToString(",").take(160)}")
        }

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            put(zip, "report.txt", meta)
            // Manual and automatic reports share the same privacy-filtered diagnostics.
            // Raw Bluetooth snapshots can contain identifiers and are intentionally excluded.
            put(zip, "mesh_diagnostics.log", diagnostics.readForUpload())
            if (!crashTrace.isNullOrBlank()) {
                // Automatic traces are already stored without exception messages.
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
