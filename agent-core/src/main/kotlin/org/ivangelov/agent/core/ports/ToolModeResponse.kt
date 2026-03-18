package org.ivangelov.agent.core.ports

import kotlinx.serialization.json.JsonObject

data class LlmToolCall(
    val name: String,
    val args: JsonObject
)

data class ToolModeResponse(
    val toolCalls: List<LlmToolCall>,
    val reply: String?,
    val rawContent: String?,
    val rawThinking: String?
)