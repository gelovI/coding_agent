package org.ivangelov.agent.orchestrator.tools

interface ToolNameResolver {
    fun resolve(llmName: String): String
}