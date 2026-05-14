package org.ivangelov.agent.orchestrator.path

import okio.Path
import okio.Path.Companion.toPath

class ProjectPathResolver(
    private val projectRoot: Path
) {
    fun tryResolveMentionedPath(userText: String): String? {
        val normalizedText = userText.replace("\\", "/")

        extractAbsolutePath(normalizedText)?.let { abs ->
            return absoluteToProjectRelative(abs)
        }

        extractRelativePath(normalizedText)?.let { rel ->
            return normalizeRelative(rel)
        }

        extractFileName(normalizedText)?.let { fileName ->
            return normalizeRelative(fileName)
        }

        return null
    }

    fun normalizeRelative(path: String): String {
        return path
            .trim()
            .replace("\\", "/")
            .removePrefix("./")
            .removePrefix("/")
            .toPath(normalize = true)
            .toString()
            .replace("\\", "/")
    }

    fun absoluteToProjectRelative(rawAbsolute: String): String? {
        val absolute = rawAbsolute.replace("\\", "/").toPath(normalize = true)
        val root = projectRoot.normalized()

        if (!isInsideRoot(absolute, root)) return null

        return makeRelativeToRoot(absolute, root)
    }

    fun isProjectRelative(path: String): Boolean {
        val p = path.trim()
        if (p.isBlank()) return false
        if (p.startsWith("/")) return false
        if (p.contains(":\\") || p.contains(":/")) return false
        if (p.contains("..")) return false
        return true
    }

    private fun extractAbsolutePath(text: String): String? {
        val absoluteRegex = Regex("""([A-Za-z]:/[^ \n\r\t"']+?\.(kt|java|xml|json|kts|gradle))""")
        return absoluteRegex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractRelativePath(text: String): String? {
        val relRegex = Regex("""((?:[\w.-]+/)+[\w.-]+\.(kt|java|xml|json|kts|gradle))""")
        return relRegex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractFileName(text: String): String? {
        val fileRegex = Regex("""([\w.-]+\.(kt|java|xml|json|kts|gradle))""")
        return fileRegex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun isInsideRoot(child: Path, root: Path): Boolean {
        val childSeg = child.normalized().segments
        val rootSeg = root.normalized().segments
        return childSeg.size >= rootSeg.size &&
                childSeg.take(rootSeg.size) == rootSeg
    }

    private fun makeRelativeToRoot(path: Path, root: Path): String {
        val fullSeg = path.normalized().segments
        val rootSeg = root.normalized().segments
        return fullSeg.drop(rootSeg.size).joinToString("/")
    }
}