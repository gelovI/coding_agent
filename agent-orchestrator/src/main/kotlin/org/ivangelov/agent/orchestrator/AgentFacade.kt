package org.ivangelov.agent.orchestrator

import kotlinx.coroutines.flow.Flow
import org.ivangelov.agent.core.agent.AgentEvent
import org.ivangelov.agent.core.model.ChatMessage

interface AgentFacade {
    fun send(userText: String): Flow<AgentEvent>
    fun history(): List<ChatMessage>
}
