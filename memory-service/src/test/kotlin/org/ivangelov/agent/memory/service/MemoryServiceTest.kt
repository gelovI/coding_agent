package org.ivangelov.agent.memory.service

import kotlinx.coroutines.runBlocking
import org.ivangelov.agent.memory.core.EmbeddingClient
import org.ivangelov.agent.memory.core.MemoryHit
import org.ivangelov.agent.memory.core.MemoryItem
import org.ivangelov.agent.memory.core.MemoryScope
import org.ivangelov.agent.memory.core.MemoryStore
import org.ivangelov.agent.memory.core.MemoryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryServiceTest {

    @Test
    fun projectRetrievalPrioritizesExplicitlyMentionedFile() = runBlocking {
        val store = FakeMemoryStore(
            hits = listOf(
                hit(
                    id = "general-project-info",
                    score = 0.99,
                    type = MemoryType.PROJECT_INFO,
                    text = """
                        TYPE:CODE
                        FILE:src/main/kotlin/OtherFile.kt
                        ---
                        class OtherFile
                    """.trimIndent()
                ),
                hit(
                    id = "target-file",
                    score = 0.20,
                    type = MemoryType.PROJECT_INFO,
                    text = """
                        TYPE:CODE
                        FILE:src/main/kotlin/TargetFile.kt
                        ---
                        class TargetFile
                    """.trimIndent()
                ),
                hit(
                    id = "project-decision",
                    score = 0.95,
                    type = MemoryType.PROJECT_DECISION,
                    text = "Projektentscheidung: Keep the architecture simple."
                )
            )
        )

        val service = MemoryService(
            embed = FakeEmbeddingClient,
            store = store
        )

        val result = service.retrieveForQuery(
            tenantId = "tenant",
            conversationId = "conversation",
            scope = MemoryScope.PROJECT,
            projectId = "project",
            query = "Analysiere TargetFile.kt und gib Verbesserungsvorschlaege.",
            topK = 2
        )

        assertEquals(2, result.size)
        assertTrue(result.first().text.contains("TargetFile.kt"))
        assertTrue(result[1].text.contains("OtherFile.kt") || result[1].text.contains("Projektentscheidung"))
    }

    @Test
    fun projectRetrievalFallsBackToProjectContextWhenNoMentionedFileMatches() = runBlocking {
        val store = FakeMemoryStore(
            hits = listOf(
                hit(
                    id = "project-note",
                    score = 0.60,
                    type = MemoryType.PROJECT_NOTE,
                    text = "Projektdatei erfolgreich geaendert: SomeOtherFile.kt"
                ),
                hit(
                    id = "tool-result",
                    score = 0.95,
                    type = MemoryType.TOOL_RESULT,
                    text = "[read_file] output"
                )
            )
        )

        val service = MemoryService(
            embed = FakeEmbeddingClient,
            store = store
        )

        val result = service.retrieveForQuery(
            tenantId = "tenant",
            conversationId = "conversation",
            scope = MemoryScope.PROJECT,
            projectId = "project",
            query = "Analysiere MissingFile.kt",
            topK = 1
        )

        assertEquals("Projektdatei erfolgreich geaendert: SomeOtherFile.kt", result.single().text)
    }

    private fun hit(
        id: String,
        score: Double,
        type: MemoryType,
        text: String
    ): MemoryHit =
        MemoryHit(
            id = id,
            score = score,
            text = text,
            scope = MemoryScope.PROJECT,
            projectId = "project",
            turnId = "turn-$id",
            ts = 1L,
            type = type
        )
}

private object FakeEmbeddingClient : EmbeddingClient {
    override suspend fun embed(text: String): FloatArray = floatArrayOf(1.0f, 0.0f)
}

private class FakeMemoryStore(
    private val hits: List<MemoryHit>
) : MemoryStore {
    override suspend fun upsert(item: MemoryItem, embedding: FloatArray) = Unit

    override suspend fun search(
        tenantId: String,
        query: String,
        scope: MemoryScope,
        projectId: String?,
        topK: Int
    ): List<MemoryHit> = hits.take(topK)

    override suspend fun searchWithVector(
        tenantId: String,
        scope: MemoryScope,
        projectId: String?,
        vector: FloatArray,
        topK: Int
    ): List<MemoryHit> = hits.take(topK)

    override suspend fun countPoints(): Int = hits.size

    override suspend fun deleteConversationMemory(
        tenantId: String,
        conversationId: String
    ): Boolean = true

    override suspend fun deleteProjectMemory(
        tenantId: String,
        projectId: String
    ): Boolean = true

    override suspend fun deleteTenantMemory(tenantId: String): Boolean = true
}
