package org.ivangelov.agent.orchestrator.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class DefaultToolResultPresenter : ToolResultPresenter {

    override fun successMessage(toolName: String, args: JsonObject): String? {
        fun arg(key: String): String? = args[key]?.jsonPrimitive?.contentOrNull

        return when (toolName) {
            "write_file" -> {
                val path = arg("file_path") ?: arg("path") ?: "Datei"
                "✅ Datei „$path“ wurde im Projektordner erstellt bzw. aktualisiert."
            }
            "write_files" -> "✅ Mehrere Dateien wurden im Projekt erstellt."
            "read_file" -> {
                val path = arg("file_path") ?: arg("path") ?: "Datei"
                "✅ Datei „$path“ wurde geladen."
            }
            "list_dir" -> {
                val path = arg("dir") ?: arg("path") ?: ""
                if (path.isBlank()) "✅ Verzeichnisinhalt wurde aufgelistet."
                else "✅ Verzeichnis „$path“ wurde aufgelistet."
            }
            "index_project" -> "✅ Projekt wurde indexiert. Du kannst jetzt Architektur-/Code-Fragen stellen."
            "analyze_architecture" -> "✅ Relevante Code-Stellen zur Architektur wurden geladen."
            else -> null
        }
    }
}