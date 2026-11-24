package app.nocturne.player.data.audio.lyrics

import android.net.Uri
import app.nocturne.player.data.audio.TagAndLyricsEngine
import app.nocturne.player.data.model.DynamicLyricsProviderConfig
import app.nocturne.player.data.model.LrcLine
import app.nocturne.player.data.model.LrcWord
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.online.OnlineHttpClient
import app.nocturne.player.data.online.ProviderHealthTracker
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

/**
 * Priority 1 lyrics adapter querying LiriQo / multi-source proxy instances configured in remote config.json.
 * Supports line-synced (.lrc), word/syllable-synced JSON payloads, and plain lyrics fallback.
 */
class LiriQoLyricsAdapter(
    private val configProvider: () -> DynamicLyricsProviderConfig?,
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val healthTracker: ProviderHealthTracker = LyricsHttpUtil.defaultHealthTracker
) : LyricsProvider {

    override val source: LyricsSource = LyricsSource.LIRIQO
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchLyrics(query: LyricsQuery): ResolvedLyricsPayload? = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        if (cfg != null && !cfg.enabled) return@withContext null

        val endpoints = resolveEndpoints(cfg)
        if (endpoints.isEmpty()) return@withContext null

        val providerId = cfg?.id ?: "liriqo"
        val orderedEndpoints = LyricsHttpUtil.selectOrderedEndpoints(
            healthTracker = healthTracker,
            providerId = providerId,
            endpoints = endpoints,
            endpointFailover = cfg?.endpointFailover ?: true
        )

        val timeoutMs = cfg?.timeoutMs ?: 8_000L
        val maxRetries = cfg?.maxRetries ?: 1
        val cooldownMs = cfg?.cooldownMs ?: 60_000L
        val timedClient = LyricsHttpUtil.clientWithTimeout(client, timeoutMs)
        val mergedHeaders = buildMap {
            put("User-Agent", cfg?.userAgent ?: "NocturnePlayer/2.0")
            putAll(InputAndUrlSecurity.sanitizeRemoteHeaderMap(cfg?.headers ?: emptyMap()))
        }
        val durationSec = (query.durationMs / 1000L).takeIf { it > 0L }

        for (baseUrl in orderedEndpoints) {
            val cleanBase = baseUrl.trimEnd('/')
            val querySuffix = buildString {
                append("title=${Uri.encode(query.cleanedTitle)}")
                if (query.cleanedArtist.isNotBlank()) {
                    append("&artist=${Uri.encode(query.cleanedArtist)}")
                }
                if (!query.album.isNullOrBlank()) {
                    append("&album=${Uri.encode(query.album)}")
                }
                if (durationSec != null) {
                    append("&duration=$durationSec")
                }
                cfg?.params?.forEach { (k, v) ->
                    append("&${Uri.encode(k)}=${Uri.encode(v)}")
                }
            }

            val candidateUrls = if (cleanBase.contains("/lyrics", ignoreCase = true)) {
                listOf("$cleanBase?$querySuffix")
            } else {
                listOf("$cleanBase/lyrics?$querySuffix", "$cleanBase/api/lyrics?$querySuffix")
            }

            for (candidateUrl in candidateUrls) {
                val res = LyricsHttpUtil.executeJsonGetWithHealth(
                    healthTracker = healthTracker,
                    providerId = providerId,
                    baseEndpoint = cleanBase,
                    fullUrl = candidateUrl,
                    timedClient = timedClient,
                    headers = mergedHeaders,
                    maxRetries = maxRetries,
                    cooldownMs = cooldownMs,
                    maxBytes = 1024L * 1024L,
                    allowLoopback = baseUrlsOverride != null
                )
                if (res is LyricsHttpUtil.EndpointFetchResult.EndpointFailure) {
                    // Fail over to next healthy base_url after cooldown was recorded
                    break
                }
                if (res is LyricsHttpUtil.EndpointFetchResult.Success) {
                    val parsed = parseResponse(res.body)
                    if (parsed != null && parsed.hasContent()) {
                        return@withContext parsed
                    }
                }
            }
        }
        null
    }

    private fun resolveEndpoints(cfg: DynamicLyricsProviderConfig?): List<String> {
        if (baseUrlsOverride != null) {
            return baseUrlsOverride.mapNotNull {
                InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = true)
            }
        }
        return cfg?.resolvedEndpoints().orEmpty().mapNotNull {
            InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = false)
        }
    }

    private fun parseResponse(body: String): ResolvedLyricsPayload? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val obj = when (root) {
            is JsonObject -> (root["data"] as? JsonObject) ?: root
            else -> return null
        }

        val instrumental = obj.boolField("instrumental") ?: false

        // 1. Word/syllable structured array (`lines`, `richsync`, `wordSynced`, `syllables`)
        val arrayLines = parseStructuredLines(
            (obj["lines"] ?: obj["wordSynced"] ?: obj["synced"] as? JsonArray ?: obj["richsync"]) as? JsonArray
        )
        if (arrayLines.isNotEmpty()) {
            val serialized = LyricsHttpUtil.serializeToEnhancedLrc(arrayLines)
            return ResolvedLyricsPayload(
                source = LyricsSource.LIRIQO,
                syncedLrc = serialized,
                plainLyrics = arrayLines.joinToString("\n") { it.text },
                parsedLines = arrayLines,
                isInstrumental = false
            )
        }

        // 2. .lrc / syncedLyrics / lyrics string
        val lrcRaw = obj.strField("lrc", "syncedLyrics", "synced", "syllableLrc", "subtitles", "lyrics")
        if (!lrcRaw.isNullOrBlank()) {
            val parsed = TagAndLyricsEngine.parseLrc(lrcRaw)
            if (parsed.isNotEmpty()) {
                return ResolvedLyricsPayload(
                    source = LyricsSource.LIRIQO,
                    syncedLrc = lrcRaw,
                    plainLyrics = parsed.joinToString("\n") { it.text },
                    parsedLines = parsed,
                    isInstrumental = false
                )
            }
        }

        // 3. Plain / unsynced lyrics
        val plainRaw = obj.strField("plainLyrics", "plain", "unsynced", "lyrics", "text")
        if (!plainRaw.isNullOrBlank()) {
            return ResolvedLyricsPayload(
                source = LyricsSource.LIRIQO,
                plainLyrics = plainRaw.trim(),
                isInstrumental = false
            )
        }

        if (instrumental) {
            return ResolvedLyricsPayload(source = LyricsSource.LIRIQO, isInstrumental = true)
        }
        return null
    }

    private fun parseStructuredLines(arr: JsonArray?): List<LrcLine> {
        if (arr == null || arr.isEmpty()) return emptyList()
        val raw = arr.mapNotNull { el ->
            val lineObj = el as? JsonObject ?: return@mapNotNull null
            val startMs = parseTimeMs(lineObj["startTimeMs"] ?: lineObj["timeMs"] ?: lineObj["start"] ?: lineObj["time"])
                ?: return@mapNotNull null
            val explicitEndMs = parseTimeMs(lineObj["endTimeMs"] ?: lineObj["end"])
            val wordsArr = (lineObj["words"] ?: lineObj["syllables"]) as? JsonArray
            val parsedWords = wordsArr?.mapNotNull { wEl ->
                val wObj = wEl as? JsonObject ?: return@mapNotNull null
                val wText = wObj.strField("text", "word", "chars", "c")?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val wStart = parseTimeMs(wObj["startTimeMs"] ?: wObj["timeMs"] ?: wObj["start"] ?: wObj["o"]) ?: startMs
                val wEnd = parseTimeMs(wObj["endTimeMs"] ?: wObj["end"]) ?: (wStart + 350L)
                LrcWord(startTimeMs = wStart, endTimeMs = wEnd.coerceAtLeast(wStart + 80L), text = wText)
            }.orEmpty()

            val lineText = lineObj.strField("text", "words", "line")?.trim()
                ?: parsedWords.joinToString(" ") { it.text }.trim()
            if (lineText.isBlank() && parsedWords.isEmpty()) return@mapNotNull null

            LrcLine(
                timeMs = startMs,
                text = lineText.ifBlank { "♪" },
                endTimeMs = explicitEndMs ?: (startMs + 3500L),
                words = parsedWords,
                isExplicitWordSync = parsedWords.isNotEmpty()
            )
        }.sortedBy { it.timeMs }

        return raw.mapIndexed { idx, line ->
            val nextStart = raw.getOrNull(idx + 1)?.timeMs
            val resolvedEnd = if (line.endTimeMs > line.timeMs + 400L) {
                line.endTimeMs
            } else if (nextStart != null) {
                nextStart.coerceAtLeast(line.timeMs + 400L)
            } else {
                line.timeMs + 4000L
            }
            val finalWords = if (line.words.isNotEmpty()) {
                line.words
            } else {
                TagAndLyricsEngine.interpolateWords(line.timeMs, resolvedEnd, line.text)
            }
            line.copy(endTimeMs = resolvedEnd, words = finalWords)
        }
    }

    private fun parseTimeMs(el: JsonElement?): Long? {
        val prim = el as? JsonPrimitive ?: return null
        prim.longOrNull?.let { v ->
            return if (v < 10_000L && prim.contentOrNull?.contains('.') == true) (v * 1000L) else v
        }
        val dbl = prim.doubleOrNull ?: prim.contentOrNull?.toDoubleOrNull() ?: return null
        // Heuristic: integers >= 1000 without decimal or tagged TimeMs are ms; floats with decimals < 10000 are seconds
        return if (prim.contentOrNull?.contains('.') == true && dbl < 10_000.0) {
            (dbl * 1000.0).toLong()
        } else {
            dbl.toLong()
        }
    }

    private fun JsonObject.strField(vararg keys: String): String? {
        for (k in keys) {
            val v = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!v.isNullOrBlank() && v != "null") return v
        }
        return null
    }

    private fun JsonObject.boolField(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
