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

        val lastLlmViolation = history
            .asReversed()
            .firstOrNull { it.role == Role.TOOL && it.content.startsWith("[llm_violation]") }
            ?.content
            ?.removePrefix("[llm_violation]")
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

            if (!lastLlmViolation.isNullOrBlank()) {
                appendLine()
                appendLine("MODEL CORRECTION NOTICE:")
                appendLine(lastLlmViolation)
                appendLine("Return either valid tool_calls or a non-empty final reply.")
            }
        }.trim()

        return listOf(ChatMessage(Role.SYSTEM, systemPrompt)) + history
    }

    override fun buildForKnowledge(
        history: List<ChatMessage>
    ): List<ChatMessage> {
        val sys = ChatMessage(Role.SYSTEM, SystemPrompts.KNOWLEDGE_MODE)

        val cleaned = history
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT || it.role == Role.TOOL || it.role == Role.SYSTEM }
            .takeLast(6)

        return listOf(sys) + cleaned
    }
}