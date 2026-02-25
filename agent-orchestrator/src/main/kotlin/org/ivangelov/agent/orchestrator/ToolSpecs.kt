package org.ivangelov.agent.orchestrator

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ToolSpec(
    val name: String,
    val required: Set<String>,
    val optional: Set<String> = emptySet()
) {
    val allowed: Set<String> = required + optional
}

object ToolSpecs {
    val ALL: Map<String, ToolSpec> = mapOf(
        "list_dir" to ToolSpec("list_dir", required = setOf("path")),
        "read_file" to ToolSpec("read_file", required = setOf("path")),
        "write_file" to ToolSpec("write_file", required = setOf("path", "content")),
        "index_project" to ToolSpec(
            name = "index_project",
            required = emptySet(),
            optional = setOf("path", "max_files", "include_ext")
        ),
        "analyze_architecture" to ToolSpec(
            name = "analyze_architecture",
            required = emptySet(),
            optional = setOf("query", "top_k")
        )
    )
}

class ToolValidator(
    private val specs: Map<String, ToolSpec> = ToolSpecs.ALL
) {
    /**
     * @return null if ok, otherwise error message
     */
    fun validate(toolName: String, args: JsonObject): String? {
        val spec = specs[toolName] ?: return "Unknown tool '$toolName'"

        val keys = args.keys
        val missing = spec.required - keys
        if (missing.isNotEmpty()) return "Missing keys: ${missing.joinToString()}"

        val unexpected = keys - spec.allowed
        if (unexpected.isNotEmpty()) return "Unexpected keys: ${unexpected.joinToString()}"

        // Optional: basic safety checks for "path"
        args["path"]?.jsonPrimitive?.contentOrNull?.let { p ->
            if (p.isBlank()) return "Invalid path: blank"
            if (p.startsWith("/") || p.contains(":\\") || p.contains(":/")) return "Invalid path: absolute not allowed ($p)"
            if (p.contains("..")) return "Invalid path: traversal not allowed ($p)"
        }

        return null
    }
}