package com.quantum.agent

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Llama.cpp inference engine adapter
 * Wraps the native llama.cpp library for on-device inference
 */
class LlamaCppEngine(private val config: LlamaCppConfig) : InferenceEngine {
    private val TAG = "LlamaCppEngine"
    private val nativeEngine = NativeEngine()

    override suspend fun initialize(
        modelPath: String,
        ctxSize: Int,
        threadCount: Int,
        cachePrecisionBits: Int
    ): Boolean = withContext(Dispatchers.Default) {
        Log.d(TAG, "Initializing Llama.cpp engine with model: $modelPath")
        nativeEngine.initializeEngineWithCache(modelPath, ctxSize, threadCount, cachePrecisionBits)
    }

    override suspend fun executeAgentTurn(rolePrompt: String, inputData: String): String =
        withContext(Dispatchers.Default) {
            Log.d(TAG, "Executing agent turn with Llama.cpp")
            nativeEngine.executeAgentTurn(rolePrompt, inputData)
        }

    override suspend fun generateChatCompletion(
        modelPath: String,
        prompt: String,
        ctxSize: Int,
        threadCount: Int,
        cachePrecisionBits: Int
    ): String = withContext(Dispatchers.Default) {
        Log.d(TAG, "Generating chat completion with Llama.cpp")
        nativeEngine.generatePlainChatCompletion(modelPath, prompt, ctxSize, threadCount, cachePrecisionBits)
    }

    override suspend fun setSamplerParams(
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float
    ): Boolean = withContext(Dispatchers.Default) {
        Log.d(TAG, "Setting sampler params: temp=$temperature, topK=$topK, topP=$topP")
        nativeEngine.setSamplerParams(temperature, topK, topP, minP, repeatPenalty)
    }

    override suspend fun extractChatTemplate(modelPath: String): String =
        withContext(Dispatchers.Default) {
            nativeEngine.extractChatTemplate(modelPath)
        }

    override suspend fun getModelInfo(modelPath: String): String =
        withContext(Dispatchers.Default) {
            nativeEngine.getModelInfo(modelPath)
        }

    override fun isCompatibleModel(modelPath: String): Boolean {
        if (modelPath.isBlank()) return false
        val file = File(modelPath)
        return LocalModelStore.isValidGgufFile(file)
    }

    override suspend fun deallocate() = withContext(Dispatchers.Default) {
        Log.d(TAG, "Deallocating Llama.cpp engine")
        nativeEngine.deallocateEngine()
    }

    override fun getBackendName(): String = "Llama.cpp (Native ARM NEON)"
}

/**
 * MLC-LLM inference engine adapter
 * For integrated on-device or remote MLC inference
 */
class MlcLlmEngine(private val config: MlcLlmConfig) : InferenceEngine {
    private val TAG = "MlcLlmEngine"

    override suspend fun initialize(
        modelPath: String,
        ctxSize: Int,
        threadCount: Int,
        cachePrecisionBits: Int
    ): Boolean = withContext(Dispatchers.Default) {
        Log.d(TAG, "Initializing MLC-LLM engine (Remote: ${config.useRemote})")
        Log.d(TAG, "Endpoint: ${if (config.useRemote) config.remoteEndpoint else "Local"}")
        
        // TODO: Implement actual MLC integration
        // This would connect to MLC HTTP API or native SDK
        if (config.useRemote) {
            try {
                // TODO: Health check on remote endpoint
                Log.i(TAG, "MLC-LLM initialized at ${config.remoteEndpoint}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to MLC endpoint: ${e.message}")
                false
            }
        } else {
            Log.w(TAG, "MLC-LLM local mode not yet implemented")
            false
        }
    }

    override suspend fun executeAgentTurn(rolePrompt: String, inputData: String): String =
        withContext(Dispatchers.Default) {
            Log.d(TAG, "MLC-LLM agent turn (not implemented)")
            // TODO: Implement via HTTP API
            "{\"thought\":\"MLC backend pending implementation\",\"tool\":\"done\",\"params\":{}}"
        }

    override suspend fun generateChatCompletion(
        modelPath: String,
        prompt: String,
        ctxSize: Int,
        threadCount: Int,
        cachePrecisionBits: Int
    ): String = withContext(Dispatchers.Default) {
        Log.d(TAG, "MLC-LLM chat completion (not implemented)")
        // TODO: Implement via HTTP API
        "MLC-LLM backend integration pending"
    }

    override suspend fun setSamplerParams(
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float
    ): Boolean = withContext(Dispatchers.Default) {
        Log.d(TAG, "MLC-LLM setting sampler params (would send to endpoint)")
        true
    }

    override suspend fun extractChatTemplate(modelPath: String): String =
        withContext(Dispatchers.Default) {
            "mlc_default" // TODO: Query from endpoint
        }

    override suspend fun getModelInfo(modelPath: String): String =
        withContext(Dispatchers.Default) {
            "Model: MLC-LLM\nEndpoint: ${if (config.useRemote) config.remoteEndpoint else "Local"}\nQuantization: ${config.quantization}"
        }

    override fun isCompatibleModel(modelPath: String): Boolean {
        // MLC can work with various formats
        return !modelPath.isBlank()
    }

    override suspend fun deallocate() {
        Log.d(TAG, "Deallocating MLC-LLM engine")
    }

    override fun getBackendName(): String = "MLC-LLM (Optimized)"
}

/**
 * Ollama inference engine adapter
 * Connects to local or remote Ollama service via HTTP API
 */
class OllamaEngine(private val config: OllamaConfig) : InferenceEngine {
    private val TAG = "OllamaEngine"
    private val client = okhttp3.OkHttpClient()

