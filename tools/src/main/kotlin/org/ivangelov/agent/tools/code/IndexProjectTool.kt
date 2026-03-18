package org.ivangelov.agent.tools.code

import okio.FileSystem
import okio.Path
import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.core.model.ToolResult
import org.ivangelov.agent.tools.Tool
import org.ivangelov.agent.memory.service.MemoryService
import org.ivangelov.agent.memory.core.MemoryScope
import org.ivangelov.agent.tools.code.indexing.CodeChunker
import org.ivangelov.agent.memory.core.MemoryType

class IndexProjectTool(
    private val root: Path,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val memory: MemoryService,
    private val tenantId: String,
    private val conversationId: String,
    private val projectId: String?
) : Tool {

    override val name: String = "index_project"

    override suspend fun execute(call: ToolCall): ToolResult {
        val kotlinFiles = fs.listRecursively(root)
            .filter { it.name.endsWith(".kt", ignoreCase = true) }
            .toList()

        var fileCount = 0
        var chunkCount = 0

        for (path in kotlinFiles) {
            val content = fs.read(path) { readUtf8() }
            if (content.isBlank()) continue

            fileCount++

            val chunks = CodeChunker.chunkCode(
                filePath = path.toString(),
                content = content
            )

            for (c in chunks) {
                // Soft-Metadata im Text (damit Retrieval später File/Chunk erkennen kann)
                val payload = buildString {
                    appendLine("TYPE:CODE")
                    appendLine("FILE:${c.filePath}")
                    appendLine("CHUNK:${c.index + 1}/${c.total}")
                    appendLine("---")
                    append(c.content)
                }

                // Index in PROJECT scope
                val memConversationId =
                    if (projectId != null) "project-index:$projectId" else conversationId

                println("INDEX_UPSERT start projectId=$projectId scope=PROJECT conv=$memConversationId")
                val ok = memory.storeIndexText(
                    tenantId = tenantId,
                    conversationId = memConversationId,
                    scope = MemoryScope.PROJECT,
                    projectId = projectId,
                    type = MemoryType.PROJECT_INFO,
                    text = payload
                )
                println("INDEX_UPSERT done")

                if (ok) chunkCount++
            }
        }

        val count = memory.debugCountPoints()
        println("QDRANT_COUNT_AFTER_INDEX=$count")

        return ToolResult(
            name = name,
            ok = true,
            content = "✅ Indexierung abgeschlossen: $fileCount Dateien, $chunkCount Chunks im PROJECT-Memory gespeichert."
        )
    }
}