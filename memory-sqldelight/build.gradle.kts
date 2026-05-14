plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":agent-core"))
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.kotlinx.datetime)
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

val verifySqlDelightMigrations = providers.gradleProperty("verifySqlDelightMigrations")
    .map(String::toBoolean)
    .getOrElse(false)

afterEvaluate {
    tasks.matching {
        name.contains("verify", ignoreCase = true) &&
                name.contains("Migration", ignoreCase = true)
    }.configureEach {
        enabled = verifySqlDelightMigrations
        onlyIf { verifySqlDelightMigrations }
        doFirst {
            System.setProperty("java.io.tmpdir", "C:/tmp")
            System.setProperty("org.sqlite.tmpdir", "C:/tmp")
        }
    }
}

gradle.taskGraph.whenReady {
    if (!verifySqlDelightMigrations) {
        allTasks
            .filter { it.project == project && it.name.contains("verify", ignoreCase = true) && it.name.contains("Migration", ignoreCase = true) }
            .forEach { task ->
                task.enabled = false
                task.onlyIf { false }
            }
    }
}
