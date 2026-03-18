plugins {
    kotlin("jvm")
    id("app.cash.sqldelight") version "2.0.2"
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":agent-core"))
    implementation("app.cash.sqldelight:runtime:2.0.2")
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
}

sqldelight {
    databases {
        create("AgentDb") {
            version = 3
            packageName.set("org.ivangelov.agent.db")
            srcDirs("src/main/sqldelight")
        }
    }
}

tasks.configureEach {
    if (name.contains("verify", ignoreCase = true) && name.contains("Migration", ignoreCase = true)) {
        enabled = false
    }
}
