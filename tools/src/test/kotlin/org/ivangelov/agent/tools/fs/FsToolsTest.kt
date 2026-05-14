package org.ivangelov.agent.tools.fs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Path.Companion.toPath
import org.ivangelov.agent.core.model.ToolCall
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FsToolsTest {

    @Test
    fun listDirListsFilesAndDirectories() = runBlocking {
        val root = tempRoot()
        try {
            File(root, "src").mkdirs()
            File(root, "README.md").writeText("hello")

            val tool = ListDirTool(root.absolutePath.toPath())
            val result = tool.execute(
                ToolCall(
                    name = "list_dir",
                    argsJson = buildJsonObject {
                        put("path", ".")
                    }
                )
            )

            assertTrue(result.ok, "Expected list_dir to succeed, got: ${result.content}")

            val lines = result.content.lines()
            assertTrue(
                lines.any { it.contains("src") },
                "Expected listed directory 'src', got:\n${result.content}"
            )
            assertTrue(
                lines.any { it.contains("README.md") },
                "Expected listed file 'README.md', got:\n${result.content}"
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readFileReadsExistingFile() = runBlocking {
        val root = tempRoot()
        try {
            File(root, "App.kt").writeText("fun main() = Unit")

            val tool = ReadFileTool(root.absolutePath.toPath())
            val result = tool.execute(
                ToolCall(
                    name = "read_file",
                    argsJson = buildJsonObject {
                        put("path", "App.kt")
                    }
                )
            )

            assertTrue(result.ok)
            assertEquals("fun main() = Unit", result.content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeFileRefusesOverwriteWithoutFlag() = runBlocking {
        val root = tempRoot()
        try {
            File(root, "App.kt").writeText("old")

            val tool = WriteFileTool(root.absolutePath.toPath())
            val result = tool.execute(
                ToolCall(
                    name = "write_file",
                    argsJson = buildJsonObject {
                        put("path", "App.kt")
                        put("content", "new")
                    }
                )
            )

            assertFalse(result.ok)
            assertContains(result.content, "Refusing to overwrite")
            assertEquals("old", File(root, "App.kt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeFileAllowsOverwriteWithFlag() = runBlocking {
        val root = tempRoot()
        try {
            File(root, "App.kt").writeText("old")

            val tool = WriteFileTool(root.absolutePath.toPath())
            val result = tool.execute(
                ToolCall(
                    name = "write_file",
                    argsJson = buildJsonObject {
                        put("path", "App.kt")
                        put("content", "new")
                        put("overwrite", true)
                    }
                )
            )

            assertTrue(result.ok)
            assertEquals("new", File(root, "App.kt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun replaceInFileFailsWhenSearchTextIsAmbiguous() = runBlocking {
        val root = tempRoot()
        try {
            File(root, "App.kt").writeText(
                """
            fun hello() = println("x")
            fun bye() = println("x")
            """.trimIndent()
            )

            val tool = ReplaceInFileTool(root.absolutePath.toPath())
            val result = tool.execute(
                ToolCall(
                    name = "replace_in_file",
                    argsJson = buildJsonObject {
                        put("path", "App.kt")
                        put("search", """println("x")""")
                        put("replace", """println("changed")""")
                    }
                )
            )

            assertFalse(result.ok)
            assertContains(result.content, "ambiguous")
            assertContains(File(root, "App.kt").readText(), """println("x")""")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeFilesRollsBackAlreadyWrittenFilesWhenBatchFails() = runBlocking {
        val root = tempRoot()
        try {
            File(root, "A.kt").writeText("old A")

            val tool = WriteFilesTool(root.absolutePath.toPath())
            val result = tool.execute(
                ToolCall(
                    name = "write_files",
                    argsJson = buildJsonObject {
                        putJsonArray("files") {
                            add(
                                buildJsonObject {
                                    put("path", "A.kt")
                                    put("content", "new A")
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("path", ".git/config")
                                    put("content", "should fail")
                                }
                            )
                        }
                    }
                )
            )

            assertFalse(result.ok)
            assertEquals("old A", File(root, "A.kt").readText())
            assertFalse(File(root, ".git/config").exists())
        } finally {
            root.deleteRecursively()
        }
    }


    private fun tempRoot(): File =
        Files.createTempDirectory("coding-agent-tools-test").toFile()
}
