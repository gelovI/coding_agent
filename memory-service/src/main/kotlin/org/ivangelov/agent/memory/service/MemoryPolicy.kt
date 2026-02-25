package org.ivangelov.agent.memory.service

import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.memory.core.MemoryType

data class StoreDecision(
    val store: Boolean,
    val type: MemoryType = MemoryType.TURN,
    val normalizedText: String = ""
)

interface MemoryPolicy {
    fun decideStore(role: ChatMessage.Role, text: String): StoreDecision

    fun normalize(text: String): String = text.trim()

    object Default : MemoryPolicy {

        private val rememberPrefixes = listOf(
            "merke dir",
            "merk dir",
            "remember",
            "from now on",
            "ab jetzt",
            "von nun an"
        )

        private fun looksLikeToolPlanJson(s: String): Boolean {
            val t = s.trim()
            if (!t.startsWith("{") || !t.endsWith("}")) return false
            return t.contains("\"tool_calls\"") || t.contains("\"reply\"")
        }

        override fun decideStore(role: ChatMessage.Role, text: String): StoreDecision {
            val t = text.trim()
            if (t.isBlank()) return StoreDecision(store = false)
            if (role == ChatMessage.Role.TOOL) return StoreDecision(store = false)

            val lower = t.lowercase()

            // Tool-Plan JSON niemals speichern
            if (looksLikeToolPlanJson(t)) {
                return StoreDecision(store = false)
            }

            // Noise / meta
            if (lower.startsWith("we need to") ||
                lower.startsWith("got it") ||
                lower == "ok" ||
                lower == "okay"
            ) {
                return StoreDecision(store = false)
            }

            // Explizit: "Merke dir: ..."
            val isExplicitRemember = rememberPrefixes.any { lower.startsWith(it) }
            if (isExplicitRemember) {
                val cleaned = t.substringAfter(":", t).trim()
                val normalized = normalize(cleaned.ifBlank { t })
                val type = classify(normalized)
                return StoreDecision(
                    store = normalized.length >= 10,
                    type = type,
                    normalizedText = normalized
                )
            }

            // Implizit extrem selektiv (damit Qdrant nicht noisy wird)
            if (t.length < 80) return StoreDecision(store = false)

            val normalized = normalize(t)
            return StoreDecision(store = true, type = MemoryType.TURN, normalizedText = normalized)
        }

        private fun classify(normalized: String): MemoryType {
            val l = normalized.lowercase()
            return when {
                "projekt" in l || "project" in l -> MemoryType.PROJECT_INFO
                "ich mag" in l || "i prefer" in l || "bitte" in l -> MemoryType.PREFERENCE
                else -> MemoryType.FACT
            }
        }

        override fun normalize(text: String): String =
            text.trim()
                .replace(Regex("\\s+"), " ")
                .removeSuffix(".")
    }
}