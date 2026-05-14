package org.ivangelov.agent.orchestrator.edit

object ModelCodeOutputSanitizer {

    fun stripWrappingMarkdownFences(text: String): String {
        return stripWrappingFence(
            stripWrappingFence(text.trim(), "```"),
            "'''"
        ).trim()
    }

    private fun stripWrappingFence(text: String, marker: String): String {
        val lines = text.lines()
        if (lines.isEmpty()) return text

        val first = lines.first().trim()
        val last = lines.last().trim()

        val withoutOpening =
            if (first.startsWith(marker)) lines.drop(1) else lines

        val withoutClosing =
            if (withoutOpening.isNotEmpty() && last == marker) withoutOpening.dropLast(1) else withoutOpening

        return withoutClosing.joinToString("\n")
    }
}
