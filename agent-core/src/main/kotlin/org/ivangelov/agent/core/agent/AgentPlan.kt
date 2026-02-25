package org.ivangelov.agent.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class AgentPlan(
    val reply: String? = null,
    @SerialName("tool_calls") val toolCalls: List<PlannedToolCall> = emptyList()
)

@Serializable
data class PlannedToolCall(
    val name: String,
    @SerialName("arguments") val args: JsonObject = buildJsonObject { }
)
