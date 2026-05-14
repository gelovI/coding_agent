package org.ivangelov.agent.orchestrator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.ivangelov.agent.core.agent.AgentError
import org.ivangelov.agent.core.agent.AgentEvent
import org.ivangelov.agent.core.agent.AgentPlan
import org.ivangelov.agent.core.agent.AgentResult
import org.ivangelov.agent.core.agent.PlanParser
import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.core.model.ChatMessage.Role
import org.ivangelov.agent.core.ports.ContextPack
import org.ivangelov.agent.core.ports.LLMClient
import org.ivangelov.agent.core.ports.LlmResponseFormat
import org.ivangelov.agent.db.ChatRepository
import org.ivangelov.agent.memory.core.MemoryScope
import org.ivangelov.agent.memory.core.MemoryType
import org.ivangelov.agent.memory.service.MemoryService
import org.ivangelov.agent.orchestrator.input.DefaultUserInputSanitizer
import org.ivangelov.agent.orchestrator.input.UserInputSanitizer
import org.ivangelov.agent.orchestrator.memory.DefaultMemoryCoordinator
import org.ivangelov.agent.orchestrator.memory.MemoryCoordinator
import org.ivangelov.agent.orchestrator.prompt.DefaultPromptBuilder
import org.ivangelov.agent.orchestrator.prompt.PromptBuilder
import org.ivangelov.agent.orchestrator.tools.DefaultToolExecutionService
import org.ivangelov.agent.orchestrator.tools.ToolExecutionResult
import org.ivangelov.agent.orchestrator.tools.ToolExecutionService
import org.ivangelov.agent.tools.ToolRegistry
import org.ivangelov.agent.tools.code.indexing.CodeChunker
import org.ivangelov.agent.orchestrator.edit.BlockRewritePromptFactory
import org.ivangelov.agent.orchestrator.edit.EditIntentDetector
import org.ivangelov.agent.orchestrator.edit.EditStrategy
import org.ivangelov.agent.orchestrator.edit.EditStrategySelector
import org.ivangelov.agent.orchestrator.edit.KotlinBlockExtractor
import org.ivangelov.agent.orchestrator.edit.PatchBuilder
import org.ivangelov.agent.orchestrator.edit.TargetBlock
import java.util.UUID

