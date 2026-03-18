package org.ivangelov.agent.memory.core

interface MemoryStore : MemoryWriter, MemoryRetriever {

    suspend fun searchWithVector(
        tenantId: String,
        scope: MemoryScope,
        projectId: String?,
        vector: FloatArray,
        topK: Int
    ): List<MemoryHit>

    suspend fun countPoints(): Int
}