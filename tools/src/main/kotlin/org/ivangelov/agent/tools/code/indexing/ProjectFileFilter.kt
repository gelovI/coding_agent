package org.ivangelov.agent.tools.code.indexing

import okio.FileSystem
import okio.Path

object ProjectFileFilter {
    val defaultIncludedExtensions: Set<String> =
        setOf("kt", "kts", "gradle", "xml", "json", "md")

    private val skippedDirectories = setOf(
        ".git",
        ".gradle",
        "build",
        "out",
        ".idea",
        ".kotlin",
        ".tmp",
        "node_modules"
    )

    private val skippedFileNames = setOf(
        "gradle-wrapper.jar"
    )

    private val skippedExtensions = setOf(
        "a",
        "class",
        "dll",
        "dmg",
        "exe",
        "gif",
        "ico",
        "jar",
        "jpeg",
        "jpg",
        "lock",
        "pdf",
        "png",
        "so",
        "webp",
        "zip"
    )

    fun shouldIndex(
        path: Path,
        fs: FileSystem,
        includeExtensions: Set<String> = defaultIncludedExtensions,
        maxFileBytes: Long = 256_000
    ): Boolean {
        val metadata = runCatching { fs.metadata(path) }.getOrNull() ?: return false
        if (metadata.isDirectory) return false

        val segments = path.segments.map { it.lowercase() }
        if (segments.any { it in skippedDirectories }) return false

        val fileName = path.name.lowercase()
        if (fileName in skippedFileNames) return false

        val size = metadata.size
        if (size != null && size > maxFileBytes) return false

        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        if (extension in skippedExtensions) return false

        if (extension == "gradle" && fileName.endsWith(".gradle.kts")) {
            return "gradle" in includeExtensions || "kts" in includeExtensions
        }

        return extension in includeExtensions
    }
}
