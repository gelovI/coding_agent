package org.ivangelov.agent.orchestrator

data class ValidatedAgentPlan(
    val toolCalls: List<ValidatedToolCall>,
    val reply: String?
)