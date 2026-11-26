package app.nocturne.player.data.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

/**
 * Safely extracts readable text from a JsonElement without throwing when an API
 * returns a nested JsonObject (e.g. YouTube/Invidious `{"runs":[{"text":"..."}]}`
 * or `{"simpleText":"..."}`) or JsonArray instead of a raw JsonPrimitive.
 */
internal fun JsonElement?.extractText(depth: Int = 0): String? {
    if (this == null || this is JsonNull || depth > 5) return null
    return when (this) {
        is JsonPrimitive -> contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

        is JsonObject -> {
            (this["simpleText"] as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: (this["text"] as? JsonPrimitive)?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: (this["runs"] as? JsonArray)?.let { runs ->
                    runs.mapNotNull { run ->
                        when (run) {
                            is JsonPrimitive -> run.contentOrNull?.takeIf { !it.equals("null", ignoreCase = true) }
                            is JsonObject -> (run["text"] as? JsonPrimitive)?.contentOrNull
                                ?.takeIf { !it.equals("null", ignoreCase = true) }
                                ?: run.extractText(depth + 1)
                            else -> run.extractText(depth + 1)
                        }
                    }
                        .joinToString("")
                        .trim()
                        .takeIf { it.isNotBlank() }
                }
                ?: this["name"]?.extractText(depth + 1)
                ?: this["title"]?.extractText(depth + 1)
                ?: this["label"]?.extractText(depth + 1)
                ?: this["value"]?.extractText(depth + 1)
                ?: this["content"]?.extractText(depth + 1)
                ?: this["description"]?.extractText(depth + 1)
        }

        is JsonArray -> {
            val parts = mapNotNull { it.extractText(depth + 1) }
            if (parts.isEmpty()) null else parts.joinToString(", ").takeIf { it.isNotBlank() }
        }
    }
}

/**
 * Safely extracts a URL string from either a JsonPrimitive, a nested JsonObject
 * (`{"url": "..."}`, `{"link": "..."}`, `{"src": "..."}`), or a JsonArray of objects/strings.
 */
internal fun JsonElement?.extractUrlString(depth: Int = 0): String? {
    if (this == null || this is JsonNull || depth > 4) return null
    return when (this) {
        is JsonPrimitive -> contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

        is JsonObject -> {
            this["url"].extractUrlString(depth + 1)
                ?: this["link"].extractUrlString(depth + 1)
                ?: this["src"].extractUrlString(depth + 1)
                ?: this["href"].extractUrlString(depth + 1)
        }

        is JsonArray -> lastOrNull()?.extractUrlString(depth + 1)
            ?: firstOrNull()?.extractUrlString(depth + 1)
    }
}

internal fun JsonElement?.extractLongSafe(depth: Int = 0): Long? {
    if (this == null || this is JsonNull || depth > 4) return null
    return when (this) {
        is JsonPrimitive -> longOrNull ?: contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
        is JsonObject -> {
            (this["value"]
                ?: this["count"]
                ?: this["seconds"]
                ?: this["duration"]
                ?: this["text"]
                ?: this["simpleText"]
                ?: this["runs"]).extractLongSafe(depth + 1)
        }
        is JsonArray -> firstOrNull()?.extractLongSafe(depth + 1)
    }
}

internal fun JsonElement?.extractIntSafe(): Int? = extractLongSafe()?.toInt()

internal fun JsonElement?.extractBooleanSafe(depth: Int = 0): Boolean? {
    if (this == null || this is JsonNull || depth > 4) return null
    return when (this) {
        is JsonPrimitive -> booleanOrNull ?: contentOrNull?.trim()?.lowercase()?.let {
            when (it) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
        }
        is JsonObject -> (this["value"] ?: this["enabled"]).extractBooleanSafe(depth + 1)
        is JsonArray -> firstOrNull()?.extractBooleanSafe(depth + 1)
    }
}

internal fun Json.parseElementOrNull(raw: String): JsonElement? =
    runCatching { parseToJsonElement(raw) }.getOrNull()

internal fun Json.parseObjectOrNull(raw: String): JsonObject? =
    parseElementOrNull(raw).asObjectOrNull()

internal fun Json.parseArrayOrNull(raw: String): JsonArray? =
    parseElementOrNull(raw).asArrayOrNull()

internal fun JsonObject.objArray(key: String): JsonArray? = this[key].asArrayOrNull()

internal fun JsonObject.objObject(key: String): JsonObject? = this[key].asObjectOrNull()

internal fun JsonObject.str(key: String): String? = this[key].extractText()

internal fun JsonObject.strUrl(key: String): String? = this[key].extractUrlString()

internal fun JsonObject.long(key: String): Long? = this[key].extractLongSafe()

internal fun JsonObject.int(key: String): Int? = this[key].extractIntSafe()

internal fun JsonObject.bool(key: String): Boolean? = this[key].extractBooleanSafe()
