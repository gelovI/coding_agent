package org.ivangelov.agent.tools.fs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.core.model.ToolResult
import org.ivangelov.agent.tools.Tool

class WriteFilesTool(
    private val root: Path,
    private val guard: ExecutionGuard = ExecutionGuard(root)
) : Tool {

    override val name: String = "write_files"

    override suspend fun execute(call: ToolCall): ToolResult {
        val files = call.argsJson["files"] as? JsonArray
            ?: return ToolResult(name, ok = false, content = "Missing argument: files")

        if (files.isEmpty()) {
            return ToolResult(name, ok = false, content = "Argument 'files' must not be empty")
        }

        val fileSpecs = try {
            files.mapIndexed { index, entry ->
                val obj = entry.jsonObject

                val rel = obj["path"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Each file requires path (missing at index $index)")

                val content = obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Each file requires content (missing at index $index)")

                FileWriteSpec(path = rel, content = content)
            }
        } catch (e: IllegalArgumentException) {
            return ToolResult(name, ok = false, content = e.message ?: "Invalid files argument")
        }

        return try {
            guard.validateWriteBatch(fileSpecs)

            val backups = mutableListOf<Pair<Path, String?>>()
            val written = mutableListOf<String>()

            try {
                for (file in fileSpecs) {
                    val target = resolveSafe(file.path)

                    val previousContent =
                        if (FileSystem.SYSTEM.exists(target)) {
                            FileSystem.SYSTEM.read(target) { readUtf8() }
                        } else {
                            null
                        }

                    backups += target to previousContent

                    target.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
                    FileSystem.SYSTEM.write(target) {
                        writeUtf8(file.content)
                    }

                    require(FileSystem.SYSTEM.exists(target)) {
                        "File was not created: ${file.path}"
                    }

                    written += file.path
                }
            } catch (e: Exception) {
                rollback(backups)
                throw e
            }

            ToolResult(
                name = name,
                ok = true,
                content = buildString {
                    appendLine("Wrote files:")
                    written.forEach { appendLine(it) }
                }.trim(),
                meta = mapOf(
                    "count" to written.size.toString(),
                    "paths" to written.joinToString(",")
                )
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(name, ok = false, content = "Execution guard blocked write_files: ${e.message}")
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "write_files failed: ${e.message}")
        }
    }

    private fun rollback(backups: List<Pair<Path, String?>>) {
        for ((path, previousContent) in backups.asReversed()) {
            try {
                if (previousContent == null) {
                    FileSystem.SYSTEM.delete(path, mustExist = false)
                } else {
                    path.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
                    FileSystem.SYSTEM.write(path) {
                        writeUtf8(previousContent)
                    }
                }
            } catch (_: Exception) {
                // Best-effort rollback
            }
        }
    }

    private fun resolveSafe(relative: String): Path {
        val relPath = relative.toPath(normalize = true)
        val normalizedRoot = root.normalized()
        val resolved = (normalizedRoot / relPath).normalized()

        require(resolved.toString().startsWith(normalizedRoot.toString())) {
            "Path escapes project root: $relative"
        }

        return resolved
    }
}