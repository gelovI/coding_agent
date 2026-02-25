pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "coding-agent"

include(
    ":agent-core",
    ":agent-orchestrator",
    ":app-desktop",
    ":memory-core",
    ":memory-qdrant",
    ":memory-sqldelight",
    ":tools",
    ":llm-ollama"
)

include("llm-ollama")
include("memory-service")