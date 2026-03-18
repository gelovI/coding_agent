package org.ivangelov.agent.orchestrator.mode

data class AgentModeDecision(
    val mode: AgentMode,
    val explicitToolName: String? = null
)