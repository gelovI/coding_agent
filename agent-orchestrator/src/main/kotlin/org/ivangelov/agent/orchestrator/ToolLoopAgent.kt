package org.ivangelov.agent.orchestrator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.ivangelov.agent.core.agent.PlanParser
import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.core.model.ChatMessage.Role
import org.ivangelov.agent.core.ports.ContextPack
import org.ivangelov.agent.core.ports.LLMClient
import org.ivangelov.agent.db.ChatRepository
import org.ivangelov.agent.memory.service.MemoryService
import org.ivangelov.agent.orchestrator.input.DefaultUserInputSanitizer
import org.ivangelov.agent.orchestrator.input.UserInputSanitizer
import org.ivangelov.agent.orchestrator.llm.DefaultResponseInterpreter
import org.ivangelov.agent.orchestrator.llm.ResponseInterpreter
import org.ivangelov.agent.orchestrator.tools.DefaultToolExecutionService
import org.ivangelov.agent.orchestrator.tools.ToolExecutionService
import org.ivangelov.agent.tools.ToolRegistry
import org.ivangelov.agent.orchestrator.mode.AgentMode
import org.ivangelov.agent.orchestrator.mode.AgentModeResolver
import org.ivangelov.agent.orchestrator.mode.HeuristicAgentModeResolver
import org.ivangelov.agent.orchestrator.prompt.DefaultPromptBuilder
import org.ivangelov.agent.orchestrator.prompt.PromptBuilder
import org.ivangelov.agent.orchestrator.memory.DefaultMemoryCoordinator
import org.ivangelov.agent.orchestrator.memory.MemoryCoordinator
import org.ivangelov.agent.core.agent.AgentEvent
import org.ivangelov.agent.orchestrator.state.AgentState
import org.ivangelov.agent.orchestrator.state.AgentStateMachine
import org.ivangelov.agent.orchestrator.state.DefaultAgentStateMachine
import org.ivangelov.agent.core.agent.AgentError
import org.ivangelov.agent.core.agent.AgentResult

