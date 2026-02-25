package org.ivangelov.agent.orchestrator

object ToolOutputFormatter {
    fun format(toolName: String, raw: String, maxChars: Int): String {
        val trimmed = raw.trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars) + "\n...TRUNCATED (${trimmed.length} chars)"
    }
}