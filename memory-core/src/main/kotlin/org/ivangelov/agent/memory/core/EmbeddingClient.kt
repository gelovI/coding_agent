package org.ivangelov.agent.memory.core

interface EmbeddingClient {
    suspend fun embed(text: String): FloatArray
}