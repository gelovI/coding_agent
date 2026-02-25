package org.ivangelov.agent.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.putJsonArray

object PlanParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseOrNull(raw: String): AgentPlan? {
        val extracted = extractFirstJsonObject(raw) ?: return null

        val normalized = runCatching {
            val root = json.parseToJsonElement(extracted).jsonObject
            normalizeToolCalls(root)
        }.getOrNull() ?: return null

        return runCatching {
            json.decodeFromJsonElement(AgentPlan.serializer(), normalized)
        }.getOrNull()
    }

    private fun normalizeToolCalls(root: JsonObject): JsonObject {
        val toolCallsEl = root["tool_calls"] ?: return root
        if (toolCallsEl !is JsonArray) return root

        val normalizedCalls = toolCallsEl.jsonArray.map { el ->
            val obj = el.jsonObject

            // if "arguments" exists but "args" doesn't -> rename
            val args = obj["args"] ?: obj["arguments"]

            buildJsonObject {
                obj["name"]?.let { put("name", it) }
                if (args != null) put("args", args)
            }
        }

        return buildJsonObject {
            // keep reply if present
            root["reply"]?.let { put("reply", it) }
            putJsonArray("tool_calls") {
                normalizedCalls.forEach { add(it) }
            }
        }
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}