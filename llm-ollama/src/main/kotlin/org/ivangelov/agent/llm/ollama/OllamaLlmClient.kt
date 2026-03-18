package org.ivangelov.agent.llm.ollama

import io.ktor.client.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.core.ports.ContextPack
import org.ivangelov.agent.core.ports.LLMClient
import io.ktor.utils.io.readUTF8Line
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import org.ivangelov.agent.core.infrastructure.HttpClients
import org.ivangelov.agent.core.ports.LlmResponse
import org.ivangelov.agent.core.ports.LlmResponseFormat
import org.ivangelov.agent.core.ports.LlmToolCall
import org.ivangelov.agent.core.agent.AgentError
import org.ivangelov.agent.core.agent.AgentResult
import org.ivangelov.agent.core.agent.PlanParser
import org.ivangelov.agent.core.ports.ToolModeResponse

class OllamaLlmClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "qwen2.5-coder:14b",
    private val http: HttpClient = HttpClients.llm
) : LLMClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun complete(
        messages: List<ChatMessage>,
        context: ContextPack,
        format: LlmResponseFormat
    ): LlmResponse {
        val enriched = buildContextInjectedMessages(messages, context)

        val req = OllamaChatRequest(
            model = model,
            stream = false,
            messages = enriched.map { it.toOllama() },
            options = OllamaOptions(
                temperature = 0.0,
                topP = 0.1,
                numCtx = 4096
            ),
            format = when (format) {
                LlmResponseFormat.TEXT -> null
                LlmResponseFormat.JSON -> "json"
            }
        )

        val resp: HttpResponse = http.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        val body = resp.bodyAsText()

        if (!resp.status.isSuccess()) {
            throw IllegalStateException("Ollama HTTP ${resp.status.value}: $body")
        }

        val decoded = json.decodeFromString(OllamaChatResponse.serializer(), body)

        return LlmResponse(
            content = decoded.message?.content,
            thinking = decoded.message?.thinking,
            toolCalls = decoded.message?.toolCalls
                ?.mapNotNull { tc ->
                    val fn = tc.function ?: return@mapNotNull null
                    LlmToolCall(
                        name = fn.name,
                        args = fn.arguments ?: JsonObject(emptyMap())
                    )
                }
                .orEmpty(),
            raw = body
        )
    }

    override suspend fun completeToolMode(
        messages: List<ChatMessage>,
        context: ContextPack
    ): AgentResult<ToolModeResponse> {
        val enriched = buildContextInjectedMessages(messages, context)

        val req = OllamaChatRequest(
            model = model,
            stream = false,
            messages = enriched.map { it.toOllama() },
            options = OllamaOptions(
                temperature = 0.0,
                topP = 0.1,
                numCtx = 4096
            ),
            format = "json"
        )

        return runCatching {
            val resp: HttpResponse = http.post("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }

            val body = resp.bodyAsText()

            println("=== TOOL MODE HTTP STATUS === ${resp.status}")
            println("=== TOOL MODE RAW HTTP BODY ===")
            println(body)
            println("==============================")

            if (!resp.status.isSuccess()) {
                return AgentResult.Failure(
                    AgentError.LlmFailure("Ollama HTTP ${resp.status.value}: $body")
                )
            }

            val decoded = json.decodeFromString(OllamaChatResponse.serializer(), body)
            val payload = decoded.message

            val nativeToolCalls = payload?.toolCalls
                ?.mapNotNull { tc ->
                    val fn = tc.function ?: return@mapNotNull null
                    LlmToolCall(
                        name = fn.name,
                        args = fn.arguments ?: JsonObject(emptyMap())
                    )
                }
                .orEmpty()

            val contentText = payload?.content.orEmpty()
            val thinkingText = payload?.thinking.orEmpty()

            val parsedContentPlan =
                if (nativeToolCalls.isEmpty() && contentText.isNotBlank()) {
                    PlanParser.parseOrNull(contentText)
                } else {
                    null
                }

            val finalToolCalls = when {
                nativeToolCalls.isNotEmpty() -> nativeToolCalls
                parsedContentPlan != null -> parsedContentPlan.toolCalls.map {
                    LlmToolCall(
                        name = it.name,
                        args = it.args
                    )
                }
                else -> emptyList()
            }

            val finalReply = when {
                nativeToolCalls.isNotEmpty() -> payload?.content
                parsedContentPlan != null -> parsedContentPlan.reply
                else -> payload?.content
            }

            println("DEBUG_COMPLETE_TOOL_MODE_FINAL")
            println("finalToolCalls=$finalToolCalls")
            println("finalReply=$finalReply")

            AgentResult.Success(
                ToolModeResponse(
                    toolCalls = finalToolCalls,
                    reply = finalReply,
                    rawContent = payload?.content,
                    rawThinking = payload?.thinking
                )
            )
        }.getOrElse { e ->
            AgentResult.Failure(
                AgentError.LlmFailure("${e::class.simpleName}: ${e.message}")
            )
        }
    }

    override fun streamReply(messages: List<ChatMessage>, context: ContextPack): Flow<String> = flow {
        val enriched = buildContextInjectedMessages(messages, context)

        val req = OllamaChatRequest(
            model = model,
            stream = true,
            messages = enriched.map { it.toOllama() },
            options = OllamaOptions(
                temperature = 0.0,
                topP = 0.1,
                numCtx = 4096
            ),
            format = null
        )

        val resp: HttpResponse = http.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        if (!resp.status.isSuccess()) {
            val body = resp.bodyAsText()
            throw IllegalStateException("Ollama HTTP ${resp.status.value}: $body")
        }

        val channel: ByteReadChannel = resp.bodyAsChannel()

        while (true) {
            val line = channel.readUTF8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            val evt = json.decodeFromString(OllamaChatEvent.serializer(), trimmed)

            val delta = evt.message?.content.orEmpty()
            if (delta.isNotEmpty()) emit(delta)

            if (evt.done == true) break
        }
    }

    private fun buildContextInjectedMessages(
        messages: List<ChatMessage>,
        context: ContextPack
    ): List<ChatMessage> {
        // Minimal & effektiv: alles Kontextwissen in eine System-Nachricht oben
        val ctxText = buildString {
            if (!context.recentSummary.isNullOrBlank()) {
                appendLine("Conversation summary:")
                appendLine(context.recentSummary)
                appendLine()
            }
            if (context.pinned.isNotEmpty()) {
                appendLine("Pinned facts:")
                context.pinned.forEach { appendLine("- ${it.content}") }
                appendLine()
            }
            if (context.retrieved.isNotEmpty()) {
                appendLine("Relevant memory:")
                context.retrieved.forEach { appendLine("- ${it.content}") }
                appendLine()
            }
        }.trim()

        val systemCtx = if (ctxText.isNotBlank())
            listOf(ChatMessage(ChatMessage.Role.SYSTEM, ctxText))
        else emptyList()

        return systemCtx + messages
    }

    private fun ChatMessage.toOllama(): OllamaMessage =
        OllamaMessage(
            role = when (role) {
                ChatMessage.Role.SYSTEM -> "system"
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
                ChatMessage.Role.TOOL -> "system"
            },
            content = content
        )
}

@Serializable
data class OllamaChatRequest(
    val model: String,
    val stream: Boolean,
    val messages: List<OllamaMessage>,
    val options: OllamaOptions? = null,
    val format: String? = null
)

@Serializable
data class OllamaOptions(
    val temperature: Double = 0.1,
    @SerialName("top_p") val topP: Double = 0.9,
    @SerialName("num_ctx") val numCtx: Int = 4096
)

@Serializable
data class OllamaChatResponse(
    val model: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val message: OllamaMessagePayload? = null,
    val done: Boolean? = null
)


@Serializable
data class OllamaMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class OllamaChatEvent(
    val done: Boolean? = null,
    val message: OllamaDeltaMessage? = null,
)

@Serializable
private data class OllamaDeltaMessage(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class OllamaMessagePayload(
    val role: String? = null,
    val content: String? = null,
    val thinking: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null
)

@Serializable
data class OllamaToolCall(
    val id: String? = null,
    val function: OllamaFunctionCall? = null
)

@Serializable
data class OllamaFunctionCall(
    val name: String,
    val arguments: JsonObject? = null
)