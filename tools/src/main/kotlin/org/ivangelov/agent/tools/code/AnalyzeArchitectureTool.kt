package org.ivangelov.agent.tools.code

import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.core.model.ToolResult
import org.ivangelov.agent.memory.service.MemoryService
import org.ivangelov.agent.tools.Tool

class AnalyzeArchitectureTool(
    private val memory: MemoryService,
    private val tenantId: String,
    private val conversationId: String,
    private val projectId: String?
) : Tool {

    override val name: String = "analyze_architecture"

    override suspend fun execute(call: ToolCall): ToolResult {

        val retrieved = memory.retrieveForQuery(
            tenantId = tenantId,
            conversationId = conversationId,
            scope = org.ivangelov.agent.memory.core.MemoryScope.PROJECT,
            projectId = projectId,
            query = "architecture structure layers services controllers state",
            topK = 20
        )

        val combined = retrieved.joinToString("\n\n") { it.content }

        return ToolResult(
            name = name,
            ok = true,
            content = combined.take(10000)
        )
    }
}