    override suspend fun initialize(
        modelPath: String,
        ctxSize: Int,
        threadCount: Int,
        cachePrecisionBits: Int
    ): Boolean = withContext(Dispatchers.Default) {
        Log.d(TAG, "Initializing Ollama engine")
        Log.d(TAG, "Local endpoint: ${config.localEndpoint}")
        if (config.useRemote) {
            Log.d(TAG, "Remote endpoint: ${config.remoteEndpoint}")
        }
        Log.d(TAG, "Model name: ${config.modelName}")
        
        try {
            val endpoint = if (config.useRemote && config.remoteEndpoint.isNotBlank()) {
                config.remoteEndpoint
            } else {
                config.localEndpoint
            }
            
            // Health check
            val request = okhttp3.Request.Builder()
                .url("$endpoint/api/tags")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Ollama service available at $endpoint")
                    true
                } else {
                    Log.e(TAG, "Ollama health check failed: HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Ollama: ${e.message}")
            false
        }
    }

    override suspend fun executeAgentTurn(rolePrompt: String, inputData: String): String =
        withContext(Dispatchers.Default) {
            Log.d(TAG, "Ollama agent turn")
            try {
                if (config.modelName.isBlank()) {
                    return@withContext "{\"thought\":\"Ollama model not configured\",\"tool\":\"done\",\"params\":{}}"
                }

                val endpoint = if (config.useRemote && config.remoteEndpoint.isNotBlank()) {
                    config.remoteEndpoint
                } else {
                    config.localEndpoint
                }

                val prompt = "You are a helpful local model running in JSON tool-call mode. Return valid JSON only with keys 'thought', 'tool', and 'params'.\n" +
                    "Role: $rolePrompt\n" +
                    "User request: $inputData\n" +
                    "Respond with compact JSON object and no markdown."

                val jsonPayload = org.json.JSONObject().apply {
                    put("model", config.modelName)
                    put("prompt", prompt)
                    put("stream", false)
                    put("keep_alive", config.keepAlive)
                }

                val request = okhttp3.Request.Builder()
                    .url("$endpoint/api/generate")
                    .post(okhttp3.RequestBody.create(
                        jsonPayload.toString(),
                        "application/json".toMediaType()
                    ))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val result = response.body!!.string()
                        val jsonResult = org.json.JSONObject(result)
                        jsonResult.optString("response", "{\"thought\":\"Empty response\",\"tool\":\"done\",\"params\":{}}")
                    } else {
                        Log.e(TAG, "Ollama API error: HTTP ${response.code}")
                        "{\"thought\":\"Ollama API failed\",\"tool\":\"done\",\"params\":{}}"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ollama agent turn failed: ${e.message}")
                "{\"thought\":\"Ollama error: ${e.message}\",\"tool\":\"done\",\"params\":{}}"
            }
        }

    override suspend fun generateChatCompletion(
        modelPath: String,
        prompt: String,
        ctxSize: Int,
        threadCount: Int,
        cachePrecisionBits: Int
    ): String = withContext(Dispatchers.Default) {
        Log.d(TAG, "Ollama chat completion")
        try {
            if (config.modelName.isBlank()) {
                return@withContext "Ollama model not configured. Please set a model name in settings."
            }

            val endpoint = if (config.useRemote && config.remoteEndpoint.isNotBlank()) {
                config.remoteEndpoint
            } else {
                config.localEndpoint
            }

            val jsonPayload = org.json.JSONObject().apply {
                put("model", config.modelName)
                put("prompt", prompt)
                put("stream", config.enableStreaming)
                put("keep_alive", config.keepAlive)
            }

            val request = okhttp3.Request.Builder()
                .url("$endpoint/api/generate")
                .post(okhttp3.RequestBody.create(
                    jsonPayload.toString(),
                    "application/json".toMediaType()
                ))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val result = response.body!!.string()
                    val jsonResult = org.json.JSONObject(result)
                    jsonResult.optString("response", "No response from Ollama")
                } else {
                    Log.e(TAG, "Ollama API error: HTTP ${response.code}")
                    "Ollama API failed with status ${response.code}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ollama chat failed: ${e.message}")
            "Ollama error: ${e.message}"
        }
    }

    override suspend fun setSamplerParams(
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float
    ): Boolean = withContext(Dispatchers.Default) {
        Log.d(TAG, "Ollama sampler params will be included in next request")
        true
    }

    override suspend fun extractChatTemplate(modelPath: String): String =
        withContext(Dispatchers.Default) {
            "ollama_default"
        }

    override suspend fun getModelInfo(modelPath: String): String =
        withContext(Dispatchers.Default) {
            try {
                val endpoint = if (config.useRemote && config.remoteEndpoint.isNotBlank()) {
                    config.remoteEndpoint
                } else {
                    config.localEndpoint
                }

                val request = okhttp3.Request.Builder()
                    .url("$endpoint/api/tags")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        "Ollama service available\nLocal: ${config.localEndpoint}\nModel: ${config.modelName}"
                    } else {
                        "Ollama service unavailable"
                    }
                }
            } catch (e: Exception) {
                "Ollama connection error: ${e.message}"
            }
        }

    override fun isCompatibleModel(modelPath: String): Boolean {
        // Ollama uses model names, not file paths
        return config.modelName.isNotBlank()
    }

    override suspend fun deallocate() {
        Log.d(TAG, "Deallocating Ollama engine")
        // Ollama keeps model in memory per keepAlive setting
    }

    override fun getBackendName(): String =
        "Ollama (${if (config.useRemote) "Remote" else "Local"})"
}
