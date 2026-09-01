package com.quantum.agent

import java.io.Serializable

/**
 * Base interface for all inference engines
 */
interface InferenceEngine {
    /**
     * Initialize the engine with model and parameters
     * @return true if initialization succeeded, false otherwise
     */
    suspend fun initialize(
        modelPath: String,
        ctxSize: Int,
        threadCount: Int,
        cachePrecisionBits: Int
    ): Boolean

    /**
     * Execute a complete inference turn with role-based prompting
     * @return JSON response with thought, tool, and params
     */
    suspend fun executeAgentTurn(rolePrompt: String, inputData: String): String

    /**
     * Generate plain chat completion
     * @return Generated text response
     */
    suspend fun generateChatCompletion(
        modelPath: String,
        prompt: String,
        ctxSize: Int,
        threadCount: Int,
        cachePrecisionBits: Int
    ): String

    /**
     * Set sampler parameters for generation
     */
    suspend fun setSamplerParams(
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float
    ): Boolean

    /**
     * Extract chat template from model
     */
    suspend fun extractChatTemplate(modelPath: String): String

    /**
     * Get model information
     */
    suspend fun getModelInfo(modelPath: String): String

    /**
     * Check if a model file is compatible with this engine
     */
    fun isCompatibleModel(modelPath: String): Boolean

    /**
     * Deallocate engine resources
     */
    suspend fun deallocate()

    /**
     * Get backend name
     */
    fun getBackendName(): String
}
