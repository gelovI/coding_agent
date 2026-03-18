package org.ivangelov.agent.tools.fs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    private val root: Path
) : Tool {

    override val name: String = "write_files"

    override suspend fun execute(call: ToolCall): ToolResult {
        val files = call.argsJson["files"] as? JsonArray
            ?: return ToolResult(name, ok = false, content = "Missing argument: files")

        if (files.isEmpty()) {
            return ToolResult(name, ok = false, content = "Argument 'files' must not be empty")
        }

        return try {
            val written = mutableListOf<String>()

            for ((index, entry) in files.withIndex()) {
                val obj: JsonObject = entry.jsonObject

                val rel = obj["path"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult(
                        name,
                        ok = false,
                        content = "Each file requires path (missing at index $index)"
                    )

                val content = obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult(
                        name,
                        ok = false,
                        content = "Each file requires content (missing at index $index)"
                    )

                if (rel.isBlank()) {
                    return ToolResult(
                        name,
                        ok = false,
                        content = "Invalid path: blank (index $index)"
                    )
                }

                val target = resolveSafe(rel)

                target.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
                FileSystem.SYSTEM.write(target) { writeUtf8(content) }

                written += rel
            }

            ToolResult(
                name = name,
                ok = true,
                content = buildString {
                    appendLine("Wrote files:")
                    written.forEach { appendLine(it) }
                }.trim()
            )
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "write_files failed: ${e.message}")
        }
    }

    private fun resolveSafe(relative: String): Path {
        require(!relative.startsWith("/")) { "Absolute paths are not allowed: $relative" }
        require(!relative.contains(":\\") && !relative.contains(":/")) {
            "Absolute paths are not allowed: $relative"
        }
        require(!relative.contains("..")) { "Path traversal not allowed: $relative" }

        val relPath = relative.toPath(normalize = true)
        val normalizedRoot = root.normalized()
        val resolved = (normalizedRoot / relPath).normalized()

        require(resolved.toString().startsWith(normalizedRoot.toString())) {
            "Path escapes project root: $relative"
        }

        return resolved
    }
}