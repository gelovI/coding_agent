package org.ivangelov.agent.llm.ollama

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.ivangelov.agent.core.infrastructure.HttpClients

class OllamaEmbedClient(
    private val http: HttpClient = HttpClients.llm,
    private val baseUrl: String = "http://127.0.0.1:11434",
    private val model: String = "mxbai-embed-large:latest"
) {
    @Serializable
    private data class EmbedRequest(
        val model: String,
        val prompt: String
    )

    @Serializable
    private data class EmbedResponse(
        val embedding: List<Double>
    )

    suspend fun embed(text: String): FloatArray {
        val response = http.post("$baseUrl/api/embeddings") {
            contentType(ContentType.Application.Json)
            setBody(EmbedRequest(model = model, prompt = text))
        }

        if (!response.status.isSuccess()) {
            val raw = response.bodyAsText()
            error("Ollama embed failed: ${response.status.value} ${response.status.description} | $raw")
        }

        val resp: EmbedResponse = response.body()
        return resp.embedding.map { it.toFloat() }.toFloatArray()
    }
}