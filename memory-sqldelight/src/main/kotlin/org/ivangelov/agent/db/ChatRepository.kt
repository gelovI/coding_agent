package org.ivangelov.agent.db

import kotlinx.datetime.Clock
import org.ivangelov.agent.core.model.ChatMessage
import java.util.UUID

class ChatRepository(
    private val db: AgentDb,
    private val clock: Clock = Clock.System
) {
    fun listConversations(tenantId: String) =
        db.agentDbQueries.selectConversationsByTenant(tenantId).executeAsList()

    fun loadMessages(conversationId: String) =
        db.agentDbQueries.selectMessagesByConversation(conversationId).executeAsList()

    fun createConversation(
        tenantId: String,
        title: String = "New Chat",
        projectId: String?
    ): String {
        val now = clock.now().toEpochMilliseconds()
        val id = UUID.randomUUID().toString()

        // upsertConversation muss projectId speichern
        db.agentDbQueries.upsertConversation(
            id = id,
            tenantId = tenantId,
            title = title,
            projectId = projectId,
            createdAt = now,
            updatedAt = now
        )
        return id
    }

    fun appendMessage(conversationId: String, role: ChatMessage.Role, content: String) {
        println("DB_APPEND conv=$conversationId role=$role chars=${content.length}")
        val now = clock.now().toEpochMilliseconds()
        val id = UUID.randomUUID().toString()

        db.agentDbQueries.insertMessage(
            id = id,
            conversationId = conversationId,
            role = role.name,
            content = content,
            createdAt = now,
            tokenCount = null,
            metaJson = null
        )
        db.agentDbQueries.touchConversation(updatedAt = now, id = conversationId)
    }

    fun getLatestConversationId(tenantId: String, projectId: String?): String? {
        val hasProject = if (projectId == null) 0L else 1L

        return db.agentDbQueries
            .getLatestConversationId(
                tenantId = tenantId,
                hasProject = hasProject,
                projectId = projectId
            )
            .executeAsOneOrNull()
    }

    fun clearMessages(conversationId: String) {
        db.agentDbQueries.deleteMessagesByConversation(conversationId)
        val now = clock.now().toEpochMilliseconds()
        db.agentDbQueries.touchConversation(updatedAt = now, id = conversationId)
    }

    fun deleteConversation(conversationId: String) {
        db.agentDbQueries.deleteMessagesByConversation(conversationId)
        db.agentDbQueries.deleteConversationById(conversationId)
    }

    fun deleteProjectChats(tenantId: String, projectId: String) {
        db.agentDbQueries.deleteMessagesByProject(
            tenantId = tenantId,
            projectId = projectId
        )
        db.agentDbQueries.deleteConversationsByProject(
            tenantId = tenantId,
            projectId = projectId
        )
    }

    fun deleteAllTenantChats(tenantId: String) {
        db.agentDbQueries.deleteAllMessagesByTenant(tenantId)
        db.agentDbQueries.deleteAllConversationsByTenant(tenantId)
    }
}