package org.ivangelov.agent.tools.code

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.core.model.ToolResult
import org.ivangelov.agent.memory.core.MemoryScope
import org.ivangelov.agent.memory.core.MemoryType
import org.ivangelov.agent.memory.service.MemoryService
import org.ivangelov.agent.tools.Tool
import org.ivangelov.agent.tools.code.indexing.CodeChunker
import org.ivangelov.agent.tools.code.indexing.ProjectFileFilter
import org.ivangelov.agent.tools.fs.ExecutionGuard

class IndexProjectTool(
    private val root: Path,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val memory: MemoryService,
    private val tenantId: String,
    private val conversationId: String,
    private val projectId: String?,
    private val guard: ExecutionGuard = ExecutionGuard(root)
) : Tool {

    override val name: String = "index_project"

    override suspend fun execute(call: ToolCall): ToolResult {
        val options = parseOptions(call)
        val start = guard.resolveInsideRoot(options.path)

        if (!fs.exists(start)) {
            return ToolResult(name, ok = false, content = "Index path does not exist: ${options.path}")
        }

        if (!fs.metadata(start).isDirectory) {
            return ToolResult(name, ok = false, content = "Index path is not a directory: ${options.path}")
        }

        val candidateFiles = fs.listRecursively(start)
            .filter { path ->
                ProjectFileFilter.shouldIndex(
                    path = path,
                    fs = fs,
                    includeExtensions = options.includeExtensions
                )
            }
            .take(options.maxFiles)
            .toList()

        var fileCount = 0
        var skippedCount = 0
        var chunkCount = 0

        val memConversationId =
            if (projectId != null) "project-index:$projectId" else conversationId

        for (path in candidateFiles) {
            val content = try {
                fs.read(path) { readUtf8() }
            } catch (_: Exception) {
                skippedCount++
                continue
            }

            if (content.isBlank()) {
                skippedCount++
                continue
            }

            fileCount++

            val relativePath = makeRelativeToRoot(path)
            val chunks = CodeChunker.chunkCode(
                filePath = relativePath,
                content = content
            )

            for (chunk in chunks) {
                val payload = buildString {
                    appendLine("TYPE:CODE")
                    appendLine("FILE:${chunk.filePath}")
                    appendLine("CHUNK:${chunk.index + 1}/${chunk.total}")
                    appendLine("---")
                    append(chunk.content)
                }

                val ok = memory.storeIndexText(
                    tenantId = tenantId,
                    conversationId = memConversationId,
                    scope = MemoryScope.PROJECT,
                    projectId = projectId,
                    type = MemoryType.PROJECT_INFO,
                    text = payload
                )

                if (ok) chunkCount++
            }
        }

        val totalCount = runCatching { memory.debugCountPoints() }.getOrNull()

        return ToolResult(
            name = name,
            ok = true,
            content = buildString {
                append("Indexierung abgeschlossen: ")
                append("$fileCount Dateien, ")
                append("$chunkCount Chunks")
                append(", $skippedCount übersprungen")
                append(".")
                append(" Startpfad: ${options.path}.")
                append(" Extensions: ${options.includeExtensions.joinToString(",")}.")
                append(" Limit: ${options.maxFiles}.")
                if (totalCount != null) {
                    append(" Qdrant-Punkte gesamt: $totalCount.")
                }
            },
            meta = mapOf(
                "files" to fileCount.toString(),
                "chunks" to chunkCount.toString(),
                "skipped" to skippedCount.toString(),
                "path" to options.path,
                "max_files" to options.maxFiles.toString(),
                "include_ext" to options.includeExtensions.joinToString(",")
            )
        )
    }

    private fun parseOptions(call: ToolCall): IndexOptions {
        val path = call.argsJson["path"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "."

        val maxFiles = call.argsJson["max_files"]
            ?.jsonPrimitive
            ?.intOrNull
            ?.coerceIn(1, 2_000)
            ?: 500

        val includeExtensions = parseIncludeExtensions(call)
            .ifEmpty { ProjectFileFilter.defaultIncludedExtensions }

        return IndexOptions(
            path = path,
            maxFiles = maxFiles,
            includeExtensions = includeExtensions
        )
    }

    private fun parseIncludeExtensions(call: ToolCall): Set<String> {
        val raw = call.argsJson["include_ext"] ?: return emptySet()

        val values = when (raw) {
            is JsonArray -> raw.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> raw.jsonPrimitive.contentOrNull
                ?.split(",", " ", ";")
                .orEmpty()
        }

        return values
            .map { it.trim().lowercase().removePrefix(".") }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun makeRelativeToRoot(path: Path): String {
        val rootSegments = root.normalized().segments
        val pathSegments = path.normalized().segments

        return pathSegments
            .drop(rootSegments.size)
            .joinToString("/")
    }

    private data class IndexOptions(
        val path: String,
        val maxFiles: Int,
        val includeExtensions: Set<String>
    )

}
