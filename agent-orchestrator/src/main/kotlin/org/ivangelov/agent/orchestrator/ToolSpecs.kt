package org.ivangelov.agent.orchestrator

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ToolArgSpec(
    val name: String,
    val description: String,
    val required: Boolean
)

data class ToolSpec(
    val name: String,
    val description: String,
    val args: List<ToolArgSpec>,
    val exampleArgs: JsonObject? = null
) {
    val required: Set<String> = args.filter { it.required }.map { it.name }.toSet()
    val optional: Set<String> = args.filterNot { it.required }.map { it.name }.toSet()
    val allowed: Set<String> = required + optional
}

object ToolSpecs {

    val ALL: Map<String, ToolSpec> = listOf(
        ToolSpec(
            name = "list_dir",
            description = "List files and directories inside the current project path.",
            args = listOf(
                ToolArgSpec(
                    name = "path",
                    description = "Relative directory path inside the project. Use '.' for the project root.",
                    required = true
                )
            ),
            exampleArgs = buildJsonObject {
                put("path", ".")
            }
        ),

        ToolSpec(
            name = "read_file",
            description = "Read a single file from the current project.",
            args = listOf(
                ToolArgSpec(
                    name = "path",
                    description = "Relative file path inside the project.",
                    required = true
                )
            ),
            exampleArgs = buildJsonObject {
                put("path", "src/main/kotlin/App.kt")
            }
        ),

        ToolSpec(
            name = "write_file",
            description = "Write a single file into the current project. Creates missing parent directories if needed.",
            args = listOf(
                ToolArgSpec(
                    name = "path",
                    description = "Relative file path inside the project.",
                    required = true
                ),
                ToolArgSpec(
                    name = "content",
                    description = "Full file content as a string.",
                    required = true
                )
            ),
            exampleArgs = buildJsonObject {
                put("path", "domain/User.kt")
                put("content", "data class User(val id: String)")
            }
        ),

        ToolSpec(
            name = "write_files",
            description = "Write multiple files into the current project in one step.",
            args = listOf(
                ToolArgSpec(
                    name = "files",
                    description = "Array of file objects. Each object must contain 'path' and 'content'.",
                    required = true
                )
            ),
            exampleArgs = buildJsonObject {
                putJsonArray("files") {
                    add(
                        buildJsonObject {
                            put("path", "domain/User.kt")
                            put("content", "data class User(val id: String)")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("path", "service/UserService.kt")
                            put("content", "class UserService")
                        }
                    )
                }
            }
        ),

        ToolSpec(
            name = "index_project",
            description = "Index project files into memory for semantic retrieval and later code questions.",
            args = listOf(
                ToolArgSpec(
                    name = "path",
                    description = "Optional relative start path inside the project.",
                    required = false
                ),
                ToolArgSpec(
                    name = "max_files",
                    description = "Optional maximum number of files to index.",
                    required = false
                ),
                ToolArgSpec(
                    name = "include_ext",
                    description = "Optional list or text filter for file extensions to include.",
                    required = false
                )
            ),
            exampleArgs = buildJsonObject {
                put("path", ".")
                put("max_files", 200)
            }
        ),

        ToolSpec(
            name = "analyze_architecture",
            description = "Retrieve architecture-relevant project knowledge from memory.",
            args = listOf(
                ToolArgSpec(
                    name = "query",
                    description = "Optional architecture question or focus area.",
                    required = false
                ),
                ToolArgSpec(
                    name = "top_k",
                    description = "Optional number of relevant chunks to retrieve.",
                    required = false
                )
            ),
            exampleArgs = buildJsonObject {
                put("query", "Wie ist die Architektur aufgebaut?")
                put("top_k", 8)
            }
        )
    ).associateBy { it.name }

    fun renderForPrompt(): String {
        return ALL.values.joinToString("\n\n") { spec ->
            buildString {
                appendLine("Tool: ${spec.name}")
                appendLine("Description: ${spec.description}")

                val requiredArgs = spec.args.filter { it.required }
                val optionalArgs = spec.args.filterNot { it.required }

                appendLine("Required args:")
                if (requiredArgs.isEmpty()) {
                    appendLine("- (none)")
                } else {
                    requiredArgs.forEach { arg ->
                        appendLine("- ${arg.name}: ${arg.description}")
                    }
                }

                appendLine("Optional args:")
                if (optionalArgs.isEmpty()) {
                    appendLine("- (none)")
                } else {
                    optionalArgs.forEach { arg ->
                        appendLine("- ${arg.name}: ${arg.description}")
                    }
                }

                spec.exampleArgs?.let {
                    appendLine("Example args:")
                    appendLine(it.toString())
                }
            }.trim()
        }
    }
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

        args["path"]?.jsonPrimitive?.contentOrNull?.let { p ->
            if (p.isBlank()) return "Invalid path: blank"
            if (p.startsWith("/") || p.contains(":\\") || p.contains(":/")) return "Invalid path: absolute not allowed ($p)"
            if (p.contains("..")) return "Invalid path: traversal not allowed ($p)"
        }

        return null
    }
}