package org.ivangelov.agent.orchestrator.path

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectPathResolverTest {

    private val resolver = ProjectPathResolver("C:/repo".toPath())

    @Test
    fun resolvesAbsolutePathInsideProjectRootToRelativePath() {
        val result = resolver.absoluteToProjectRelative("C:/repo/src/main/App.kt")

        assertEquals("src/main/App.kt", result)
    }

    @Test
    fun rejectsAbsolutePathOutsideProjectRoot() {
        val result = resolver.absoluteToProjectRelative("C:/other/src/main/App.kt")

        assertNull(result)
    }

    @Test
    fun resolvesMentionedRelativePath() {
        val result = resolver.tryResolveMentionedPath("Bitte lies src/main/kotlin/App.kt.")

        assertEquals("src/main/kotlin/App.kt", result)
    }

    @Test
    fun resolvesMentionedFileNameWhenNoPathIsPresent() {
        val result = resolver.tryResolveMentionedPath("Analysiere App.kt")

        assertEquals("App.kt", result)
    }

    @Test
    fun validatesProjectRelativePaths() {
        assertTrue(resolver.isProjectRelative("src/main/App.kt"))
        assertFalse(resolver.isProjectRelative("../secret.txt"))
        assertFalse(resolver.isProjectRelative("C:/repo/secret.txt"))
        assertFalse(resolver.isProjectRelative("/etc/passwd"))
    }
}
