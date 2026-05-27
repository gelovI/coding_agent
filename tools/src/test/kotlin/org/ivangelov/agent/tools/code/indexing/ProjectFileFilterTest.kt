package org.ivangelov.agent.tools.code.indexing

import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectFileFilterTest {

    @Test
    fun allowsSupportedTextFiles() {
        val root = tempRoot()
        try {
            val file = File(root, "src/App.kt").also {
                it.parentFile.mkdirs()
                it.writeText("class App")
            }

            assertTrue(
                ProjectFileFilter.shouldIndex(
                    path = file.absolutePath.toPath(),
                    fs = FileSystem.SYSTEM
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun skipsGeneratedAndDependencyDirectories() {
        val root = tempRoot()
        try {
            val generated = File(root, ".kotlin/generated/App.kt").also {
                it.parentFile.mkdirs()
                it.writeText("class Generated")
            }
            val dependency = File(root, "node_modules/pkg/index.kt").also {
                it.parentFile.mkdirs()
                it.writeText("class Dependency")
            }

            assertFalse(ProjectFileFilter.shouldIndex(generated.absolutePath.toPath(), FileSystem.SYSTEM))
            assertFalse(ProjectFileFilter.shouldIndex(dependency.absolutePath.toPath(), FileSystem.SYSTEM))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun skipsBinaryAndLargeFiles() {
        val root = tempRoot()
        try {
            val binary = File(root, "image.png").also {
                it.writeBytes(byteArrayOf(1, 2, 3))
            }
            val large = File(root, "Large.kt").also {
                it.writeText("x".repeat(300_000))
            }

            assertFalse(ProjectFileFilter.shouldIndex(binary.absolutePath.toPath(), FileSystem.SYSTEM))
            assertFalse(ProjectFileFilter.shouldIndex(large.absolutePath.toPath(), FileSystem.SYSTEM))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun tempRoot(): File =
        Files.createTempDirectory("coding-agent-file-filter-test").toFile()
}
