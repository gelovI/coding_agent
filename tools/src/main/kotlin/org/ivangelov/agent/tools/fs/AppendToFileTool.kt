package org.ivangelov.agent.tools.fs

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.core.model.ToolResult
import org.ivangelov.agent.tools.Tool

class AppendToFileTool(
    private val root: Path,
    private val guard: ExecutionGuard = ExecutionGuard(root)
) : Tool {

    override val name: String = "append_to_file"

    override suspend fun execute(call: ToolCall): ToolResult {
        val rel = call.argsJson.str("path")
            ?: return ToolResult(name, ok = false, content = "Missing argument: path")

        val content = call.argsJson.str("content")
            ?: return ToolResult(name, ok = false, content = "Missing argument: content")

        return try {
            guard.validateWrite(rel, content)

            val target = resolveSafe(rel)
            if (!FileSystem.SYSTEM.exists(target)) {
                return ToolResult(
                    name = name,
                    ok = false,
                    content = "File does not exist: $rel"
                )
            }

            val existing = FileSystem.SYSTEM.read(target) { readUtf8() }

            val combined = existing + content
            guard.validateWrite(rel, combined)

            target.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(target) {
                writeUtf8(combined)
            }

            ToolResult(
                name = name,
                ok = true,
                content = "Appended to $rel",
                meta = mapOf("path" to rel)
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(name, ok = false, content = "Execution guard blocked append_to_file: ${e.message}")
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "append_to_file failed: ${e.message}")
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