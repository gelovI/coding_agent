package org.ivangelov.agent.core.ports

import kotlinx.coroutines.flow.Flow
import org.ivangelov.agent.core.agent.AgentResult
import org.ivangelov.agent.core.model.ChatMessage

data class ContextPack(
    val pinned: List<ChatMessage>,
    val retrieved: List<ChatMessage>,
    val recentSummary: String?
)

interface LLMClient {
    fun streamReply(
        messages: List<ChatMessage>,
        context: ContextPack
    ): Flow<String>

    suspend fun complete(
        messages: List<ChatMessage>,
        context: ContextPack,
        format: LlmResponseFormat
    ): LlmResponse

    suspend fun completeToolMode(
        messages: List<ChatMessage>,
        context: ContextPack
    ): AgentResult<ToolModeResponse>
}