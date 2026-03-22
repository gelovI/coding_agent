package org.ivangelov.agent.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun MessageBubble(role: String, text: String) {
    val isUser = role == "USER"
    val isTool = role == "TOOL"

    val align = if (isUser) Alignment.End else Alignment.Start
    val shape = RoundedCornerShape(16.dp)

    val containerColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isTool -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    val roleLabel = when (role) {
        "USER" -> "Du"
        "ASSISTANT" -> "Agent"
        "TOOL" -> "Tool"
        else -> role
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Surface(
            tonalElevation = if (isTool) 0.dp else 2.dp,
            shape = shape,
            color = containerColor,
            modifier = if (isTool) {
                Modifier.fillMaxWidth(0.92f)
            } else {
                Modifier
            }
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(6.dp))

                SelectionContainer {
                    RenderRichText(text)
                }
            }
        }
    }
}

@Composable
private fun RenderRichText(text: String) {
    val parts = splitByCodeFences(text)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is RichPart.Paragraph -> {
                    val t = part.text.trim()
                    if (t.isNotEmpty()) Text(t, style = MaterialTheme.typography.bodyMedium)
                }
                is RichPart.CodeBlock -> {
                    CodeBlockCard(language = part.language, code = part.code)
                }
            }
        }
    }
}

private sealed class RichPart {
    data class Paragraph(val text: String) : RichPart()
    data class CodeBlock(val language: String?, val code: String) : RichPart()
}

private fun splitByCodeFences(input: String): List<RichPart> {
    val regex = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```")
    val out = mutableListOf<RichPart>()

    var last = 0
    for (m in regex.findAll(input)) {
        val start = m.range.first
        val end = m.range.last + 1

        if (start > last) out += RichPart.Paragraph(input.substring(last, start))

        val lang = m.groupValues[1].takeIf { it.isNotBlank() }
        val code = m.groupValues[2]
        out += RichPart.CodeBlock(lang, code)

        last = end
    }

    if (last < input.length) out += RichPart.Paragraph(input.substring(last))
    return out
}

@Composable
private fun CodeBlockCard(language: String?, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    val bg = MaterialTheme.colorScheme.surfaceVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language ?: "code",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code.trimEnd()))
                        copied = true
                    }
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Outlined.Done else Icons.Outlined.ContentCopy,
                        contentDescription = "Copy"
                    )
                }
            }

            val scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp, max = 260.dp)
                    .verticalScroll(scroll)
                    .padding(10.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = code.trimEnd(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}