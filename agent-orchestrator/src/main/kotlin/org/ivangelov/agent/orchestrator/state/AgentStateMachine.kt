package org.ivangelov.agent.orchestrator.state

import org.ivangelov.agent.orchestrator.mode.AgentMode

interface AgentStateMachine {
    fun initialState(): AgentState

    fun route(mode: AgentMode): AgentTransition

    fun toRetrieveMemory(): AgentTransition
    fun toBuildPrompt(): AgentTransition
    fun toCallLlm(): AgentTransition
    fun toParseResponse(): AgentTransition
    fun toExecuteTools(): AgentTransition
    fun toFinalize(): AgentTransition
    fun toComplete(): AgentTransition
    fun toFailed(reason: String): AgentTransition
}