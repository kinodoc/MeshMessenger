package com.example.meshmessenger.mesh

import android.content.Context
import com.example.meshmessenger.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Shared uploader for manual and periodic privacy-filtered diagnostic archives. */
object MeshBugReportUploader {
    private const val ENDPOINT = "https://194.87.186.159/api/bugreports"

    fun upload(report: File): String {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/zip")
            connection.setRequestProperty("X-Mesh-Version", BuildConfig.VERSION_NAME)
            connection.setFixedLengthStreamingMode(report.length().toInt())
            connection.outputStream.use { output -> report.inputStream().use { it.copyTo(output) } }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = JSONObject(payload)
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(when (json.optString("error")) {
                    "rate_limited" -> "rate_limited"
                    "service_not_configured", "upstream_unavailable" -> "server_unavailable"
                    "invalid_zip", "invalid_upload_size" -> "invalid_archive"
                    else -> "http_$code"
                })
            }
            return if (json.optBoolean("duplicate", false)) "duplicate" else "uploaded"
        } finally {
            connection.disconnect()
        }
    }
}
