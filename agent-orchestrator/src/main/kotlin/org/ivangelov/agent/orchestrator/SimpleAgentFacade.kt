package org.ivangelov.agent.orchestrator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.ivangelov.agent.core.agent.AgentEvent
import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.core.model.ChatMessage.Role
import org.ivangelov.agent.core.ports.ContextPack
import org.ivangelov.agent.core.ports.LLMClient
import org.ivangelov.agent.db.ChatRepository

class SimpleAgentFacade(
    private val repo: ChatRepository,
    private val conversationId: String,
    private val llm: LLMClient
) : AgentFacade {

    override fun send(userText: String): Flow<AgentEvent> = flow {
        repo.appendMessage(conversationId, Role.USER, userText)

        emit(
            AgentEvent.UserMessageStored(
                conversationId = conversationId,
                text = userText
            )
        )

        val history = history() // includes the new user message
        val ctx = ContextPack(
            pinned = emptyList(),
            retrieved = emptyList(),
            recentSummary = null
        )

        val acc = StringBuilder()
        try {
            llm.streamReply(history, ctx).collect { delta ->
                acc.append(delta)
                emit(AgentEvent.StreamDelta(delta))            }
        } catch (t: Throwable) {
            val msg = "LLM error: ${t.message ?: t::class.simpleName}"
            repo.appendMessage(conversationId, Role.ASSISTANT, msg)
            emit(AgentEvent.AssistantMessage(msg))
            emit(AgentEvent.Completed)
            return@flow
        }

        val final = acc.toString().ifBlank { "(empty)" }
        repo.appendMessage(conversationId, Role.ASSISTANT, final)

        emit(AgentEvent.AssistantMessage(final))
        emit(AgentEvent.Completed)
    }

    override fun history(): List<ChatMessage> {
        val msgs = repo.loadMessages(conversationId)
        return msgs.map {
            ChatMessage(
                role = Role.valueOf(it.role),
                content = it.content
            )
        }
    }
}
