package org.ivangelov.agent.tools.fs

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecutionGuardTest {

    private val root = "C:/repo".toPath()
    private val guard = ExecutionGuard(root)

    @Test
    fun allowsRelativePathInsideProjectRoot() {
        val resolved = guard.resolveInsideRoot("src/main/App.kt")

        assertTrue(resolved.toString().replace("\\", "/").endsWith("C:/repo/src/main/App.kt"))
    }

    @Test
    fun blocksUnixAbsolutePath() {
        assertFailsWith<IllegalArgumentException> {
            guard.resolveInsideRoot("/etc/passwd")
        }
    }

    @Test
    fun blocksWindowsAbsolutePath() {
        assertFailsWith<IllegalArgumentException> {
            guard.resolveInsideRoot("C:\\Users\\Ivan\\secret.txt")
        }
    }

    @Test
    fun blocksPathTraversal() {
        assertFailsWith<IllegalArgumentException> {
            guard.resolveInsideRoot("../secret.txt")
        }
    }

    @Test
    fun blocksWritesIntoGitDirectory() {
        assertFailsWith<IllegalArgumentException> {
            guard.validateWrite(".git/config", "content")
        }
    }

    @Test
    fun blocksWritesIntoBuildDirectory() {
        assertFailsWith<IllegalArgumentException> {
            guard.validateWrite("build/generated.txt", "content")
        }
    }

    @Test
    fun blocksEmptyContentForWrites() {
        assertFailsWith<IllegalArgumentException> {
            guard.validateWrite("src/main/App.kt", "")
        }
    }
}
