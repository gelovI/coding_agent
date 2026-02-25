package org.ivangelov.agent.app.di

import okio.Path
import okio.Path.Companion.toPath
import org.ivangelov.agent.app.util.Logger
import org.ivangelov.agent.app.util.StdoutLogger
import org.ivangelov.agent.core.infrastructure.HttpClients
import org.ivangelov.agent.db.DbFactory
import org.ivangelov.agent.memory.service.MemoryService

class AppDependencies private constructor(
    val logger: Logger,
    val defaultRoot: Path,

    val db: Any,
    val chatRepo: org.ivangelov.agent.db.ChatRepository,
    val projectRepo: org.ivangelov.agent.db.ProjectRepository,

    val rootResolver: ProjectRootResolver,

    val createAgent: (
        tenantId: String,
        conversationId: String,
        projectId: String?,
        toolsRoot: Path
    ) -> org.ivangelov.agent.orchestrator.ToolLoopAgentFacade
) {
    companion object {
        fun create(): AppDependencies {
            val logger: Logger = StdoutLogger()

            val defaultRoot = System.getProperty("user.dir").toPath()
            val rootResolver = ProjectRootResolver(defaultRoot = defaultRoot, logger = logger)

            val db = DbFactory.create("coding-agent.db")
            val chatRepo = org.ivangelov.agent.db.ChatRepository(db)
            val projectRepo = org.ivangelov.agent.db.ProjectRepository(db)

            val http = HttpClients.llm

            val llm = org.ivangelov.agent.llm.ollama.OllamaLlmClient(
                model = "gpt-oss:20b",
                http = http
            )

            // memory pipeline (shared)
            val embed = org.ivangelov.agent.llm.ollama.OllamaEmbedClient(http = http)
            val qdrant = org.ivangelov.agent.memory.qdrant.QdrantMemoryStore(http = http)
            val memory = org.ivangelov.agent.memory.service.MemoryService(embed = embed, store = qdrant)

            // ToolRegistry factory per root
            fun toolRegistryFor(
                root: Path,
                tenantId: String,
                conversationId: String,
                projectId: String?,
                memory: MemoryService
            ): org.ivangelov.agent.tools.ToolRegistry {
                return org.ivangelov.agent.tools.ToolRegistry(
                    listOf(
                        org.ivangelov.agent.tools.fs.ListDirTool(root),
                        org.ivangelov.agent.tools.fs.ReadFileTool(root),
                        org.ivangelov.agent.tools.fs.WriteFileTool(root),

                        // Project indexing into vector memory
                        org.ivangelov.agent.tools.code.IndexProjectTool(
                            root = root,
                            memory = memory,
                            tenantId = tenantId,
                            conversationId = conversationId,
                            projectId = projectId
                        ),

                        // Retrieve relevant code chunks for architecture analysis
                        org.ivangelov.agent.tools.code.AnalyzeArchitectureTool(
                            memory = memory,
                            tenantId = tenantId,
                            conversationId = conversationId,
                            projectId = projectId
                        )
                    )
                )
            }

            val createAgent =
                { tenantId: String, conversationId: String, projectId: String?, toolsRoot: Path ->
                    val tools = toolRegistryFor(
                        root = toolsRoot,
                        tenantId = tenantId,
                        conversationId = conversationId,
                        projectId = projectId,
                        memory = memory
                    )

                    org.ivangelov.agent.orchestrator.ToolLoopAgentFacade(
                        repo = chatRepo,
                        tenantId = tenantId,
                        conversationId = conversationId,
                        llm = llm,
                        tools = tools,
                        memory = memory,
                        projectId = projectId
                    )
                }

            return AppDependencies(
                logger = logger,
                defaultRoot = defaultRoot,
                db = db as Any,
                chatRepo = chatRepo,
                projectRepo = projectRepo,
                rootResolver = rootResolver,
                createAgent = createAgent
            )
        }
    }
}