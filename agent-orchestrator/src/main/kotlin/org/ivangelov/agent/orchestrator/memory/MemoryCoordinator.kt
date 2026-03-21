package org.ivangelov.agent.orchestrator.memory

import org.ivangelov.agent.core.model.ChatMessage

interface MemoryCoordinator {
    fun resolveContext(
        conversationId: String,
        projectId: String?
    ): MemoryContext

    suspend fun storeUserTurn(
        tenantId: String,
        conversationId: String,
        projectId: String?,
        text: String
    )

    suspend fun storeAssistantTurn(
        tenantId: String,
        conversationId: String,
        projectId: String?,
        text: String
    )

    suspend fun retrieveForKnowledge(
        tenantId: String,
        conversationId: String,
        projectId: String?,
        query: String,
        topK: Int = 8
    ): List<ChatMessage>

    suspend fun retrieveForToolLoop(
        tenantId: String,
        conversationId: String,
        projectId: String?,
        query: String,
        topK: Int = 8
    ): List<ChatMessage>

    suspend fun clearConversationMemory(
        tenantId: String,
        conversationId: String
    ): Boolean

    suspend fun clearProjectMemory(
        tenantId: String,
        projectId: String
    ): Boolean

    suspend fun clearTenantMemory(
        tenantId: String
    ): Boolean
}