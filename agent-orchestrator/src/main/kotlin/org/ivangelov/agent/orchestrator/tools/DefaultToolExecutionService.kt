package org.ivangelov.agent.orchestrator.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.orchestrator.ToolValidator
import org.ivangelov.agent.tools.ToolRegistry
import org.ivangelov.agent.core.agent.AgentError
import org.ivangelov.agent.core.agent.AgentResult

class DefaultToolExecutionService(
    private val tools: ToolRegistry,
    private val validator: ToolValidator = ToolValidator(),
    private val nameResolver: ToolNameResolver = DefaultToolNameResolver(),
    private val presenter: ToolResultPresenter = DefaultToolResultPresenter(),
    private val maxToolOutputChars: Int = 20_000
) : ToolExecutionService {

    override suspend fun execute(
        toolNameRaw: String,
        argsAlreadyNormalized: JsonObject
    ): AgentResult<ToolExecutionResult> {
        val toolName = nameResolver.resolve(toolNameRaw)
        val tool = tools.get(toolName)

        val args = ensureRequiredDefaults(toolName, argsAlreadyNormalized)
        val validationError = validator.validate(toolName, args)

        if (tool == null) {
            return AgentResult.Failure(
                AgentError.ToolFailure(
                    toolName = toolName,
                    message = "Tool not found. Available: ${tools.list().joinToString()}"
                )
            )
        }

        if (validationError != null) {
            return AgentResult.Failure(
                AgentError.ToolValidationFailure(
                    toolName = toolName,
                    message = validationError
                )
            )
        }

        return runCatching {
            val r = tool.execute(ToolCall(toolName, args))
            val text = if (r.ok) r.content else "ERROR: ${r.content}"

            if (!r.ok) {
                AgentResult.Failure(
                    AgentError.ToolFailure(
                        toolName = toolName,
                        message = r.content
                    )
                )
            } else {
                AgentResult.Success(
                    ToolExecutionResult(
                        toolName = toolName,
                        normalizedArgs = args,
                        ok = true,
                        rawOutput = text.take(maxToolOutputChars),
                        userMessage = presenter.successMessage(toolName, args)
                    )
                )
            }
        }.getOrElse { e ->
            AgentResult.Failure(
                AgentError.ToolFailure(
                    toolName = toolName,
                    message = "${e::class.simpleName}: ${e.message}"
                )
            )
        }
    }

    private fun ensureRequiredDefaults(toolName: String, args: JsonObject): JsonObject {
        if (toolName == "list_dir" && !args.containsKey("path")) {
            return buildJsonObject {
                put("path", ".")
            }
        }
        return args
    }
}