plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":memory-service"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.squareup.okio:okio:3.9.1")
}