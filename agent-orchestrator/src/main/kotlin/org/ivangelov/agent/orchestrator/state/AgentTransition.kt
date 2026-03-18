package org.ivangelov.agent.orchestrator.state

data class AgentTransition(
    val from: AgentState,
    val to: AgentState,
    val reason: String? = null
)