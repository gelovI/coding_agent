package org.ivangelov.agent.tools.code

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Path.Companion.toPath
import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.memory.core.EmbeddingClient
import org.ivangelov.agent.memory.core.MemoryHit
import org.ivangelov.agent.memory.core.MemoryItem
import org.ivangelov.agent.memory.core.MemoryScope
import org.ivangelov.agent.memory.core.MemoryStore
import org.ivangelov.agent.memory.service.MemoryService
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive


class IndexProjectToolTest {

    @Test
    fun indexProjectRespectsMaxFiles() = runBlocking {
        val root = tempRoot()
        val store = FakeMemoryStore()
        val memory = MemoryService(
            embed = FakeEmbeddingClient(),
            store = store
        )

        try {
            File(root, "A.kt").writeText("class A")
            File(root, "B.kt").writeText("class B")
            File(root, "C.kt").writeText("class C")

            val tool = IndexProjectTool(
                root = root.absolutePath.toPath(),
                memory = memory,
                tenantId = "tenant",
                conversationId = "conversation",
                projectId = "project"
            )

            val result = tool.execute(
                ToolCall(
                    name = "index_project",
                    argsJson = buildJsonObject {
                        put("path", ".")
                        put("max_files", 2)
                        putJsonArray("include_ext") {
                            add(JsonPrimitive("kt"))
                        }
                    }
                )
            )

            assertTrue(result.ok, result.content)
            assertEquals("2", result.meta["files"])
            assertEquals(2, store.items.size)
            assertContains(result.content, "2 Dateien")
            assertContains(result.content, "Limit: 2")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun indexProjectRespectsIncludeExtensions() = runBlocking {
        val root = tempRoot()
        val store = FakeMemoryStore()
        val memory = MemoryService(
            embed = FakeEmbeddingClient(),
            store = store
        )

        try {
            File(root, "App.kt").writeText("class App")
            File(root, "README.md").writeText("# Docs")
            File(root, "notes.txt").writeText("do not index")

            val tool = IndexProjectTool(
                root = root.absolutePath.toPath(),
                memory = memory,
                tenantId = "tenant",
                conversationId = "conversation",
                projectId = "project"
            )

            val result = tool.execute(
                ToolCall(
                    name = "index_project",
                    argsJson = buildJsonObject {
                        put("path", ".")
                        put("max_files", 20)
                        put("include_ext", "kt,md")
                    }
                )
            )

            assertTrue(result.ok, result.content)
            assertEquals("2", result.meta["files"])

            val indexedText = store.items.joinToString("\n") { it.text }
            assertContains(indexedText, "FILE:App.kt")
            assertContains(indexedText, "FILE:README.md")
            assertFalse(indexedText.contains("notes.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun indexProjectIndexesOnlySelectedPath() = runBlocking {
        val root = tempRoot()
        val store = FakeMemoryStore()
        val memory = MemoryService(
            embed = FakeEmbeddingClient(),
            store = store
        )

        try {
            File(root, "app/src/main").mkdirs()
            File(root, "other").mkdirs()

            File(root, "app/src/main/App.kt").writeText("class App")
            File(root, "other/Other.kt").writeText("class Other")

            val tool = IndexProjectTool(
                root = root.absolutePath.toPath(),
                memory = memory,
                tenantId = "tenant",
                conversationId = "conversation",
                projectId = "project"
            )

            val result = tool.execute(
                ToolCall(
                    name = "index_project",
                    argsJson = buildJsonObject {
                        put("path", "app/src/main")
                        put("max_files", 20)
                        put("include_ext", "kt")
                    }
                )
            )

            assertTrue(result.ok, result.content)
            assertEquals("1", result.meta["files"])

            val indexedText = store.items.joinToString("\n") { it.text }
            assertContains(indexedText, "FILE:app/src/main/App.kt")
            assertFalse(indexedText.contains("Other.kt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun indexProjectSkipsBuildAndGitDirectories() = runBlocking {
        val root = tempRoot()
        val store = FakeMemoryStore()
        val memory = MemoryService(
            embed = FakeEmbeddingClient(),
            store = store
        )

        try {
            File(root, "src").mkdirs()
            File(root, "build").mkdirs()
            File(root, ".git").mkdirs()

            File(root, "src/App.kt").writeText("class App")
            File(root, "build/Generated.kt").writeText("class Generated")
            File(root, ".git/Hidden.kt").writeText("class Hidden")

            val tool = IndexProjectTool(
                root = root.absolutePath.toPath(),
                memory = memory,
                tenantId = "tenant",
                conversationId = "conversation",
                projectId = "project"
            )

            val result = tool.execute(
                ToolCall(
                    name = "index_project",
                    argsJson = buildJsonObject {
                        put("path", ".")
                        put("max_files", 20)
                        put("include_ext", "kt")
                    }
                )
            )

            assertTrue(result.ok, result.content)
            assertEquals("1", result.meta["files"])

            val indexedText = store.items.joinToString("\n") { it.text }
            assertContains(indexedText, "FILE:src/App.kt")
            assertFalse(indexedText.contains("Generated.kt"))
            assertFalse(indexedText.contains("Hidden.kt"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun tempRoot(): File =
        Files.createTempDirectory("coding-agent-index-project-test").toFile()

    private class FakeEmbeddingClient : EmbeddingClient {
        override suspend fun embed(text: String): FloatArray =
            floatArrayOf(0.1f, 0.2f, 0.3f)
    }

    private class FakeMemoryStore : MemoryStore {
        val items = mutableListOf<MemoryItem>()

        override suspend fun upsert(item: MemoryItem, embedding: FloatArray) {
            items += item
        }

        override suspend fun search(
            tenantId: String,
            query: String,
            scope: MemoryScope,
            projectId: String?,
            topK: Int
        ): List<MemoryHit> = emptyList()

        override suspend fun searchWithVector(
            tenantId: String,
            scope: MemoryScope,
            projectId: String?,
            vector: FloatArray,
            topK: Int
        ): List<MemoryHit> = emptyList()

        override suspend fun countPoints(): Int = items.size

        override suspend fun deleteConversationMemory(
            tenantId: String,
            conversationId: String
        ): Boolean = true

        override suspend fun deleteProjectMemory(
            tenantId: String,
            projectId: String
        ): Boolean = true

        override suspend fun deleteTenantMemory(
            tenantId: String
        ): Boolean = true
    }
}
