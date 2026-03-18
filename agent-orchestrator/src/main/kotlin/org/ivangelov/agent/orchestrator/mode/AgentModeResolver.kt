package org.ivangelov.agent.orchestrator.mode

interface AgentModeResolver {
    fun resolve(userText: String): AgentModeDecision
}