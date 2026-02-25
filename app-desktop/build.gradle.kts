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
    implementation("com.squareup.okio:okio:3.9.1")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation(compose.materialIconsExtended)
}

compose.desktop {
    application {
        mainClass = "org.ivangelov.agent.app.MainKt"
    }
}
