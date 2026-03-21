package org.ivangelov.agent.orchestrator.mode

class HeuristicAgentModeResolver : AgentModeResolver {

    override fun resolve(userText: String): AgentModeDecision {
        val t = userText.lowercase()

        if (containsInlineCode(userText)) {
            return AgentModeDecision(mode = AgentMode.CHAT)
        }

        if (looksLikeMultiStepFileTask(t)) {
            return AgentModeDecision(mode = AgentMode.TOOL_LOOP)
        }

        val explicitTool = detectExplicitToolName(t)
            ?: detectHeuristicToolName(t)

        if (explicitTool != null) {
            return AgentModeDecision(
                mode = AgentMode.EXPLICIT_TOOL,
                explicitToolName = explicitTool
            )
        }

        if (looksLikeKnowledgeQuestion(t)) {
            return AgentModeDecision(mode = AgentMode.KNOWLEDGE)
        }

        return AgentModeDecision(mode = AgentMode.CHAT)
    }

    private fun containsInlineCode(text: String): Boolean {
        val hasFence = "```" in text || "~~~" in text

        val hasUiCodeHints = listOf(
            "modifier =",
            "box(",
            "column(",
            "row(",
            "text(",
            "image(",
            ".fillmaxsize(",
            ".padding(",
            "roundedcornershape(",
            "alignment.",
            "verticalalignment",
            "horizontalalignment"
        ).any { it in text.lowercase() }

        val hasManyIndentedLines = text
            .lines()
            .count { line -> line.startsWith("    ") || line.startsWith("\t") } >= 3

        return hasFence || hasUiCodeHints || hasManyIndentedLines
    }

    private fun detectExplicitToolName(t: String): String? {
        return when {
            "index_project" in t -> "index_project"
            "analyze_architecture" in t -> "analyze_architecture"
            "replace_in_file" in t -> "replace_in_file"
            "append_to_file" in t -> "append_to_file"
            "write_files" in t -> "write_files"
            "write_file" in t -> "write_file"
            "read_file" in t -> "read_file"
            "list_dir" in t -> "list_dir"
            else -> null
        }
    }

    private fun detectHeuristicToolName(t: String): String? {
        return when {
            "list directory" in t ||
                    "list dir" in t ||
                    "zeige ordner" in t ||
                    "zeige verzeichnis" in t -> "list_dir"

            "lies die datei" in t ||
                    "lese die datei" in t ||
                    "öffne die datei" in t ||
                    "oeffne die datei" in t ||
                    "read file" in t -> "read_file"

            "append to file" in t ||
                    "hänge an die datei an" in t ||
                    "haenge an die datei an" in t ||
                    "ergänze die datei" in t ||
                    "ergaenze die datei" in t -> "append_to_file"

            "replace in file" in t ||
                    "ersetze in der datei" in t -> "replace_in_file"

            "schreibe datei" in t ||
                    "write file" in t ||
                    "erstelle datei" in t ||
                    "create file" in t -> "write_file"

            else -> null
        }
    }

    private fun looksLikeMultiStepFileTask(t: String): Boolean {
        val hasFileContext =
            "datei" in t ||
                    "file" in t ||
                    ".kt" in t ||
                    ".java" in t ||
                    ".xml" in t ||
                    ".json" in t ||
                    ".gradle" in t ||
                    ".kts" in t ||
                    "/" in t ||
                    "\\" in t

        val hasModificationIntent = listOf(
            "kommentiere",
            "kommentier",
            "kommentieren",
            "ändere",
            "aendere",
            "bearbeite",
            "überarbeite",
            "ueberarbeite",
            "verbessere",
            "refactore",
            "refaktor",
            "ergänze",
            "ergaenze",
            "füge ein",
            "fuege ein",
            "ersetze",
            "rewrite",
            "modify",
            "edit",
            "update"
        ).any { it in t }

        return hasFileContext && hasModificationIntent
    }

    private fun looksLikeKnowledgeQuestion(t: String): Boolean {
        return listOf(
            "architektur",
            "architecture",
            "wie funktioniert",
            "erklär",
            "erklaer",
            "warum ist",
            "wie ist",
            "struktur",
            "aufbau",
            "datenfluss",
            "welche schichten",
            "welche komponenten",
            "refactoring-vorschlag",
            "refactoring vorschlag"
        ).any { it in t }
    }
}