package org.ivangelov.agent.orchestrator.edit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object PatchBuilder {

    fun buildReplaceArgs(
        path: String,
        originalBlock: String,
        replacementBlock: String
    ): JsonObject {
        return buildJsonObject {
            put("path", JsonPrimitive(path))
            put("search", JsonPrimitive(originalBlock))
            put("replace", JsonPrimitive(replacementBlock))
        }
    }
}