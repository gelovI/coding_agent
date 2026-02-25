package org.ivangelov.agent.memory.core

interface MemoryWriter {
    suspend fun upsert(item: MemoryItem, embedding: FloatArray)
}

interface MemoryRetriever {
    suspend fun search(
        tenantId: String,
        query: String,
        scope: MemoryScope,
        projectId: String?,
        topK: Int
    ): List<MemoryHit>
}