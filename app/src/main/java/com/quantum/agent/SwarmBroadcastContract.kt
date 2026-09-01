package com.quantum.agent

object SwarmBroadcastContract {
    const val ACTION_SWARM_TELEMETRY = "com.quantum.agent.ACTION_SWARM_TELEMETRY"
    const val EXTRA_ROLE = "EXTRA_ROLE"
    const val EXTRA_THOUGHT = "EXTRA_THOUGHT"
    const val EXTRA_TOOL = "EXTRA_TOOL"
    const val EXTRA_PARAMS = "EXTRA_PARAMS"
    const val EXTRA_SYSTEM_STATUS = "EXTRA_SYSTEM_STATUS"
    const val EXTRA_MESSAGE_TYPE = "EXTRA_MESSAGE_TYPE"
    const val EXTRA_MESSAGE_BODY = "EXTRA_MESSAGE_BODY"
    const val MESSAGE_TYPE_PROMPT = "PROMPT"
    const val MESSAGE_TYPE_CONTEXT = "CONTEXT"
    const val MESSAGE_TYPE_ENGINE = "ENGINE"
    const val MESSAGE_TYPE_TOOL = "TOOL"
    const val MESSAGE_TYPE_THOUGHT = "THOUGHT"
}
