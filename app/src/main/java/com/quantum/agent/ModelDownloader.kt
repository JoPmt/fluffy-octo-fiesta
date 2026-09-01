package com.quantum.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

interface DownloadCallback {
    fun onProgress(percentage: Int, bytesDownloaded: Long, totalBytes: Long)
    fun onSuccess(fileAbsolutePath: String)
    fun onFailure(errorMessage: String)
}

class ModelDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun downloadHuggingFaceModel(repoUrl: String, destinationFile: File, callback: DownloadCallback) {
        withContext(Dispatchers.IO) {
            try {
                // If it's a simulated or sample test URL or HF model card, download safely or write sample weights header
                val request = Request.Builder()
                    .url(repoUrl)
                    .header("User-Agent", "QuantumSwarmCore/1.0.0 (Android Edge LLM)")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        callback.onFailure("Server rejected download request: HTTP ${response.code}")
                        return@withContext
                    }
                    val body = response.body
                    if (body == null) {
                        callback.onFailure("Empty server data returned.")
                        return@withContext
                    }

                    destinationFile.parentFile?.mkdirs()
                    val totalBytes = body.contentLength()
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(destinationFile)
                    val buffer = ByteArray(65536)
                    var bytesDownloaded: Long = 0
                    var read: Int
                    var lastPercent = -1

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        bytesDownloaded += read
                        if (totalBytes > 0) {
                            val percentage = ((bytesDownloaded * 100) / totalBytes).toInt()
                            if (percentage != lastPercent) {
                                lastPercent = percentage
                                callback.onProgress(percentage, bytesDownloaded, totalBytes)
                            }
                        } else {
                            // Indeterminate stream
                            val simulatedPercent = minOf(99, (bytesDownloaded / (1024 * 1024)).toInt())
                            if (simulatedPercent != lastPercent) {
                                lastPercent = simulatedPercent
                                callback.onProgress(simulatedPercent, bytesDownloaded, -1)
                            }
                        }
                    }
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    callback.onProgress(100, bytesDownloaded, bytesDownloaded)
                    callback.onSuccess(destinationFile.absolutePath)
                }
            } catch (e: Exception) {
                callback.onFailure(e.localizedMessage ?: "Network connection dropped.")
            }
        }
    }
}
