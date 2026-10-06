package com.example.meshmessenger.mesh

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
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
        crashTrace: String? = null
    ): File {
        val dir = context.getExternalFilesDir("Download") ?: context.cacheDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "MeshMessenger-bugreport-${stamp}.zip")

        val meta = buildString {
            appendLine("MeshMessenger diagnostic report")
            appendLine("version=${com.example.meshmessenger.BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${com.example.meshmessenger.BuildConfig.VERSION_CODE}")
            appendLine("android=${Build.VERSION.RELEASE}")
            appendLine("api=${Build.VERSION.SDK_INT}")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("diagnostic_schema=4")
            appendLine("created=${Date()}")
        }

        // Do not archive raw Bluetooth snapshots: some Android implementations include
        // nearby device names or addresses. The event log carries BT state and error codes.
        val bluetoothSummary = "Raw Bluetooth snapshot omitted for privacy. See mesh_diagnostics.log for the extended BT lifecycle trace.\n"
        val safeTrace = crashTrace.orEmpty().lineSequence()
            .filter { it.startsWith("event=") || it.startsWith("exception=") || it.startsWith(" at ") || it.startsWith("cause=") }
            .map { line ->
                line.replace(Regex("(?i)(thread=)[^ ]+"), "$1[redacted]")
                    .replace(Regex("(?i)(message|text|name|address|token|key)=([^ ]+)"), "$1=[redacted]")
                    .take(500)
            }
            .take(140)
            .joinToString("\n")

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            put(zip, "report.txt", meta)
            put(zip, "runtime_state.txt", runtimeState(context))
            put(zip, "bluetooth.txt", bluetoothSummary)
            put(zip, "mesh_diagnostics.log", diagnostics.readForUpload())
            if (safeTrace.isNotBlank()) put(zip, "crash_trace.txt", safeTrace.take(48_000))
        }
        return file
    }

    /**
     * Coarse operating state only. Never include addresses, nearby names, SSIDs,
     * IP addresses, account identifiers, message contents, contacts, or keys.
     * Included identically in automatic and manual report archives.
     */
    private fun runtimeState(context: Context): String = buildString {
        appendLine("runtime_diagnostics_schema=1")
        appendLine("bluetooth_permission=" + permission(context, Manifest.permission.BLUETOOTH_CONNECT))
        appendLine("bluetooth_scan_permission=" + permission(context, Manifest.permission.BLUETOOTH_SCAN)))
        appendLine("location_permission=" + permission(context, Manifest.permission.ACCESS_FINE_LOCATION))
        appendLine("bluetooth_enabled=" + runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.isEnabled?.toString() ?: "unknown"
        }.getOrDefault("unknown"))
        appendLine("active_network_transport=" + runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = manager?.activeNetwork
            val caps = if (active != null) manager.getNetworkCapabilities(active) else null
            when {
                caps == null -> "none_or_unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        }.getOrDefault("unknown"))
    }

    private fun permission(context: Context, name: String): String =
        if (Build.VERSION.SDK_INT < 31 && (name == Manifest.permission.BLUETOOTH_CONNECT ||
                name == Manifest.permission.BLUETOOTH_SCAN)) {
            "not_required_on_this_android"
        } else if (ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "not_granted"
        }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}