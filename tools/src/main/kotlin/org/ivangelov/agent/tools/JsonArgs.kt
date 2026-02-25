package org.ivangelov.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull
