package org.ivangelov.agent.orchestrator

import kotlinx.serialization.json.JsonObject

data class ValidatedToolCall(
    val name: String,
    val args: JsonObject
)