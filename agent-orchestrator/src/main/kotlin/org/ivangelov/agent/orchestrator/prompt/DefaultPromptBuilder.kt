package org.ivangelov.agent.orchestrator.prompt

import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.core.model.ChatMessage.Role
import org.ivangelov.agent.orchestrator.SystemPrompts
import org.ivangelov.agent.tools.ToolRegistry

class DefaultPromptBuilder : PromptBuilder {

    override fun buildForToolLoop(
        history: List<ChatMessage>,
        tools: ToolRegistry
    ): List<ChatMessage> {
        val lastValidationError = history
            .asReversed()
            .firstOrNull { it.role == Role.TOOL && it.content.startsWith("[tool_validation_error]") }
            ?.content
            ?.removePrefix("[tool_validation_error]")
            ?.trim()

        val systemPrompt = buildString {
            appendLine(SystemPrompts.toolModeWithAvailableTools(tools))

            if (!lastValidationError.isNullOrBlank()) {
                appendLine()
                appendLine("CORRECTION NOTICE:")
                appendLine("The previous tool plan was invalid.")
                appendLine("Validation error:")
                appendLine(lastValidationError)
                appendLine()
                appendLine("You must correct the tool arguments.")
                appendLine("Do not repeat the same invalid tool call.")
                appendLine("Return valid JSON only.")
            }
        }.trim()

        val relevantHistory = history
            .filterNot { it.role == Role.SYSTEM }
            .takeLast(4)

        return listOf(ChatMessage(Role.SYSTEM, systemPrompt)) + relevantHistory
    }

    override fun buildForKnowledge(
        history: List<ChatMessage>
    ): List<ChatMessage> {
        val sys = ChatMessage(Role.SYSTEM, SystemPrompts.CHAT_MODE)
        val cleaned = history.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
        return listOf(sys) + cleaned
    }
}