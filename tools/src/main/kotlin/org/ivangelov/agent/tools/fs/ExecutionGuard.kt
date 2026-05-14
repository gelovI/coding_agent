package org.ivangelov.agent.tools.fs

import okio.Path
import okio.Path.Companion.toPath

class ExecutionGuard(
    private val projectRoot: Path,
    private val maxContentChars: Int = 200_000
) {
    private val forbiddenTopLevelDirs = setOf(
        ".git",
        ".gradle",
        "build",
        "out",
        ".idea"
    )

    fun resolveInsideRoot(path: String): Path {
        require(path.isNotBlank()) { "Path is empty" }
        require(!path.startsWith("/")) { "Absolute paths are not allowed: $path" }
        require(!path.contains(":\\") && !path.contains(":/")) {
            "Absolute paths are not allowed: $path"
        }
        require(!path.contains("..")) { "Path traversal is not allowed: $path" }

        val relPath = path.trim().ifBlank { "." }.toPath(normalize = true)
        val normalizedRoot = projectRoot.normalized()
        val resolved = (normalizedRoot / relPath).normalized()

        val rootSegments = normalizedRoot.segments
        val resolvedSegments = resolved.segments

        require(
            resolvedSegments.size >= rootSegments.size &&
                    resolvedSegments.take(rootSegments.size) == rootSegments
        ) {
            "Path escapes project root: $path"
        }

        return resolved
    }

    fun validateWrite(path: String, content: String) {
        require(path.isNotBlank()) { "Path is empty" }
        require(content.isNotBlank()) { "Content is empty" }
        require(content.length <= maxContentChars) {
            "Content too large (${content.length} chars > $maxContentChars)"
        }

        resolveInsideRoot(path)

        val relPath = path.trim().toPath(normalize = true)
        val normalizedSegments = relPath.segments
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (normalizedSegments.isNotEmpty()) {
            val firstSegment = normalizedSegments.first()
            require(firstSegment !in forbiddenTopLevelDirs) {
                "Writing into protected directory is forbidden: $firstSegment"
            }
        }

        require(normalizedSegments.none { it == ".git" || it == ".gradle" }) {
            "Writing into protected internal directories is forbidden: $path"
        }
    }

    fun validateWriteBatch(files: List<FileWriteSpec>) {
        require(files.isNotEmpty()) { "files must not be empty" }

        val seenPaths = mutableSetOf<String>()

        files.forEach { file ->
            validateWrite(file.path, file.content)
            require(seenPaths.add(file.path)) {
                "Duplicate file path in batch: ${file.path}"
            }
        }
    }
}

data class FileWriteSpec(
    val path: String,
    val content: String
)
