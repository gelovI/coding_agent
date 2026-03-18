package org.ivangelov.agent.app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okio.Path
import org.ivangelov.agent.app.di.ProjectRootResolver
import org.ivangelov.agent.app.util.Logger
import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.core.agent.AgentEvent

class ChatController(
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val chatRepo: org.ivangelov.agent.db.ChatRepository,
    private val projectRepo: org.ivangelov.agent.db.ProjectRepository,
    private val rootResolver: ProjectRootResolver,
    private val defaultRoot: Path,
    private val createAgent: (
        tenantId: String,
        conversationId: String,
        projectId: String?,
        toolsRoot: Path
    ) -> org.ivangelov.agent.orchestrator.ToolLoopAgentFacade
) {
    private val tenantId: String = "local-user"

    // session-only
    private var globalConversationId: String? = null

    private var sendJob: Job? = null

    var projects: List<org.ivangelov.agent.db.Project> by mutableStateOf(emptyList())
        private set

    var activeProjectId: String? by mutableStateOf(null)
        private set

    var conversationId: String? by mutableStateOf(null)
        private set

    var toolsRoot: Path by mutableStateOf(defaultRoot)
        private set

    var input: String by mutableStateOf("")
        private set

    var streamingAssistant: String by mutableStateOf("")
        private set

    var history: List<ChatMessage> by mutableStateOf(emptyList())
        private set

    var isSending: Boolean by mutableStateOf(false)
        private set

    var agent: org.ivangelov.agent.orchestrator.ToolLoopAgentFacade? by mutableStateOf(null)
        private set

    var toolsRootWasFallback: Boolean by mutableStateOf(false)
        private set

    var toolsRootReason: String? by mutableStateOf(null)
        private set

    val uiMessages: List<ChatMessage>
        get() =
            if (streamingAssistant.isBlank()) history
            else history + ChatMessage(ChatMessage.Role.ASSISTANT, streamingAssistant)

    fun onStart() {
        scope.launch {
            projects = projectRepo.list(tenantId)
            if (activeProjectId == null) {
                activeProjectId = projects.firstOrNull()?.id
            }
            ensureConversationAndReload()
        }
    }

    fun onInputChange(value: String) {
        input = value
    }

    fun selectProject(id: String?) {
        scope.launch {
            cancelActiveSend("project switch")

            // switching to GLOBAL resets session-global conversation
            if (id == null) globalConversationId = null

            activeProjectId = id
            ensureConversationAndReload()
        }
    }

    fun createProject(name: String, rootPath: String?) {
        scope.launch {
            cancelActiveSend("create project")

            val id = projectRepo.create(tenantId, name, rootPath)
            projects = projectRepo.list(tenantId)
            activeProjectId = id
            ensureConversationAndReload()
        }
    }

    fun clear() {
        scope.launch {
            cancelActiveSend("clear")

            input = ""
            streamingAssistant = ""
            reloadHistory()
        }
    }

    fun send() {
        val a = agent ?: return
        val cid = conversationId ?: return

        val text = input.trim()
        if (text.isEmpty()) return

        cancelActiveSend("new send")

        input = ""
        isSending = true

        sendJob = scope.launch {
            try {
                streamingAssistant = ""

                a.send(text).collect { event ->
                    when (event) {
                        is AgentEvent.UserMessageStored -> {
                            history = chatRepo.loadMessages(cid).map {
                                ChatMessage(ChatMessage.Role.valueOf(it.role), it.content)
                            }
                        }

                        is AgentEvent.StreamDelta -> {
                            appendAssistantDelta(event.text)
                        }

                        is AgentEvent.ToolExecuted -> {
                            if (streamingAssistant.isNotBlank()) {
                                appendHistoryMessage(
                                    ChatMessage(ChatMessage.Role.ASSISTANT, streamingAssistant)
                                )
                                streamingAssistant = ""
                            }

                            appendHistoryMessage(
                                ChatMessage(
                                    ChatMessage.Role.TOOL,
                                    "[${event.toolName}]\n${event.output}"
                                )
                            )
                        }

                        is AgentEvent.AssistantMessage -> {
                            if (streamingAssistant.isNotBlank()) {
                                streamingAssistant = ""
                            }

                            appendHistoryMessage(
                                ChatMessage(ChatMessage.Role.ASSISTANT, event.text)
                            )
                        }

                        AgentEvent.Completed -> {
                            history = chatRepo.loadMessages(cid).map {
                                ChatMessage(ChatMessage.Role.valueOf(it.role), it.content)
                            }
                            streamingAssistant = ""
                            isSending = false
                        }
                    }
                }
            } catch (ce: CancellationException) {
                logger.info("Send cancelled: ${ce.message ?: "no message"}")
                throw ce
            } catch (t: Throwable) {
                logger.error("Send failed", t)
            } finally {
                streamingAssistant = ""
                isSending = false
                sendJob = null
            }
        }
    }

    private fun appendAssistantDelta(delta: String) {
        streamingAssistant += delta
    }

    private fun appendHistoryMessage(message: ChatMessage) {
        history = history + message
    }

    private fun ensureConversationAndReload() {
        // resolve tools root based on selected project
        val active = projects.firstOrNull { it.id == activeProjectId }
        val projectName = active?.name

        // NOTE: If your field is not rootPath, adjust this one line:
        val projectRootPath: String? = active?.rootPath

        val trimmed = projectRootPath?.trim().takeUnless { it.isNullOrBlank() }
        if (trimmed == null) {
            toolsRoot = defaultRoot
            toolsRootWasFallback = true
            toolsRootReason = "Kein Projekt-Root gesetzt"
        } else {
            val resolved = rootResolver.resolve(trimmed, projectName)
            toolsRoot = resolved

            // fallback detection (rootResolver returns defaultRoot when invalid)
            if (resolved == defaultRoot) {
                toolsRootWasFallback = true
                toolsRootReason = "Projekt-Root ungültig – Fallback auf Default"
            } else {
                toolsRootWasFallback = false
                toolsRootReason = null
            }
        }

        conversationId =
            if (activeProjectId == null) {
                val id = globalConversationId ?: chatRepo.createConversation(
                    tenantId = tenantId,
                    title = "Global Session",
                    projectId = null
                ).also { globalConversationId = it }
                id
            } else {
                chatRepo.getLatestConversationId(tenantId, activeProjectId)
                    ?: chatRepo.createConversation(
                        tenantId = tenantId,
                        title = "Project Chat",
                        projectId = activeProjectId
                    )
            }

        val cid = conversationId
        agent = if (cid == null) null else createAgent(tenantId, cid, activeProjectId, toolsRoot)

        streamingAssistant = ""
        input = ""
        reloadHistory()
    }

    private fun reloadHistory() {
        val cid = conversationId ?: return
        history = chatRepo.loadMessages(cid).map {
            ChatMessage(ChatMessage.Role.valueOf(it.role), it.content)
        }
    }

    private fun cancelActiveSend(reason: String) {
        val job = sendJob ?: return
        logger.warn("Cancelling active send due to: $reason")
        job.cancel(CancellationException(reason))
        sendJob = null

        // make UI consistent immediately
        streamingAssistant = ""
        isSending = false
    }

    fun deleteProject(projectId: String) {
        scope.launch {
            cancelActiveSend("delete project")

            projectRepo.delete(tenantId, projectId)

            projects = projectRepo.list(tenantId)

            if (activeProjectId == projectId) {
                activeProjectId = projects.firstOrNull()?.id
                ensureConversationAndReload()
            }
        }
    }
}