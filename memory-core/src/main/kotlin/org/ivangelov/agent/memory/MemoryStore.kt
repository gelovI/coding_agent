package org.ivangelov.agent.memory

import kotlinx.datetime.Instant

data class MemoryItem(
    val id: String,
    val tenantId: String,
    val projectId: String?,
    val scope: Scope,
    val type: Type,
    val text: String,
    val embedding: FloatArray? = null,
    val tags: List<String> = emptyList(),
    val importance: Double = 0.3,
    val createdAt: Instant,
    val lastAccessedAt: Instant? = null
) {
    enum class Scope { USER, PROJECT, SESSION }
    enum class Type { FACT, PREFERENCE, CODE_SNIPPET, DECISION, ERROR, DOC }
}

data class MemoryQuery(
    val tenantId: String,
    val projectId: String? = null,
    val types: Set<MemoryItem.Type> = emptySet(),
    val scope: Set<MemoryItem.Scope> = emptySet(),
    val text: String,
    val k: Int = 8
)

data class MemoryHit(
    val item: MemoryItem,
    val score: Double
)

interface MemoryStore {
    suspend fun upsert(item: MemoryItem)
    suspend fun search(query: MemoryQuery): List<MemoryHit>
}
