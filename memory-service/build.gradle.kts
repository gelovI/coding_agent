plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Models + ports
    api(project(":memory-core"))

    // Qdrant store adapter
    implementation(project(":memory-qdrant"))

    // ChatMessage (kommt aus agent-core)
    implementation(project(":agent-core"))

    // Embeddings client
    implementation(project(":llm-ollama")) // falls dein Modul so heißt; sonst anpassen

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}