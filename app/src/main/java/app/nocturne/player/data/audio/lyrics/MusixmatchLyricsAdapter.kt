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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient

/**
 * Priority 2 lyrics adapter querying Musixmatch desktop API with remote User-Agent, Cookie,
 * app_id from config.json, and automatic free guest token retrieval + caching.
 */
class MusixmatchLyricsAdapter(
    private val configProvider: () -> DynamicLyricsProviderConfig?,
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val healthTracker: ProviderHealthTracker = LyricsHttpUtil.defaultHealthTracker
) : LyricsProvider {

    override val source: LyricsSource = LyricsSource.MUSIXMATCH
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedTokenAtMs: Long = 0L

    override suspend fun fetchLyrics(query: LyricsQuery): ResolvedLyricsPayload? = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        if (cfg != null && !cfg.enabled) return@withContext null

        val endpoints = resolveEndpoints(cfg)
        if (endpoints.isEmpty()) return@withContext null

        val providerId = cfg?.id ?: "musixmatch"
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
        val appId = cfg?.appId?.takeIf { it.isNotBlank() }
            ?: cfg?.params?.get("app_id")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_APP_ID
        val userAgent = cfg?.userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
        val cookie = cfg?.cookie?.takeIf { it.isNotBlank() }
        val extraHeaders = InputAndUrlSecurity.sanitizeRemoteHeaderMap(cfg?.headers ?: emptyMap())
        val durationSec = (query.durationMs / 1000L).takeIf { it > 0L }

        for (baseUrl in orderedEndpoints) {
            val cleanBase = baseUrl.trimEnd('/')
            var token = getOrFetchToken(
                providerId = providerId,
                cleanBase = cleanBase,
                timedClient = timedClient,
                appId = appId,
                userAgent = userAgent,
                cookie = cookie,
                extraHeaders = extraHeaders,
                maxRetries = maxRetries,
                cooldownMs = cooldownMs,
                forceRefresh = false
            ) ?: continue

            for (attempt in 0..1) {
                val macroUrl = buildString {
                    append("$cleanBase/ws/1.1/macro.subtitles.get")
                    append("?format=json")
                    append("&namespace=lyrics_richsynched")
                    append("&subtitle_format=lrc")
                    append("&q_track=${Uri.encode(query.cleanedTitle)}")
                    if (query.cleanedArtist.isNotBlank()) {
                        append("&q_artist=${Uri.encode(query.cleanedArtist)}")
                    }
                    if (!query.album.isNullOrBlank()) {
                        append("&q_album=${Uri.encode(query.album)}")
                    }
                    if (durationSec != null) {
                        append("&f_subtitle_length=$durationSec")
                        append("&f_subtitle_length_max_deviation=5")
                    }
                    append("&usertoken=${Uri.encode(token)}")
                    append("&app_id=${Uri.encode(appId)}")
                }

                val body = executeMusixmatchGet(
                    providerId = providerId,
                    cleanBase = cleanBase,
                    rawUrl = macroUrl,
                    timedClient = timedClient,
                    userAgent = userAgent,
                    cookie = cookie,
                    extraHeaders = extraHeaders,
                    maxRetries = maxRetries,
                    cooldownMs = cooldownMs
                ) ?: break
                val statusCode = extractStatusCode(body)
                if (statusCode in setOf(401, 402, 403) && attempt == 0) {
                    token = getOrFetchToken(
                        providerId = providerId,
                        cleanBase = cleanBase,
                        timedClient = timedClient,
                        appId = appId,
                        userAgent = userAgent,
                        cookie = cookie,
                        extraHeaders = extraHeaders,
                        maxRetries = maxRetries,
                        cooldownMs = cooldownMs,
                        forceRefresh = true
                    ) ?: break
                    continue
                }
                if (statusCode != null && statusCode !in 200..299) {
                    break
                }

                val parsed = parseMacroResponse(body)
                if (parsed != null && parsed.hasContent()) {
                    return@withContext parsed
                }
                break
            }
        }
        null
    }

    private suspend fun getOrFetchToken(
        providerId: String,
        cleanBase: String,
        timedClient: OkHttpClient,
        appId: String,
        userAgent: String,
        cookie: String?,
        extraHeaders: Map<String, String>,
        maxRetries: Int,
        cooldownMs: Long,
        forceRefresh: Boolean
    ): String? = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        val current = cachedToken
        if (!forceRefresh && !current.isNullOrBlank() && (now - cachedTokenAtMs) < TOKEN_TTL_MS) {
            return@withLock current
        }

        val tokenUrl = "$cleanBase/ws/1.1/token.get?app_id=${Uri.encode(appId)}&format=json"
        val body = executeMusixmatchGet(
            providerId = providerId,
            cleanBase = cleanBase,
            rawUrl = tokenUrl,
            timedClient = timedClient,
            userAgent = userAgent,
            cookie = cookie,
            extraHeaders = extraHeaders,
            maxRetries = maxRetries,
            cooldownMs = cooldownMs
        ) ?: return@withLock null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return@withLock null
        val msg = root["message"] as? JsonObject ?: return@withLock null
        val userToken = ((msg["body"] as? JsonObject)?.get("user_token") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" && !it.startsWith("UpgradeOnly") }
            ?: return@withLock null

        cachedToken = userToken
        cachedTokenAtMs = now
        userToken
    }

    private fun executeMusixmatchGet(
        providerId: String,
        cleanBase: String,
        rawUrl: String,
        timedClient: OkHttpClient,
        userAgent: String,
        cookie: String?,
        extraHeaders: Map<String, String>,
        maxRetries: Int,
        cooldownMs: Long
    ): String? {
        val headers = buildMap {
            putAll(extraHeaders)
            put("User-Agent", userAgent)
            put("Authority", "apic-desktop.musixmatch.com")
            put("Origin", "https://apic-desktop.musixmatch.com")
        }
        val res = LyricsHttpUtil.executeJsonGetWithHealth(
            healthTracker = healthTracker,
            providerId = providerId,
            baseEndpoint = cleanBase,
            fullUrl = rawUrl,
            timedClient = timedClient,
            headers = headers,
            cookie = cookie,
            maxRetries = maxRetries,
            cooldownMs = cooldownMs,
            maxBytes = 1024L * 1024L,
            allowLoopback = baseUrlsOverride != null
        )
        return (res as? LyricsHttpUtil.EndpointFetchResult.Success)?.body
    }

    private fun resolveEndpoints(cfg: DynamicLyricsProviderConfig?): List<String> {
        if (baseUrlsOverride != null) {
            return baseUrlsOverride.mapNotNull {
                InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = true)
            }
        }
        val configured = cfg?.resolvedEndpoints().orEmpty().mapNotNull {
            InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = false)
        }
        return if (configured.isNotEmpty()) configured else listOf(DEFAULT_MUSIXMATCH_BASE)
    }

    private fun extractStatusCode(body: String): Int? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val header = (root["message"] as? JsonObject)?.get("header") as? JsonObject ?: return null
        return (header["status_code"] as? JsonPrimitive)?.intOrNull
    }

    private fun parseMacroResponse(body: String): ResolvedLyricsPayload? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val msg = root["message"] as? JsonObject ?: return null
        val msgBody = msg["body"] as? JsonObject ?: return null
        val macroCalls = msgBody["macro_calls"] as? JsonObject

        // 1. Word-synced richsync
        val richsyncCall = (macroCalls?.get("track.richsync.get") as? JsonObject)
        val richsyncBodyStr = (((richsyncCall?.get("message") as? JsonObject)
            ?.get("body") as? JsonObject)
            ?.get("richsync") as? JsonObject)
            ?.let { (it["richsync_body"] as? JsonPrimitive)?.contentOrNull }
        if (!richsyncBodyStr.isNullOrBlank()) {
            val richLines = parseRichSyncBody(richsyncBodyStr)
            if (richLines.isNotEmpty()) {
                return ResolvedLyricsPayload(
                    source = LyricsSource.MUSIXMATCH,
                    syncedLrc = LyricsHttpUtil.serializeToEnhancedLrc(richLines),
                    plainLyrics = richLines.joinToString("\n") { it.text },
                    parsedLines = richLines
                )
            }
        }

        // 2. Line-synced subtitles (.lrc)
        val subtitlesCall = (macroCalls?.get("track.subtitles.get") as? JsonObject)
        val subtitleList = (((subtitlesCall?.get("message") as? JsonObject)
            ?.get("body") as? JsonObject)
            ?.get("subtitle_list") as? JsonArray)
        val subtitleBody = (((subtitleList?.firstOrNull() as? JsonObject)
            ?.get("subtitle") as? JsonObject)
            ?.get("subtitle_body") as? JsonPrimitive)?.contentOrNull
        if (!subtitleBody.isNullOrBlank()) {
            val parsedLines = TagAndLyricsEngine.parseLrc(subtitleBody)
            if (parsedLines.isNotEmpty()) {
                return ResolvedLyricsPayload(
                    source = LyricsSource.MUSIXMATCH,
                    syncedLrc = subtitleBody,
                    plainLyrics = parsedLines.joinToString("\n") { it.text },
                    parsedLines = parsedLines
                )
            }
        }

        // 3. Plain lyrics
        val lyricsCall = (macroCalls?.get("track.lyrics.get") as? JsonObject)
        val lyricsObj = (((lyricsCall?.get("message") as? JsonObject)
            ?.get("body") as? JsonObject)
            ?.get("lyrics") as? JsonObject)
        val plainBody = (lyricsObj?.get("lyrics_body") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" }
        val instrumental = ((lyricsObj?.get("instrumental") as? JsonPrimitive)?.intOrNull == 1)

        if (!plainBody.isNullOrBlank()) {
            return ResolvedLyricsPayload(
                source = LyricsSource.MUSIXMATCH,
                plainLyrics = plainBody.trim(),
                isInstrumental = false
            )
        }
        if (instrumental) {
            return ResolvedLyricsPayload(source = LyricsSource.MUSIXMATCH, isInstrumental = true)
        }
        return null
    }

    private fun parseRichSyncBody(richsyncBodyStr: String): List<LrcLine> {
        val arr = runCatching { json.parseToJsonElement(richsyncBodyStr) as? JsonArray }.getOrNull()
            ?: return emptyList()
        val raw = arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val tsSec = (obj["ts"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val teSec = (obj["te"] as? JsonPrimitive)?.doubleOrNull ?: (tsSec + 3.5)
            val startMs = (tsSec * 1000.0).toLong().coerceAtLeast(0L)
            val endMs = (teSec * 1000.0).toLong().coerceAtLeast(startMs + 400L)
            val fullLine = (obj["x"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            val syllables = (obj["l"] as? JsonArray).orEmpty()

            val words = mutableListOf<LrcWord>()
            val wordOffsets = syllables.mapNotNull { sEl ->
                val sObj = sEl as? JsonObject ?: return@mapNotNull null
                val chars = (sObj["c"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
                if (chars.isBlank()) return@mapNotNull null
                val offsetSec = (sObj["o"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                (startMs + (offsetSec * 1000.0).toLong()) to chars
            }
            wordOffsets.forEachIndexed { idx, (wStart, chars) ->
                val wEnd = wordOffsets.getOrNull(idx + 1)?.first?.coerceAtLeast(wStart + 80L)
                    ?: endMs.coerceAtLeast(wStart + 250L)
                words += LrcWord(startTimeMs = wStart, endTimeMs = wEnd, text = chars)
            }

            val resolvedText = fullLine.ifBlank { words.joinToString(" ") { it.text } }.trim()
            if (resolvedText.isBlank()) return@mapNotNull null
            LrcLine(
                timeMs = startMs,
                text = resolvedText,
                endTimeMs = endMs,
                words = words,
                isExplicitWordSync = words.isNotEmpty()
            )
        }.sortedBy { it.timeMs }
        return raw
    }

    companion object {
        const val DEFAULT_MUSIXMATCH_BASE = "https://apic-desktop.musixmatch.com"
        const val DEFAULT_APP_ID = "web-desktop-app-v1.0"
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val TOKEN_TTL_MS = 45 * 60_000L
    }
}
