package org.ivangelov.agent.orchestrator.memory

import org.ivangelov.agent.memory.core.MemoryScope

data class MemoryContext(
    val scope: MemoryScope,
    val retrievalConversationId: String
)