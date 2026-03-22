package org.ivangelov.agent.memory.service

import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.memory.core.*
import java.nio.charset.StandardCharsets
import java.util.UUID

class MemoryService(
    private val embed: EmbeddingClient,
    private val store: MemoryStore,
    private val policy: MemoryPolicy = MemoryPolicy.Default
) {
    suspend fun debugCountPoints(): Int = store.countPoints()

    suspend fun retrieveForQuery(
        tenantId: String,
        conversationId: String,
        scope: MemoryScope,
        projectId: String?,
        query: String,
        topK: Int = 8,
        minScore: Double = 0.55
    ): List<RetrievedMemory> {
        if (query.isBlank()) return emptyList()

        val qVec = embed.embed(query)
        val hits = store.searchWithVector(
            tenantId = tenantId,
            scope = scope,
            projectId = projectId,
            vector = qVec,
            topK = topK * 3
        )

        val architectureLike = query.lowercase().let {
            "architektur" in it ||
                    "architecture" in it ||
                    "struktur" in it ||
                    "aufbau" in it ||
                    "komponenten" in it ||
                    "schichten" in it ||
                    "datenfluss" in it ||
                    "überblick" in it ||
                    "ueberblick" in it
        }

        val rankedHits = if (architectureLike) {
            val projectInfoHits = hits
                .filter { it.type == MemoryType.PROJECT_INFO }
                .sortedByDescending { it.score }

            if (projectInfoHits.isNotEmpty()) {
                projectInfoHits
            } else {
                hits.sortedByDescending { it.score }
            }
        } else {
            hits.sortedByDescending { it.score }
        }

        println(
            "MEM_SEARCH hits=${hits.size} topScores=" +
                    rankedHits.take(5).joinToString { "${it.type}:${"%.3f".format(it.score)}" }
        )

        val finalHits = if (architectureLike) {
            rankedHits
                .filter { it.type == MemoryType.PROJECT_INFO || it.score >= minScore }
                .take(topK)
        } else {
            rankedHits
                .filter { it.score >= minScore }
                .take(topK)
        }

        finalHits.forEachIndexed { i, hit ->
            println("MEM_FINAL_HIT_$i type=${hit.type} score=${"%.3f".format(hit.score)} text=${hit.text.take(140)}")
        }

        return finalHits.map {
            RetrievedMemory(
                text = it.text.trim().take(350),
                score = it.score,
                scope = it.scope,
                projectId = it.projectId,
                ts = it.ts
            )
        }
    }

    suspend fun maybeStoreTurn(
        tenantId: String,
        conversationId: String,
        scope: MemoryScope,
        projectId: String?,
        role: ChatMessage.Role,
        text: String
    ) {
        val decision = policy.decideStore(role, text)
        if (!decision.store) return

        val normalized = decision.normalizedText.ifBlank { policy.normalize(text) }
        val embedding = embed.embed(normalized)

        val id = when (decision.type) {
            MemoryType.FACT, MemoryType.PROJECT_INFO, MemoryType.PREFERENCE ->
                stableId(tenantId, scope, projectId, decision.type, normalized)
            else -> UUID.randomUUID().toString()
        }

        val item = MemoryItem(
            id = id,
            tenantId = tenantId,
            scope = scope,
            projectId = projectId,
            conversationId = conversationId,
            turnId = UUID.randomUUID().toString(),
            type = decision.type,
            ts = System.currentTimeMillis(),
            text = normalized
        )

        store.upsert(item, embedding)
    }

    suspend fun storeIndexText(
        tenantId: String,
        conversationId: String,
        scope: MemoryScope,
        projectId: String?,
        type: MemoryType = MemoryType.PROJECT_INFO,
        text: String
    ): Boolean {
        return runCatching {
            val normalized = policy.normalize(text)
            val embedding = embed.embed(normalized)

            val id = stableId(
                tenantId = tenantId,
                scope = scope,
                projectId = projectId,
                type = type,
                text = normalized
            )

            val item = MemoryItem(
                id = id,
                tenantId = tenantId,
                scope = scope,
                projectId = projectId,
                conversationId = conversationId,
                turnId = UUID.randomUUID().toString(),
                type = type,
                ts = System.currentTimeMillis(),
                text = normalized
            )

            store.upsert(item, embedding)
            true
        }.getOrElse { e ->
            println("INDEX_UPSERT_FAILED: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    private fun stableId(
        tenantId: String,
        scope: MemoryScope,
        projectId: String?,
        type: MemoryType,
        text: String
    ): String {
        val key = "tenant=$tenantId|scope=${scope.name}|pid=${projectId ?: "-"}|type=${type.name}|text=${text.lowercase()}"
        return UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    suspend fun deleteConversationMemory(
        tenantId: String,
        conversationId: String
    ): Boolean {
        return store.deleteConversationMemory(tenantId, conversationId)
    }

    suspend fun deleteProjectMemory(
        tenantId: String,
        projectId: String
    ): Boolean {
        return store.deleteProjectMemory(tenantId, projectId)
    }

    suspend fun deleteTenantMemory(
        tenantId: String
    ): Boolean {
        return store.deleteTenantMemory(tenantId)
    }
}