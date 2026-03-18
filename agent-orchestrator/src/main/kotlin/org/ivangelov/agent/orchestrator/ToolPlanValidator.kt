package org.ivangelov.agent.orchestrator

import org.ivangelov.agent.core.agent.AgentResult
import org.ivangelov.agent.core.agent.AgentPlan

interface ToolPlanValidator {
    fun validate(plan: AgentPlan): AgentResult<ValidatedAgentPlan>
}