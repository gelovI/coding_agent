package org.ivangelov.agent.memory.qdrant

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.ivangelov.agent.core.infrastructure.HttpClients
import org.ivangelov.agent.memory.core.*
import org.ivangelov.agent.memory.core.MemoryStore

class QdrantMemoryStore(
    private val http: HttpClient = HttpClients.qdrant,
    private val baseUrl: String = "http://127.0.0.1:6333",
    private val collection: String = "agent_memory"
) : MemoryStore {

    suspend fun ensureCollection(vectorSize: Int) {
        val check = http.get("$baseUrl/collections/$collection")
        if (check.status.value == 200) return

        println("Creating Qdrant collection: $collection at $baseUrl (dim=$vectorSize)")

        val createResp = http.put("$baseUrl/collections/$collection") {
            contentType(ContentType.Application.Json)
            setBody(buildCreateCollectionBody(vectorSize))
        }

        if (createResp.status.value !in 200..299) {
            val body = createResp.bodyAsText()
            throw IllegalArgumentException("Failed to create collection $collection: ${createResp.status}. Body=$body")
        }
    }

    override suspend fun upsert(item: MemoryItem, embedding: FloatArray) {
        ensureCollection(embedding.size)

        val scopeKey = when (item.scope) {
            MemoryScope.GLOBAL -> "GLOBAL"
            MemoryScope.PROJECT -> "PROJECT:${requireNotNull(item.projectId) { "projectId required for PROJECT scope" }}"
        }

        val payload = buildJsonObject {
            put("tenantId", item.tenantId)
            put("scope", item.scope.name)
            put("scopeKey", scopeKey)
            item.projectId?.let { put("projectId", it) }
            put("conversationId", item.conversationId)
            put("turnId", item.turnId)
            put("type", item.type.name)
            put("ts", item.ts)
            put("text", item.text)
        }

        val reqJson = buildJsonObject {
            putJsonArray("points") {
                add(
                    buildJsonObject {
                        put("id", item.id) // UUID string ok
                        putJsonObject("vectors") {
                            put("default", JsonArray(embedding.toList().map { JsonPrimitive(it) }))
                        }
                        put("payload", payload)
                    }
                )
            }
        }

        val resp = http.put("$baseUrl/collections/$collection/points?wait=true") {
            contentType(ContentType.Application.Json)
            setBody(reqJson)
        }

        val body = resp.bodyAsText()
        println("QDRANT_UPSERT_STATUS=${resp.status}")
        println("QDRANT_UPSERT_BODY=$body")

        require(resp.status.value in 200..299) {
            "Qdrant upsert failed: ${resp.status} body=$body"
        }
    }

    override suspend fun search(
        tenantId: String,
        query: String,
        scope: MemoryScope,
        projectId: String?,
        topK: Int
    ): List<MemoryHit> {
        error("Call searchWithVector(...) via MemoryService wrapper")
    }

    override suspend fun searchWithVector(
        tenantId: String,
        scope: MemoryScope,
        projectId: String?,
        vector: FloatArray,
        topK: Int
    ): List<MemoryHit> {
        ensureCollection(vector.size)

        val filter = buildScopeKeyFilter(tenantId, scope, projectId)

        val reqJson = buildJsonObject {
            putJsonObject("vector") {
                put("name", "default")
                putJsonArray("vector") {
                    vector.forEach { add(JsonPrimitive(it)) }
                }
            }

            put("limit", topK)

            // IMPORTANT: request payload explicitly
            put("with_payload", true)

            // optional: we don't need vectors back
            put("with_vector", false)

            put("filter", Json.encodeToJsonElement(filter))
        }

        val httpResp = http.post("$baseUrl/collections/$collection/points/search") {
            contentType(ContentType.Application.Json)
            setBody(reqJson)
        }

        val raw = httpResp.bodyAsText()
        println("QDRANT_SEARCH_STATUS=${httpResp.status}")
        println("QDRANT_SEARCH_BODY=$raw")

        require(httpResp.status.value in 200..299) {
            "Qdrant search failed: $raw"
        }

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(raw).jsonObject
        val result = root["result"]?.jsonArray ?: JsonArray(emptyList())

        return result.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.toString() ?: return@mapNotNull null
            val score = obj["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0

            val payload = obj["payload"]?.jsonObject ?: return@mapNotNull null

            val text = payload["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val scopeStr = payload["scope"]?.jsonPrimitive?.contentOrNull ?: "GLOBAL"
            val pid = payload["projectId"]?.jsonPrimitive?.contentOrNull
            val turnId = payload["turnId"]?.jsonPrimitive?.contentOrNull ?: ""
            val ts = payload["ts"]?.jsonPrimitive?.longOrNull ?: 0L

            MemoryHit(
                id = id.trim('"'),
                score = score,
                text = text,
                scope = runCatching { MemoryScope.valueOf(scopeStr) }.getOrDefault(MemoryScope.GLOBAL),
                projectId = pid,
                turnId = turnId,
                ts = ts
            )
        }.sortedByDescending { it.score }
    }

    private fun buildScopeKeyFilter(
        tenantId: String,
        scope: MemoryScope,
        projectId: String?
    ): QdrantFilter {
        val must = mutableListOf(
            QdrantCondition(key = "tenantId", match = QdrantMatch(JsonPrimitive(tenantId)))
        )

        val scopeKey = when (scope) {
            MemoryScope.GLOBAL -> "GLOBAL"
            MemoryScope.PROJECT -> "PROJECT:${requireNotNull(projectId) { "projectId required" }}"
        }

        must += QdrantCondition(key = "scopeKey", match = QdrantMatch(JsonPrimitive(scopeKey)))

        return QdrantFilter(must = must)
    }

    private fun buildCreateCollectionBody(vectorSize: Int) = buildJsonObject {
        putJsonObject("vectors") {
            putJsonObject("default") {
                put("size", vectorSize)
                put("distance", "Cosine")
            }
        }
    }

    override suspend fun countPoints(): Int {
        val resp: QdrantCountResponse = http.post("$baseUrl/collections/$collection/points/count") {
            contentType(ContentType.Application.Json)
            setBody(QdrantCountRequest(exact = true))
        }.body()
        return resp.result.count
    }

    suspend fun deleteByFilter(filter: QdrantFilter): Boolean {
        val reqJson = buildJsonObject {
            put("filter", Json.encodeToJsonElement(filter))
        }

        val resp = http.post("$baseUrl/collections/$collection/points/delete?wait=true") {
            contentType(ContentType.Application.Json)
            setBody(reqJson)
        }

        val body = resp.bodyAsText()
        println("QDRANT_DELETE_STATUS=${resp.status}")
        println("QDRANT_DELETE_BODY=$body")

        return resp.status.value in 200..299
    }

    @Override
    override suspend fun deleteConversationMemory(
        tenantId: String,
        conversationId: String
    ): Boolean {
        val filter = QdrantFilter(
            must = listOf(
                QdrantCondition(
                    key = "tenantId",
                    match = QdrantMatch(JsonPrimitive(tenantId))
                ),
                QdrantCondition(
                    key = "conversationId",
                    match = QdrantMatch(JsonPrimitive(conversationId))
                )
            )
        )

        return deleteByFilter(filter)
    }

    override suspend fun deleteProjectMemory(
        tenantId: String,
        projectId: String
    ): Boolean {
        val filter = QdrantFilter(
            must = listOf(
                QdrantCondition(
                    key = "tenantId",
                    match = QdrantMatch(JsonPrimitive(tenantId))
                ),
                QdrantCondition(
                    key = "projectId",
                    match = QdrantMatch(JsonPrimitive(projectId))
                )
            )
        )

        return deleteByFilter(filter)
    }

    override suspend fun deleteTenantMemory(
        tenantId: String
    ): Boolean {
        val filter = QdrantFilter(
            must = listOf(
                QdrantCondition(
                    key = "tenantId",
                    match = QdrantMatch(JsonPrimitive(tenantId))
                )
            )
        )

        return deleteByFilter(filter)
    }
}