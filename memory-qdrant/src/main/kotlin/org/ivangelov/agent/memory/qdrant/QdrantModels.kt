package org.ivangelov.agent.memory.qdrant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class QdrantCreateCollection(
    val vectors: Map<String, QdrantVectorParams>
)

@Serializable
data class QdrantVectorParams(
    val size: Int,
    val distance: String = "Cosine"
)

@Serializable
data class QdrantPointUpsert(
    val points: List<QdrantPoint>
)

@Serializable
data class QdrantPoint(
    val id: String,
    val vectors: Map<String, List<Float>>,
    val payload: JsonObject
)

@Serializable
data class QdrantNamedVector(
    val name: String,
    val vector: List<Float>
)

@Serializable
data class QdrantSearchRequest(
    val vector: QdrantNamedVector,
    val limit: Int,
    val with_payload: Boolean = true,
    val filter: QdrantFilter? = null
)

@Serializable
data class QdrantFilter(
    val must: List<QdrantCondition> = emptyList(),
    val should: List<QdrantCondition> = emptyList()
)

@Serializable
data class QdrantCondition(
    val key: String,
    val match: QdrantMatch? = null
)

@Serializable
data class QdrantMatch(
    val value: JsonElement
)

@Serializable
data class QdrantSearchResponse(
    val time: Double? = null,
    val status: JsonElement? = null,
    val result: List<QdrantScoredPoint>? = null
)

@Serializable
data class QdrantScoredPoint(
    val id: JsonElement,
    val score: Double,
    val payload: JsonObject? = null
)

@Serializable
data class QdrantCountRequest(
    val exact: Boolean = true
)

@Serializable
data class QdrantCountResponse(
    val result: QdrantCountResult
)

@Serializable
data class QdrantCountResult(
    val count: Int
)

@Serializable
data class QdrantGetPointsRequest(
    val ids: List<String>,
    val with_payload: Boolean = true,
    val with_vector: Boolean = false
)