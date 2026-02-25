package org.ivangelov.agent.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProjectBar(
    projects: List<org.ivangelov.agent.db.Project>,
    activeProjectId: String?,
    onSelect: (String?) -> Unit,
    onCreate: (name: String, rootPath: String?) -> Unit,
    onDelete: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val active = projects.firstOrNull { it.id == activeProjectId }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(active?.name ?: "Projekt wählen")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("GLOBAL (kein Projekt)") },
                onClick = {
                    expanded = false
                    onSelect(null)
                }
            )
            projects.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    onClick = {
                        expanded = false
                        onSelect(p.id)
                    }
                )
            }
        }

        Button(onClick = { showDialog = true }) {
            Text("+ Projekt")
        }

        // DELETE BUTTON
        OutlinedButton(
            onClick = { showDeleteDialog = true },
            enabled = active != null
        ) {
            Text("🗑 Löschen")
        }
    }

    if (showDialog) {
        CreateProjectDialog(
            onDismiss = { showDialog = false },
            onCreate = { name, root ->
                showDialog = false
                onCreate(name, root)
            }
        )
    }

    if (showDeleteDialog && active != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDelete(active.id)
                    }
                ) {
                    Text("Löschen bestätigen")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteDialog = false }
                ) {
                    Text("Abbrechen")
                }
            },
            title = { Text("Projekt löschen") },
            text = {
                Text("Möchtest du das Projekt \"${active.name}\" wirklich löschen?")
            }
        )
    }
}

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, rootPath: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim(), root.trim().ifBlank { null }) },
                enabled = name.trim().isNotEmpty()
            ) { Text("Erstellen") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Abbrechen") }
        },
        title = { Text("Neues Projekt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Projektname") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = root,
                    onValueChange = { root = it },
                    label = { Text("Root Path (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}