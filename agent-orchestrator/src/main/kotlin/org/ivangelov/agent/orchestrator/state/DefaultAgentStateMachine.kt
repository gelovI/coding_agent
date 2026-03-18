package org.ivangelov.agent.orchestrator.state

import org.ivangelov.agent.orchestrator.mode.AgentMode

class DefaultAgentStateMachine : AgentStateMachine {

    override fun initialState(): AgentState = AgentState.START

    override fun route(mode: AgentMode): AgentTransition {
        return when (mode) {
            AgentMode.KNOWLEDGE ->
                AgentTransition(AgentState.ROUTE, AgentState.RETRIEVE_MEMORY, "knowledge route")

            AgentMode.EXPLICIT_TOOL,
            AgentMode.CHAT ->
                AgentTransition(AgentState.ROUTE, AgentState.RETRIEVE_MEMORY, "tool/default route")
        }
    }

    override fun toRetrieveMemory(): AgentTransition =
        AgentTransition(AgentState.ROUTE, AgentState.RETRIEVE_MEMORY, "retrieve memory")

    override fun toBuildPrompt(): AgentTransition =
        AgentTransition(AgentState.RETRIEVE_MEMORY, AgentState.BUILD_PROMPT, "build prompt")

    override fun toCallLlm(): AgentTransition =
        AgentTransition(AgentState.BUILD_PROMPT, AgentState.CALL_LLM, "call llm")

    override fun toParseResponse(): AgentTransition =
        AgentTransition(AgentState.CALL_LLM, AgentState.PARSE_RESPONSE, "parse response")

    override fun toExecuteTools(): AgentTransition =
        AgentTransition(AgentState.PARSE_RESPONSE, AgentState.EXECUTE_TOOLS, "execute tools")

    override fun toFinalize(): AgentTransition =
        AgentTransition(AgentState.PARSE_RESPONSE, AgentState.FINALIZE, "finalize response")

    override fun toComplete(): AgentTransition =
        AgentTransition(AgentState.FINALIZE, AgentState.COMPLETE, "turn complete")

    override fun toFailed(reason: String): AgentTransition =
        AgentTransition(AgentState.FAILED, AgentState.FAILED, reason)
}