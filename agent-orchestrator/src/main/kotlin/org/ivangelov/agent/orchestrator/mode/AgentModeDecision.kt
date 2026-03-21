package org.ivangelov.agent.orchestrator.mode

data class AgentModeDecision(
    val mode: AgentMode,
    val explicitToolName: String? = null
) {
    init {
        require(mode != AgentMode.EXPLICIT_TOOL || !explicitToolName.isNullOrBlank()) {
            "Invalid AgentModeDecision: EXPLICIT_TOOL requires explicitToolName"
        }
    }
}