package com.quantum.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class McpClientBridge {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun fetchAvailableTools(serverUrl: String): List<String> {
        val toolNames = mutableListOf<String>()
        try {
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", "m_list")
                put("method", "tools/list")
                put("params", JSONObject())
            }
            val request = Request.Builder().url(serverUrl).post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val tools = JSONObject(body).optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
                for (i in 0 until tools.length()) {
                    toolNames.add(tools.getJSONObject(i).getString("name"))
                }
            }
        } catch (e: Exception) {
            // Server offline or unreachable
        }
        return toolNames
    }

    fun executeMcpTool(serverUrl: String, toolName: String, arguments: JSONObject?): String {
        return try {
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", "m_call")
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments ?: JSONObject())
                })
            }
            val request = Request.Builder().url(serverUrl).post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
            httpClient.newCall(request).execute().use { response ->
                response.body?.string() ?: "{\"error\": \"Empty response interface\"}"
            }
        } catch (e: Exception) {
            "{\"error\": \"Connection failure: ${e.localizedMessage}\"}"
        }
    }
}