class ToolLoopAgentFacade(
    private val repo: ChatRepository,
    private val tenantId: String,
    private val conversationId: String,
    private val llm: LLMClient,
    private val tools: ToolRegistry,
    private val memory: MemoryService,
    private val projectId: String? = null,
    private val maxSteps: Int = 4,
    private val maxToolCallsTotal: Int = 12,
    private val maxToolOutputChars: Int = 20_000,
) : AgentFacade {

    private val validator = ToolValidator()
    private val inputSanitizer: UserInputSanitizer = DefaultUserInputSanitizer
    private val responseInterpreter: ResponseInterpreter = DefaultResponseInterpreter()
    private val modeResolver: AgentModeResolver = HeuristicAgentModeResolver()
    private val promptBuilder: PromptBuilder = DefaultPromptBuilder()
    private val memoryCoordinator: MemoryCoordinator = DefaultMemoryCoordinator(memory)
    private val stateMachine: AgentStateMachine = DefaultAgentStateMachine()
    private val toolPlanValidator: ToolPlanValidator = DefaultToolPlanValidator(validator)
    private val toolExecutionService: ToolExecutionService =
        DefaultToolExecutionService(
            tools = tools,
            validator = validator,
            maxToolOutputChars = maxToolOutputChars
        )

    override fun send(userText: String): Flow<AgentEvent> = flow {
        val sanitizedUserText = inputSanitizer.sanitize(userText)
        val modeDecision = modeResolver.resolve(userText)

        var currentState = stateMachine.initialState()

        currentState = AgentState.ROUTE
        println("AGENT_STATE: START -> ROUTE")

        val routeTransition = stateMachine.route(modeDecision.mode)
        currentState = routeTransition.to
        println("AGENT_STATE: ROUTE -> ${routeTransition.to} (${routeTransition.reason})")

        repo.appendMessage(conversationId, Role.USER, sanitizedUserText)

        emit(
            AgentEvent.UserMessageStored(
                conversationId = conversationId,
                text = sanitizedUserText
            )
        )

        memoryCoordinator.storeUserTurn(
            tenantId = tenantId,
            conversationId = conversationId,
            projectId = projectId,
            text = sanitizedUserText
        )

        // Deterministic path: User verlangt explizit ein Tool
        if (modeDecision.mode == AgentMode.EXPLICIT_TOOL) {
            println("AGENT_STATE_ENTER: EXPLICIT_TOOL_FLOW")

            currentState = AgentState.EXECUTE_TOOLS
            println("AGENT_STATE: -> EXECUTE_TOOLS")

            val explicitTool = requireNotNull(modeDecision.explicitToolName)
            val args = ToolArgsNormalizer.normalize(explicitTool, buildJsonObject { })

            when (val result = executeOneTool(explicitTool, args)) {
                is AgentResult.Success -> {
                    emit(
                        AgentEvent.ToolExecuted(
                            toolName = result.value.toolName,
                            output = result.value.rawOutput
                        )
                    )

                    result.value.userMessage?.let {
                        emit(AgentEvent.AssistantMessage(it))
                    }
                }

                is AgentResult.Failure -> {
                    val msg = when (val error = result.error) {
                        is AgentError.ToolFailure ->
                            "Tool ${error.toolName} fehlgeschlagen: ${error.message}"

                        is AgentError.ToolValidationFailure -> {
                            if (error.toolName == "write_file") {
                                "[write_file]\nINVALID_TOOL_ARGS: ${error.message}\nwrite_file requires:\n- path: relative file path inside the project\n- content: full file content string\nReturn corrected JSON only."
                            } else {
                                "[${error.toolName}]\nINVALID_TOOL_ARGS: ${error.message}"
                            }
                        }

                        else ->
                            "Tool-Ausführung fehlgeschlagen."
                    }

                    currentState = AgentState.FAILED
                    emit(AgentEvent.AssistantMessage(msg))
                    emit(AgentEvent.Completed)
                    return@flow
                }
            }

            currentState = AgentState.FINALIZE
            println("AGENT_STATE: -> FINALIZE")

            currentState = AgentState.COMPLETE
            println("AGENT_STATE: -> COMPLETE")
            emit(AgentEvent.Completed)
            return@flow
        }

        // 1) Knowledge Mode: Architektur/Erklär-Fragen -> normaler Chat-Modus (kein JSON Tool Mode)
        if (modeDecision.mode == AgentMode.KNOWLEDGE) {
            println("AGENT_STATE_ENTER: KNOWLEDGE_FLOW")

            currentState = AgentState.RETRIEVE_MEMORY
            println("AGENT_STATE: -> RETRIEVE_MEMORY")

            val retrieved = memoryCoordinator.retrieveForKnowledge(
                tenantId = tenantId,
                conversationId = conversationId,
                projectId = projectId,
                query = userText,
                topK = 8
            )

            currentState = AgentState.BUILD_PROMPT
            println("AGENT_STATE: -> BUILD_PROMPT")

            if (retrieved.isEmpty()) {
                val msg = "Ich finde aktuell keine relevanten Code-Chunks im Memory. " +
                        "Bitte prüfen: Projekt ausgewählt? projectId=$projectId conversationId=$conversationId. " +
                        "Danach erneut indexieren (index_project)."
                repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                currentState = AgentState.COMPLETE
                emit(AgentEvent.AssistantMessage(msg))
                emit(AgentEvent.Completed)
                return@flow
            }

            val ctx = ContextPack(
                pinned = emptyList(),
                retrieved = retrieved.map { it.copy(content = it.content.take(350)) },
                recentSummary = null
            )

            val messages = promptBuilder.buildForKnowledge(history())

            currentState = AgentState.CALL_LLM
            println("AGENT_STATE: -> CALL_LLM")

            val resp = runCatching {
                llm.complete(messages, ctx, org.ivangelov.agent.core.ports.LlmResponseFormat.TEXT)
            }.getOrElse { e ->
                val msg = "LLM_ERROR: ${e::class.simpleName}: ${e.message}"
                repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                currentState = AgentState.FAILED
                emit(AgentEvent.AssistantMessage(msg))
                emit(AgentEvent.Completed)
                return@flow
            }

            currentState = AgentState.PARSE_RESPONSE
            println("AGENT_STATE: -> PARSE_RESPONSE")

            val answerRaw = resp.content?.trim().takeUnless { it.isNullOrBlank() }
                ?: resp.thinking?.trim().takeUnless { it.isNullOrBlank() }
                ?: "Ich konnte keine Antwort generieren."

            println("KNOWLEDGE_RAW >>> $answerRaw")
            val answer = responseInterpreter.toDisplayText(answerRaw)

            currentState = AgentState.FINALIZE
            println("AGENT_STATE: -> FINALIZE")

            repo.appendMessage(conversationId, Role.ASSISTANT, answer)
            currentState = AgentState.COMPLETE
            println("AGENT_STATE: -> COMPLETE")
            emit(AgentEvent.AssistantMessage(answer))
            emit(AgentEvent.Completed)
            return@flow
        }

        var step = 0
        var toolCallsUsed = 0

        val executedThisTurn = mutableSetOf<String>()

        suspend fun execOnce(toolNameRaw: String, argsRaw: JsonObject): Boolean {
            val resolvedToolName = toolNameRaw
            val normalizedArgs = argsRaw

            println("DEBUG_TOOL_ARGS_NORMALIZED")
            println("tool=$resolvedToolName")
            println("args=$normalizedArgs")

            val key = "$resolvedToolName|$normalizedArgs"
            if (!executedThisTurn.add(key)) return false

            return when (val result = executeOneTool(resolvedToolName, normalizedArgs)) {
                is AgentResult.Success -> {
                    emit(
                        AgentEvent.ToolExecuted(
                            toolName = result.value.toolName,
                            output = result.value.rawOutput
                        )
                    )

                    result.value.userMessage?.let {
                        emit(AgentEvent.AssistantMessage(it))
                    }

                    true
                }

                is AgentResult.Failure -> {
                    val msg = when (val error = result.error) {
                        is AgentError.ToolFailure ->
                            "Tool ${error.toolName} fehlgeschlagen: ${error.message}"

                        is AgentError.ToolValidationFailure ->
                            if (error.toolName == "write_file") {
                                "Ungültige Tool-Argumente für write_file: ${error.message}\n" +
                                        "write_file benötigt:\n" +
                                        "- path: relativer Dateipfad im Projekt\n" +
                                        "- content: kompletter Dateiinhalt als String"
                            } else if (error.toolName == "write_files") {
                                "Ungültige Tool-Argumente für write_files: ${error.message}\n" +
                                        "write_files benötigt:\n" +
                                        "- files: Array von Objekten\n" +
                                        "- jedes Objekt mit:\n" +
                                        "  - path\n" +
                                        "  - content"
                            } else {
                                "Ungültige Tool-Argumente für ${error.toolName}: ${error.message}"
                            }

                        else ->
                            "Tool-Ausführung fehlgeschlagen."
                    }

                    emit(AgentEvent.AssistantMessage(msg))
                    false
                }
            }
        }

        var nonJsonViolations = 0
        val maxNonJsonViolations = 2

        var validationFailures = 0
        val maxValidationFailures = 2

        println("AGENT_STATE_ENTER: TOOL_LOOP_FLOW")

        while (step++ < maxSteps) {
            var toolCallsExecutedThisStep = 0

            currentState = AgentState.RETRIEVE_MEMORY
            println("AGENT_STATE: -> RETRIEVE_MEMORY")

            val retrieved = emptyList<ChatMessage>()

            currentState = AgentState.BUILD_PROMPT
            println("AGENT_STATE: -> BUILD_PROMPT")

            val messages = promptBuilder.buildForToolLoop(history(), tools)


            val ctx = ContextPack(
                pinned = emptyList(),
                retrieved = retrieved,
                recentSummary = null
            )

            currentState = AgentState.CALL_LLM
            println("AGENT_STATE: -> CALL_LLM")

            val toolModeResult = llm.completeToolMode(messages, ctx)

            currentState = AgentState.PARSE_RESPONSE
            println("AGENT_STATE: -> PARSE_RESPONSE")

            val toolModeResponse = when (toolModeResult) {
                is AgentResult.Success -> toolModeResult.value

                is AgentResult.Failure -> {
                    val msg = when (val error = toolModeResult.error) {
                        is AgentError.LlmFailure -> "LLM_ERROR: ${error.message}"
                        else -> "LLM_ERROR: Unbekannter Fehler im Tool-Mode."
                    }

                    repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                    currentState = AgentState.FAILED
                    println("AGENT_STATE: -> FAILED (llm error)")
                    emit(AgentEvent.AssistantMessage(msg))
                    emit(AgentEvent.Completed)
                    return@flow
                }
            }

            val rawContent = toolModeResponse.rawContent.orEmpty()
            val rawThinking = toolModeResponse.rawThinking.orEmpty()

            val structuredPlan = org.ivangelov.agent.core.agent.AgentPlan(
                toolCalls = toolModeResponse.toolCalls.map {
                    org.ivangelov.agent.core.agent.PlannedToolCall(
                        name = it.name,
                        args = it.args
                    )
                },
                reply = toolModeResponse.reply
            )

            println("DEBUG_STRUCTURED_PLAN")
            println("toolCalls=${structuredPlan.toolCalls}")
            println("reply=${structuredPlan.reply}")

            val plan = if (structuredPlan.toolCalls.isNotEmpty() || !structuredPlan.reply.isNullOrBlank()) {
                structuredPlan
            } else {
                var parsedFallback = PlanParser.parseOrNull(rawContent)
                if (parsedFallback == null && rawThinking.isNotBlank()) {
                    parsedFallback = PlanParser.parseOrNull(rawThinking)
                }

                if (parsedFallback == null) {
                    nonJsonViolations++

                    repo.appendMessage(
                        conversationId,
                        Role.TOOL,
                        "[llm_violation]\nNon-JSON output received (retry $nonJsonViolations/$maxNonJsonViolations):\n" +
                                rawContent.ifBlank { rawThinking.ifBlank { "(empty)" } }
                    )

                    if (nonJsonViolations > maxNonJsonViolations) {
                        val msg =
                            "Das Modell hat mehrfach keinen gültigen Tool-Plan geliefert. Bitte erneut versuchen oder Anfrage vereinfachen."
                        repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                        currentState = AgentState.FAILED
                        emit(AgentEvent.AssistantMessage(msg))
                        emit(AgentEvent.Completed)
                        return@flow
                    }

                    continue
                }

                parsedFallback
            }

            println("DEBUG_PLAN_BEFORE_VALIDATION")
            println("plan=$plan")

            val validatedPlanResult = toolPlanValidator.validate(plan)

            val validatedPlan = when (validatedPlanResult) {
                is AgentResult.Success -> validatedPlanResult.value

                is AgentResult.Failure -> {
                    val errorMsg = when (val error = validatedPlanResult.error) {
                        is AgentError.InvalidPlan -> error.raw
                        else -> "Unknown tool plan validation error"
                    }

                    println("DEBUG_PLAN_VALIDATION_FAILED")
                    println(errorMsg)

                    validationFailures++

                    repo.appendMessage(
                        conversationId,
                        Role.TOOL,
                        "[tool_validation_error]\n$errorMsg"
                    )

                    if (validationFailures > maxValidationFailures) {
                        val msg = "Tool-Plan konnte nicht korrigiert werden."
                        repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                        currentState = AgentState.FAILED
                        emit(AgentEvent.AssistantMessage(msg))
                        emit(AgentEvent.Completed)
                        return@flow
                    }

                    continue
                }
            }

            // 2) Final answer
            if (validatedPlan.toolCalls.isEmpty()) {
                currentState = AgentState.FINALIZE
                println("AGENT_STATE: -> FINALIZE")

                val finalRaw = validatedPlan.reply.orEmpty().ifBlank { rawContent }.trim()
                val final = responseInterpreter.toDisplayText(finalRaw)

                memoryCoordinator.storeAssistantTurn(
                    tenantId = tenantId,
                    conversationId = conversationId,
                    projectId = projectId,
                    text = final
                )

                repo.appendMessage(conversationId, Role.ASSISTANT, final)
                currentState = AgentState.COMPLETE
                println("AGENT_STATE: -> COMPLETE")
                emit(AgentEvent.AssistantMessage(final))
                emit(AgentEvent.Completed)
                return@flow
            }

            // 3) Execute tool calls
            for (tc in validatedPlan.toolCalls) {
                println("DEBUG_TOOL_CALL_FROM_MODEL")
                println("name=${tc.name}")
                println("args_raw=${tc.args}")

                if (toolCallsUsed++ >= maxToolCallsTotal) {
                    val msg = "Max tool calls reached ($maxToolCallsTotal)."
                    repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                    currentState = AgentState.FAILED
                    emit(AgentEvent.AssistantMessage(msg))
                    emit(AgentEvent.Completed)
                    return@flow
                }

                val executedSuccessfully = execOnce(tc.name, tc.args)
                if (executedSuccessfully) {
                    toolCallsExecutedThisStep++
                }
            }

            if (toolCallsExecutedThisStep > 0) {
                println("AGENT_STATE: -> FINALIZE (tool execution done)")
                currentState = AgentState.FINALIZE

                emit(AgentEvent.Completed)
                return@flow
            }

            val msg = "Es wurden keine neuen gültigen Tool-Schritte ausgeführt."
            repo.appendMessage(conversationId, Role.ASSISTANT, msg)
            currentState = AgentState.FAILED
            println("AGENT_STATE: -> FAILED (no effective tool execution)")
            emit(AgentEvent.AssistantMessage(msg))
            emit(AgentEvent.Completed)
            return@flow
        }

        val msg = "Max steps reached ($maxSteps). Please narrow the request."
        repo.appendMessage(conversationId, Role.ASSISTANT, msg)
        currentState = AgentState.FAILED
        emit(AgentEvent.AssistantMessage(msg))
        emit(AgentEvent.Completed)
    }

    override fun history(): List<ChatMessage> {
        val msgs = repo.loadMessages(conversationId)
        return msgs.map { ChatMessage(Role.valueOf(it.role), it.content) }
    }

    private suspend fun executeOneTool(
        toolNameRaw: String,
        argsAlreadyNormalized: JsonObject
    ): AgentResult<org.ivangelov.agent.orchestrator.tools.ToolExecutionResult> {
        return when (val result = toolExecutionService.execute(toolNameRaw, argsAlreadyNormalized)) {
            is AgentResult.Success -> {
                val value = result.value

                repo.appendMessage(conversationId, Role.TOOL, "[${value.toolName}]\n${value.rawOutput}")

                value.userMessage?.let { shortMsg ->
                    repo.appendMessage(conversationId, Role.ASSISTANT, shortMsg)
                }

                AgentResult.Success(value)
            }

            is AgentResult.Failure -> {
                val errorText = when (val error = result.error) {

                    is AgentError.ToolFailure ->
                        "[${error.toolName}]\nTOOL_FAILURE: ${error.message}"

                    is AgentError.ToolValidationFailure -> {

                        when (error.toolName) {

                            "write_file" ->
                                """
                                [write_file]
                                INVALID_TOOL_ARGS: ${error.message}
                                
                                write_file requires:
                                - path: relative file path inside the project
                                - content: full file content string
                                
                                Example:
                                {
                                  "tool_calls": [
                                    {
                                      "name": "write_file",
                                      "args": {
                                        "path": "domain/User.kt",
                                        "content": "data class User(val id: String)"
                                      }
                                    }
                                  ],
                                  "reply": ""
                                }
                                
                                Return corrected JSON only.
                                """.trimIndent()

                                                            "write_files" ->
                                                                """
                                [write_files]
                                INVALID_TOOL_ARGS: ${error.message}
                                
                                write_files requires:
                                - files: array of file objects
                                
                                Each file object must contain:
                                - path
                                - content
                                
                                Example:
                                {
                                  "tool_calls": [
                                    {
                                      "name": "write_files",
                                      "args": {
                                        "files": [
                                          {
                                            "path": "domain/User.kt",
                                            "content": "data class User(val id: String)"
                                          }
                                        ]
                                      }
                                    }
                                  ],
                                  "reply": ""
                                }
                                
                                Return corrected JSON only.
                                """.trimIndent()
                            else ->
                                "[${error.toolName}]\nINVALID_TOOL_ARGS: ${error.message}"
                        }
                    }

                    else ->
                        "[tool]\nUNEXPECTED_TOOL_ERROR"
                }

                repo.appendMessage(conversationId, Role.TOOL, errorText)

                AgentResult.Failure(result.error)
            }
        }
    }
}