package org.ivangelov.agent.orchestrator.edit

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelCodeOutputSanitizerTest {

    @Test
    fun stripsKotlinMarkdownFenceAroundCode() {
        val raw = """
            ```kotlin
            fun answer(): Int = 42
            ```
        """.trimIndent()

        assertEquals(
            "fun answer(): Int = 42",
            ModelCodeOutputSanitizer.stripWrappingMarkdownFences(raw)
        )
    }

    @Test
    fun stripsApostropheFenceAroundCode() {
        val raw = """
            '''kotlin
            val name = "agent"
            '''
        """.trimIndent()

        assertEquals(
            "val name = \"agent\"",
            ModelCodeOutputSanitizer.stripWrappingMarkdownFences(raw)
        )
    }

    @Test
    fun keepsBackticksInsideCodeStrings() {
        val raw = """
            ```kotlin
            val text = "Use `code` in Markdown"
            ```
        """.trimIndent()

        assertEquals(
            "val text = \"Use `code` in Markdown\"",
            ModelCodeOutputSanitizer.stripWrappingMarkdownFences(raw)
        )
    }
}
