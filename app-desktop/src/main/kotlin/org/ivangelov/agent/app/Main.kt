package org.ivangelov.agent.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.ivangelov.agent.app.di.AppDependencies
import org.ivangelov.agent.app.ui.AppRoot

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Coding Agent"
    ) {
        val deps = remember { AppDependencies.create() }

        MaterialTheme {
            AppRoot(deps = deps)
        }
    }
}