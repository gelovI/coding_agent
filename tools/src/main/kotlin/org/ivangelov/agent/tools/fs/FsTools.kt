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
    private val root: Path,
    private val fs: FileSystem = FileSystem.SYSTEM
) : Tool {
    override val name: String = "list_dir"

    override suspend fun execute(call: ToolCall): ToolResult {
        val rel = call.argsJson.str("path")
            ?.trim()
            ?: return ToolResult(name, ok = false, content = "Missing argument: path")

        return try {
            val target = resolveSafe(rel)

            if (!fs.exists(target)) {
                return ToolResult(name, ok = false, content = "Directory not found: $rel")
            }

            val meta = fs.metadata(target)
            if (!meta.isDirectory) {
                return ToolResult(name, ok = false, content = "Path is not a directory: $rel")
            }

            val entries = fs.list(target)
                .sortedBy { it.name }
                .map { child ->
                    val childMeta = runCatching { fs.metadata(child) }.getOrNull()
                    val type = when {
                        childMeta == null -> "unknown"
                        childMeta.isDirectory -> "dir"
                        else -> "file"
                    }
                    "$type ${makeRelativeToRoot(child)}"
                }

            ToolResult(
                name = name,
                ok = true,
                content = if (entries.isEmpty()) "(empty directory)" else entries.joinToString("\n")
            )
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "list_dir failed: ${e.message}")
        }
    }

    private fun resolveSafe(raw: String): Path {
        val p = raw.trim().ifBlank { "." }.replace("\\", "/").toPath(normalize = true)
        val normalizedRoot = root.normalized()
        val resolved = (normalizedRoot / p).normalized()

        require(isInsideRoot(resolved, normalizedRoot)) { "Path escapes root" }
        return resolved
    }

    private fun isInsideRoot(child: Path, root: Path): Boolean {
        val childSeg = child.normalized().segments
        val rootSeg = root.normalized().segments
        return childSeg.size >= rootSeg.size &&
                childSeg.take(rootSeg.size) == rootSeg
    }

    private fun makeRelativeToRoot(path: Path): String {
        val fullSeg = path.normalized().segments
        val rootSeg = root.normalized().segments
        return fullSeg.drop(rootSeg.size).joinToString("/")
    }
}

