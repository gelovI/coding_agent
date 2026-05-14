plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":memory-core"))
    implementation(project(":tools"))
    implementation(project(":memory-sqldelight"))
    implementation(project(":llm-ollama"))
    implementation(project(":memory-qdrant"))
    implementation(project(":memory-service"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("com.squareup.okio:okio:3.9.0")
}
