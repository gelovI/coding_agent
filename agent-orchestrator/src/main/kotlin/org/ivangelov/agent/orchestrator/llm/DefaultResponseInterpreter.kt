package org.ivangelov.agent.orchestrator.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DefaultResponseInterpreter : ResponseInterpreter {

    private val jsonLenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun toDisplayText(raw: String): String {
        val t0 = raw.trim()
        if (t0.isBlank()) return ""

        val unquoted = if (t0.startsWith("\"") && t0.endsWith("\"")) {
            runCatching { jsonLenient.parseToJsonElement(t0).jsonPrimitive.content }
                .getOrDefault(t0)
        } else t0

        val t = unquoted.trim()

        if (!(t.startsWith("{") && t.endsWith("}")) && !(t.startsWith("[") && t.endsWith("]"))) {
            return t.replace("\\n", "\n").replace("<br>", "\n")
        }

        return runCatching {
            val root = jsonLenient.parseToJsonElement(t)

            val preferredKeys = setOf(
                "reply", "answer", "content", "text",
                "antwort", "architekturdarstellung",
                "message", "output", "error"
            )

            fun findPreferred(obj: JsonObject): String? {
                for ((k, v) in obj) {
                    if (k.lowercase() in preferredKeys) {
                        v.jsonPrimitive.contentOrNull?.let { return it }
                    }
                }
                return null
            }

            val candidates = mutableListOf<String>()

            fun collect(el: JsonElement) {
                when (el) {
                    is JsonObject -> {
                        findPreferred(el)?.let { candidates.add(it) }
                        el.values.forEach { collect(it) }
                    }
                    is JsonArray -> el.forEach { collect(it) }
                    is JsonPrimitive -> el.contentOrNull?.let { candidates.add(it) }
                }
            }

            collect(root)

            candidates
                .map { it.replace("\\n", "\n").replace("<br>", "\n").trim() }
                .filter { it.length >= 5 }
                .maxByOrNull { it.length }
                ?: "⚠️ Leere/ungültige Antwort erhalten."
        }.getOrDefault("⚠️ Fehler beim Parsen der Modell-Antwort.")
    }
}