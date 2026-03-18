package org.ivangelov.agent.orchestrator.prompt

import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.tools.ToolRegistry

interface PromptBuilder {
    fun buildForToolLoop(
        history: List<ChatMessage>,
        tools: ToolRegistry
    ): List<ChatMessage>

    fun buildForKnowledge(
        history: List<ChatMessage>
    ): List<ChatMessage>
}