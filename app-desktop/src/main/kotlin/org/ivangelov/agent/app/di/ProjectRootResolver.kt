package org.ivangelov.agent.app.di

import java.io.File
import okio.Path
import okio.Path.Companion.toPath
import org.ivangelov.agent.app.util.Logger

class ProjectRootResolver(
    private val defaultRoot: Path,
    private val logger: Logger
) {
    fun resolve(rootPathRaw: String?, projectName: String?): Path {
        val trimmed = rootPathRaw?.trim().takeUnless { it.isNullOrBlank() }

        if (trimmed == null) {
            logger.warn("Project '${projectName ?: "unknown"}' has no rootPath; using default root: $defaultRoot")
            return defaultRoot
        }

        val f = File(trimmed)
        if (!f.exists() || !f.isDirectory) {
            logger.warn(
                "Project '${projectName ?: "unknown"}' rootPath '$trimmed' does not exist or is not a directory; " +
                        "using default root: $defaultRoot"
            )
            return defaultRoot
        }

        // IMPORTANT: convert via String to okio.Path (NOT java.nio.file.Path)
        val resolved: Path = f.canonicalPath.toPath()

        val appDir = File(f, "app")
        if (appDir.exists() && appDir.isDirectory) {
            val appPath = appDir.canonicalPath.toPath()
            logger.info("Detected Android app module for '${projectName ?: "unknown"}': $appPath")
            return appPath
        }

        if (resolved != defaultRoot) {
            logger.info("Using project root for '${projectName ?: "unknown"}': $resolved")
        }
        return resolved
    }
}