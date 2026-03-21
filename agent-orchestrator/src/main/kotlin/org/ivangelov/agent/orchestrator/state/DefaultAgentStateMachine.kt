package org.ivangelov.agent.orchestrator.state

import org.ivangelov.agent.orchestrator.mode.AgentMode

class DefaultAgentStateMachine : AgentStateMachine {

    override fun initialState(): AgentState = AgentState.START

    override fun route(mode: AgentMode): AgentTransition {
        return when (mode) {
            AgentMode.EXPLICIT_TOOL ->
                AgentTransition(
                    from = AgentState.ROUTE,
                    to = AgentState.EXECUTE_TOOLS,
                    reason = "explicit tool route"
                )

            AgentMode.KNOWLEDGE ->
                AgentTransition(
                    from = AgentState.ROUTE,
                    to = AgentState.RETRIEVE_MEMORY,
                    reason = "knowledge route"
                )

            AgentMode.TOOL_LOOP ->
                AgentTransition(
                    from = AgentState.ROUTE,
                    to = AgentState.RETRIEVE_MEMORY,
                    reason = "tool/default route"
                )

            AgentMode.CHAT ->
                AgentTransition(
                    from = AgentState.ROUTE,
                    to = AgentState.RETRIEVE_MEMORY,
                    reason = "chat route"
                )
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