class ToolLoopAgentFacade(
    private val repo: ChatRepository,
    private val tenantId: String,
    private val conversationId: String,
    private val llm: LLMClient,
    private val tools: ToolRegistry,
    private val memory: MemoryService,
    private val projectRoot: Path,
    private val projectId: String? = null,
    private val maxSteps: Int = 4,
    private val maxToolCallsTotal: Int = 12,
    private val maxToolOutputChars: Int = 20_000,
) : AgentFacade {

    private val validator = ToolValidator()
    private val inputSanitizer: UserInputSanitizer = DefaultUserInputSanitizer
    private val promptBuilder: PromptBuilder = DefaultPromptBuilder()
    private val memoryCoordinator: MemoryCoordinator = DefaultMemoryCoordinator(memory)
    private val toolPlanValidator: ToolPlanValidator = DefaultToolPlanValidator(validator)
    private val toolExecutionService: ToolExecutionService =
        DefaultToolExecutionService(
            tools = tools,
            validator = validator,
            maxToolOutputChars = maxToolOutputChars
        )

    private fun String.redactedForLog(maxChars: Int = 500): String {
        val compact = replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxChars) compact else compact.take(maxChars) + "...(truncated)"
    }

    private fun JsonObject.redactedForLog(maxChars: Int = 500): String =
        toString().redactedForLog(maxChars)

    private val pathResolver = ProjectPathResolver(projectRoot)

    private var smartEditAttempted: Boolean = false
    private var smartEditFailed: Boolean = false
    private var smartEditPath: String? = null

    private var scaffoldingPlanRejectedCount: Int = 0
    private var pendingMutationApproval: PendingMutationApproval? = null
    private var approvedMutationRequestId: String? = null

    private enum class RequestMode {
        ANALYSIS,
        MODIFICATION,
        SCAFFOLDING
    }

    private data class ToolLoopSessionState(
        var step: Int = 0,
        var toolCallsUsed: Int = 0,
        var nonJsonViolations: Int = 0,
        var validationFailures: Int = 0,
        var executedAtLeastOne: Boolean = false,
        var mutatingToolExecutedSuccessfully: Boolean = false,
        var successfulReadFileOutput: String? = null,
        var lastResolvedReadPath: String? = null,
        var replaceRecoveryReadCount: Int = 0,
        var consecutiveSamePathReadCount: Int = 0,
        var lastReadPathAcrossSteps: String? = null,
        var discoveryReady: Boolean = false,
        var indexConfirmed: Boolean = false
    )

    private data class DiscoveryState(
        val rootListed: Boolean = false,
        val buildFilesRead: Boolean = false,
        val manifestRead: Boolean = false,
        val entryPointsRead: Boolean = false,
        val projectIndexAvailable: Boolean = false
    ) {
        val isReadyForScaffoldingPlanning: Boolean
            get() = rootListed && buildFilesRead
    }

    private data class ExecutionCycleResult(
        val mutated: Boolean,
        val readSomething: Boolean,
        val shouldContinue: Boolean = true,
        val approvalRequested: Boolean = false
    )

    private data class PendingMutationApproval(
        val requestId: String,
        val plan: ValidatedAgentPlan,
        val state: ToolLoopSessionState,
        val userText: String,
        val retrieved: List<ChatMessage>,
        val successMessage: String
    )

    private data class ScaffoldingBlueprint(
        val summary: String,
        val files: List<ScaffoldFilePlan>
    )

    private data class ScaffoldFilePlan(
        val path: String,
        val purpose: String
    )

    private data class ProjectStyleSnapshot(
        val composeEnabled: Boolean,
        val usesComponentActivity: Boolean,
        val packageName: String?,
        val knownSymbols: Set<String>,
        val availableLibraries: Set<String>
    )

    private data class GeneratedFileReview(
        val accepted: Boolean,
        val reason: String? = null
    )

    private fun resetTurnState() {
        smartEditAttempted = false
        smartEditFailed = false
        smartEditPath = null
        scaffoldingPlanRejectedCount = 0
        pendingMutationApproval = null
        approvedMutationRequestId = null
    }

    private fun detectRequestMode(userText: String): RequestMode {
        return when {
            wantsProjectScaffolding(userText) -> RequestMode.SCAFFOLDING
            wantsFileModification(userText) -> RequestMode.MODIFICATION
            else -> RequestMode.ANALYSIS
        }
    }

    override fun send(userText: String): Flow<AgentEvent> = flow {
        resetTurnState()

        val sanitizedUserText = inputSanitizer.sanitize(userText)

        val emitEvent: suspend (AgentEvent) -> Unit = { event ->
            emit(event)
        }

        storeIncomingUserTurn(sanitizedUserText, emitEvent)

        when (detectRequestMode(sanitizedUserText)) {
            RequestMode.SCAFFOLDING -> runScaffoldingLoop(sanitizedUserText, emitEvent)
            RequestMode.MODIFICATION -> runModificationLoop(sanitizedUserText, emitEvent)
            RequestMode.ANALYSIS -> runAnalysisLoop(sanitizedUserText, emitEvent)
        }
    }

    fun approvePendingMutation(requestId: String): Flow<AgentEvent> = flow {
        val emitEvent: suspend (AgentEvent) -> Unit = { event -> emit(event) }
        val pending = pendingMutationApproval

        if (pending == null || pending.requestId != requestId) {
            failWithMessage("Keine ausstehende Schreibbestätigung gefunden.", emitEvent)
            return@flow
        }

        pendingMutationApproval = null
        approvedMutationRequestId = requestId

        try {
            val cycle = executeValidatedPlan(
                plan = pending.plan,
                state = pending.state,
                userText = pending.userText,
                retrieved = pending.retrieved,
                emit = emitEvent,
                approvalSuccessMessage = pending.successMessage
            )

            if (cycle.mutated) {
                finalizeAssistantReply(pending.successMessage, emitEvent)
            } else {
                failWithMessage("Bestätigte Änderung konnte nicht ausgeführt werden.", emitEvent)
            }
        } finally {
            approvedMutationRequestId = null
        }
    }

    fun rejectPendingMutation(requestId: String): Flow<AgentEvent> = flow {
        val emitEvent: suspend (AgentEvent) -> Unit = { event -> emit(event) }
        val pending = pendingMutationApproval

        if (pending != null && pending.requestId == requestId) {
            pendingMutationApproval = null
        }

        failWithMessage("Schreibvorgang abgebrochen.", emitEvent)
    }

    private suspend fun storeIncomingUserTurn(
        sanitizedUserText: String,
        emit: suspend (AgentEvent) -> Unit
    ) {
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

        if (projectId != null) {
            extractProjectDecisionCandidate(sanitizedUserText)?.let { decision ->
                val stored = memoryCoordinator.storeProjectDecision(
                    tenantId = tenantId,
                    conversationId = conversationId,
                    projectId = projectId,
                    text = decision
                )
                println("PROJECT_DECISION_STORE stored=$stored text=${decision.redactedForLog()}")
            }
        }
    }

    private suspend fun runScaffoldingLoop(
        sanitizedUserText: String,
        emit: suspend (AgentEvent) -> Unit
    ) {
        println("AGENT_STATE_ENTER: SCAFFOLDING_FLOW")

        val state = ToolLoopSessionState()

        val discovery = ensureScaffoldingDiscoveryContext(
            userText = sanitizedUserText,
            state = state,
            emit = emit
        )

        state.discoveryReady = discovery.isReadyForScaffoldingPlanning
        state.indexConfirmed = discovery.projectIndexAvailable

        println(
            "DEBUG_DISCOVERY_STATE ready=${state.discoveryReady} " +
                    "rootListed=${discovery.rootListed} " +
                    "buildFilesRead=${discovery.buildFilesRead} " +
                    "manifestRead=${discovery.manifestRead} " +
                    "entryPointsRead=${discovery.entryPointsRead}"
        )

        if (!state.discoveryReady) {
            failWithMessage(
                "Abbruch: Projektstruktur konnte nicht ausreichend ermittelt werden.",
                emit
            )
            return
        }

        val retrieved = retrieveContextForMode(
            userText = sanitizedUserText,
            mode = RequestMode.SCAFFOLDING
        )

        val styleSnapshot = buildProjectStyleSnapshot(retrieved)

        println(
            "DEBUG_STYLE_SNAPSHOT composeEnabled=${styleSnapshot.composeEnabled} " +
                    "usesComponentActivity=${styleSnapshot.usesComponentActivity} " +
                    "packageName=${styleSnapshot.packageName} " +
                    "knownSymbolsCount=${styleSnapshot.knownSymbols.size} " +
                    "availableLibraries=${styleSnapshot.availableLibraries}"
        )

        val blueprint = requestScaffoldingBlueprint(
            userText = sanitizedUserText,
            retrieved = retrieved
        )

        if (blueprint == null) {
            failWithMessage(
                "Abbruch: Architektur-Blueprint konnte nicht erzeugt werden.",
                emit
            )
            return
        }

        println("DEBUG_BLUEPRINT_SUMMARY=${blueprint.summary.redactedForLog()}")
        println("DEBUG_BLUEPRINT_FILES=${blueprint.files.map { it.path }}")

        val sanitizedBlueprint = sanitizeBlueprintAgainstProject(
            blueprint = blueprint,
            snapshot = styleSnapshot
        )

        println("DEBUG_SANITIZED_BLUEPRINT_FILES=${sanitizedBlueprint.files.map { it.path }}")

        val plan = materializeBlueprintToWriteFiles(
            blueprint = sanitizedBlueprint,
            snapshot = styleSnapshot
        )
        if (plan == null) {
            failWithMessage(
                "Abbruch: Blueprint konnte nicht in write_files umgesetzt werden.",
                emit
            )
            return
        }

        println("DEBUG_BLUEPRINT_WRITE_PLAN=${plan.toString().redactedForLog()}")

        val cycle = executeValidatedPlan(
            plan = plan,
            state = state,
            userText = sanitizedUserText,
            retrieved = retrieved,
            emit = emit,
            approvalSuccessMessage = "Architektur/Projektstruktur wurde erstellt."
        )

        if (cycle.approvalRequested) return

        if (cycle.mutated) {
            finalizeAssistantReply("Architektur/Projektstruktur wurde erstellt.", emit)
        } else {
            failWithMessage(
                "Abbruch: Es konnten keine Dateien für die Architektur erstellt werden.",
                emit
            )
        }
    }

    private suspend fun requestScaffoldingBlueprint(
        userText: String,
        retrieved: List<ChatMessage>
    ): ScaffoldingBlueprint? {
        val snapshot = buildProjectStyleSnapshot(retrieved)

        val projectFacts = buildString {
            appendLine("Project facts:")
            appendLine("- package: ${snapshot.packageName ?: "unknown"}")
            appendLine("- composeEnabled: ${snapshot.composeEnabled}")
            appendLine("- usesComponentActivity: ${snapshot.usesComponentActivity}")
            appendLine("- availableLibraries: ${snapshot.availableLibraries.joinToString(",")}")
        }

        val messages = listOf(
            ChatMessage(
                Role.SYSTEM,
                """
                Plan a minimal Android scaffolding blueprint.
                Return plain text only.
                No JSON.
                No explanations.
                No tools.
                
                Output format:
                SUMMARY: one short sentence
                FILE: relative/path/File1.kt | short purpose
                FILE: relative/path/File2.kt | short purpose
                FILE: relative/path/File3.kt | short purpose
                
                Rules:
                - propose at most 3 files
                - use relative project paths only
                - keep the architecture minimal
                - do not introduce Retrofit or Room unless explicitly available
                - if the project uses Compose, keep Compose architecture
                """.trimIndent()
            ),
            ChatMessage(
                Role.USER,
                """
                Request:
                $userText
                
                $projectFacts
                """.trimIndent()
            )
        )

        println("DEBUG_BLUEPRINT_REQUEST_MESSAGES=${messages.size}")
        println("DEBUG_BLUEPRINT_REQUEST_FACTS=${projectFacts.redactedForLog()}")

        val response = try {
            llm.complete(
                messages = messages,
                context = ContextPack(
                    pinned = emptyList(),
                    retrieved = emptyList(),
                    recentSummary = null
                ),
                format = LlmResponseFormat.TEXT
            )
        } catch (e: Exception) {
            println("DEBUG_BLUEPRINT_REQUEST_FAILED=${e::class.simpleName}: ${e.message}")
            return fallbackBlueprint(snapshot, userText)
        }

        val text = response.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: response.thinking?.trim()?.takeIf { it.isNotBlank() }
            ?: return fallbackBlueprint(snapshot, userText)

        println("DEBUG_BLUEPRINT_RAW=${text.redactedForLog()}")

        return parseScaffoldingBlueprint(text) ?: fallbackBlueprint(snapshot, userText)
    }

    private fun fallbackBlueprint(
        snapshot: ProjectStyleSnapshot,
        userText: String
    ): ScaffoldingBlueprint? {
        val pkg = snapshot.packageName ?: return null
        val pkgPath = pkg.replace('.', '/')

        val files = mutableListOf<ScaffoldFilePlan>()

        files += ScaffoldFilePlan(
            path = "app/src/main/java/$pkgPath/ui/MainViewModel.kt",
            purpose = "Minimal ViewModel for UI state"
        )

        files += ScaffoldFilePlan(
            path = "app/src/main/java/$pkgPath/repository/GameRepository.kt",
            purpose = "Repository contract"
        )

        if (!snapshot.composeEnabled) {
            files += ScaffoldFilePlan(
                path = "app/src/main/java/$pkgPath/ui/UiState.kt",
                purpose = "Simple UI state model"
            )
        }

        println("DEBUG_BLUEPRINT_FALLBACK_USED userText=${userText.redactedForLog(120)} files=${files.map { it.path }}")

        return ScaffoldingBlueprint(
            summary = "Fallback minimal scaffolding blueprint",
            files = files.take(3)
        )
    }

    private fun parseScaffoldingBlueprint(text: String): ScaffoldingBlueprint? {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val summary = lines
            .firstOrNull { it.startsWith("SUMMARY:", ignoreCase = true) }
            ?.substringAfter("SUMMARY:", "")
            ?.trim()
            .orEmpty()

        val files = lines.mapNotNull { line ->
            if (!line.startsWith("FILE:", ignoreCase = true)) return@mapNotNull null

            val payload = line.substringAfter("FILE:", "").trim()
            val parts = payload.split("|", limit = 2).map { it.trim() }

            val rawPath = parts.getOrNull(0).orEmpty()
            val purpose = parts.getOrNull(1).orEmpty()

            if (rawPath.isBlank()) return@mapNotNull null

            val normalizedPath = enforceAndroidSourceSet(
                pathResolver.normalizeRelative(rawPath)
            )

            ScaffoldFilePlan(
                path = normalizedPath,
                purpose = purpose
            )
        }

        if (files.isEmpty()) {
            println("DEBUG_BLUEPRINT_PARSE_FAILED no FILE entries")
            return null
        }

        return ScaffoldingBlueprint(
            summary = summary,
            files = files.distinctBy { it.path }.take(3)
        )
    }

    private fun sanitizeBlueprintAgainstProject(
        blueprint: ScaffoldingBlueprint,
        snapshot: ProjectStyleSnapshot
    ): ScaffoldingBlueprint {
        val filtered = blueprint.files
            .filterNot { file ->
                val purpose = file.purpose.lowercase()

                val obviouslyRiskyInfra =
                    ("retrofit" in purpose || "api service" in purpose || "remote api" in purpose) &&
                            "retrofit" !in snapshot.availableLibraries

                val obviouslyRiskyRoom =
                    ("room" in purpose || "dao" in purpose || "database" in purpose) &&
                            "room" !in snapshot.availableLibraries

                obviouslyRiskyInfra || obviouslyRiskyRoom
            }
            .map { file ->
                if (
                    snapshot.composeEnabled &&
                    file.path.endsWith("MainActivity.kt")
                ) {
                    file.copy(
                        purpose = file.purpose + ". Keep existing Compose setup and ComponentActivity if MainActivity already exists."
                    )
                } else {
                    file
                }
            }
            .distinctBy { it.path }
            .take(6)

        return if (filtered.isNotEmpty()) {
            blueprint.copy(files = filtered)
        } else {
            blueprint
        }
    }

    private suspend fun materializeBlueprintToWriteFiles(
        blueprint: ScaffoldingBlueprint,
        snapshot: ProjectStyleSnapshot
    ): ValidatedAgentPlan? {
        val fileEntries = mutableListOf<JsonObject>()
        val plannedPaths = blueprint.files.map { it.path }.toSet()

        for (file in blueprint.files.take(5)) {
            val firstAttempt = generateFileContentFromBlueprint(
                file = file,
                snapshot = snapshot
            ) ?: continue

            var content = sanitizeGeneratedFileContent(firstAttempt)
            var review = validateGeneratedFileContent(
                path = file.path,
                content = content,
                snapshot = snapshot,
                plannedPaths = plannedPaths
            )

            if (!review.accepted) {
                println("DEBUG_GENERATED_FILE_REJECTED path=${file.path} reason=${review.reason}")

                val repaired = repairGeneratedFileContent(
                    file = file,
                    snapshot = snapshot,
                    invalidContent = content,
                    rejectionReason = review.reason ?: "Unknown validation error"
                )

                if (!repaired.isNullOrBlank()) {
                    content = sanitizeGeneratedFileContent(repaired)
                    review = validateGeneratedFileContent(
                        path = file.path,
                        content = content,
                        snapshot = snapshot,
                        plannedPaths = plannedPaths
                    )
                }
            }

            if (!review.accepted) {
                println("DEBUG_GENERATED_FILE_FINAL_REJECT path=${file.path} reason=${review.reason}")
                continue
            }

            fileEntries += buildJsonObject {
                put("path", JsonPrimitive(file.path))
                put("content", JsonPrimitive(content))
            }
        }

        if (fileEntries.isEmpty()) {
            println("DEBUG_BLUEPRINT_MATERIALIZE_FAILED no valid file contents generated")
            return null
        }

        val plan = AgentPlan(
            toolCalls = listOf(
                org.ivangelov.agent.core.agent.PlannedToolCall(
                    name = "write_files",
                    args = buildJsonObject {
                        put("files", kotlinx.serialization.json.buildJsonArray {
                            fileEntries.forEach { add(it) }
                        })
                    }
                )
            ),
            reply = ""
        )

        return when (val validated = toolPlanValidator.validate(plan)) {
            is AgentResult.Success -> validated.value
            is AgentResult.Failure -> {
                println("DEBUG_BLUEPRINT_WRITE_PLAN_VALIDATION_FAILED=${validated.error}")
                null
            }
        }
    }

    private suspend fun generateFileContentFromBlueprint(
        file: ScaffoldFilePlan,
        snapshot: ProjectStyleSnapshot
    ): String? {
        val packageHint = extractPackageFromPath(file.path)
            ?: snapshot.packageName

        val projectRules = buildString {
            appendLine("Project rules:")
            if (snapshot.composeEnabled) {
                appendLine("- This is a Jetpack Compose project.")
                appendLine("- Do NOT generate AppCompatActivity.")
                appendLine("- Do NOT generate setContentView(R.layout...).")
                appendLine("- Keep existing Compose style.")
            }
            if ("room" !in snapshot.availableLibraries) {
                appendLine("- Do NOT use Room annotations or Room DAO APIs.")
            }
            if ("retrofit" !in snapshot.availableLibraries) {
                appendLine("- Do NOT use Retrofit APIs.")
            }
            appendLine("- Avoid references to undefined domain models unless you also define them.")
            appendLine("- Prefer minimal self-contained code.")
            appendLine("- Return only raw file content.")
        }

        val messages = listOf(
            ChatMessage(
                Role.SYSTEM,
                """
                Generate only the requested file content.
                Return raw file content only.
                No markdown fences.
                Keep it minimal and project-consistent.
                """.trimIndent()
            ),
            ChatMessage(
                Role.USER,
                """
                File path: ${file.path}
                Purpose: ${file.purpose}
                Package hint: ${packageHint ?: "(none)"}
                
                $projectRules
                """.trimIndent()
            )
        )

        println("DEBUG_FILE_CONTENT_REQUEST path=${file.path}")

        val content = try {
            val response = llm.complete(
                messages = messages,
                context = ContextPack(
                    pinned = emptyList(),
                    retrieved = emptyList(),
                    recentSummary = null
                ),
                format = LlmResponseFormat.TEXT
            )

            response.content?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: response.thinking?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            println("DEBUG_FILE_CONTENT_REQUEST_FAILED path=${file.path} error=${e::class.simpleName}: ${e.message}")
            null
        }

        val sanitized = content?.let(::sanitizeGeneratedFileContent)
        if (!sanitized.isNullOrBlank()) return sanitized

        generateDeterministicScaffoldContent(file, snapshot)?.let {
            println("DEBUG_FILE_CONTENT_FALLBACK path=${file.path}")
            return it
        }

        return null
    }

    private suspend fun repairGeneratedFileContent(
        file: ScaffoldFilePlan,
        snapshot: ProjectStyleSnapshot,
        invalidContent: String,
        rejectionReason: String
    ): String? {
        val packageHint = extractPackageFromPath(file.path) ?: snapshot.packageName

        val messages = listOf(
            ChatMessage(
                Role.SYSTEM,
                """
                Repair the generated file.
                Return only raw corrected file content.
                No markdown fences.
                Keep the file minimal and valid.
                """.trimIndent()
            ),
            ChatMessage(
                Role.USER,
                """
                File path: ${file.path}
                Purpose: ${file.purpose}
                Package hint: ${packageHint ?: "(none)"}
                
                The previous generated file was rejected.
                Rejection reason:
                $rejectionReason
                
                Previous content:
                $invalidContent
                
                Repair the file so it matches the project constraints.
                """.trimIndent()
            )
        )

        println("DEBUG_FILE_CONTENT_REPAIR path=${file.path} reason=$rejectionReason")

        return try {
            val response = llm.complete(
                messages = messages,
                context = ContextPack(
                    pinned = emptyList(),
                    retrieved = emptyList(),
                    recentSummary = null
                ),
                format = LlmResponseFormat.TEXT
            )

            response.content?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: response.thinking?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            println("DEBUG_FILE_CONTENT_REPAIR_FAILED path=${file.path} error=${e::class.simpleName}: ${e.message}")
            null
        }?.let(::sanitizeGeneratedFileContent)
    }

    private fun validateGeneratedFileContent(
        path: String,
        content: String,
        snapshot: ProjectStyleSnapshot,
        plannedPaths: Set<String>
    ): GeneratedFileReview {
        val lower = content.lowercase()

        if (snapshot.composeEnabled) {
            if ("appcompatactivity" in lower) {
                return GeneratedFileReview(false, "Compose project must not generate AppCompatActivity")
            }
            if ("setcontentview(" in lower) {
                return GeneratedFileReview(false, "Compose project must not generate setContentView")
            }
            if ("r.layout." in lower) {
                return GeneratedFileReview(false, "Compose project must not depend on XML activity layouts")
            }
        }

        if ("room" !in snapshot.availableLibraries) {
            if ("@dao" in lower || "@insert" in lower || "@query(" in lower) {
                return GeneratedFileReview(false, "Room annotations used without Room dependency context")
            }
        }

        if ("retrofit" !in snapshot.availableLibraries) {
            if ("retrofit2" in lower || "@get(" in lower || "@post(" in lower) {
                return GeneratedFileReview(false, "Retrofit API used without Retrofit dependency context")
            }
        }

        val allowedFrameworkSymbols = setOf(
            "String",
            "Int",
            "Long",
            "Boolean",
            "List",
            "Unit",
            "Flow",
            "MutableStateFlow",
            "StateFlow",
            "ViewModel",
            "ComponentActivity",
            "Bundle",
            "Modifier",
            "Composable",
            "Preview",
            "Text",
            "Scaffold",
            "Surface",
            "MaterialTheme"
        )

        val suspiciousSymbols = extractReferencedTypeNames(content)
            .filter { symbol ->
                symbol !in snapshot.knownSymbols &&
                        symbol !in allowedFrameworkSymbols &&
                        plannedPaths.none { planned ->
                            planned.substringAfterLast("/").substringBeforeLast(".") == symbol
                        }
            }
            .filterNot { it.endsWith("Theme") }

        if (suspiciousSymbols.isNotEmpty()) {
            return GeneratedFileReview(false, "Unknown referenced symbols: $suspiciousSymbols")
        }

        if (path.endsWith("MainActivity.kt") && snapshot.composeEnabled) {
            if (!content.contains("ComponentActivity")) {
                return GeneratedFileReview(false, "Compose MainActivity should keep ComponentActivity")
            }
        }

        return GeneratedFileReview(true)
    }

    private fun extractReferencedTypeNames(content: String): Set<String> {
        val ignore = setOf(
            "SUMMARY",
            "FILE",
            "TODO",
            "Composable",
            "Preview"
        )

        return Regex("""\b[A-Z][A-Za-z0-9_]{2,}\b""")
            .findAll(content)
            .map { it.value }
            .filterNot { it in ignore }
            .toSet()
    }

    private fun extractPackageFromPath(path: String): String? {
        val normalized = path.replace("\\", "/")

        val marker = "/java/"
        val idx = normalized.indexOf(marker)

        if (idx >= 0) {
            val tail = normalized.substring(idx + marker.length)
            val pkg = tail.substringBeforeLast("/", "")
                .replace("/", ".")
                .trim('.')
            if (pkg.isNotBlank()) return pkg
        }

        val kotlinMarker = "/kotlin/"
        val idx2 = normalized.indexOf(kotlinMarker)

        if (idx2 >= 0) {
            val tail = normalized.substring(idx2 + kotlinMarker.length)
            val pkg = tail.substringBeforeLast("/", "")
                .replace("/", ".")
                .trim('.')
            if (pkg.isNotBlank()) return pkg
        }

        return null
    }

    private fun buildProjectStyleSnapshot(
        retrieved: List<ChatMessage>
    ): ProjectStyleSnapshot {
        val joined = retrieved.joinToString("\n\n") { it.content }

        val composeEnabled =
            joined.contains("compose = true", ignoreCase = true) ||
                    joined.contains("setContent {", ignoreCase = true) ||
                    joined.contains("ComponentActivity", ignoreCase = true) ||
                    joined.contains("androidx.compose", ignoreCase = true)

        val usesComponentActivity =
            joined.contains("ComponentActivity", ignoreCase = true)

        val packageName =
            Regex("""package\s+([a-zA-Z0-9_.]+)""")
                .find(joined)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        val symbolsFromDeclarations =
            Regex("""\b(?:class|interface|object|data\s+class)\s+([A-Z][A-Za-z0-9_]*)""")
                .findAll(joined)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .toSet()

        val symbolsFromPaths =
            Regex("""FILE:([^\s]+)""")
                .findAll(joined)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .map { it.substringAfterLast("/") }
                .map { it.substringBeforeLast(".") }
                .filter { it.matches(Regex("""[A-Z][A-Za-z0-9_]*""")) }
                .toSet()

        val libs = buildSet {
            if (joined.contains("androidx.room", ignoreCase = true)) add("room")
            if (joined.contains("retrofit", ignoreCase = true)) add("retrofit")
            if (joined.contains("hilt", ignoreCase = true)) add("hilt")
            if (joined.contains("ktor", ignoreCase = true)) add("ktor")
            if (joined.contains("compose", ignoreCase = true)) add("compose")
            if (joined.contains("stateflow", ignoreCase = true) || joined.contains("mutableStateFlow", ignoreCase = true)) add("coroutines")
        }

        return ProjectStyleSnapshot(
            composeEnabled = composeEnabled,
            usesComponentActivity = usesComponentActivity,
            packageName = packageName,
            knownSymbols = symbolsFromDeclarations + symbolsFromPaths + setOf(
                "MainActivity",
                "ViewModel"
            ),
            availableLibraries = libs
        )
    }

    private fun sanitizeGeneratedFileContent(content: String): String {
        return content
            .replace("```kotlin", "")
            .replace("```kt", "")
            .replace("```", "")
            .trim()
    }

    private fun generateDeterministicScaffoldContent(
        file: ScaffoldFilePlan,
        snapshot: ProjectStyleSnapshot
    ): String? {
        val pkg = extractPackageFromPath(file.path) ?: snapshot.packageName ?: return null
        val name = file.path.substringAfterLast("/")

        return when (name) {
            "MainViewModel.kt" -> """
                package $pkg
                
                import androidx.lifecycle.ViewModel
                
                class MainViewModel : ViewModel()
            """.trimIndent()

            "GameRepository.kt" -> """
                package $pkg
                
                interface GameRepository
            """.trimIndent()

            else -> null
        }
    }

    private suspend fun runModificationLoop(
        sanitizedUserText: String,
        emit: suspend (AgentEvent) -> Unit
    ) {
        println("AGENT_STATE_ENTER: MODIFICATION_FLOW")

        if (shouldTrySmartEditFirst(sanitizedUserText)) {
            val smartEditHandled = tryForcedReadThenEditSmart(
                userText = sanitizedUserText,
                emit = emit
            )
            if (smartEditHandled) return
        }

        runGenericToolLoop(
            sanitizedUserText = sanitizedUserText,
            emit = emit,
            mode = RequestMode.MODIFICATION
        )
    }

    private suspend fun runAnalysisLoop(
        sanitizedUserText: String,
        emit: suspend (AgentEvent) -> Unit
    ) {
        println("AGENT_STATE_ENTER: ANALYSIS_FLOW")
        runGenericToolLoop(
            sanitizedUserText = sanitizedUserText,
            emit = emit,
            mode = RequestMode.ANALYSIS
        )
    }

    private suspend fun runGenericToolLoop(
        sanitizedUserText: String,
        emit: suspend (AgentEvent) -> Unit,
        mode: RequestMode
    ) {
        val state = ToolLoopSessionState()
        val maxNonJsonViolations = 3
        val maxValidationFailures = 3

        while (state.step++ < maxSteps.coerceAtLeast(6)) {
            println("AGENT_LOOP_STEP=${state.step}")

            val retrieved = retrieveContextForMode(
                userText = sanitizedUserText,
                mode = mode
            )

            val planResult = requestValidatedPlan(
                userText = sanitizedUserText,
                retrieved = retrieved,
                mode = mode,
                discoveryReady = true
            )

            val validatedPlan = when (planResult) {
                is AgentResult.Success -> planResult.value
                is AgentResult.Failure -> {
                    state.validationFailures++
                    if (state.validationFailures > maxValidationFailures) {
                        failWithMessage("Tool-Plan konnte nicht korrigiert werden.", emit)
                        return
                    }
                    continue
                }
            }

            if (validatedPlan.toolCalls.isEmpty()) {
                val finalReply = validatedPlan.reply.orEmpty().trim()

                if (finalReply.isBlank()) {
                    state.nonJsonViolations++
                    repo.appendMessage(
                        conversationId,
                        Role.TOOL,
                        "[llm_violation]\nEmpty final plan received (${state.nonJsonViolations}/$maxNonJsonViolations)."
                    )

                    if (state.nonJsonViolations > maxNonJsonViolations) {
                        failWithMessage("Das Modell hat mehrfach keinen verwertbaren Plan geliefert.", emit)
                        return
                    }
                    continue
                }

                finalizeAssistantReply(finalReply, emit)
                return
            }

            val cycle = executeValidatedPlan(
                plan = validatedPlan,
                state = state,
                userText = sanitizedUserText,
                retrieved = retrieved,
                emit = emit,
                approvalSuccessMessage = "Änderungen wurden durchgeführt."
            )

            if (cycle.approvalRequested) return

            if (cycle.mutated) {
                finalizeAssistantReply("Änderungen wurden durchgeführt.", emit)
                return
            }

            if (!state.successfulReadFileOutput.isNullOrBlank() && mode == RequestMode.ANALYSIS) {
                finalizeAnalysisFromReadFile(
                    userText = sanitizedUserText,
                    readFileContent = state.successfulReadFileOutput!!,
                    emit = emit
                )
                return
            }

            if (!cycle.shouldContinue && state.executedAtLeastOne) {
                break
            }
        }

        failWithMessage("Max steps reached. Bitte Anfrage eingrenzen oder erneut versuchen.", emit)
    }

    private suspend fun requestValidatedPlan(
        userText: String,
        retrieved: List<ChatMessage>,
        mode: RequestMode,
        discoveryReady: Boolean
    ): AgentResult<ValidatedAgentPlan> {
        val ctx = ContextPack(
            pinned = emptyList(),
            retrieved = retrieved,
            recentSummary = null
        )

        val messages = when (mode) {
            RequestMode.SCAFFOLDING -> buildScaffoldingMessages(
                userText = userText,
                discoveryReady = discoveryReady
            )
            RequestMode.MODIFICATION -> promptBuilder.buildForToolLoop(relevantHistory(), tools)
            RequestMode.ANALYSIS -> promptBuilder.buildForToolLoop(relevantHistory(), tools)
        }

        println("DEBUG_REQUEST_VALIDATED_PLAN mode=$mode discoveryReady=$discoveryReady")
        println("DEBUG_REQUEST_MESSAGES_COUNT=${messages.size}")
        println("DEBUG_REQUEST_RETRIEVED_COUNT=${retrieved.size}")
        println(
            "DEBUG_REQUEST_RETRIEVED_HEAD=${
                retrieved.take(3).joinToString(" || ") { it.content.redactedForLog(180) }
            }"
        )

        val toolModeResult = llm.completeToolMode(messages, ctx)

        val toolModeResponse = when (toolModeResult) {
            is AgentResult.Success -> toolModeResult.value
            is AgentResult.Failure -> {
                println("DEBUG_TOOLMODE_FAILURE=${toolModeResult.error}")
                return AgentResult.Failure(toolModeResult.error)
            }
        }

        val rawContent = toolModeResponse.rawContent.orEmpty()
        val rawThinking = toolModeResponse.rawThinking.orEmpty()

        println("DEBUG_TOOLMODE_RAW_CONTENT=${rawContent.redactedForLog()}")
        println("DEBUG_TOOLMODE_RAW_THINKING=${rawThinking.redactedForLog()}")

        val structuredPlan = AgentPlan(
            toolCalls = toolModeResponse.toolCalls.map {
                org.ivangelov.agent.core.agent.PlannedToolCall(
                    name = it.name,
                    args = it.args
                )
            },
            reply = toolModeResponse.reply
        )

        println("DEBUG_STRUCTURED_TOOL_CALLS=${structuredPlan.toolCalls.map { it.name }}")
        println("DEBUG_STRUCTURED_REPLY=${structuredPlan.reply.orEmpty().redactedForLog()}")

        val plan = if (structuredPlan.toolCalls.isNotEmpty() || !structuredPlan.reply.isNullOrBlank()) {
            structuredPlan
        } else {
            val parsed =
                PlanParser.parseOrNull(rawContent)
                    ?: rawThinking.takeIf { it.isNotBlank() }?.let { PlanParser.parseOrNull(it) }

            if (parsed == null) {
                val raw = rawContent.ifBlank { rawThinking.ifBlank { "(empty)" } }
                println("DEBUG_PLAN_PARSE_FAILED raw=${raw.redactedForLog()}")
                return AgentResult.Failure(
                    AgentError.InvalidPlan(
                        "LLM did not return structured plan. Raw content: ${raw.redactedForLog()}"
                    )
                )
            }

            parsed
        }

        println("DEBUG_PLAN_AFTER_PARSE=${plan.toString().redactedForLog()}")

        val validatedPlan = when (val validated = toolPlanValidator.validate(plan)) {
            is AgentResult.Success -> {
                println("DEBUG_PLAN_AFTER_VALIDATION=${validated.value.toString().redactedForLog()}")
                validated.value
            }
            is AgentResult.Failure -> {
                println("DEBUG_PLAN_VALIDATION_FAILED=${validated.error}")
                return AgentResult.Failure(validated.error)
            }
        }

        val scaffoldingError = validateScaffoldingPlan(
            userText = userText,
            plan = validatedPlan,
            discoveryReady = discoveryReady
        )

        if (scaffoldingError != null) {
            println("DEBUG_SCAFFOLDING_ERROR=$scaffoldingError")
            repo.appendMessage(
                conversationId,
                Role.TOOL,
                """
            [tool_validation_error]
            $scaffoldingError
            
            Return corrected JSON only.
            Rules:
            - if discovery is ready, create or modify project files now
            - do not return reply-only plan
            - prefer write_files for multi-file scaffolding
            - create multiple concrete files for clean architecture
            - do not continue with read-only tools after discovery is ready
            """.trimIndent()
            )
            return AgentResult.Failure(AgentError.InvalidPlan(scaffoldingError))
        }

        if (mode == RequestMode.SCAFFOLDING &&
            discoveryReady &&
            validatedPlan.toolCalls.none { isMutatingTool(it.name) }
        ) {
            val correction =
                "Plan rejected: discovery is already sufficient. Return mutating tool calls now."

            println("DEBUG_SCAFFOLDING_FORCE_MUTATION=$correction")

            repo.appendMessage(
                conversationId,
                Role.TOOL,
                """
            [tool_validation_error]
            $correction
            
            Return corrected JSON only.
            Use write_files or multiple write_file calls.
            Create concrete Android/Kotlin project files for the requested architecture.
            """.trimIndent()
            )

            return AgentResult.Failure(AgentError.InvalidPlan(correction))
        }

        return AgentResult.Success(validatedPlan)
    }

    private fun buildScaffoldingMessages(
        userText: String,
        discoveryReady: Boolean
    ): List<ChatMessage> {
        val compactHistory = history()
            .filterNot { it.role == Role.SYSTEM }
            .takeLast(4)
            .map { msg ->
                val maxChars = when (msg.role) {
                    Role.USER -> 500
                    Role.ASSISTANT -> 400
                    Role.TOOL -> 500
                    else -> 300
                }
                ChatMessage(msg.role, msg.content.take(maxChars))
            }

        val system = if (!discoveryReady) {
            """
        You are an Android coding agent in scaffolding mode.
        Inspect the existing Android project structure first.
        Use tools.
        Return a valid tool plan only.
        Avoid long explanations.
        """.trimIndent()
        } else {
            """
        You are an Android coding agent in scaffolding mode.
        Discovery is complete.
        Now return mutating tool calls only.
        Use write_files if possible.
        Create concrete Android/Kotlin project files with relative paths.
        Return valid tool-plan JSON only.
        Avoid explanations.
        """.trimIndent()
        }

        val user = if (!discoveryReady) {
            "User request: $userText"
        } else {
            "User request: $userText. Discovery is complete. Create files now."
        }

        return buildList {
            add(ChatMessage(Role.SYSTEM, system))
            add(ChatMessage(Role.USER, user))
            addAll(compactHistory)
        }
    }

    private suspend fun ensureScaffoldingDiscoveryContext(
        userText: String,
        state: ToolLoopSessionState,
        emit: suspend (AgentEvent) -> Unit
    ): DiscoveryState {
        if (projectId == null) {
            return DiscoveryState(
                rootListed = true,
                buildFilesRead = true,
                manifestRead = true,
                entryPointsRead = true,
                projectIndexAvailable = false
            )
        }

        if (state.discoveryReady) {
            return DiscoveryState(
                rootListed = true,
                buildFilesRead = true,
                manifestRead = true,
                entryPointsRead = true,
                projectIndexAvailable = state.indexConfirmed
            )
        }

        var rootListed = false
        var buildFilesRead = false
        var manifestRead = false
        var entryPointsRead = false

        val executedThisPhase = mutableSetOf<String>()

        suspend fun execOnce(toolName: String, args: JsonObject): ToolExecutionResult? {
            val key = "$toolName|$args"
            if (!executedThisPhase.add(key)) return null

            return when (val result = executeOneTool(toolName, args)) {
                is AgentResult.Success -> {
                    emit(
                        AgentEvent.ToolExecuted(
                            toolName = result.value.toolName,
                            output = if (result.value.toolName == "read_file") {
                                "[read_file] loaded ${result.value.meta["path"] ?: "file"}"
                            } else {
                                result.value.rawOutput
                            }
                        )
                    )
                    state.executedAtLeastOne = true
                    result.value
                }
                is AgentResult.Failure -> null
            }
        }

        execOnce("list_dir", buildJsonObject {})?.let {
            rootListed = true
        }

        listOf(
            "app/build.gradle.kts",
            "build.gradle.kts",
            "settings.gradle.kts"
        ).forEach { path ->
            execOnce("read_file", buildPathArgs(path))?.let {
                buildFilesRead = true
            }
        }

        listOf(
            "app/src/main/AndroidManifest.xml",
            "src/main/AndroidManifest.xml"
        ).forEach { path ->
            execOnce("read_file", buildPathArgs(path))?.let {
                manifestRead = true
            }
        }

        listOf(
            "app/src/main/java/com/gelov/tick_tack_toe/MainActivity.kt",
            "app/src/main/java/MainActivity.kt",
            "src/main/java/MainActivity.kt"
        ).forEach { path ->
            execOnce("read_file", buildPathArgs(path))?.let {
                entryPointsRead = true
            }
        }

        val ready = rootListed && buildFilesRead

        if (ready) {
            state.discoveryReady = true
        }

        return DiscoveryState(
            rootListed = rootListed,
            buildFilesRead = buildFilesRead,
            manifestRead = manifestRead,
            entryPointsRead = entryPointsRead,
            projectIndexAvailable = state.indexConfirmed
        )
    }

    private fun buildApprovalRequiredEvent(
        requestId: String,
        plan: ValidatedAgentPlan
    ): AgentEvent.ApprovalRequired {
        val mutatingCalls = plan.toolCalls.filter { isMutatingTool(it.name) }
        val totalPaths = mutatingCalls
            .flatMap { extractAffectedProjectPaths(it.name, it.args) }
            .distinct()
            .size

        val summary = buildString {
            append("Der Agent möchte ")
            append(mutatingCalls.size)
            append(if (mutatingCalls.size == 1) " schreibendes Tool" else " schreibende Tools")
            if (totalPaths > 0) {
                append(" auf ")
                append(totalPaths)
                append(if (totalPaths == 1) " Datei" else " Dateien")
            }
            append(" ausführen.")
        }

        return AgentEvent.ApprovalRequired(
            requestId = requestId,
            summary = summary,
            toolCalls = mutatingCalls.map { call ->
                org.ivangelov.agent.core.agent.PendingToolCall(
                    toolName = call.name,
                    paths = extractAffectedProjectPaths(call.name, call.args),
                    argsPreview = call.args.redactedForLog(maxChars = 900)
                )
            }
        )
    }

    private suspend fun executeValidatedPlan(
        plan: ValidatedAgentPlan,
        state: ToolLoopSessionState,
        userText: String,
        retrieved: List<ChatMessage>,
        emit: suspend (AgentEvent) -> Unit,
        approvalSuccessMessage: String = "Änderungen wurden durchgeführt."
    ): ExecutionCycleResult {
        if (plan.toolCalls.any { isMutatingTool(it.name) } && approvedMutationRequestId == null) {
            val requestId = UUID.randomUUID().toString()
            pendingMutationApproval = PendingMutationApproval(
                requestId = requestId,
                plan = plan,
                state = state,
                userText = userText,
                retrieved = retrieved,
                successMessage = approvalSuccessMessage
            )

            emit(buildApprovalRequiredEvent(requestId, plan))
            emit(AgentEvent.Completed)

            return ExecutionCycleResult(
                mutated = false,
                readSomething = false,
                shouldContinue = false,
                approvalRequested = true
            )
        }

        val executedThisStep = mutableSetOf<String>()
        var mutated = false
        var readSomething = false

        for (tc in plan.toolCalls) {
            if (state.toolCallsUsed++ >= maxToolCallsTotal.coerceAtLeast(24)) {
                failWithMessage("Max tool calls reached.", emit)
                return ExecutionCycleResult(
                    mutated = mutated,
                    readSomething = readSomething,
                    shouldContinue = false
                )
            }

            val normalizedArgs = when {
                tc.name == "write_files" -> dedupeWriteFilesArgs(tc.args)
                shouldNormalizePath(tc.name) -> normalizeToolArgs(tc.name, tc.args)
                else -> tc.args
            }

            val effectiveArgs = normalizedArgs

            val executionKey = "${tc.name}|$effectiveArgs"
            if (!executedThisStep.add(executionKey)) {
                println("AGENT_SKIP_DUPLICATE_TOOL_CALL")
                continue
            }

            when (val result = executeOneTool(tc.name, effectiveArgs)) {
                is AgentResult.Success -> {
                    val value = result.value

                    emit(
                        AgentEvent.ToolExecuted(
                            toolName = value.toolName,
                            output = if (value.toolName == "read_file") {
                                "[read_file] loaded ${value.meta["path"] ?: "file"}"
                            } else {
                                value.rawOutput
                            }
                        )
                    )

                    state.executedAtLeastOne = true

                    if (value.toolName == "read_file") {
                        readSomething = true
                        state.successfulReadFileOutput = value.rawOutput

                        val currentReadPath =
                            value.meta["path"] ?: effectiveArgs["path"]?.jsonPrimitive?.contentOrNull

                        val previousReadPath = state.lastReadPathAcrossSteps
                        state.lastResolvedReadPath = currentReadPath

                        if (isSamePath(currentReadPath, previousReadPath)) {
                            state.consecutiveSamePathReadCount++
                        } else {
                            state.consecutiveSamePathReadCount = 1
                            state.lastReadPathAcrossSteps = currentReadPath
                        }
                    }

                    if (isMutatingTool(value.toolName)) {
                        mutated = true
                        state.mutatingToolExecutedSuccessfully = true
                        state.consecutiveSamePathReadCount = 0
                        state.lastReadPathAcrossSteps = null
                    }
                }

                is AgentResult.Failure -> {
                    if (tc.name == "read_file" && looksLikeReadFileNotFound(result.error)) {
                        val recovered = tryRecoverReadFileFailure(
                            normalizedArgs = effectiveArgs,
                            sanitizedUserText = userText,
                            retrieved = retrieved,
                            executedThisTurn = executedThisStep,
                            emit = emit
                        )

                        if (recovered != null) {
                            readSomething = true
                            state.executedAtLeastOne = true
                            state.successfulReadFileOutput = recovered.rawOutput
                            state.lastResolvedReadPath = recovered.meta["path"] ?: state.lastResolvedReadPath
                            continue
                        }
                    }

                    if (tc.name == "replace_in_file") {
                        val failedPath =
                            effectiveArgs["path"]?.jsonPrimitive?.contentOrNull.orEmpty()

                        if (failedPath.isNotBlank()) {
                            if (state.replaceRecoveryReadCount >= 2) {
                                failWithMessage(
                                    "Abbruch: Änderung konnte nach erneutem Dateilesen nicht präzise angewendet werden.",
                                    emit
                                )
                                return ExecutionCycleResult(
                                    mutated = mutated,
                                    readSomething = readSomething,
                                    shouldContinue = false
                                )
                            }

                            val retryArgs = buildPathArgs(failedPath)
                            val retryKey = "read_file|$retryArgs"

                            if (executedThisStep.add(retryKey)) {
                                state.replaceRecoveryReadCount++

                                when (val retryResult = executeOneTool("read_file", retryArgs)) {
                                    is AgentResult.Success -> {
                                        emit(
                                            AgentEvent.ToolExecuted(
                                                toolName = retryResult.value.toolName,
                                                output = "[read_file] loaded ${retryResult.value.meta["path"] ?: failedPath}"
                                            )
                                        )

                                        state.executedAtLeastOne = true
                                        state.successfulReadFileOutput = retryResult.value.rawOutput
                                        state.lastResolvedReadPath = retryResult.value.meta["path"] ?: state.lastResolvedReadPath
                                        state.lastReadPathAcrossSteps = state.lastResolvedReadPath
                                        state.consecutiveSamePathReadCount = 1
                                        continue
                                    }

                                    is AgentResult.Failure -> {
                                        failWithMessage(
                                            "Abbruch: Datei konnte nach fehlgeschlagener Änderung nicht erneut geladen werden.",
                                            emit
                                        )
                                        return ExecutionCycleResult(
                                            mutated = mutated,
                                            readSomething = readSomething,
                                            shouldContinue = false
                                        )
                                    }
                                }
                            }
                        }
                    }

                    println("AGENT_REPLAN_AFTER_TOOL_FAILURE")
                    continue
                }
            }
        }

        return ExecutionCycleResult(
            mutated = mutated,
            readSomething = readSomething,
            shouldContinue = true
        )
    }

    override fun history(): List<ChatMessage> {
        val msgs = repo.loadMessages(conversationId)
        return msgs.map { ChatMessage(Role.valueOf(it.role), it.content) }
    }

    private suspend fun executeOneTool(
        toolNameRaw: String,
        argsAlreadyNormalized: JsonObject
    ): AgentResult<ToolExecutionResult> {
        return when (val result = toolExecutionService.execute(toolNameRaw, argsAlreadyNormalized)) {
            is AgentResult.Success -> {
                val value = result.value

                repo.appendMessage(conversationId, Role.TOOL, "[${value.toolName}]\n${value.rawOutput}")

                if (value.toolName != "read_file" && !isMutatingTool(value.toolName)) {
                    value.userMessage?.let { shortMsg ->
                        repo.appendMessage(conversationId, Role.ASSISTANT, shortMsg)
                    }
                }

                if (projectId != null && isMutatingTool(value.toolName)) {
                    buildProjectNoteFromToolSuccess(
                        toolName = value.toolName,
                        args = value.normalizedArgs,
                        rawOutput = value.rawOutput
                    )?.let { note ->
                        val stored = memoryCoordinator.storeProjectNote(
                            tenantId = tenantId,
                            conversationId = conversationId,
                            projectId = projectId,
                            text = note
                        )
                        println("PROJECT_NOTE_STORE stored=$stored text=${note.redactedForLog()}")
                    }
                }

                if (projectId != null && isMutatingTool(value.toolName)) {
                    val affectedPaths = extractAffectedProjectPaths(
                        toolName = value.toolName,
                        args = value.normalizedArgs
                    )

                    affectedPaths.forEach { path ->
                        reindexProjectFileIfPossible(path)
                    }
                }

                AgentResult.Success(value)
            }

            is AgentResult.Failure -> {
                val errorText = when (val error = result.error) {
                    is AgentError.ToolFailure ->
                        "[${error.toolName}]\nTOOL_FAILURE: ${error.message}"

                    is AgentError.ToolValidationFailure -> when (error.toolName) {
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

                    else ->
                        "[tool]\nUNEXPECTED_TOOL_ERROR"
                }

                repo.appendMessage(conversationId, Role.TOOL, errorText)
                AgentResult.Failure(result.error)
            }
        }
    }

    private suspend fun executeSmartEditFlow(
        userText: String,
        targetPath: String,
        fileContent: String,
        emit: suspend (AgentEvent) -> Unit
    ): Boolean {
        val intent = EditIntentDetector.detect(userText, targetPath)
        val blocks = KotlinBlockExtractor.extractRelevantBlocks(fileContent, userText)
        val plan = EditStrategySelector.choose(fileContent, intent, blocks)

        println("SMART_EDIT_PLAN strategy=${plan.strategy} reason=${plan.reason}")

        return when (plan.strategy) {
            EditStrategy.BLOCK_REWRITE,
            EditStrategy.SMALL_EXACT_REPLACE -> {
                val block = plan.targetBlocks.firstOrNull() ?: return false
                rewriteSingleBlock(
                    userText = userText,
                    path = targetPath,
                    block = block,
                    emit = emit
                )
            }

            EditStrategy.MULTI_EXACT_REPLACE -> {
                rewriteMultipleBlocks(
                    userText = userText,
                    path = targetPath,
                    blocks = plan.targetBlocks,
                    emit = emit
                )
            }

            EditStrategy.FULL_FILE_REWRITE -> {
                rewriteWholeFile(
                    userText = userText,
                    path = targetPath,
                    originalFileContent = fileContent,
                    emit = emit
                )
            }

            EditStrategy.NEEDS_TARGET_LOCALIZATION -> {
                val msg = "Änderung ist noch nicht präzise genug lokalisiert. Zielblock konnte nicht sicher bestimmt werden."
                repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                emit(AgentEvent.AssistantMessage(msg))
                emit(AgentEvent.Completed)
                true
            }
        }
    }

    private suspend fun rewriteSingleBlock(
        userText: String,
        path: String,
        block: TargetBlock,
        emit: suspend (AgentEvent) -> Unit
    ): Boolean {
        val messages = BlockRewritePromptFactory.buildMessages(
            userText = userText,
            targetBlock = block,
            resolvedPath = path
        )

        val response = llm.complete(
            messages = messages,
            context = ContextPack(
                pinned = emptyList(),
                retrieved = emptyList(),
                recentSummary = null
            ),
            format = LlmResponseFormat.TEXT
        )

        val replacement = response.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: response.thinking?.trim()?.takeIf { it.isNotBlank() }
            ?: return false

        val originalNormalized = block.originalText.trim()
        val replacementNormalized = replacement.trim()

        if (replacementNormalized == originalNormalized) {
            val msg = "Keine Änderung erforderlich."
            repo.appendMessage(conversationId, Role.ASSISTANT, msg)
            emit(AgentEvent.AssistantMessage(msg))
            emit(AgentEvent.Completed)
            return true
        }

        val replaceArgs = PatchBuilder.buildReplaceArgs(
            path = path,
            originalBlock = block.originalText,
            replacementBlock = replacement
        )

        val approvalMsg = "Änderungen in $path wurden durchgeführt."
        val approvalCycle = executeValidatedPlan(
            plan = ValidatedAgentPlan(
                toolCalls = listOf(ValidatedToolCall("replace_in_file", replaceArgs)),
                reply = ""
            ),
            state = ToolLoopSessionState(),
            userText = userText,
            retrieved = emptyList(),
            emit = emit,
            approvalSuccessMessage = approvalMsg
        )

        if (approvalCycle.approvalRequested) return true
        if (!approvalCycle.mutated) return false

        repo.appendMessage(conversationId, Role.ASSISTANT, approvalMsg)
        emit(AgentEvent.AssistantMessage(approvalMsg))
        emit(AgentEvent.Completed)
        return true

    }

    private suspend fun rewriteMultipleBlocks(
        userText: String,
        path: String,
        blocks: List<TargetBlock>,
        emit: suspend (AgentEvent) -> Unit
    ): Boolean {
        val sorted = blocks.sortedByDescending { it.startOffset }

        for (block in sorted) {
            val ok = rewriteSingleBlock(
                userText = userText,
                path = path,
                block = block,
                emit = emit
            )
            if (!ok) return false
        }
        return true
    }

    private suspend fun rewriteWholeFile(
        userText: String,
        path: String,
        originalFileContent: String,
        emit: suspend (AgentEvent) -> Unit
    ): Boolean {
        val messages = listOf(
            ChatMessage(
                Role.SYSTEM,
                """
            Rewrite the file content according to the user request.
            Return ONLY the full new file content.
            Do not use markdown fences.
            Preserve package/import structure unless changes are necessary.
            Preserve valid Kotlin syntax.
            """.trimIndent()
            ),
            ChatMessage(
                Role.USER,
                """
            File: $path
            
            Request:
            $userText
            
            Original file:
            $originalFileContent
            """.trimIndent()
            )
        )

        val response = llm.complete(
            messages = messages,
            context = ContextPack(
                pinned = emptyList(),
                retrieved = emptyList(),
                recentSummary = null
            ),
            format = LlmResponseFormat.TEXT
        )

        val rewritten = response.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: response.thinking?.trim()?.takeIf { it.isNotBlank() }
            ?: return false

        val args = buildJsonObject {
            put("path", JsonPrimitive(path))
            put("content", JsonPrimitive(rewritten))
        }

        val approvalMsg = "Datei $path wurde neu geschrieben."
        val approvalCycle = executeValidatedPlan(
            plan = ValidatedAgentPlan(
                toolCalls = listOf(ValidatedToolCall("write_file", args)),
                reply = ""
            ),
            state = ToolLoopSessionState(),
            userText = userText,
            retrieved = emptyList(),
            emit = emit,
            approvalSuccessMessage = approvalMsg
        )

        if (approvalCycle.approvalRequested) return true
        if (!approvalCycle.mutated) return false

        repo.appendMessage(conversationId, Role.ASSISTANT, approvalMsg)
        emit(AgentEvent.AssistantMessage(approvalMsg))
        emit(AgentEvent.Completed)
        return true

    }

    private suspend fun tryForcedReadThenEditSmart(
        userText: String,
        emit: suspend (AgentEvent) -> Unit
    ): Boolean {
        if (projectId == null) return false

        val forcedPath = extractMentionedFilePath(userText) ?: return false

        if (smartEditAttempted && smartEditFailed && smartEditPath == forcedPath) {
            val msg = "Abbruch: Änderung konnte in $forcedPath nicht sicher angewendet werden."
            repo.appendMessage(conversationId, Role.ASSISTANT, msg)
            emit(AgentEvent.AssistantMessage(msg))
            emit(AgentEvent.Completed)
            return true
        }

        smartEditAttempted = true
        smartEditPath = forcedPath

        println("AGENT_FORCE_READ_FILE_FOR_SMART_EDIT")
        println("forcedPath=$forcedPath")

        val readResult = when (val forced = executeOneTool("read_file", buildPathArgs(forcedPath))) {
            is AgentResult.Success -> forced.value
            is AgentResult.Failure -> {
                smartEditFailed = true
                return false
            }
        }

        emit(
            AgentEvent.ToolExecuted(
                toolName = readResult.toolName,
                output = "[read_file] loaded ${readResult.meta["path"] ?: forcedPath}"
            )
        )

        val resolvedPath = readResult.meta["path"] ?: forcedPath
        val fileContent = readResult.rawOutput

        val ok = executeSmartEditFlow(
            userText = userText,
            targetPath = resolvedPath,
            fileContent = fileContent,
            emit = emit
        )

        smartEditFailed = !ok
        return ok
    }

    private suspend fun tryRecoverReadFileFailure(
        normalizedArgs: JsonObject,
        sanitizedUserText: String,
        retrieved: List<ChatMessage>,
        executedThisTurn: MutableSet<String>,
        emit: suspend (AgentEvent) -> Unit
    ): ToolExecutionResult? {
        val failedPath = normalizedArgs["path"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val fallbackCandidates = buildList {
            chooseFallbackReadPath(sanitizedUserText, failedPath)?.let { add(it) }

            retrieved.forEach { hit ->
                val content = hit.content

                Regex("""FILE:([^\n\r]+?\.(kt|java|xml|json|kts|gradle|gradle\.kts))""")
                    .find(content)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { fullPath ->
                        pathResolver.absoluteToProjectRelative(fullPath)?.let { relative ->
                            if (relative.endsWith(failedPath.substringAfterLast("/"), ignoreCase = true)) {
                                add(relative)
                            }
                        }
                    }
            }
        }.distinct()

        val fallbackPath = fallbackCandidates.firstOrNull()
        if (fallbackPath == null || fallbackPath == failedPath) return null

        println("AGENT_RETRY_READ_FILE_WITH_FALLBACK_PATH")
        println("failedPath=$failedPath")
        println("fallbackPath=$fallbackPath")

        val retryArgs = buildPathArgs(fallbackPath)
        val retryKey = "read_file|$retryArgs"

        if (!executedThisTurn.add(retryKey)) return null

        return when (val retryResult = executeOneTool("read_file", retryArgs)) {
            is AgentResult.Success -> {
                val visibleToolOutput = when (retryResult.value.toolName) {
                    "read_file" -> "[read_file] loaded ${retryResult.value.meta["path"] ?: "file"}"
                    else -> retryResult.value.rawOutput
                }

                emit(
                    AgentEvent.ToolExecuted(
                        toolName = retryResult.value.toolName,
                        output = visibleToolOutput
                    )
                )

                if (retryResult.value.toolName != "read_file" && !isMutatingTool(retryResult.value.toolName)) {
                    retryResult.value.userMessage?.let {
                        emit(AgentEvent.AssistantMessage(it))
                    }
                }

                retryResult.value
            }

            is AgentResult.Failure -> {
                println("AGENT_FALLBACK_READ_FILE_FAILED")
                null
            }
        }
    }

    private fun wantsFileModification(userText: String): Boolean {
        val t = userText.lowercase()

        return listOf(
            "ändere",
            "aendere",
            "bearbeite",
            "ergänze",
            "ergaenze",
            "füge",
            "fuege",
            "schreibe in",
            "kommentiere",
            "kommentar",
            "kommentare",
            "refactor",
            "refaktoriere",
            "verbessere",
            "patch",
            "fixe",
            "korrigiere",
            "erstelle in",
            "ändere die datei",
            "bearbeite die datei"
        ).any { it in t }
    }

    private fun shouldTrySmartEditFirst(userText: String): Boolean {
        if (projectId == null) return false
        if (!wantsFileModification(userText)) return false
        return extractMentionedFilePath(userText) != null
    }

    private suspend fun finalizeAnalysisFromReadFile(
        userText: String,
        readFileContent: String,
        emit: suspend (AgentEvent) -> Unit
    ) {
        val analysisMessages = promptBuilder.buildForKnowledge(
            listOf(
                ChatMessage(Role.USER, userText),
                ChatMessage(Role.TOOL, "[read_file]\n$readFileContent")
            )
        )

        val analysisCtx = ContextPack(
            pinned = emptyList(),
            retrieved = emptyList(),
            recentSummary = null
        )

        val analysisResp = runCatching {
            llm.complete(
                analysisMessages,
                analysisCtx,
                LlmResponseFormat.TEXT
            )
        }.getOrElse { e ->
            failWithMessage("LLM_ERROR: ${e::class.simpleName}: ${e.message}", emit)
            return
        }

        val final = analysisResp.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: analysisResp.thinking?.trim()?.takeIf { it.isNotBlank() }
            ?: "Ich konnte aus dem gelesenen Dateiinhalt keine Antwort erzeugen."

        finalizeAssistantReply(final, emit)
    }

    private suspend fun finalizeAssistantReply(
        text: String,
        emit: suspend (AgentEvent) -> Unit
    ) {
        if (shouldStoreAssistantTurn(text)) {
            memoryCoordinator.storeAssistantTurn(
                tenantId = tenantId,
                conversationId = conversationId,
                projectId = projectId,
                text = text
            )
        } else {
            println("MEM_SKIP_ASSISTANT_STORE: filtered generic/low-value assistant reply")
        }

        repo.appendMessage(conversationId, Role.ASSISTANT, text)
        emit(AgentEvent.AssistantMessage(text))
        emit(AgentEvent.Completed)
    }

    private suspend fun failWithMessage(
        text: String,
        emit: suspend (AgentEvent) -> Unit
    ) {
        repo.appendMessage(conversationId, Role.ASSISTANT, text)
        emit(AgentEvent.AssistantMessage(text))
        emit(AgentEvent.Completed)
    }

    private fun wantsProjectScaffolding(userText: String): Boolean {
        val t = userText.lowercase()

        return listOf(
            "projektstruktur",
            "projekt architektur",
            "architektur erstellen",
            "clean architecture",
            "mvvm struktur",
            "ordnerstruktur",
            "feature struktur",
            "paketstruktur",
            "module struktur",
            "scaffold",
            "grundstruktur",
            "projekt verzeichnis",
            "dateistruktur erstellen",
            "create project structure",
            "setup architecture",
            "project scaffolding"
        ).any { it in t }
    }

    private fun validateScaffoldingPlan(
        userText: String,
        plan: ValidatedAgentPlan,
        discoveryReady: Boolean
    ): String? {
        if (!wantsProjectScaffolding(userText)) return null

        val toolCalls = plan.toolCalls
        if (toolCalls.isEmpty()) {
            return "Plan rejected: scaffolding request requires tool calls."
        }

        val readOnlyTools = setOf("read_file", "list_dir", "index_project")
        val allReadOnly = toolCalls.all { it.name in readOnlyTools }

        if (!discoveryReady) {
            return null
        }

        if (allReadOnly) {
            return "Plan rejected: discovery is already sufficient. Scaffolding must now create or modify files."
        }

        val forbiddenOnlyIndex = toolCalls.all { it.name == "index_project" }
        if (forbiddenOnlyIndex) {
            return "Plan rejected: scaffolding request cannot be solved by index_project only."
        }

        val mutatingTools = toolCalls.filter {
            it.name in setOf("write_file", "write_files", "append_to_file", "replace_in_file")
        }

        if (mutatingTools.isEmpty()) {
            return "Plan rejected: after discovery, scaffolding must contain mutating tool calls."
        }

        val touchesOnlyBuildGradle = mutatingTools.all { tc ->
            val path = tc.args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            path.endsWith("build.gradle.kts")
        }

        if (touchesOnlyBuildGradle) {
            return "Plan rejected: scaffolding cannot modify only build.gradle.kts. It must create project source files."
        }

        val hasWriteFiles = toolCalls.any { it.name == "write_files" }
        val hasMultipleWriteFile = toolCalls.count { it.name == "write_file" } >= 2

        if (!hasWriteFiles && !hasMultipleWriteFile) {
            return "Plan rejected: scaffolding should create multiple files (use write_files or multiple write_file calls)."
        }

        return null
    }

    private suspend fun retrieveContextForMode(
        userText: String,
        mode: RequestMode
    ): List<ChatMessage> {
        val topK = when (mode) {
            RequestMode.SCAFFOLDING -> 4
            RequestMode.MODIFICATION -> 6
            RequestMode.ANALYSIS -> 6
        }

        val maxChars = when (mode) {
            RequestMode.SCAFFOLDING -> 700
            RequestMode.MODIFICATION -> 1000
            RequestMode.ANALYSIS -> 900
        }

        val raw = memoryCoordinator.retrieveForToolLoop(
            tenantId = tenantId,
            conversationId = conversationId,
            projectId = projectId,
            query = userText,
            topK = topK
        )

        val filtered = when (mode) {
            RequestMode.SCAFFOLDING -> raw.filter {
                val c = it.content
                val lower = c.lowercase()

                (c.startsWith("TYPE:CODE") || c.contains("FILE:")) &&
                        (
                                "mainactivity.kt" in lower ||
                                        "build.gradle.kts" in lower ||
                                        "theme.kt" in lower ||
                                        "androidmanifest.xml" in lower
                                )
            }.filterNot {
                val c = it.content
                c.startsWith("Projektdatei erfolgreich geändert:") ||
                        c.startsWith("Projektentscheidung:")
            }
            else -> raw
        }

        return filtered
            .distinctBy { extractChunkIdentity(it.content) }
            .take(topK)
            .map { it.copy(content = compressRetrievedChunk(it.content, maxChars)) }
    }

    private fun compressRetrievedChunk(content: String, maxChars: Int): String {
        val normalized = content
            .replace(Regex("""FILE:[^\n\r]+""")) { match ->
                val raw = match.value.removePrefix("FILE:")
                val relative = pathResolver.absoluteToProjectRelative(raw) ?: raw
                "FILE:$relative"
            }
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized.take(maxChars)
    }

    private fun extractChunkIdentity(content: String): String {
        val file = Regex("""FILE:([^\n\r]+)""")
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        val chunk = Regex("""CHUNK:([^\n\r]+)""")
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        return "$file#$chunk"
    }

    private fun shouldForceReadFile(
        userText: String,
        reply: String,
        projectId: String?
    ): Boolean {
        if (projectId == null) return false

        val mentioned = extractMentionedFilePath(userText) ?: return false
        if (mentioned.isBlank()) return false

        val trimmedReply = reply.trim()

        return trimmedReply.isBlank() || looksLikeGenericCompletion(trimmedReply)
    }

    private fun relevantHistory(): List<ChatMessage> {
        val all = history().filterNot { it.role == Role.SYSTEM }

        val lastUserMessages = all
            .filter { it.role == Role.USER }
            .takeLast(2)

        val lastReadFiles = all
            .filter { it.role == Role.TOOL && it.content.startsWith("[read_file]") }
            .takeLast(2)

        val recentToolAndAssistant = all
            .filter { it.role == Role.TOOL || it.role == Role.ASSISTANT }
            .takeLast(4)

        fun trim(msg: ChatMessage): ChatMessage {
            val maxChars = when {
                msg.role == Role.TOOL && msg.content.startsWith("[read_file]") -> 7000
                msg.role == Role.TOOL -> 2000
                msg.role == Role.ASSISTANT -> 1500
                msg.role == Role.USER -> 2000
                else -> 1000
            }
            return ChatMessage(msg.role, msg.content.take(maxChars))
        }

        return (lastUserMessages + lastReadFiles + recentToolAndAssistant)
            .map(::trim)
            .distinctBy { "${it.role}:${it.content}" }
            .takeLast(8)
    }

    private fun shouldNormalizePath(toolName: String): Boolean {
        return toolName in setOf(
            "read_file",
            "write_file",
            "write_files",
            "append_to_file",
            "replace_in_file",
            "list_dir"
        )
    }

    private fun normalizeToolArgs(toolName: String, args: JsonObject): JsonObject {
        if (!shouldNormalizePath(toolName)) return args

        if (toolName == "write_files") {
            return dedupeWriteFilesArgs(args)
        }

        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return args
        val normalizedPath = pathResolver.normalizeRelative(path.replace("\\", "/").trim())

        return if (normalizedPath == path) {
            args
        } else {
            buildJsonObject {
                args.forEach { (k, v) ->
                    if (k == "path") put(k, JsonPrimitive(normalizedPath))
                    else put(k, v)
                }
            }
        }
    }

    private fun shouldAnswerAfterReadOnlyTool(userText: String): Boolean {
        val t = userText.lowercase()

        return listOf(
            "verbesserung",
            "verbesserungsvorschläge",
            "verbessern",
            "review",
            "analyse",
            "analysiere",
            "erklär",
            "erklaer",
            "erkläre",
            "bewerte",
            "beurteile",
            "refactoring",
            "feedback"
        ).any { it in t }
    }

    private fun validateFinalReplyWithoutTools(
        reply: String,
        projectId: String?,
        hasProjectContext: Boolean,
        modificationRequested: Boolean
    ): String? {
        if (reply.isBlank()) {
            return "Plan rejected: empty final reply."
        }

        if (modificationRequested) {
            return "Plan rejected: modification request cannot finish with reply only. Use mutating tool calls."
        }

        if (projectId != null && looksLikeGenericCompletion(reply)) {
            return "Plan rejected: generic completion reply without tool calls. Use tools or provide a concrete project-specific answer."
        }

        if (projectId != null && looksLikeCode(reply)) {
            return "Plan rejected: reply contains code but no tool calls were made. Read project files first and do not invent code."
        }

        if (projectId != null && !hasProjectContext && looksProjectSpecific(reply)) {
            return "Plan rejected: project-specific final reply without tool calls and without project context."
        }

        return null
    }

    private fun looksLikeCode(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false

        return t.contains("fun ") ||
                t.contains("class ") ||
                t.contains("import ") ||
                t.contains("val ") ||
                t.contains("var ") ||
                t.contains("override ") ||
                t.contains("package ") ||
                (t.contains("{") && t.contains("}"))
    }

    private fun looksProjectSpecific(text: String): Boolean {
        val t = text.lowercase()

        return listOf(
            ".kt",
            ".java",
            "viewmodel",
            "repository",
            "composable",
            "activity",
            "fragment",
            "usecase",
            "stateflow",
            "mutableStateFlow",
            "jetpack compose",
            "android"
        ).any { it in t }
    }

    private fun shouldStoreAssistantTurn(text: String): Boolean {
        val t = text.lowercase().trim()

        if (t.isBlank()) return false

        val blockedPatterns = listOf(
            "bitte versuche es erneut",
            "please try again",
            "max steps reached",
            "tool-plan konnte nicht korrigiert werden",
            "das modell hat mehrfach keinen gültigen tool-plan geliefert"
        )

        return blockedPatterns.none { it in t }
    }

    private fun looksLikeProjectDecisionConfirmation(userText: String): Boolean {
        val t = userText.lowercase().trim()

        val exactSignals = listOf(
            "genau so",
            "genau so machen wir das",
            "das behalten wir so",
            "so behalten wir das",
            "so machen wir das",
            "das ist die richtige lösung",
            "das ist richtig",
            "so soll der agent arbeiten",
            "das nehmen wir",
            "das passt so",
            "passt so",
            "perfekt so",
            "genau so soll es sein"
        )

        return exactSignals.any { it in t }
    }

    private fun extractProjectDecisionCandidate(userText: String): String? {
        val normalized = userText.trim()
            .replace(Regex("\\s+"), " ")

        if (!looksLikeProjectDecisionConfirmation(normalized)) return null
        if (normalized.length < 8) return null

        val tooGeneric = setOf(
            "passt so",
            "perfekt",
            "super",
            "genau"
        )

        if (normalized.lowercase() in tooGeneric) return null

        return "Projektentscheidung: $normalized"
    }

    private fun extractMentionedFilePath(userText: String): String? {
        return pathResolver.tryResolveMentionedPath(userText)
    }

    private fun looksLikeGenericCompletion(text: String): Boolean {
        val t = text.lowercase().trim()

        val exactMatches = setOf(
            "fertig.",
            "fertig",
            "erledigt.",
            "erledigt",
            "done.",
            "done",
            "die aufgabe ist abgeschlossen.",
            "die aufgabe ist abgeschlossen",
            "aufgabe abgeschlossen.",
            "aufgabe abgeschlossen"
        )

        if (t in exactMatches) return true

        val genericPatterns = listOf(
            "die aufgabe ist abgeschlossen",
            "ich habe die aufgabe abgeschlossen",
            "die arbeit ist abgeschlossen",
            "alles erledigt",
            "task completed",
            "the task is complete",
            "the task is completed"
        )

        return genericPatterns.any { it in t }
    }

    private fun buildPathArgs(path: String): JsonObject {
        return buildJsonObject {
            put("path", JsonPrimitive(path))
        }
    }

    private fun isSamePath(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return a.replace('\\', '/').trim() == b.replace('\\', '/').trim()
    }

    private fun enforceAndroidSourceSet(path: String): String {
        val normalized = path.replace("\\", "/").removePrefix("/")

        if (
            normalized.startsWith("app/src/main/java/") ||
            normalized.startsWith("app/src/main/kotlin/") ||
            normalized.startsWith("src/main/java/") ||
            normalized.startsWith("src/main/kotlin/")
        ) {
            return normalized
        }

        if (normalized.endsWith(".kt") || normalized.endsWith(".java")) {
            return when {
                normalized.startsWith("app/") -> normalized
                else -> "app/src/main/java/$normalized"
            }
        }

        return normalized
    }

    private fun dedupeWriteFilesArgs(args: JsonObject): JsonObject {
        val files = args["files"]?.jsonArray ?: return args
        val normalized = linkedMapOf<String, JsonObject>()

        files.forEach { el ->
            val obj = el.jsonObject

            val rawPath = obj["path"]?.jsonPrimitive?.contentOrNull
                ?.replace("\\", "/")
                ?.trim()
                .orEmpty()

            if (rawPath.isBlank()) return@forEach

            val normalizedPath = enforceAndroidSourceSet(
                pathResolver.normalizeRelative(rawPath)
            )

            val normalizedObj = buildJsonObject {
                obj.forEach { (k, v) ->
                    if (k == "path") {
                        put("path", JsonPrimitive(normalizedPath))
                    } else {
                        put(k, v)
                    }
                }
            }

            normalized[normalizedPath] = normalizedObj
        }

        return buildJsonObject {
            put("files", kotlinx.serialization.json.buildJsonArray {
                normalized.values.forEach { add(it) }
            })
        }
    }

    private fun buildProjectNoteFromToolSuccess(
        toolName: String,
        args: JsonObject,
        rawOutput: String
    ): String? {
        fun pathArg(): String? = args["path"]?.jsonPrimitive?.contentOrNull?.trim()

        return when (toolName) {
            "write_file" -> {
                pathArg()?.let { "Projektdatei erfolgreich geschrieben: $it" }
            }

            "append_to_file" -> {
                pathArg()?.let { "Projektdatei erfolgreich erweitert: $it" }
            }

            "replace_in_file" -> {
                pathArg()?.let { "Projektdatei erfolgreich geändert: $it" }
            }

            "write_files" -> {
                "Mehrere Projektdateien erfolgreich geschrieben."
            }

            else -> null
        }
    }

    private fun looksLikeReadFileNotFound(error: AgentError): Boolean {
        return error is AgentError.ToolFailure &&
                error.toolName == "read_file" &&
                (
                        "not found" in error.message.lowercase() ||
                                "nicht gefunden" in error.message.lowercase() ||
                                "does not exist" in error.message.lowercase()
                        )
    }

    private fun chooseFallbackReadPath(userText: String, failedPath: String): String? {
        val mentioned = extractMentionedFilePath(userText) ?: return null
        if (mentioned.equals(failedPath, ignoreCase = true)) return null
        return mentioned
    }

    private suspend fun reindexProjectFileIfPossible(path: String) {
        if (projectId == null) return
        if (path.isBlank()) return

        runCatching {
            val normalizedRelative = path.replace("\\", "/").removePrefix("/")
            val absolutePath = (projectRoot / normalizedRelative.toPath()).normalized()

            if (!FileSystem.SYSTEM.exists(absolutePath)) {
                println("PROJECT_REINDEX_SKIP file_missing path=$normalizedRelative")
                return
            }

            val content = FileSystem.SYSTEM.read(absolutePath) { readUtf8() }
            if (content.isBlank()) {
                println("PROJECT_REINDEX_SKIP file_blank path=$normalizedRelative")
                return
            }

            val chunks = CodeChunker.chunkCode(
                filePath = normalizedRelative,
                content = content
            )

            val payloads = chunks.map { c ->
                buildString {
                    appendLine("TYPE:CODE")
                    appendLine("FILE:${c.filePath.replace("\\", "/")}")
                    appendLine("CHUNK:${c.index + 1}/${c.total}")
                    appendLine("---")
                    append(c.content)
                }
            }

            var chunkCount = 0
            for (payload in payloads) {
                val ok = memory.storeIndexText(
                    tenantId = tenantId,
                    conversationId = "project-index:$projectId",
                    scope = MemoryScope.PROJECT,
                    projectId = projectId,
                    type = MemoryType.PROJECT_INFO,
                    text = payload
                )
                if (ok) chunkCount++
            }

            println("PROJECT_REINDEX_PARTIAL path=$normalizedRelative chunks=$chunkCount replaceMode=MISSING")
        }.onFailure { e ->
            println("PROJECT_REINDEX_FAILED path=$path error=${e::class.simpleName}: ${e.message}")
        }
    }

    private fun extractAffectedProjectPaths(
        toolName: String,
        args: JsonObject
    ): List<String> {
        return when (toolName) {
            "write_file",
            "append_to_file",
            "replace_in_file" -> {
                args["path"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { listOf(it) }
                    ?: emptyList()
            }

            "write_files" -> {
                val raw = args["files"] ?: return emptyList()
                runCatching {
                    raw.jsonArray.mapNotNull { el ->
                        el.jsonObject["path"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }
                }.getOrDefault(emptyList())
            }

            else -> emptyList()
        }
    }
}

private fun isMutatingTool(toolName: String): Boolean {
    return toolName in setOf(
        "write_file",
        "write_files",
        "append_to_file",
        "replace_in_file"
    )
}

private class ProjectPathResolver(
    private val projectRoot: Path
) {
    fun tryResolveMentionedPath(userText: String): String? {
        val normalizedText = userText.replace("\\", "/")

        extractAbsolutePath(normalizedText)?.let { abs ->
            return absoluteToProjectRelative(abs)
        }

        extractRelativePath(normalizedText)?.let { rel ->
            return normalizeRelative(rel)
        }

        extractFileName(normalizedText)?.let { fileName ->
            return normalizeRelative(fileName)
        }

        return null
    }

    fun normalizeRelative(path: String): String {
        return path
            .trim()
            .replace("\\", "/")
            .removePrefix("./")
            .removePrefix("/")
            .toPath(normalize = true)
            .toString()
            .replace("\\", "/")
    }

    fun absoluteToProjectRelative(rawAbsolute: String): String? {
        val absolute = rawAbsolute.replace("\\", "/").toPath(normalize = true)
        val root = projectRoot.normalized()

        if (!isInsideRoot(absolute, root)) return null

        return makeRelativeToRoot(absolute, root)
    }

    private fun extractAbsolutePath(text: String): String? {
        val absoluteRegex = Regex("""([A-Za-z]:/[^ \n\r\t"']+?\.(kt|java|xml|json|kts|gradle))""")
        return absoluteRegex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractRelativePath(text: String): String? {
        val relRegex = Regex("""((?:[\w.-]+/)+[\w.-]+\.(kt|java|xml|json|kts|gradle))""")
        return relRegex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractFileName(text: String): String? {
        val fileRegex = Regex("""([\w.-]+\.(kt|java|xml|json|kts|gradle))""")
        return fileRegex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun isInsideRoot(child: Path, root: Path): Boolean {
        val childSeg = child.normalized().segments
        val rootSeg = root.normalized().segments
        return childSeg.size >= rootSeg.size &&
                childSeg.take(rootSeg.size) == rootSeg
    }

    private fun makeRelativeToRoot(path: Path, root: Path): String {
        val fullSeg = path.normalized().segments
        val rootSeg = root.normalized().segments
        return fullSeg.drop(rootSeg.size).joinToString("/")
    }
}
