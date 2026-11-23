package app.nocturne.player.data.online

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object JsonPathExtractor {

    fun extractElement(root: JsonElement?, pathSpec: String?): JsonElement? {
        if (root == null || pathSpec.isNullOrBlank()) return null
        val alternatives = pathSpec.split('|')
        for (alt in alternatives) {
            val clean = alt.trim()
            if (clean.isEmpty()) continue
            val found = if (clean == "$" || clean == ".") {
                root
            } else {
                traverseSinglePath(root, clean)
            }
            if (found != null && found !is JsonNull) {
                if (found is JsonPrimitive && found.contentOrNull.isNullOrBlank()) continue
                if (found is JsonArray && found.isEmpty()) continue
                return found
            }
        }
        return null
    }

    fun extractString(root: JsonElement?, pathSpec: String?): String? {
        val el = extractElement(root, pathSpec) ?: return null
        return elementToString(el)
    }

    fun extractLong(root: JsonElement?, pathSpec: String?): Long? {
        val el = extractElement(root, pathSpec) ?: return null
        return when (el) {
            is JsonPrimitive -> el.longOrNull ?: el.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
            is JsonArray -> (el.firstOrNull() as? JsonPrimitive)?.let { p ->
                p.longOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
            }
            else -> null
        }
    }

    fun extractInt(root: JsonElement?, pathSpec: String?): Int? {
        val el = extractElement(root, pathSpec) ?: return null
        return when (el) {
            is JsonPrimitive -> el.intOrNull ?: el.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    fun extractBoolean(root: JsonElement?, pathSpec: String?): Boolean? {
        val el = extractElement(root, pathSpec) ?: return null
        return (el as? JsonPrimitive)?.booleanOrNull
            ?: (el as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.let {
                when (it) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
            }
    }

    fun extractArray(root: JsonElement?, pathSpec: String?): List<JsonElement> {
        if (root == null) return emptyList()
        if (pathSpec.isNullOrBlank()) {
            return (root as? JsonArray)?.toList().orEmpty()
        }
        val el = extractElement(root, pathSpec) ?: return emptyList()
        return when (el) {
            is JsonArray -> el.toList()
            is JsonObject -> listOf(el)
            else -> emptyList()
        }
    }

    fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun elementToString(el: JsonElement): String? {
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            is JsonArray -> {
                val parts = el.mapNotNull { elementToString(it) }.distinct()
                parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
            }
            is JsonObject -> {
                (el["name"] as? JsonPrimitive)?.contentOrNull
                    ?: (el["title"] as? JsonPrimitive)?.contentOrNull
                    ?: (el["url"] as? JsonPrimitive)?.contentOrNull
                    ?: (el["text"] as? JsonPrimitive)?.contentOrNull
                    ?: (el["value"] as? JsonPrimitive)?.contentOrNull
            }
        }
    }

    private fun traverseSinglePath(root: JsonElement, rawPath: String): JsonElement? {
        val tokens = tokenizePath(rawPath.removePrefix("$."))
        return step(root, tokens, 0)
    }

    private fun step(current: JsonElement, tokens: List<PathToken>, idx: Int): JsonElement? {
        if (idx >= tokens.size) return current
        return when (val token = tokens[idx]) {
            is PathToken.Key -> {
                val obj = current as? JsonObject ?: return null
                val next = obj[token.name] ?: return null
                step(next, tokens, idx + 1)
            }
            is PathToken.Index -> {
                val arr = current as? JsonArray ?: return null
                if (arr.isEmpty()) return null
                val resolvedIdx = if (token.index < 0) arr.size + token.index else token.index
                if (resolvedIdx !in arr.indices) return null
                step(arr[resolvedIdx], tokens, idx + 1)
            }
            PathToken.Wildcard -> {
                val arr = current as? JsonArray ?: return null
                if (arr.isEmpty()) return null
                val mapped = arr.mapNotNull { child ->
                    step(child, tokens, idx + 1)
                }
                if (mapped.isEmpty()) null else JsonArray(mapped)
            }
        }
    }

    private sealed interface PathToken {
        data class Key(val name: String) : PathToken
        data class Index(val index: Int) : PathToken
        data object Wildcard : PathToken
    }

    private fun tokenizePath(path: String): List<PathToken> {
        val tokens = mutableListOf<PathToken>()
        val segments = path.split('.')
        for (rawSeg in segments) {
            if (rawSeg.isEmpty()) continue
            var cursor = 0
            val bracketIdx = rawSeg.indexOf('[')
            if (bracketIdx < 0) {
                tokens += PathToken.Key(rawSeg)
            } else {
                if (bracketIdx > 0) {
                    tokens += PathToken.Key(rawSeg.substring(0, bracketIdx))
                }
                cursor = bracketIdx
                while (cursor < rawSeg.length) {
                    val start = rawSeg.indexOf('[', cursor)
                    if (start < 0) break
                    val end = rawSeg.indexOf(']', start + 1)
                    if (end < 0) break
                    val inner = rawSeg.substring(start + 1, end).trim()
                    when (inner) {
                        "*", "all" -> tokens += PathToken.Wildcard
                        "last" -> tokens += PathToken.Index(-1)
                        else -> inner.toIntOrNull()?.let { tokens += PathToken.Index(it) }
                    }
                    cursor = end + 1
                }
            }
        }
        return tokens
    }
}
