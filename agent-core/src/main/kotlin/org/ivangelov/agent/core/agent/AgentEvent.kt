package org.ivangelov.agent.core.agent

sealed interface AgentEvent {

    data class UserMessageStored(
        val conversationId: String,
        val text: String
    ) : AgentEvent

    data class ToolExecuted(
        val toolName: String,
        val output: String
    ) : AgentEvent

    data class AssistantMessage(
        val text: String
    ) : AgentEvent

    data class StreamDelta(
        val text: String
    ) : AgentEvent

    data object Completed : AgentEvent
}