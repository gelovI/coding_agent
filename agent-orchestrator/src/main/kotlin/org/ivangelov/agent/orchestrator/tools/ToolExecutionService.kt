package org.ivangelov.agent.orchestrator.tools

import kotlinx.serialization.json.JsonObject
import org.ivangelov.agent.core.agent.AgentResult

interface ToolExecutionService {
    suspend fun execute(
        toolNameRaw: String,
        argsAlreadyNormalized: JsonObject
    ): AgentResult<ToolExecutionResult>
}