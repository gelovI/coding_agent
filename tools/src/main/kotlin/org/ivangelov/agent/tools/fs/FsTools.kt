package org.ivangelov.agent.tools.fs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.core.model.ToolResult
import org.ivangelov.agent.tools.Tool

class ListDirTool(
    private val root: Path
) : Tool {
    override val name: String = "list_dir"

    override suspend fun execute(call: ToolCall): ToolResult {
        val rel = call.argsJson.str("path")
            ?.trim()
            ?: return ToolResult(name, ok = false, content = "Missing argument: path")

        return try {
            val target = resolveSafe(rel)
            val text = FileSystem.SYSTEM.read(target) { readUtf8() }
            ToolResult(name, ok = true, content = text)
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "read_file failed: ${e.message}")
        }
    }

    private fun resolveSafe(raw: String): Path {
        val p = raw.trim().ifBlank { "." }.toPath()
        val abs = (root / p).normalized()
        require(abs.toString().startsWith(root.toString())) { "Path escapes root" }
        return abs
    }
}

class ReadFileTool(
    private val root: Path
) : Tool {
    override val name: String = "read_file"

    override suspend fun execute(call: ToolCall): ToolResult {
        val rel = call.argsJson.str("path")
            ?: return ToolResult(name, ok = false, content = "Missing argument: path")

        val target = resolveSafe(rel)

        return try {
            val text = FileSystem.SYSTEM.read(target) { readUtf8() }
            ToolResult(name, ok = true, content = text)
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "read_file failed: ${e.message}")
        }
    }

    private fun resolveSafe(raw: String): Path {
        val p = raw.trim().toPath()
        val abs = (root / p).normalized()
        require(abs.toString().startsWith(root.toString())) { "Path escapes root" }
        return abs
    }
}

class WriteFileTool(
    private val root: Path,
    private val guard: ExecutionGuard = ExecutionGuard(root)
) : Tool {

    override val name: String = "write_file"

    override suspend fun execute(call: ToolCall): ToolResult {
        val rel = call.argsJson.str("path")
            ?: return ToolResult(name, ok = false, content = "Missing argument: path")

        val content = call.argsJson.str("content")
            ?: return ToolResult(name, ok = false, content = "Missing argument: content")

        return try {
            guard.validateWrite(rel, content)

            val target = resolveSafe(rel)
            val overwrite = call.argsJson["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false

            if (FileSystem.SYSTEM.exists(target) && !overwrite) {
                return ToolResult(
                    name = name,
                    ok = false,
                    content = "Refusing to overwrite existing file without overwrite=true: $rel"
                )
            }
            target.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(target) {
                writeUtf8(content)
            }

            ToolResult(
                name = name,
                ok = true,
                content = "Wrote $rel",
                meta = mapOf("path" to rel)
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(name, ok = false, content = "Execution guard blocked write_file: ${e.message}")
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "write_file failed: ${e.message}")
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

// ---- Json helpers ----
fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull