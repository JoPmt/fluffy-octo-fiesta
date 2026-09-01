package com.quantum.agent

import android.util.Log
import java.io.File

/**
 * Factory for creating inference engine instances
 */
class InferenceEngineFactory {
    companion object {
        private const val TAG = "EngineFactory"

        /**
         * Create appropriate inference engine based on configuration
         */
        fun createEngine(backend: InferenceBackend, config: SwarmConfig): InferenceEngine {
            return when (backend) {
                InferenceBackend.LLAMA_CPP -> {
                    Log.i(TAG, "Creating Llama.cpp inference engine")
                    LlamaCppEngine(config.llamaCppConfig)
                }
                InferenceBackend.MLC_LLM -> {
                    Log.i(TAG, "Creating MLC-LLM inference engine")
                    MlcLlmEngine(config.mlcLlmConfig)
                }
                InferenceBackend.OLLAMA -> {
                    Log.i(TAG, "Creating Ollama inference engine")
                    OllamaEngine(config.ollamaConfig)
                }
            }
        }

        /**
         * Check which backends are available on this device
         */
        fun checkAvailableBackends(): Map<InferenceBackend, EngineCapabilityResult> {
            return mapOf(
                InferenceBackend.LLAMA_CPP to checkLlamaCppAvailability(),
                InferenceBackend.MLC_LLM to checkMlcLlmAvailability(),
                InferenceBackend.OLLAMA to checkOllamaAvailability()
            )
        }

        private fun checkLlamaCppAvailability(): EngineCapabilityResult {
            return try {
                // Try to load the native library
                System.loadLibrary("llama_agent")
                EngineCapabilityResult(
                    isAvailable = true,
                    message = "Llama.cpp native library loaded successfully"
                )
            } catch (e: UnsatisfiedLinkError) {
                EngineCapabilityResult(
                    isAvailable = false,
                    message = "Llama.cpp native library not found: ${e.message}",
                    requiresSetup = true
                )
            } catch (e: Exception) {
                EngineCapabilityResult(
                    isAvailable = false,
                    message = "Llama.cpp check failed: ${e.message}",
                    requiresSetup = false
                )
            }
        }

        private fun checkMlcLlmAvailability(): EngineCapabilityResult {
            return EngineCapabilityResult(
                isAvailable = false,
                message = "MLC-LLM backend requires native SDK integration",
                requiresSetup = true
            )
        }

        private fun checkOllamaAvailability(): EngineCapabilityResult {
            return EngineCapabilityResult(
                isAvailable = true,
                message = "Ollama backend available (requires local or remote service)",
                requiresSetup = false // Can be set up at runtime
            )
        }
    }
}

/**
 * Result of engine capability check
 */
data class EngineCapabilityResult(
    val isAvailable: Boolean,
    val message: String,
    val requiresSetup: Boolean = false
)
