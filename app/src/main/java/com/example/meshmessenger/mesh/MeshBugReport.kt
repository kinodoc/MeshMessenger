package com.example.meshmessenger.mesh

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.example.meshmessenger.BuildConfig
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates a common privacy-filtered diagnostic archive for manual and automatic reports. */
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
            appendLine("android=" + safeMeta(Build.VERSION.RELEASE ?: "unknown", 40))
            appendLine("api=" + Build.VERSION.SDK_INT)
            appendLine("abi=" + safeMeta(Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), 80))
            appendLine("bluetoothEnabled=" + runCatching {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                if (Build.VERSION.SDK_INT >= 31 &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                ) "permission_missing" else manager.adapter?.isEnabled?.toString() ?: "unavailable"
            }.getOrDefault("unavailable"))
            appendLine("network=" + runCatching {
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
            }.getOrDefault("unavailable"))
            appendLine("networkValidated=" + runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.activeNetwork?.let {
                    cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }?.toString() ?: "false"
            }.getOrDefault("unavailable"))
            appendLine("batteryOptimizationIgnored=" + runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName).toString()
            }.getOrDefault("unavailable"))
            appendLine("notificationPermission=" + permissionState(context, Manifest.permission.POST_NOTIFICATIONS))
            appendLine("bluetoothScanPermission=" + permissionState(context, Manifest.permission.BLUETOOTH_SCAN))
            appendLine("bluetoothConnectPermission=" + permissionState(context, Manifest.permission.BLUETOOTH_CONNECT))
            appendLine("bluetoothAdvertisePermission=" + permissionState(context, Manifest.permission.BLUETOOTH_ADVERTISE))
            appendLine("locationPermission=" + permissionState(context, Manifest.permission.ACCESS_FINE_LOCATION))
        }

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            put(zip, "report.txt", meta)
            // Do not archive raw Bluetooth snapshots: they may contain nearby names or addresses.
            // Both report modes always include the same allowlisted event history and metadata.
            put(zip, "mesh_diagnostics.log", diagnostics.readForUpload())
            // Include only filtered crash context if available; messages and causes are excluded.
            if (!crashTrace.isNullOrBlank()) {
                put(zip, "crash_trace.txt", sanitizeTrace(crashTrace).take(48_000))
            }
        }
        return file
    }

    private fun permissionState(context: Context, permission: String): String =
        if (Build.VERSION.SDK_INT < permissionIntroducedApi(permission)) "not_applicable"
        else if (runCatching { context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) "granted"
        else "denied"

    private fun permissionIntroducedApi(permission: String): Int = when (permission) {
        Manifest.permission.POST_NOTIFICATIONS -> 33
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE -> 31
        else -> 1
    }

    private fun safeMeta(value: String, max: Int): String =
        value.filter { it.isLetterOrDigit() || it in "._,- " }.take(max)

    private fun sanitizeTrace(trace: String): String = trace.lineSequence().mapNotNull { line ->
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