class ReadFileTool(
    private val root: Path,
    private val indexedFiles: List<Path> = emptyList(),
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val guard: ExecutionGuard = ExecutionGuard(root)
) : Tool {
    override val name: String = "read_file"

    override suspend fun execute(call: ToolCall): ToolResult {
        val rel = call.argsJson.str("path")
            ?.trim()
            ?: return ToolResult(name, ok = false, content = "Missing argument: path")

        return try {
            val resolved = resolveCandidate(rel)

            if (resolved == null) {
                val suggestions = findSuggestions(rel)
                val msg = buildString {
                    append("File not found: ").append(rel)
                    if (suggestions.isNotEmpty()) {
                        append("\nPossible matches:")
                        suggestions.forEach { append("\n- ").append(it) }
                    }
                }
                return ToolResult(name, ok = false, content = msg)
            }

            val meta = fs.metadata(resolved)
            if (meta.isDirectory) {
                return ToolResult(
                    name,
                    ok = false,
                    content = "Path is a directory, not a file: ${makeRelativeToRoot(resolved)}"
                )
            }

            val text = fs.read(resolved) { readUtf8() }
            ToolResult(
                name,
                ok = true,
                content = text,
                meta = mapOf("path" to makeRelativeToRoot(resolved))
            )
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "read_file failed: ${e.message}")
        }
    }

    private fun resolveCandidate(raw: String): Path? {
        val normalizedInput = raw.trim().replace("\\", "/")
        if (normalizedInput.isBlank()) return null

        // 1) Direkt projekt-relativ auflösen
        val direct = runCatching {
            guard.resolveInsideRoot(normalizedInput)
        }.getOrNull()

        if (direct != null && fs.exists(direct)) {
            val meta = runCatching { fs.metadata(direct) }.getOrNull()
            if (meta != null && !meta.isDirectory) return direct
        }

        val candidates = candidateFiles()

        // 2) Exakter relativer Pfad-Match
        val exactRelativeMatches = candidates.filter { candidate ->
            makeRelativeToRoot(candidate).equals(normalizedInput, ignoreCase = true)
        }
        if (exactRelativeMatches.size == 1) return exactRelativeMatches.first()

        // 3) Suffix-Match
        val suffixMatches = candidates.filter { candidate ->
            makeRelativeToRoot(candidate).endsWith(normalizedInput, ignoreCase = true)
        }
        if (suffixMatches.size == 1) return suffixMatches.first()

        // 4) Dateiname-Match
        val fileName = normalizedInput.substringAfterLast('/')
        val fileNameMatches = candidates.filter { candidate ->
            candidate.name.equals(fileName, ignoreCase = true)
        }
        if (fileNameMatches.size == 1) return fileNameMatches.first()

        return null
    }

    private fun findSuggestions(raw: String): List<String> {
        val normalizedInput = raw.trim().replace("\\", "/")
        val fileName = normalizedInput.substringAfterLast('/')

        return candidateFiles()
            .map { makeRelativeToRoot(it) }
            .filter { rel ->
                rel.equals(normalizedInput, ignoreCase = true) ||
                        rel.endsWith(normalizedInput, ignoreCase = true) ||
                        rel.contains("/$fileName", ignoreCase = true) ||
                        rel.endsWith(fileName, ignoreCase = true)
            }
            .distinct()
            .sorted()
            .take(8)
    }

    private fun candidateFiles(): List<Path> {
        val normalizedRoot = root.normalized()

        val injected = indexedFiles
            .map { it.normalized() }
            .filter { isInsideRoot(it, normalizedRoot) }

        if (injected.isNotEmpty()) {
            return injected
                .filter { path ->
                    val meta = runCatching { fs.metadata(path) }.getOrNull()
                    meta != null && !meta.isDirectory
                }
                .distinctBy { it.toString().replace("\\", "/").lowercase() }
                .sortedBy { makeRelativeToRoot(it) }
        }

        return fs.listRecursively(normalizedRoot)
            .filter { path ->
                val meta = runCatching { fs.metadata(path) }.getOrNull()
                meta != null && !meta.isDirectory
            }
            .toList()
            .distinctBy { it.toString().replace("\\", "/").lowercase() }
            .sortedBy { makeRelativeToRoot(it) }
    }

    private fun isInsideRoot(child: Path, root: Path): Boolean {
        val childSeg = child.normalized().segments
        val rootSeg = root.normalized().segments
        return childSeg.size >= rootSeg.size &&
                childSeg.take(rootSeg.size) == rootSeg
    }

    private fun makeRelativeToRoot(path: Path): String {
        val fullSeg = path.normalized().segments
        val rootSeg = root.normalized().segments
        return fullSeg.drop(rootSeg.size).joinToString("/")
    }
}

class WriteFileTool(
    private val root: Path,
    private val guard: ExecutionGuard = ExecutionGuard(root),
    private val fs: FileSystem = FileSystem.SYSTEM
) : Tool {

    override val name: String = "write_file"

    override suspend fun execute(call: ToolCall): ToolResult {
        val rel = call.argsJson.str("path")
            ?: return ToolResult(name, ok = false, content = "Missing argument: path")

        val content = call.argsJson.str("content")
            ?: return ToolResult(name, ok = false, content = "Missing argument: content")

        return try {
            guard.validateWrite(rel, content)

            val target = guard.resolveInsideRoot(rel)
            val overwrite = call.argsJson["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false

            if (fs.exists(target) && !overwrite) {
                return ToolResult(
                    name = name,
                    ok = false,
                    content = "Refusing to overwrite existing file without overwrite=true: $rel"
                )
            }

            target.parent?.let { fs.createDirectories(it) }
            fs.write(target) {
                writeUtf8(content)
            }

            ToolResult(
                name = name,
                ok = true,
                content = "Wrote ${makeRelativeToRoot(target)}",
                meta = mapOf("path" to makeRelativeToRoot(target))
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(name, ok = false, content = "Execution guard blocked write_file: ${e.message}")
        } catch (e: Exception) {
            ToolResult(name, ok = false, content = "write_file failed: ${e.message}")
        }
    }

    private fun makeRelativeToRoot(path: Path): String {
        val fullSeg = path.normalized().segments
        val rootSeg = root.normalized().segments
        return fullSeg.drop(rootSeg.size).joinToString("/")
    }
}

// ---- Json helpers ----
fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull