package com.quantum.agent

import java.io.Serializable

enum class KvCachePrecision(val bitValue: Int) {
    FP16(16), INT8(8), INT4(4)
}

enum class ChatInferenceMode {
    SOLO,
    SWARM
}

enum class InferenceBackend(val displayName: String) {
    LLAMA_CPP("Llama.cpp (Native)"),
    MLC_LLM("MLC-LLM (Optimized)"),
    OLLAMA("Ollama (Local/Remote)"),
}

data class MessageLog(
    val senderRole: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

data class McpServerConfig(
    val serverName: String,
    val endpointUrl: String,
    val isEnabled: Boolean = true
) : Serializable

data class AgentToolPermissions(
    val allowSystemTools: Boolean = true,
    val allowedMcpServers: List<String> = emptyList()
) : Serializable

/**
 * Configuration for Llama.cpp backend
 */
data class LlamaCppConfig(
    val nGpuLayers: Int = 0,
    val useNeon: Boolean = true,
    val useDotProd: Boolean = true
) : Serializable

/**
 * Configuration for MLC-LLM backend
 */
data class MlcLlmConfig(
    val useRemote: Boolean = false,
    val remoteEndpoint: String = "http://127.0.0.1:8000",
    val deviceType: String = "cpu", // or "gpu"
    val quantization: String = "q4f16_1" // MLC quantization format
) : Serializable

/**
 * Configuration for Ollama backend
 */
data class OllamaConfig(
    val localEndpoint: String = "http://127.0.0.1:11434",
    val useRemote: Boolean = false,
    val remoteEndpoint: String = "",
    val modelName: String = "", // Model name in Ollama
    val keepAlive: String = "5m", // Keep model in memory for 5 minutes
    val enableStreaming: Boolean = true
) : Serializable

data class SwarmConfig(
    val maxAgents: Int = 3,
    val selectedModelPath: String = "",
    val selectedModelName: String = "No model selected",
    val inferenceMode: ChatInferenceMode = ChatInferenceMode.SOLO,
    val inferenceBackend: InferenceBackend = InferenceBackend.LLAMA_CPP,
    val verbosityLevel: Int = 2,
    val kvCacheSize: Int = 2048,
    val enableFastAttention: Boolean = true,
    val threadClampCount: Int = 4,
    val cachePrecision: KvCachePrecision = KvCachePrecision.INT8,
    val orchestratorPrompt: String = "You are the Coordinator. Breakdown tasks into operations.",
    val analystPrompt: String = "You are the System Analyst. Parse localized text matrices.",
    val executorPrompt: String = "You are the Hardware Executor. Interact with device system APIs.",
    val globalMemoryHistory: ArrayList<MessageLog> = arrayListOf(),
    val mcpServers: List<McpServerConfig> = listOf(
        McpServerConfig("Local Sensor Bridge", "http://127.0.0.1:8080/mcp", isEnabled = true),
        McpServerConfig("External Knowledge Node", "https://mcp.quantum-swarm.net/api", isEnabled = false)
    ),
    val orchestratorTools: AgentToolPermissions = AgentToolPermissions(allowSystemTools = true),
    val analystTools: AgentToolPermissions = AgentToolPermissions(allowSystemTools = false),
    val executorTools: AgentToolPermissions = AgentToolPermissions(allowSystemTools = true),
    // Model sampler parameters
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val enableStreaming: Boolean = true,
    // Backend-specific configurations
    val llamaCppConfig: LlamaCppConfig = LlamaCppConfig(),
    val mlcLlmConfig: MlcLlmConfig = MlcLlmConfig(),
    val ollamaConfig: OllamaConfig = OllamaConfig()
) : Serializable
