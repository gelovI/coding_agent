package org.ivangelov.agent.core.ports

import kotlinx.coroutines.flow.Flow
import org.ivangelov.agent.core.model.ChatMessage

data class ContextPack(
    val pinned: List<ChatMessage>,
    val retrieved: List<ChatMessage>,
    val recentSummary: String?
)

interface LLMClient {
    /** Emits text chunks as they arrive (streaming). */
    fun streamReply(
        messages: List<ChatMessage>,
        context: ContextPack
    ): Flow<String>
}
