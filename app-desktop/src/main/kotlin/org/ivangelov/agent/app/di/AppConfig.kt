package org.ivangelov.agent.app.di

data class AppConfig(
    val dbPath: String,
    val ollamaBaseUrl: String,
    val chatModel: String,
    val embeddingModel: String,
    val qdrantBaseUrl: String,
    val qdrantCollection: String,
    val maxSteps: Int,
    val maxToolCallsTotal: Int,
    val maxToolOutputChars: Int
)

object AppConfigLoader {

    fun load(): AppConfig =
        AppConfig(
            dbPath = stringValue(
                systemProperty = "agent.db.path",
                environmentVariable = "AGENT_DB_PATH",
                defaultValue = "coding-agent.db"
            ),
            ollamaBaseUrl = stringValue(
                systemProperty = "agent.ollama.baseUrl",
                environmentVariable = "OLLAMA_BASE_URL",
                defaultValue = "http://127.0.0.1:11434"
            ),
            chatModel = stringValue(
                systemProperty = "agent.chat.model",
                environmentVariable = "AGENT_CHAT_MODEL",
                defaultValue = "qwen2.5-coder:14b"
            ),
            embeddingModel = stringValue(
                systemProperty = "agent.embedding.model",
                environmentVariable = "AGENT_EMBED_MODEL",
                defaultValue = "mxbai-embed-large:latest"
            ),
            qdrantBaseUrl = stringValue(
                systemProperty = "agent.qdrant.baseUrl",
                environmentVariable = "QDRANT_BASE_URL",
                defaultValue = "http://127.0.0.1:6333"
            ),
            qdrantCollection = stringValue(
                systemProperty = "agent.qdrant.collection",
                environmentVariable = "QDRANT_COLLECTION",
                defaultValue = "agent_memory"
            ),
            maxSteps = intValue(
                systemProperty = "agent.maxSteps",
                environmentVariable = "AGENT_MAX_STEPS",
                defaultValue = 4
            ),
            maxToolCallsTotal = intValue(
                systemProperty = "agent.maxToolCalls",
                environmentVariable = "AGENT_MAX_TOOL_CALLS",
                defaultValue = 12
            ),
            maxToolOutputChars = intValue(
                systemProperty = "agent.maxToolOutputChars",
                environmentVariable = "AGENT_MAX_TOOL_OUTPUT_CHARS",
                defaultValue = 20_000
            )
        )

    private fun stringValue(
        systemProperty: String,
        environmentVariable: String,
        defaultValue: String
    ): String =
        System.getProperty(systemProperty)
            ?: System.getenv(environmentVariable)
            ?: defaultValue

    private fun intValue(
        systemProperty: String,
        environmentVariable: String,
        defaultValue: Int
    ): Int =
        System.getProperty(systemProperty)
            ?.toIntOrNull()
            ?: System.getenv(environmentVariable)?.toIntOrNull()
            ?: defaultValue
}
