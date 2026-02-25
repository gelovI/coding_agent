package org.ivangelov.agent.tools

import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.core.model.ToolResult

interface Tool {
    val name: String
    suspend fun execute(call: ToolCall): ToolResult
}

class ToolRegistry(
    private val tools: List<Tool>
) {
    private val byName = tools.associateBy { it.name }

    private fun normalize(name: String): String =
        when (name) {
            "create_file" -> "write_file"
            else -> name
        }

    fun get(name: String): Tool? = byName[normalize(name)]

    fun list(): List<String> = tools.map { it.name }.sorted()
}
