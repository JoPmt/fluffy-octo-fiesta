package com.quantum.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class HuggingFaceModel(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val ggufFile: String?
) {
    val displayName: String
        get() = id.substringAfterLast('/').replace('-', ' ').replace('_', ' ')

    val downloadUrl: String?
        get() = ggufFile?.let { file ->
            "https://huggingface.co/$id/resolve/main/$file"
        }
}

class HuggingFaceRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun buildModelsRequestUrl(searchTerm: String = "", limit: Int = 30): String {
        val safeSearch = searchTerm.trim()
        val encodedSearch = if (safeSearch.isBlank()) "" else "&search=${URLEncoder.encode(safeSearch, StandardCharsets.UTF_8.toString())}"
        return "https://huggingface.co/api/models?library=gguf$encodedSearch&sort=downloads&direction=-1&limit=$limit"
    }

    suspend fun fetchGgufModels(searchTerm: String = "", limit: Int = 30): List<HuggingFaceModel> = withContext(Dispatchers.IO) {
        val modelsRequest = Request.Builder()
            .url(buildModelsRequestUrl(searchTerm, limit))
            .header("User-Agent", "QuantumSwarmCore/1.0.0 (Android Edge LLM)")
            .build()

        client.newCall(modelsRequest).execute().use { response ->
            if (!response.isSuccessful) error("Hugging Face returned HTTP ${response.code}")
            val models = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (index in 0 until models.length()) {
                    val model = models.optJSONObject(index) ?: continue
                    val id = model.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val files = fetchGgufFileNames(id)
                    add(
                        HuggingFaceModel(
                            id = id,
                            downloads = model.optLong("downloads"),
                            likes = model.optLong("likes"),
                            ggufFile = files.firstOrNull()
                        )
                    )
                }
            }.filter { it.ggufFile != null }
        }
    }

    private fun fetchGgufFileNames(modelId: String): List<String> {
        val request = Request.Builder()
            .url("https://huggingface.co/api/models/$modelId/tree/main?recursive=false")
            .header("User-Agent", "QuantumSwarmCore/1.0.0 (Android Edge LLM)")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val files = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (index in 0 until files.length()) {
                    val path = files.optJSONObject(index)?.optString("path").orEmpty()
                    if (path.endsWith(".gguf", ignoreCase = true)) add(path)
                }
            }
        }
    }
}