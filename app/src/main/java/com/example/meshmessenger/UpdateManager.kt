package com.example.meshmessenger

import android.content.Context
import android.content.Intent
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

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/kinodoc/MeshMessenger/releases/latest"
        private const val APK_PREFIX = "MeshMessenger"
    }

    data class ReleaseInfo(val version: String, val apkUrl: String, val apkName: String)

    fun check(onResult: (Result<ReleaseInfo?>) -> Unit) {
        Thread {
            runCatching {
                val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "MeshMessenger")
                }
                try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }.mapCatching { body ->
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v")
                val assets = json.optJSONArray("assets") ?: return@mapCatching null
                var found: ReleaseInfo? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", true) && name.startsWith(APK_PREFIX) && url.startsWith("https://")) {
                        found = ReleaseInfo(tag, url, name)
                        break
                    }
                }
                found
            }.onSuccess { result ->
                context.mainExecutor.execute { onResult(Result.success(result)) }
            }.onFailure { error ->
                context.mainExecutor.execute { onResult(Result.failure(error)) }
            }
        }.start()
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
                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
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
