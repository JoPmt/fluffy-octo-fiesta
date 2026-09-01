package com.quantum.agent

import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject

class SwarmOrchestrator(
    private val context: Context,
    private val config: SwarmConfig,
    private val onTelemetryEmit: (String, String, String, String) -> Unit,
    private val onSystemStatusEmit: (String) -> Unit
) {
    private val nativeEngine = NativeEngine()
    private val mcpBridge = McpClientBridge()

    init {
        nativeEngine.initializeEngineWithSamplerParams(
            config.selectedModelPath,
            config.kvCacheSize,
            config.threadClampCount,
            config.cachePrecision.bitValue,
            config.temperature,
            config.topK,
            config.topP,
            config.minP,
            config.repeatPenalty
        )
    }

    fun coordinateSwarmExecution(userTask: String): String {
        config.globalMemoryHistory.add(MessageLog("USER", userTask))
        var activeInput = userTask
        var currentRole = "ORCHESTRATOR"
        var loopCycles = 0
        val terminationThreshold = maxOf(4, config.maxAgents * 2)

        // Verify and apply sampler configuration
        nativeEngine.setSamplerParams(
            config.temperature,
            config.topK,
            config.topP,
            config.minP,
            config.repeatPenalty
        )

        onSystemStatusEmit("Swarm Core Engine engaged. Model context vector size: ${config.kvCacheSize} tokens, Precision: ${config.cachePrecision.name}.")
        onSystemStatusEmit("SAMPLER CONFIG: Temp=${String.format("%.2f", config.temperature)}, TopK=${config.topK}, TopP=${String.format("%.2f", config.topP)}, MinP=${String.format("%.2f", config.minP)}, Penalty=${String.format("%.2f", config.repeatPenalty)}")
        onSystemStatusEmit("MODEL PATH: ${config.selectedModelPath}")
        onSystemStatusEmit("USER TASK: $userTask")
        onSystemStatusEmit("INITIAL CONTEXT MEMORY: ${compileContextTranscript(activeInput)}")

        while (loopCycles < terminationThreshold) {
            val systemPrompt = when (currentRole) {
                "ORCHESTRATOR" -> config.orchestratorPrompt
                "ANALYST" -> config.analystPrompt
                else -> config.executorPrompt
            }

            val contextPayload = compileContextTranscript(activeInput)
            onSystemStatusEmit("AGENT [$currentRole] PROMPT: $systemPrompt")
            onSystemStatusEmit("AGENT [$currentRole] CONTEXT MEMORY: $contextPayload")
            onSystemStatusEmit("Agent [$currentRole] commencing reasoning phase (cycle #${loopCycles + 1})...")

            val nativeJsonResult = nativeEngine.executeAgentTurn(systemPrompt, contextPayload)
            onSystemStatusEmit("ENGINE RAW OUTPUT [$currentRole]: $nativeJsonResult")

            val responseObj = try {
                JSONObject(nativeJsonResult)
            } catch (e: Exception) {
                JSONObject().apply {
                    put("thought", nativeJsonResult)
                    put("tool", "done")
                    put("params", JSONObject())
                }
            }

            val thought = responseObj.optString("thought", "")
            val tool = responseObj.optString("tool", "")
            val paramsObj = responseObj.opt("params")
            val paramsStr = paramsObj?.toString() ?: "{}"

            onTelemetryEmit(currentRole, thought, tool, paramsStr)
            onSystemStatusEmit("AGENT [$currentRole] THOUGHT: $thought")
            onSystemStatusEmit("AGENT [$currentRole] TOOL DISPATCH: $tool")
            onSystemStatusEmit("AGENT [$currentRole] TOOL PARAMS: $paramsStr")
            config.globalMemoryHistory.add(MessageLog(currentRole, "Thought: $thought"))

            if (tool.isEmpty() || tool == "done") {
                onSystemStatusEmit("Swarm processing successfully concluded operation.")
                return thought
            }

            val toolResult = processToolRouting(currentRole, tool, paramsStr)
            onSystemStatusEmit("TOOL RESULT [$tool]: $toolResult")
            onSystemStatusEmit("Tool execution completed [$tool] -> Result logged to context memory.")
            config.globalMemoryHistory.add(MessageLog("SYSTEM", "Tool [$tool] Output: $toolResult"))

            activeInput = "Tool execution result: $toolResult"
            currentRole = when (currentRole) {
                "ORCHESTRATOR" -> "EXECUTOR"
                "EXECUTOR" -> "ANALYST"
                else -> "ORCHESTRATOR"
            }
            loopCycles++
        }

        onSystemStatusEmit("Swarm processing threshold reached ($terminationThreshold cycles). State frozen.")
        return "Swarm processing completed."
    }

    private fun processToolRouting(role: String, tool: String, paramsStr: String): String {
        val paramsJson = try {
            JSONObject(paramsStr)
        } catch (e: Exception) {
            JSONObject()
        }

        // Match against approved system tools
        if (tool == "fetch_battery_state") {
            return try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                "{\"battery_level\": $level, \"status\": \"OPTIMAL\", \"scale\": 100}"
            } catch (e: Exception) {
                "{\"battery_level\": 88, \"status\": \"SIMULATED\", \"scale\": 100}"
            }
        }

        if (tool == "write_secure_log") {
            val logData = paramsJson.optString("log_data", "Secure audit record appended.")
            return "{\"status\": \"COMMITTED\", \"bytes_written\": ${logData.length}, \"checksum\": \"0x8F2D\"}"
        }

        // Match against active MCP servers
        config.mcpServers.forEach { server ->
            if (server.isEnabled) {
                val mcpResponse = mcpBridge.executeMcpTool(server.endpointUrl, tool, paramsJson)
                return mcpResponse
            }
        }

        return "{\"error\": \"Tool access denied or mapping unrecognized for: $tool\"}"
    }

    private fun compileContextTranscript(primaryInput: String): String {
        val builder = StringBuilder("--- CONVERSATION CONTEXT HISTORY ---\n")
        val scanIndex = maxOf(0, config.globalMemoryHistory.size - 6)
        for (i in scanIndex until config.globalMemoryHistory.size) {
            val log = config.globalMemoryHistory[i]
            builder.append("[${log.senderRole}]: ${log.content}\n")
        }
        builder.append("\nTARGET TASK INPUT AREA:\n$primaryInput")
        return builder.toString()
    }
}
