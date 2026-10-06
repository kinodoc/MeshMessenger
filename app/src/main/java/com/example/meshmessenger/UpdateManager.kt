package com.example.meshmessenger

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {
    private var pendingFile: File? = null

    /**
     * GitHub updates use the device normal Internet connection.
     * Do not fall back to URL.openConnection(): Android may route that through the VPN.
     */
    private fun openHttpConnection(url: String): HttpURLConnection {
        // Prefer Android's normal routing. OEM network policies and VPNs can make
        // an explicitly bound physical network unusable even when Internet works.
        runCatching {
            return URL(url).openConnection() as HttpURLConnection
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            network
        }.sortedByDescending { network ->
            cm.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }

        var lastError: Throwable? = null
        for (network in candidates) {
            try {
                return network.openConnection(URL(url)) as HttpURLConnection
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Нет доступного сетевого подключения")
    }
    companion object {
        private const val UPDATE_URL = "https://194.87.186.159/mesh-update/Concept/update.json"
        private const val APK_PREFIX = "MeshMessenger"
    }

    data class ReleaseInfo(val version: String, val apkUrl: String, val apkName: String, val versionCode: Int? = null, val sha256: String? = null)

    fun check(onResult: (Result<ReleaseInfo?>) -> Unit) {
        Thread {
            val result = runCatching { fetchLatestFromVps() }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    private fun fetchLatestFromVps(): ReleaseInfo? {
        val connection = openHttpConnection(UPDATE_URL).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MeshMessenger")
        }
        return try {
            if (connection.responseCode !in 200..299) error("VPS update HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = json.optString("version").removePrefix("v")
            val versionCode = json.optInt("versionCode", -1).takeIf { it > 0 }
            val apkUrl = json.optString("apkUrl")
            val apkName = json.optString("apkName")
            val sha256 = json.optString("sha256").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            if (version.isBlank() || !apkUrl.startsWith("https://") || apkName.isBlank()) return null
            ReleaseInfo(version, apkUrl, apkName, versionCode, sha256)
        } finally {
            connection.disconnect()
        }
    }


    fun isNewer(release: ReleaseInfo): Boolean = release.versionCode?.let { it > BuildConfig.VERSION_CODE }
        ?: (compareVersions(release.version, BuildConfig.VERSION_NAME) > 0)

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun downloadAndInstall(release: ReleaseInfo, onError: (Throwable) -> Unit) {
        Thread {
            runCatching {
                val connection = openHttpConnection(release.apkUrl).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "MeshMessenger")
                }
                if (connection.responseCode !in 200..299) error("VPS: HTTP ${connection.responseCode}")
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: error("Не удалось открыть каталог загрузок")
                dir.mkdirs()
                val file = File(dir, release.apkName)
                connection.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                connection.disconnect()
                Handler(Looper.getMainLooper()).post { install(file) }
            }.onFailure { error -> Handler(Looper.getMainLooper()).post { onError(error) } }
        }.start()
    }

    fun resumePendingInstall() {
        pendingFile?.let { file ->
            if (android.os.Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) install(file)
        }
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            pendingFile = file
            context.startActivity(intent)
            android.app.AlertDialog.Builder(context)
                .setTitle("Разрешение на обновление")
                .setMessage("Разреши установку приложений для Mesh Messenger, затем снова нажми «Установить».")
                .setPositiveButton("ОК", null)
                .show()
            return
        }
        pendingFile = null
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
