package org.ivangelov.agent.orchestrator.memory

import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.memory.core.MemoryScope
import org.ivangelov.agent.memory.service.MemoryService

class DefaultMemoryCoordinator(
    private val memory: MemoryService
) : MemoryCoordinator {

    override fun resolveContext(
        conversationId: String,
        projectId: String?
    ): MemoryContext {
        val scope = if (projectId != null) MemoryScope.PROJECT else MemoryScope.GLOBAL
        val retrievalConversationId =
            if (scope == MemoryScope.PROJECT && projectId != null) "project-index:$projectId"
            else conversationId

        return MemoryContext(
            scope = scope,
            retrievalConversationId = retrievalConversationId
        )
    }

    override suspend fun storeUserTurn(
        tenantId: String,
        conversationId: String,
        projectId: String?,
        text: String
    ) {
        val ctx = resolveContext(conversationId, projectId)
        runCatching {
            memory.maybeStoreTurn(
                tenantId = tenantId,
                conversationId = conversationId,
                scope = ctx.scope,
                projectId = projectId,
                role = ChatMessage.Role.USER,
                text = text
            )
        }
    }

    override suspend fun storeAssistantTurn(
        tenantId: String,
        conversationId: String,
        projectId: String?,
        text: String
    ) {
        val ctx = resolveContext(conversationId, projectId)
        runCatching {
            memory.maybeStoreTurn(
                tenantId = tenantId,
                conversationId = conversationId,
                scope = ctx.scope,
                projectId = projectId,
                role = ChatMessage.Role.ASSISTANT,
                text = text
            )
        }
    }

    override suspend fun retrieveForKnowledge(
        tenantId: String,
        conversationId: String,
        projectId: String?,
        query: String,
        topK: Int
    ): List<ChatMessage> {
        val ctx = resolveContext(conversationId, projectId)

        var retrieved = runCatching {
            memory.retrieveForQuery(
                tenantId = tenantId,
                conversationId = ctx.retrievalConversationId,
                scope = ctx.scope,
                projectId = projectId,
                query = query,
                topK = topK
            )
        }.getOrElse { e ->
            println("MEMORY_RETRIEVE_ERROR: ${e::class.simpleName}: ${e.message}")
            throw e
        }

        if (retrieved.isEmpty()) {
            retrieved = runCatching {
                memory.retrieveForQuery(
                    tenantId = tenantId,
                    conversationId = ctx.retrievalConversationId,
                    scope = ctx.scope,
                    projectId = projectId,
                    query = "TYPE:CODE FILE:",
                    topK = topK
                )
            }.getOrDefault(emptyList())
        }

        return retrieved.map {
            ChatMessage(ChatMessage.Role.SYSTEM, it.text)
        }
    }

    override suspend fun retrieveForToolLoop(
        tenantId: String,
        conversationId: String,
        projectId: String?,
        query: String,
        topK: Int
    ): List<ChatMessage> {
        val ctx = resolveContext(conversationId, projectId)

        return runCatching {
            memory.retrieveForQuery(
                tenantId = tenantId,
                conversationId = ctx.retrievalConversationId,
                scope = ctx.scope,
                projectId = projectId,
                query = query,
                topK = topK
            )
        }.getOrDefault(emptyList()).map {
            ChatMessage(ChatMessage.Role.SYSTEM, it.text)
        }
    }

    override suspend fun clearConversationMemory(
        tenantId: String,
        conversationId: String
    ): Boolean {
        return runCatching {
            memory.deleteConversationMemory(tenantId, conversationId)
        }.getOrDefault(false)
    }

    override suspend fun clearProjectMemory(
        tenantId: String,
        projectId: String
    ): Boolean {
        return runCatching {
            memory.deleteProjectMemory(tenantId, projectId)
        }.getOrDefault(false)
    }

    override suspend fun clearTenantMemory(
        tenantId: String
    ): Boolean {
        return runCatching {
            memory.deleteTenantMemory(tenantId)
        }.getOrDefault(false)
    }
}