package ru.criptobot.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun toJsonString(obj: Any?): String = when (obj) {
    null -> "null"
    is String -> json.encodeToString(obj)
    is Map<*, *> -> json.encodeToString(obj.mapKeys { it.key.toString() }.mapValues { it.value.toJsonElement() })
    is Number -> obj.toString()
    is Boolean -> obj.toString()
    else -> json.encodeToString(obj.toString())
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject {
        for ((k, v) in this@toJsonElement) {
            if (k != null) put(k.toString(), v.toJsonElement())
        }
    }
    is Iterable<*> -> kotlinx.serialization.json.buildJsonArray {
        for (item in this@toJsonElement) add(item.toJsonElement())
    }
    else -> JsonPrimitive(toString())
}
