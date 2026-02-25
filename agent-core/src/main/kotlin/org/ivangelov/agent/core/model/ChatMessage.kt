package org.ivangelov.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: Role,
    val content: String,
    val meta: Map<String, String> = emptyMap()
) {
    @Serializable
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}
