package org.ivangelov.agent.core.agent

sealed class AgentError {
    data class LlmFailure(
        val message: String
    ) : AgentError()

    data class ToolFailure(
        val toolName: String,
        val message: String
    ) : AgentError()

    data class ToolValidationFailure(
        val toolName: String,
        val message: String
    ) : AgentError()

    data class MemoryFailure(
        val message: String
    ) : AgentError()

    data class InvalidPlan(
        val raw: String
    ) : AgentError()

    data class UnexpectedFailure(
        val message: String
    ) : AgentError()
}