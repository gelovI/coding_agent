package org.ivangelov.agent.orchestrator.mode

class HeuristicAgentModeResolver : AgentModeResolver {

    override fun resolve(userText: String): AgentModeDecision {
        val explicitTool = detectExplicitToolRequest(userText)
        if (explicitTool != null) {
            return AgentModeDecision(
                mode = AgentMode.EXPLICIT_TOOL,
                explicitToolName = explicitTool
            )
        }

        if (isKnowledgeQuestion(userText)) {
            return AgentModeDecision(mode = AgentMode.KNOWLEDGE)
        }

        return AgentModeDecision(mode = AgentMode.CHAT)
    }

    private fun isKnowledgeQuestion(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "architektur", "architecture", "aufgebaut", "struktur", "design",
            "module", "layer", "schichten", "komponenten", "datenfluss",
            "wie funktioniert", "erklär", "erklaer"
        ).any { it in t }
    }

    private fun detectExplicitToolRequest(text: String): String? {
        val t = text.lowercase()

        return when {
            "index_project" in t -> "index_project"
            "analyze_architecture" in t -> "analyze_architecture"
            "list_dir" in t -> "list_dir"
            "read_file" in t -> "read_file"
            "write_file" in t -> "write_file"
            else -> null
        }
    }
}