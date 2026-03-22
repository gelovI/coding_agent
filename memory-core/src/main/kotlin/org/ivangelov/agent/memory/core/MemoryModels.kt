package org.ivangelov.agent.memory.core

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryScope { GLOBAL, PROJECT }

@Serializable
enum class MemoryType {
    FACT,
    PREFERENCE,
    PROJECT_INFO,
    TOOL_RESULT,
    TURN
}

@Serializable
data class MemoryItem(
    val id: String,              // point id in Qdrant (uuid)
    val tenantId: String,
    val scope: MemoryScope,
    val projectId: String?,       // null for GLOBAL
    val conversationId: String,
    val turnId: String,
    val type: MemoryType = MemoryType.TURN,
    val ts: Long,
    val text: String
)

@Serializable
data class MemoryHit(
    val id: String,
    val score: Double,
    val text: String,
    val scope: MemoryScope,
    val projectId: String?,
    val turnId: String,
    val ts: Long,
    val type: MemoryType
)