package org.ivangelov.agent.orchestrator

import kotlinx.coroutines.flow.Flow
import org.ivangelov.agent.core.model.ChatMessage

interface AgentFacade {
    fun send(userText: String): Flow<String>
    fun history(): List<ChatMessage>
}
