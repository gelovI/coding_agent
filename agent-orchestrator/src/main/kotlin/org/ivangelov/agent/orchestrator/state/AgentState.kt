package org.ivangelov.agent.orchestrator.state

enum class AgentState {
    START,
    ROUTE,
    RETRIEVE_MEMORY,
    BUILD_PROMPT,
    CALL_LLM,
    PARSE_RESPONSE,
    EXECUTE_TOOLS,
    FINALIZE,
    COMPLETE,
    FAILED
}