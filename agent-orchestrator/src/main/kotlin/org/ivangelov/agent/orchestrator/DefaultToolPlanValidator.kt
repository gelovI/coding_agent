package org.ivangelov.agent.orchestrator

import org.ivangelov.agent.core.agent.AgentError
import org.ivangelov.agent.core.agent.AgentPlan
import org.ivangelov.agent.core.agent.AgentResult
import org.ivangelov.agent.orchestrator.tools.DefaultToolNameResolver

class DefaultToolPlanValidator(
    private val toolValidator: ToolValidator = ToolValidator()
) : ToolPlanValidator {

    override fun validate(plan: AgentPlan): AgentResult<ValidatedAgentPlan> {
        val validatedCalls = mutableListOf<ValidatedToolCall>()

        for (call in plan.toolCalls) {
            val resolvedToolName = DefaultToolNameResolver().resolve(call.name)
            val normalizedArgs = ToolArgsNormalizer.normalize(resolvedToolName, call.args)

            val validationError = toolValidator.validate(resolvedToolName, normalizedArgs)
            if (validationError != null) {
                return AgentResult.Failure(
                    AgentError.InvalidPlan(
                        raw = "Tool '$resolvedToolName' has invalid args: $validationError | args=$normalizedArgs"
                    )
                )
            }

            validatedCalls += ValidatedToolCall(
                name = resolvedToolName,
                args = normalizedArgs
            )
        }

        return AgentResult.Success(
            ValidatedAgentPlan(
                toolCalls = validatedCalls,
                reply = plan.reply
            )
        )
    }
}