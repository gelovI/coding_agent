package org.ivangelov.agent.orchestrator.edit

import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.core.model.ChatMessage.Role

object BlockRewritePromptFactory {

    fun buildMessages(
        userText: String,
        targetBlock: TargetBlock,
        resolvedPath: String
    ): List<ChatMessage> {
        return listOf(
            ChatMessage(
                Role.SYSTEM,
                """
                You are editing a Kotlin source block.
                Return ONLY the rewritten block text.
                
                Rules:
                - Preserve valid Kotlin syntax
                - Keep the same surrounding structure and purpose
                - Do not add explanations
                - Do not add markdown fences
                - Do not invent unrelated code
                - If the user asked for comments, add comments directly into this block
                """.trimIndent()
            ),
            ChatMessage(
                Role.USER,
                """
                File: $resolvedPath
                
                User request:
                $userText
                
                Original block:
                ${targetBlock.originalText}
                """.trimIndent()
            )
        )
    }
}