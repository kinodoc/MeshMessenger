package com.example.meshmessenger

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {
    private var pendingFile: File? = null

    /**
     * GitHub updates must use the real Internet connection, never the NetBird VPN.
     * Do not fall back to URL.openConnection(): Android may route that through the VPN.
     */
    private fun openHttpConnection(url: String): HttpURLConnection {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            val isInternetTransport =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !isInternetTransport ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) return@mapNotNull null
            network
        }.sortedByDescending { network ->
            cm.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }

        for (network in candidates) {
            try {
                val connection = network.openConnection(URL(url)) as HttpURLConnection
                // При активном VPN Android может запрещать приложению
                // явно привязывать сокет к физической сети (EPERM).
                // Проверяем привязку сразу и при неудаче пробуем следующую.
                connection.connectTimeout = 5000
                connection.connect()
                return connection
            } catch (_: Exception) {
                // Сеть могла исчезнуть или стать недоступной для UID из-за VPN.
            }
        }

        // Если Android не разрешает per-network binding при активном VPN,
        // используем обычный маршрут системы. Он может идти через VPN,
        // но не требует запрещённого bindSocket().
        return URL(url).openConnection() as HttpURLConnection
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/kinodoc/MeshMessenger/releases/latest"
        private const val RELEASE_PAGE_URL = "https://github.com/kinodoc/MeshMessenger/releases/latest"
        private const val APK_PREFIX = "MeshMessenger"
    }

    data class ReleaseInfo(val version: String, val apkUrl: String, val apkName: String)

    fun check(onResult: (Result<ReleaseInfo?>) -> Unit) {
        Thread {
            val result = runCatching { fetchLatestFromApi() }
                .recoverCatching { fetchLatestFromGitHubPage() }
            context.mainExecutor.execute { onResult(result) }
        }.start()
    }

    private fun fetchLatestFromApi(): ReleaseInfo? {
        val connection = openHttpConnection(RELEASES_URL).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MeshMessenger")
        }
        return try {
            if (connection.responseCode !in 200..299) error("GitHub API HTTP ${connection.responseCode}")
            parseReleaseJson(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parseReleaseJson(body: String): ReleaseInfo? {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").removePrefix("v")
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", true) && name.startsWith(APK_PREFIX) && url.startsWith("https://")) {
                return ReleaseInfo(tag, url, name)
            }
        }
        return null
    }

    private fun fetchLatestFromGitHubPage(): ReleaseInfo? {
        val connection = openHttpConnection(RELEASE_PAGE_URL).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MeshMessenger")
        }
        return try {
            if (connection.responseCode !in 200..299) error("GitHub releases page HTTP ${connection.responseCode}")
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val tag = Regex("/kinodoc/MeshMessenger/releases/tag/([^\"/?]+)")
                .find(html)?.groupValues?.getOrNull(1)?.removePrefix("v")
                ?: return null
            val apkName = "MeshMessenger-v$tag.apk"
            val apkUrl = "https://github.com/kinodoc/MeshMessenger/releases/download/v$tag/$apkName"
            ReleaseInfo(tag, apkUrl, apkName)
        } finally {
            connection.disconnect()
        }
    }

    fun isNewer(version: String): Boolean = compareVersions(version, BuildConfig.VERSION_NAME) > 0

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
                if (connection.responseCode !in 200..299) error("GitHub: HTTP ${connection.responseCode}")
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: error("Не удалось открыть каталог загрузок")
                dir.mkdirs()
                val file = File(dir, release.apkName)
                connection.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                connection.disconnect()
                context.mainExecutor.execute { install(file) }
            }.onFailure { error -> context.mainExecutor.execute { onError(error) } }
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
