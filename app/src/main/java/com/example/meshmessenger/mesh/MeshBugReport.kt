package com.example.meshmessenger.mesh

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
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

        // This metadata is deliberately shared by manual and automatic reports.
        // Never include chat/contact stores, identities, raw BLE scan results or message payloads.
        val meta = buildString {
            appendLine("MeshMessenger diagnostic report")
            appendLine("diagnostic_schema=3")
            appendLine("version=${com.example.meshmessenger.BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${com.example.meshmessenger.BuildConfig.VERSION_CODE}")
            appendLine("android=${safeMeta(Build.VERSION.RELEASE, 40)}")
            appendLine("api=${Build.VERSION.SDK_INT}")
            appendLine("manufacturer=${safeMeta(Build.MANUFACTURER, 80)}")
            appendLine("model=${safeMeta(Build.MODEL, 100)}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.take(4).joinToString(",").let { safeMeta(it, 160) }}")
            appendLine("uptimeMs=${SystemClock.elapsedRealtime()}")
            appendLine("bluetoothEnabled=${bluetoothState(context)}")
            appendLine("networkTransport=${networkTransport(context)}")
            appendLine("networkValidated=${networkValidated(context)}")
            appendLine("batteryOptimizationIgnored=${batteryOptimizationIgnored(context)}")
            appendLine("notificationPermission=${permissionState(context, Manifest.permission.POST_NOTIFICATIONS, 33)}")
            appendLine("bluetoothScanPermission=${permissionState(context, Manifest.permission.BLUETOOTH_SCAN, 31)}")
            appendLine("bluetoothConnectPermission=${permissionState(context, Manifest.permission.BLUETOOTH_CONNECT, 31)}")
            appendLine("bluetoothAdvertisePermission=${permissionState(context, Manifest.permission.BLUETOOTH_ADVERTISE, 31)}")
            appendLine("locationPermission=${permissionState(context, Manifest.permission.ACCESS_FINE_LOCATION, 1)}")
            appendLine("diagnosticLogBytes=${diagnostics.readForUpload().toByteArray(StandardCharsets.UTF_8).size}")
        }

        // Raw Bluetooth snapshots can contain nearby device names or addresses; omit them.
        val bluetoothSummary = "Raw Bluetooth snapshot omitted for privacy. See mesh_diagnostics.log for BLE state events.\n"
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
            put(zip, "bluetooth.txt", bluetoothSummary)
            put(zip, "mesh_diagnostics.log", diagnostics.readForUpload())
            if (safeTrace.isNotBlank()) put(zip, "crash_trace.txt", safeTrace.take(48_000))
        }
        return file
    }

    private fun bluetoothState(context: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return "permission_missing"
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter?.isEnabled?.toString() ?: "unavailable"
    }.getOrDefault("unavailable")

    private fun networkTransport(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> "disconnected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    }.getOrDefault("unavailable")

    private fun networkValidated(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            ?.toString() ?: "false"
    }.getOrDefault("unavailable")

    private fun batteryOptimizationIgnored(context: Context): String = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName).toString()
    }.getOrDefault("unavailable")

    private fun permissionState(context: Context, permission: String, introducedApi: Int): String =
        if (Build.VERSION.SDK_INT < introducedApi) "not_applicable"
        else if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "granted"
        else "denied"

    private fun safeMeta(value: String, max: Int): String =
        value.filter { it.isLetterOrDigit() || it in "._,- " }.take(max)

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}
