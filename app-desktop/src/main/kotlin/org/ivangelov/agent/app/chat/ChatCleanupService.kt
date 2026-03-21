package org.ivangelov.agent.app.chat

import org.ivangelov.agent.db.ChatRepository
import org.ivangelov.agent.orchestrator.memory.MemoryCoordinator

class ChatCleanupService(
    private val chatRepo: ChatRepository,
    private val memoryCoordinator: MemoryCoordinator
) {
    suspend fun clearChatOnly(conversationId: String) {
        chatRepo.clearMessages(conversationId)
    }

    suspend fun clearChatAndMemory(
        tenantId: String,
        conversationId: String
    ): Boolean {
        chatRepo.clearMessages(conversationId)
        return memoryCoordinator.clearConversationMemory(
            tenantId = tenantId,
            conversationId = conversationId
        )
    }

    suspend fun deleteConversationCompletely(
        tenantId: String,
        conversationId: String
    ): Boolean {
        chatRepo.deleteConversation(conversationId)
        return memoryCoordinator.clearConversationMemory(
            tenantId = tenantId,
            conversationId = conversationId
        )
    }

    suspend fun deleteProjectChatsAndMemory(
        tenantId: String,
        projectId: String
    ): Boolean {
        chatRepo.deleteProjectChats(tenantId, projectId)
        return memoryCoordinator.clearProjectMemory(tenantId, projectId)
    }

    suspend fun deleteAllTenantData(
        tenantId: String
    ): Boolean {
        chatRepo.deleteAllTenantChats(tenantId)
        return memoryCoordinator.clearTenantMemory(tenantId)
    }
}