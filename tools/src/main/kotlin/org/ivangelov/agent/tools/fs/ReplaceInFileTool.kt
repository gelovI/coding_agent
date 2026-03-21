package org.ivangelov.agent.tools.fs

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.core.model.ToolResult
import org.ivangelov.agent.tools.Tool

class ReplaceInFileTool(
    private val root: Path,
    private val guard: ExecutionGuard = ExecutionGuard(root)
) : Tool {

    override val name: String = "replace_in_file"

    override suspend fun execute(call: ToolCall): ToolResult {
        val rel = call.argsJson.str("path")
            ?: return ToolResult(name, ok = false, content = "Missing argument: path")

        val search = call.argsJson.str("search")
            ?: return ToolResult(name, ok = false, content = "Missing argument: search")

        val replace = call.argsJson.str("replace")
            ?: return ToolResult(name, ok = false, content = "Missing argument: replace")

        if (search.isEmpty()) {
            return ToolResult(name, ok = false, content = "Argument 'search' must not be empty")
        }

        return try {
            val target = resolveSafe(rel)

            if (!FileSystem.SYSTEM.exists(target)) {
                return ToolResult(name, ok = false, content = "File does not exist: $rel")
            }

            val existing = FileSystem.SYSTEM.read(target) { readUtf8() }

            val occurrences = existing.windowed(search.length, 1).count { it == search }
            if (occurrences == 0) {
                return ToolResult(name, ok = false, content = "Search text not found in file: $rel")
            }

            if (occurrences > 1) {
                return ToolResult(
                    name,
                    ok = false,
                    content = "Search text is ambiguous ($occurrences matches). Please use a more specific search string."
                )
            }

            val updated = existing.replaceFirst(search, replace)
            guard.validateWrite(rel, updated)

            FileSystem.SYSTEM.write(target) {
                writeUtf8(updated)
            }

            ToolResult(
                name = name,
                ok = true,
                content = "Replaced text in $rel",
                meta = mapOf("path" to rel, "matches" to "1")
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(name, ok = false, content = "Execution guard blocked replace_in_file: ${e.message}")
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "replace_in_file failed: ${e.message}")
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