package org.ivangelov.agent.orchestrator

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object ToolArgsNormalizer {

    /**
     * Canonical tool args:
     * - unwraps nested {"arguments":{...}} or {"args":{...}}
     * - applies aliases: filePath/file/filepath -> path, text/body/data -> content
     * - ensures result is a FLAT JsonObject
     */
    fun normalize(toolName: String, raw: JsonObject): JsonObject {
        // 1) unwrap common wrappers
        val unwrapped = unwrap(raw)

        // 2) mutable map of JsonElement (non-null values)
        val map: MutableMap<String, JsonElement> = unwrapped.toMutableMap()

        // 3) path aliases (only set when alias exists)
        if ("path" !in map) {
            (map.remove("filePath")
                ?: map.remove("filepath")
                ?: map.remove("file")
                    )?.let { map["path"] = it }
        }

        // 4) content aliases (write_file)
        if (toolName == "write_file" && "content" !in map) {
            (map.remove("text")
                ?: map.remove("data")
                ?: map.remove("body")
                    )?.let { map["content"] = it }
        }

        // 5) optional cleanup: remove blank string primitives
        map.entries.removeIf { (_, v) ->
            val p = v as? JsonPrimitive ?: return@removeIf false
            p.isString && p.content.isBlank()
        }

        // 6) rebuild immutable JsonObject
        return buildJsonObject {
            for ((k, v) in map) put(k, v)
        }
    }

    /**
     * Repeatedly unwrap:
     * - {"arguments":{...}}
     * - {"args":{...}}
     */
    private fun unwrap(obj: JsonObject): JsonObject {
        var cur: JsonObject = obj
        while (true) {
            val next: JsonObject? =
                (cur["arguments"] as? JsonElement)?.let { it as? JsonObject }
                    ?: (cur["args"] as? JsonElement)?.let { it as? JsonObject }

            if (next != null) cur = next else return cur
        }
    }
}