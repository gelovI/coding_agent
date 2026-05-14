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

    data class ApprovalRequired(
        val requestId: String,
        val summary: String,
        val toolCalls: List<PendingToolCall>
    ) : AgentEvent

    data class AssistantMessage(
        val text: String
    ) : AgentEvent

    data class StreamDelta(
        val text: String
    ) : AgentEvent

    data object Completed : AgentEvent
}

data class PendingToolCall(
    val toolName: String,
    val paths: List<String>,
    val argsPreview: String
)
