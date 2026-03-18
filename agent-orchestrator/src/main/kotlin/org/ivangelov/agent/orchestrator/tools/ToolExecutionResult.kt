package org.ivangelov.agent.orchestrator.tools

import kotlinx.serialization.json.JsonObject

data class ToolExecutionResult(
    val toolName: String,
    val normalizedArgs: JsonObject,
    val ok: Boolean,
    val rawOutput: String,
    val userMessage: String?
)