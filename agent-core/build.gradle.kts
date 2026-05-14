plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.uuid)

    api(libs.ktor.client.core)

    // Ktor client
    implementation(libs.ktor.client.cio)

    // JSON negotiation
    implementation(libs.ktor.client.contentneg)
    implementation(libs.ktor.serialization.json)
}
