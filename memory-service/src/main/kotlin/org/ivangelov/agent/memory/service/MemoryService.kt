package org.ivangelov.agent.memory.service

import org.ivangelov.agent.core.model.ChatMessage
import org.ivangelov.agent.memory.core.*
import java.nio.charset.StandardCharsets
import java.util.UUID

class MemoryService(
    private val embed: EmbeddingClient,
    private val store: MemoryStore,
    private val policy: MemoryPolicy = MemoryPolicy.Default
) {
    suspend fun debugCountPoints(): Int = store.countPoints()

    suspend fun retrieveForQuery(
        tenantId: String,
        conversationId: String,
        scope: MemoryScope,
        projectId: String?,
        query: String,
        topK: Int = 8,
        minScore: Double = 0.55
    ): List<RetrievedMemory> {
        if (query.isBlank()) return emptyList()

        val qVec = embed.embed(query)
        val hits = store.searchWithVector(
            tenantId = tenantId,
            scope = scope,
            projectId = projectId,
            vector = qVec,
            topK = topK * 3
        )

        val mentionedFile = Regex("""([\w./-]+\.(kt|java|xml|json|kts|gradle))""")
            .find(query.replace("\\", "/"))
            ?.groupValues
            ?.getOrNull(1)
            ?.substringAfterLast("/")

        fun isLowValueTurn(text: String): Boolean {
            val t = text.lowercase().trim()

            val blockedPatterns = listOf(
                "es tut mir leid",
                "bitte versuche es erneut",
                "ich konnte keine",
                "ich konnte nicht",
                "überprüfe die datei auf mögliche fehler",
                "please try again",
                "i'm sorry",
                "i could not",
                "the provided code snippet defines",
                "overall, this class provides",
                "zipgameviewmodel",
                "data structures",
                "event handling",
                "game logic"
            )

            return blockedPatterns.any { it in t }
        }

        fun isBadProjectTurn(text: String): Boolean {
            val t = text.lowercase().trim()

            if (t.length > 1200) return true

            val blockedPatterns = listOf(
                "the provided code snippet defines",
                "overall, this class provides",
                "here are some meaningful comments",
                "natürlich, hier sind einige sinnvolle kommentare",
                "diese kommentare sollten",
                "```kotlin",
                "fun ondragto(",
                "class zipgameviewmodel",
                "class gameviewmodel"
            )

            return blockedPatterns.any { it in t }
        }

        val filteredHits = hits.filterNot { hit ->
            hit.type == MemoryType.TURN &&
                    (isLowValueTurn(hit.text) || isBadProjectTurn(hit.text))
        }

        val cleanedHits = filteredHits.filter { it.score.isFinite() }

        val architectureLike = query.lowercase().let {
            "architektur" in it ||
                    "architecture" in it ||
                    "struktur" in it ||
                    "aufbau" in it ||
                    "komponenten" in it ||
                    "schichten" in it ||
                    "datenfluss" in it ||
                    "überblick" in it ||
                    "ueberblick" in it
        }

        val fileReviewLike = query.lowercase().let {
            "verbesserung" in it ||
                    "verbesserungsvorschläge" in it ||
                    "review" in it ||
                    "analysiere" in it ||
                    "analyse" in it ||
                    "bewerte" in it ||
                    "code review" in it ||
                    ".kt" in it ||
                    ".java" in it
        }

        fun matchesMentionedFile(hit: MemoryHit): Boolean =
            mentionedFile != null &&
                    hit.text.contains("FILE:", ignoreCase = true) &&
                    hit.text.contains(mentionedFile, ignoreCase = true)

        val rankedHits = when {
            scope == MemoryScope.PROJECT -> {
                val fileMatchedHits = cleanedHits
                    .filter(::matchesMentionedFile)
                    .sortedByDescending { it.score }

                val projectInfoHits = cleanedHits
                    .filter { it.type == MemoryType.PROJECT_INFO }
                    .sortedByDescending { it.score }

                val decisionHits = cleanedHits
                    .filter { it.type == MemoryType.PROJECT_DECISION }
                    .sortedByDescending { it.score }

                val noteHits = cleanedHits
                    .filter { it.type == MemoryType.PROJECT_NOTE }
                    .sortedByDescending { it.score }

                val toolResultHits = cleanedHits
                    .filter { it.type == MemoryType.TOOL_RESULT }
                    .sortedByDescending { it.score }

                val turnHits = cleanedHits
                    .filter { it.type == MemoryType.TURN }
                    .sortedByDescending { it.score }

                val otherHits = cleanedHits
                    .filterNot {
                        it.type == MemoryType.PROJECT_INFO ||
                                it.type == MemoryType.PROJECT_DECISION ||
                                it.type == MemoryType.PROJECT_NOTE ||
                                it.type == MemoryType.TOOL_RESULT ||
                                it.type == MemoryType.TURN
                    }
                    .sortedByDescending { it.score }

                (fileMatchedHits + projectInfoHits + decisionHits + noteHits + toolResultHits + turnHits + otherHits)
                    .distinctBy { it.id }
            }

            architectureLike -> {
                val projectInfoHits = cleanedHits
                    .filter { it.type == MemoryType.PROJECT_INFO }
                    .sortedByDescending { it.score }

                if (projectInfoHits.isNotEmpty()) {
                    projectInfoHits
                } else {
                    cleanedHits.sortedByDescending { it.score }
                }
            }

            fileReviewLike -> {
                val projectInfoHits = cleanedHits
                    .filter { it.type == MemoryType.PROJECT_INFO }
                    .sortedByDescending { it.score }

                val otherHits = cleanedHits
                    .filterNot { it.type == MemoryType.PROJECT_INFO }
                    .sortedByDescending { it.score }

                projectInfoHits + otherHits
            }

            else -> {
                cleanedHits.sortedByDescending { it.score }
            }
        }

        val hasPositiveScores = rankedHits.any { it.score > 0.0 }
        val effectiveMinScore = if (hasPositiveScores) maxOf(minScore, 0.15) else 0.0

        val finalHits = when {
            scope == MemoryScope.PROJECT -> {
                val filePreferred = rankedHits.filter(::matchesMentionedFile)
                val contextualPreferred = rankedHits
                    .filterNot(::matchesMentionedFile)
                    .filter {
                        it.type == MemoryType.PROJECT_INFO ||
                                it.type == MemoryType.PROJECT_DECISION ||
                                it.type == MemoryType.PROJECT_NOTE
                    }

                val fallback = rankedHits
                    .filterNot { hit ->
                        filePreferred.any { it.id == hit.id } ||
                                contextualPreferred.any { it.id == hit.id }
                    }

                (filePreferred + contextualPreferred + fallback)
                    .distinctBy { it.id }
                    .take(topK)
            }

            architectureLike || fileReviewLike -> {
                rankedHits
                    .filter { hit ->
                        when (hit.type) {
                            MemoryType.PROJECT_INFO -> true
                            else -> hit.score >= effectiveMinScore
                        }
                    }
                    .take(topK)
            }

            else -> {
                rankedHits
                    .filter { it.score >= effectiveMinScore }
                    .take(topK)
            }
        }

        println(
            "MEM_SEARCH hits=${hits.size} filtered=${filteredHits.size} cleaned=${cleanedHits.size} ranked=${rankedHits.size} final=${finalHits.size} topFinal=" +
                    finalHits.take(5).joinToString { "${it.type}:${"%.3f".format(it.score)}:${it.text.take(60).replace("\n", " ")}" }
        )

        return finalHits.map {
            RetrievedMemory(
                text = it.text.trim().take(350),
                score = it.score,
                scope = it.scope,
                projectId = it.projectId,
                ts = it.ts
            )
        }
    }

    suspend fun maybeStoreTurn(
        tenantId: String,
        conversationId: String,
        scope: MemoryScope,
        projectId: String?,
        role: ChatMessage.Role,
        text: String
    ) {
        val decision = policy.decideStore(role, text)
        if (!decision.store) return

        val normalized = decision.normalizedText.ifBlank { policy.normalize(text) }
        val embedding = embed.embed(normalized)

        val id = when (decision.type) {
            MemoryType.FACT,
            MemoryType.PROJECT_INFO,
            MemoryType.PREFERENCE,
            MemoryType.PROJECT_NOTE,
            MemoryType.PROJECT_DECISION ->
                stableId(tenantId, scope, projectId, decision.type, normalized)

            else -> UUID.randomUUID().toString()
        }

        val item = MemoryItem(
            id = id,
            tenantId = tenantId,
            scope = scope,
            projectId = projectId,
            conversationId = conversationId,
            turnId = UUID.randomUUID().toString(),
            type = decision.type,
            ts = System.currentTimeMillis(),
            text = normalized
        )

        store.upsert(item, embedding)
    }

    suspend fun storeIndexText(
        tenantId: String,
        conversationId: String,
        scope: MemoryScope,
        projectId: String?,
        type: MemoryType = MemoryType.PROJECT_INFO,
        text: String
    ): Boolean {
        return runCatching {
            val normalized = policy.normalize(text)
            val embedding = embed.embed(normalized)

            val id = stableId(
                tenantId = tenantId,
                scope = scope,
                projectId = projectId,
                type = type,
                text = normalized
            )

            val item = MemoryItem(
                id = id,
                tenantId = tenantId,
                scope = scope,
                projectId = projectId,
                conversationId = conversationId,
                turnId = UUID.randomUUID().toString(),
                type = type,
                ts = System.currentTimeMillis(),
                text = normalized
            )

            store.upsert(item, embedding)
            true
        }.getOrElse { e ->
            println("INDEX_UPSERT_FAILED: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    suspend fun replaceProjectFileIndex(
        tenantId: String,
        projectId: String,
        relativePath: String,
        chunks: List<String>
    ): Boolean {
        // TODO: Später alte Chunks dieser Datei gezielt löschen und neu speichern.
        // Übergangsweise nur append/store.
        var storedAny = false

        for (chunk in chunks) {
            val ok = storeIndexText(
                tenantId = tenantId,
                conversationId = "project-index:$projectId",
                scope = MemoryScope.PROJECT,
                projectId = projectId,
                type = MemoryType.PROJECT_INFO,
                text = chunk
            )
            if (ok) storedAny = true
        }

        return storedAny
    }

    suspend fun storeProjectNote(
        tenantId: String,
        conversationId: String,
        projectId: String,
        text: String
    ): Boolean {
        return storeProjectMemoryItem(
            tenantId = tenantId,
            conversationId = conversationId,
            projectId = projectId,
            type = MemoryType.PROJECT_NOTE,
            text = text
        )
    }

    suspend fun storeProjectDecision(
        tenantId: String,
        conversationId: String,
        projectId: String,
        text: String
    ): Boolean {
        return storeProjectMemoryItem(
            tenantId = tenantId,
            conversationId = conversationId,
            projectId = projectId,
            type = MemoryType.PROJECT_DECISION,
            text = text
        )
    }

    private suspend fun storeProjectMemoryItem(
        tenantId: String,
        conversationId: String,
        projectId: String,
        type: MemoryType,
        text: String
    ): Boolean {
        if (text.isBlank()) return false

        return runCatching {
            val normalized = policy.normalize(text)
            val embedding = embed.embed(normalized)

            val id = stableId(
                tenantId = tenantId,
                scope = MemoryScope.PROJECT,
                projectId = projectId,
                type = type,
                text = normalized
            )

            val item = MemoryItem(
                id = id,
                tenantId = tenantId,
                scope = MemoryScope.PROJECT,
                projectId = projectId,
                conversationId = conversationId,
                turnId = UUID.randomUUID().toString(),
                type = type,
                ts = System.currentTimeMillis(),
                text = normalized
            )

            store.upsert(item, embedding)
            true
        }.getOrElse { e ->
            println("PROJECT_MEMORY_UPSERT_FAILED: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    private fun stableId(
        tenantId: String,
        scope: MemoryScope,
        projectId: String?,
        type: MemoryType,
        text: String
    ): String {
        val key = "tenant=$tenantId|scope=${scope.name}|pid=${projectId ?: "-"}|type=${type.name}|text=${text.lowercase()}"
        return UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    suspend fun deleteConversationMemory(
        tenantId: String,
        conversationId: String
    ): Boolean {
        return store.deleteConversationMemory(tenantId, conversationId)
    }

    suspend fun deleteProjectMemory(
        tenantId: String,
        projectId: String
    ): Boolean {
        return store.deleteProjectMemory(tenantId, projectId)
    }

    suspend fun deleteTenantMemory(
        tenantId: String
    ): Boolean {
        return store.deleteTenantMemory(tenantId)
    }
}
