package org.ivangelov.agent.orchestrator.llm

interface ResponseInterpreter {
    fun toDisplayText(raw: String): String
}