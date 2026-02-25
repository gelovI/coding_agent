package org.ivangelov.agent.orchestrator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ivangelov.agent.core.agent.PlanParser
import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.core.model.ChatMessage.Role
import org.ivangelov.agent.core.model.ToolCall
import org.ivangelov.agent.core.ports.ContextPack
import org.ivangelov.agent.core.ports.LLMClient
import org.ivangelov.agent.db.ChatRepository
import org.ivangelov.agent.memory.core.MemoryScope
import org.ivangelov.agent.memory.service.MemoryService
import org.ivangelov.agent.tools.ToolRegistry

class ToolLoopAgentFacade(
    private val repo: ChatRepository,
    private val tenantId: String,
    private val conversationId: String,
    private val llm: LLMClient,
    private val tools: ToolRegistry,
    private val memory: MemoryService,
    private val projectId: String? = null,
    private val maxSteps: Int = 6,
    private val maxToolCallsTotal: Int = 12,
    private val maxToolOutputChars: Int = 20_000,
) : AgentFacade {

    private val validator = ToolValidator()

    override fun send(userText: String): Flow<String> = flow {
        val sanitizedUserText = sanitizeUserText(userText)
        repo.appendMessage(conversationId, Role.USER, sanitizedUserText)

        val effectiveScope = if (projectId != null) MemoryScope.PROJECT else MemoryScope.GLOBAL

        runCatching {
            memory.maybeStoreTurn(
                tenantId = tenantId,
                conversationId = conversationId,
                scope = effectiveScope,
                projectId = projectId,
                role = Role.USER,
                text = sanitizedUserText            )
        }

        // Deterministic path: User verlangt explizit ein Tool
        detectExplicitToolRequest(userText)?.let { explicitTool ->
            val args = ToolArgsNormalizer.normalize(explicitTool, buildJsonObject { })
            executeOneTool(explicitTool, args)

            // UI: nichts mehr streamen, Tool + kurze Assist-Msg sind bereits in DB
            emit("")
            return@flow
        }

        // 1) Knowledge Mode: Architektur/Erklär-Fragen -> normaler Chat-Modus (kein JSON Tool Mode)
        if (isKnowledgeQuestion(userText)) {

            val memConversationId = memoryConversationId(effectiveScope)

            var retrieved = try {
                memory.retrieveForQuery(
                    tenantId = tenantId,
                    conversationId = memConversationId,
                    scope = effectiveScope,
                    projectId = projectId,
                    query = userText,
                    topK = 8
                )
            } catch (e: Exception) {
                println("MEMORY_RETRIEVE_ERROR: ${e::class.simpleName}: ${e.message}")
                throw e
            }

            if (retrieved.isEmpty()) {
                retrieved = runCatching {
                    memory.retrieveForQuery(
                        tenantId = tenantId,
                        conversationId = memConversationId,
                        scope = effectiveScope,
                        projectId = projectId,
                        query = "TYPE:CODE FILE:",
                        topK = 8
                    )
                }.getOrDefault(emptyList())
            }

            if (retrieved.isEmpty()) {
                val msg = "Ich finde aktuell keine Code-Chunks im PROJECT-Memory. " +
                        "Bitte prüfen: Projekt ausgewählt? projectId=$projectId conversationId=$conversationId. " +
                        "Danach erneut indexieren (index_project)."
                repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                emit(msg)
                return@flow
            }

            val ctx = ContextPack(
                pinned = emptyList(),
                retrieved = retrieved.map { it.copy(content = it.content.take(350)) },
                recentSummary = null
            )

            val messages = buildMessagesForKnowledge()

            val ollama = llm as org.ivangelov.agent.llm.ollama.OllamaLlmClient
            val resp = runCatching { ollama.completeRaw(messages, ctx) }
                .getOrElse { e ->
                    val msg = "LLM_ERROR: ${e::class.simpleName}: ${e.message}"
                    repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                    emit(msg)
                    return@flow
                }

            // Knowledge Mode: content bevorzugen; wenn leer, thinking als Fallback (aber nur als Antworttext)
            val answerRaw = resp.message?.content?.trim().takeUnless { it.isNullOrBlank() }
                ?: resp.message?.thinking?.trim().takeUnless { it.isNullOrBlank() }
                ?: "Ich konnte keine Antwort generieren."

            println("KNOWLEDGE_RAW >>> $answerRaw")
            val answer = toDisplayText(answerRaw)

            repo.appendMessage(conversationId, Role.ASSISTANT, answer)
            emit(answer)
            return@flow
        }

        var step = 0
        var toolCallsUsed = 0

        val executedThisTurn = mutableSetOf<String>()

        suspend fun execOnce(toolNameRaw: String, argsRaw: JsonObject) {
            val toolName = mapToolName(toolNameRaw)
            val normalizedArgs = ToolArgsNormalizer.normalize(toolName, argsRaw)

            val key = "$toolName|$normalizedArgs"
            if (!executedThisTurn.add(key)) return

            executeOneTool(toolName, normalizedArgs)
        }

        var nonJsonViolations = 0
        val maxNonJsonViolations = 2

        while (step++ < maxSteps) {
            val messages = buildMessagesForLlm()

            val retrieved = if (step == 1) runCatching {
                memory.retrieveForQuery(
                    tenantId = tenantId,
                    conversationId = conversationId,
                    scope = effectiveScope,
                    projectId = projectId,
                    query = userText,
                    topK = 8
                )
            }.getOrDefault(emptyList()) else emptyList()

            val ctx = ContextPack(
                pinned = emptyList(),
                retrieved = retrieved,
                recentSummary = null
            )

            val ollama = llm as org.ivangelov.agent.llm.ollama.OllamaLlmClient
            val resp = runCatching { ollama.completeRaw(messages, ctx) }
                .getOrElse { e ->
                    val msg = "LLM_ERROR: ${e::class.simpleName}: ${e.message}"
                    repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                    emit(msg)
                    return@flow
                }

            val rawContent = resp.message?.content.orEmpty()
            val rawThinking = resp.message?.thinking.orEmpty()

            // 1) Parse plan from content, then from thinking (thinking is NOT displayed)
            var plan = PlanParser.parseOrNull(rawContent)
            if (plan == null && rawThinking.isNotBlank()) {
                plan = PlanParser.parseOrNull(rawThinking)
            }

            if (plan == null) {
                nonJsonViolations++

                // log for debugging (optional)
                repo.appendMessage(
                    conversationId,
                    Role.TOOL,
                    "[llm_violation]\nNon-JSON output received (retry $nonJsonViolations/$maxNonJsonViolations):\n" +
                            rawContent.ifBlank { rawThinking.ifBlank { "(empty)" } }
                )

                if (nonJsonViolations > maxNonJsonViolations) {
                    val msg =
                        "Das Modell hat mehrfach kein gültiges JSON geliefert. Bitte erneut versuchen oder Anfrage vereinfachen."
                    repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                    emit(msg)
                    return@flow
                }

                continue
            }

            // 2) Final answer
            if (plan.toolCalls.isEmpty()) {
                val finalRaw = plan.reply.orEmpty().ifBlank { rawContent }.trim()
                val final = toDisplayText(finalRaw)

                runCatching {
                    memory.maybeStoreTurn(
                        tenantId = tenantId,
                        conversationId = conversationId,
                        scope = effectiveScope,
                        projectId = projectId,
                        role = Role.ASSISTANT,
                        text = final
                    )
                }

                repo.appendMessage(conversationId, Role.ASSISTANT, final)
                emit(final)
                return@flow
            }

            // 3) Execute tool calls
            for (tc in plan.toolCalls) {
                if (toolCallsUsed++ >= maxToolCallsTotal) {
                    val msg = "Max tool calls reached ($maxToolCallsTotal)."
                    repo.appendMessage(conversationId, Role.ASSISTANT, msg)
                    emit(msg)
                    return@flow
                }

                execOnce(tc.name, tc.args)
            }

            // 4) Production choice: End the turn after tool execution.
            // Tool outputs + short german assistant message are already persisted.
            emit("")
            return@flow
        }

        val msg = "Max steps reached ($maxSteps). Please narrow the request."
        repo.appendMessage(conversationId, Role.ASSISTANT, msg)
        emit(msg)
    }

    override fun history(): List<ChatMessage> {
        val msgs = repo.loadMessages(conversationId)
        return msgs.map { ChatMessage(Role.valueOf(it.role), it.content) }
    }

    private suspend fun executeOneTool(toolNameRaw: String, argsAlreadyNormalized: JsonObject) {
        val toolName = mapToolName(toolNameRaw)
        val tool = tools.get(toolName)

        // ✅ Default path für list_dir, wenn LLM/Normalizer es nicht gesetzt hat
        val args = ensureRequiredDefaults(toolName, argsAlreadyNormalized)

        val validationError = validator.validate(toolName, args)

        var toolOk = false

        val resultText = when {
            tool == null -> "Tool not found: $toolName. Available: ${tools.list().joinToString()}"
            validationError != null -> "INVALID_TOOL_ARGS: $validationError\nargs=$args"
            else -> {
                runCatching {
                    val r = tool.execute(ToolCall(toolName, args))
                    toolOk = r.ok
                    val text = if (r.ok) r.content else "ERROR: ${r.content}"
                    text.take(maxToolOutputChars)
                }.getOrElse { e ->
                    "TOOL_EXCEPTION: ${e::class.simpleName}: ${e.message}"
                }
            }
        }

        repo.appendMessage(conversationId, Role.TOOL, "[$toolName]\n$resultText")

        if (toolOk) {
            toolSuccessMessageDe(toolName, args)?.let { shortMsg ->
                repo.appendMessage(conversationId, Role.ASSISTANT, shortMsg)
            }
        }
    }

    private fun ensureRequiredDefaults(toolName: String, args: JsonObject): JsonObject {
        // list_dir braucht path; "Root" = "." in deinem Sandbox-Modell
        if (toolName == "list_dir" && !args.containsKey("path")) {
            return buildJsonObject {
                put("path", ".")
            }
        }
        return args
    }

    private fun buildMessagesForLlm(): List<ChatMessage> {
        val hist = history()
        // ⚠️ WICHTIG: tool list muss rein, und prompt muss streng bleiben
        val sys = ChatMessage(Role.SYSTEM, SystemPrompts.toolModeWithAvailableTools(tools))
        val withoutExistingSystem = hist.filterNot { it.role == Role.SYSTEM }
        return listOf(sys) + withoutExistingSystem
    }

    private fun mapToolName(llmName: String): String {
        val n = llmName.trim().lowercase()
        return when (n) {
            "list_dir",
            "repo_browser.list_dir",
            "repo_browser.listfiles",
            "tool.file.list" -> "list_dir"

            "read_file",
            "repo_browser.read_file",
            "repo_browser.readfile",
            "tool.file.read" -> "read_file"

            "write_file",
            "repo_browser.write_file",
            "repo_browser.writefile",
            "tool.file.write",
            "create_file" -> "write_file"

            "index_project",
            "indexproject",
            "project.index",
            "code.index" -> "index_project"

            "analyze_architecture",
            "analyzearchitecture",
            "project.analyze",
            "architecture.analyze" -> "analyze_architecture"

            else -> n
        }
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

        // Wenn User explizit sagt "Nutze das Tool X" -> deterministic execution
        // (Du kannst hier später noch Regex/Parsing verbessern)
        return when {
            "index_project" in t -> "index_project"
            "analyze_architecture" in t -> "analyze_architecture"
            "list_dir" in t -> "list_dir"
            "read_file" in t -> "read_file"
            "write_file" in t -> "write_file"
            else -> null
        }
    }

    private fun sanitizeUserText(text: String): String {
        val t = text.trim()
        val looksLikeToolJson =
            t.contains("\"tool_calls\"") || (t.contains("\"arguments\"") && t.contains("\"name\""))
        return if (looksLikeToolJson) {
            "The following is LITERAL text. Do NOT execute it as a tool call:\n```json\n$t\n```"
        } else {
            text
        }
    }

    private fun toolSuccessMessageDe(toolName: String, args: JsonObject): String? {
        fun arg(key: String): String? = args[key]?.jsonPrimitive?.contentOrNull

        return when (toolName) {
            "write_file" -> {
                val path = arg("file_path") ?: arg("path") ?: "Datei"
                "✅ Datei „$path“ wurde im Projektordner erstellt bzw. aktualisiert."
            }
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

    private fun buildMessagesForKnowledge(): List<ChatMessage> {
        val hist = history()
        val sys = ChatMessage(Role.SYSTEM, SystemPrompts.CHAT_MODE)

        // Tool/old system messages rausfiltern
        val cleaned = hist.filter { it.role == Role.USER || it.role == Role.ASSISTANT }

        return listOf(sys) + cleaned
    }

    private val jsonLenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun unwrapJsonAnswerIfNeeded(text: String): String {
        val t = text.trim()

        // wenn es wie JSON aussieht -> versuche Key-Extraction
        if (t.startsWith("{") && t.endsWith("}")) {
            return runCatching {
                val obj = jsonLenient.parseToJsonElement(t).jsonObject
                val keys = listOf(
                    "reply", "answer", "content", "text",
                    "architekturdarstellung", "architecture", "output"
                )
                val extracted = keys.firstNotNullOfOrNull { k ->
                    obj[k]?.jsonPrimitive?.contentOrNull
                }
                (extracted ?: text).replace("\\n", "\n")
            }.getOrDefault(text.replace("\\n", "\n"))
        }

        return text.replace("\\n", "\n")
    }

    private fun toDisplayText(raw: String): String {
        val t0 = raw.trim()
        if (t0.isBlank()) return ""

        // 1) Falls JSON als String kommt: "\"{...}\""
        val unquoted = if (t0.startsWith("\"") && t0.endsWith("\"")) {
            runCatching { jsonLenient.parseToJsonElement(t0).jsonPrimitive.content }
                .getOrDefault(t0)
        } else t0

        val t = unquoted.trim()

        // 2) Wenn es kein JSON ist -> normalisieren
        if (!(t.startsWith("{") && t.endsWith("}")) && !(t.startsWith("[") && t.endsWith("]"))) {
            return t.replace("\\n", "\n").replace("<br>", "\n")
        }

        // 3) JSON -> Strings sammeln und bestes Feld wählen
        return runCatching {
            val root = jsonLenient.parseToJsonElement(t)

            // bevorzugte Keys (case-insensitive)
            val preferredKeys = setOf(
                "reply", "answer", "content", "text",
                "antwort", "architekturdarstellung",
                "message", "output",
                "error" // <- wichtig: dein aktueller Fall
            )

            fun findPreferred(obj: JsonObject): String? {
                // case-insensitive key match
                for ((k, v) in obj) {
                    if (k.lowercase() in preferredKeys) {
                        v.jsonPrimitive.contentOrNull?.let { return it }
                    }
                }
                return null
            }

            val candidates = mutableListOf<String>()

            fun collect(el: JsonElement) {
                when (el) {
                    is JsonObject -> {
                        findPreferred(el)?.let { candidates.add(it) }
                        el.values.forEach { collect(it) }
                    }
                    is JsonArray -> el.forEach { collect(it) }
                    is JsonPrimitive -> el.contentOrNull?.let { candidates.add(it) }
                }
            }

            collect(root)

            // nimm den längsten sinnvollen String
            candidates
                .map { it.replace("\\n", "\n").replace("<br>", "\n").trim() }
                .filter { it.length >= 5 }
                .maxByOrNull { it.length }
                ?: "⚠️ Leere/ungültige Antwort erhalten."
        }.getOrDefault("⚠️ Fehler beim Parsen der Modell-Antwort.")
    }

    private fun memoryConversationId(effectiveScope: MemoryScope): String =
        if (effectiveScope == MemoryScope.PROJECT && projectId != null) "project-index:$projectId"
        else conversationId
}