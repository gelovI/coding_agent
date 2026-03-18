package org.ivangelov.agent.core.ports

import kotlinx.serialization.json.JsonObject

enum class LlmResponseFormat {
    TEXT,
    JSON
}

data class LlmResponse(
    val content: String?,
    val thinking: String?,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val raw: String? = null
)