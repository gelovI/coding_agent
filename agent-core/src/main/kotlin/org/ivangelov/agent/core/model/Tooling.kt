package org.ivangelov.agent.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class ToolCall(
    val name: String,
    val argsJson: JsonObject = buildJsonObject { }
)

@Serializable
data class ToolResult(
    val name: String,
    val ok: Boolean,
    val content: String,
    val meta: Map<String, String> = emptyMap()
)
