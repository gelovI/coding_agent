package org.ivangelov.agent.orchestrator.tools

import kotlinx.serialization.json.JsonObject

interface ToolResultPresenter {
    fun successMessage(toolName: String, args: JsonObject): String?
}