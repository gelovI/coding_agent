package org.ivangelov.agent.core.agent

sealed class AgentResult<out T> {
    data class Success<T>(val value: T) : AgentResult<T>()
    data class Failure(val error: AgentError) : AgentResult<Nothing>()
}