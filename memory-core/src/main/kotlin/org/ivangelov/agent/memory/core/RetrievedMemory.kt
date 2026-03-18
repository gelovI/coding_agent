package org.ivangelov.agent.memory.core

data class RetrievedMemory(
    val text: String,
    val score: Double,
    val scope: MemoryScope,
    val projectId: String?,
    val ts: Long
)