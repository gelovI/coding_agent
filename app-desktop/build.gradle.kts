plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":memory-service"))
    implementation(project(":memory-core"))
    implementation(project(":llm-ollama"))
    implementation(project(":agent-core"))
    implementation(project(":agent-orchestrator"))
    implementation(project(":memory-sqldelight"))
    implementation(project(":tools"))
    implementation(project(":memory-qdrant"))
    implementation(libs.okio)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.datetime)
    implementation(compose.materialIconsExtended)
}

compose.desktop {
    application {
        mainClass = "org.ivangelov.agent.app.MainKt"
    }
}
