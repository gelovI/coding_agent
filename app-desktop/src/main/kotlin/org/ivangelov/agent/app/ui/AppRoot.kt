package org.ivangelov.agent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ivangelov.agent.app.chat.ChatController
import org.ivangelov.agent.app.di.AppDependencies
import org.ivangelov.agent.app.ui.components.MessageBubble
import org.ivangelov.agent.app.ui.components.ProjectBar
import org.ivangelov.agent.app.ui.components.ToolsRootBanner

@Composable
fun AppRoot(deps: AppDependencies) {
    val scope = rememberCoroutineScope()

    val controller = remember(deps, scope) {
        ChatController(
            scope = scope,
            logger = deps.logger,
            chatRepo = deps.chatRepo,
            projectRepo = deps.projectRepo,
            rootResolver = deps.rootResolver,
            defaultRoot = deps.defaultRoot,
            createAgent = deps.createAgent
        )
    }

    LaunchedEffect(Unit) {
        controller.onStart()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Coding Agent", style = MaterialTheme.typography.titleLarge)

        ProjectBar(
            projects = controller.projects,
            activeProjectId = controller.activeProjectId,
            onSelect = { id -> controller.selectProject(id) },
            onCreate = { name, root -> controller.createProject(name, root) },
            onDelete = { id -> controller.deleteProject(id) }
        )

        ToolsRootBanner(
            root = controller.toolsRoot.toString(),
            isFallback = controller.toolsRootWasFallback,
            reason = controller.toolsRootReason
        )

        if (controller.conversationId == null || controller.agent == null) {
            Text("Loading conversation…")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(controller.uiMessages) { msg ->
                MessageBubble(role = msg.role.name, text = msg.content)
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = controller.input,
                onValueChange = controller::onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 160.dp),
                label = { Text("Message") },
                singleLine = false,
                minLines = 1,
                maxLines = 8
            )

            Button(
                enabled = controller.agent != null && !controller.isSending,
                onClick = { controller.send() }
            ) { Text("Send") }

            OutlinedButton(
                enabled = controller.agent != null && !controller.isSending,
                onClick = { controller.clear() }
            ) { Text("Clear") }
        }
    }
}