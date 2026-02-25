package org.ivangelov.agent.orchestrator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    override fun send(userText: String): Flow<String> = flow {
        repo.appendMessage(conversationId, Role.USER, userText)

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
                emit(delta)
            }
        } catch (t: Throwable) {
            val msg = "LLM error: ${t.message ?: t::class.simpleName}"
            repo.appendMessage(conversationId, Role.ASSISTANT, msg)
            emit("\n$msg")
            return@flow
        }

        val final = acc.toString().ifBlank { "(empty)" }
        repo.appendMessage(conversationId, Role.ASSISTANT, final)
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
