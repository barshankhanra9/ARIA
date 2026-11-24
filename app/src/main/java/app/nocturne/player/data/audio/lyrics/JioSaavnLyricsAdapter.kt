package app.nocturne.player.data.audio.lyrics

import android.net.Uri
import app.nocturne.player.data.model.DynamicLyricsProviderConfig
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.online.JsonPathExtractor
import app.nocturne.player.data.online.OnlineHttpClient
import app.nocturne.player.data.online.ProviderHealthTracker
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient

/**
 * Priority 3 lyrics adapter fetching built-in lyrics from JioSaavn endpoints whenever a
 * JioSaavn track is playing and the `lyrics` capability is enabled for JioSaavn.
 */
class JioSaavnLyricsAdapter(
    private val configProvider: () -> DynamicLyricsProviderConfig?,
    private val jioSaavnEndpointsProvider: () -> List<String>,
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val healthTracker: ProviderHealthTracker = LyricsHttpUtil.defaultHealthTracker
) : LyricsProvider {

    override val source: LyricsSource = LyricsSource.JIOSAAVN
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchLyrics(query: LyricsQuery): ResolvedLyricsPayload? = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        if (baseUrlsOverride == null) {
            if (cfg == null || !cfg.enabled || !cfg.supportsCapability("lyrics")) return@withContext null
        } else if (cfg != null && (!cfg.enabled || !cfg.supportsCapability("lyrics"))) {
            return@withContext null
        }

        val isSaavnTrack = query.isOnline && (
            query.onlineProvider.contains("jiosaavn", ignoreCase = true) ||
                query.onlineProvider.contains("saavn", ignoreCase = true)
            )
        if (!isSaavnTrack || query.sourceId.isBlank()) return@withContext null

        val safeSourceId = InputAndUrlSecurity.sanitizePathSegment(query.sourceId) ?: return@withContext null
        val endpoints = resolveEndpoints(cfg)
        if (endpoints.isEmpty()) return@withContext null

        val providerId = cfg?.id ?: "jiosaavn_lyrics"
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
        val headers = InputAndUrlSecurity.sanitizeRemoteHeaderMap(cfg?.headers ?: emptyMap())
        val encodedId = Uri.encode(safeSourceId)

        for (baseUrl in orderedEndpoints) {
            val cleanBase = baseUrl.trimEnd('/')
            val candidateUrls = listOf(
                "$cleanBase/songs/$encodedId/lyrics",
                "$cleanBase/lyrics?id=$encodedId"
            )
            for (url in candidateUrls) {
                val res = LyricsHttpUtil.executeJsonGetWithHealth(
                    healthTracker = healthTracker,
                    providerId = providerId,
                    baseEndpoint = cleanBase,
                    fullUrl = url,
                    timedClient = timedClient,
                    headers = headers,
                    maxRetries = maxRetries,
                    cooldownMs = cooldownMs,
                    maxBytes = 512L * 1024L,
                    allowLoopback = baseUrlsOverride != null
                )
                if (res is LyricsHttpUtil.EndpointFetchResult.EndpointFailure) {
                    break
                }
                if (res is LyricsHttpUtil.EndpointFetchResult.Success) {
                    val lyricsText = extractLyricsText(res.body)
                    if (!lyricsText.isNullOrBlank()) {
                        return@withContext ResolvedLyricsPayload(
                            source = LyricsSource.JIOSAAVN,
                            plainLyrics = lyricsText
                        )
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
        val explicit = cfg?.resolvedEndpoints().orEmpty()
        val combined = if (explicit.isNotEmpty()) explicit else jioSaavnEndpointsProvider()
        return combined.mapNotNull { InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = false) }
    }

    private fun extractLyricsText(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val dataObj = when (val data = root["data"]) {
            is JsonObject -> data
            is JsonArray -> data.firstOrNull() as? JsonObject
            else -> root
        } ?: root
        val raw = (dataObj["lyrics"] as? JsonPrimitive)?.contentOrNull
            ?: (root["lyrics"] as? JsonPrimitive)?.contentOrNull
            ?: return null
        val normalized = raw
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .trim()
        val decoded = JsonPathExtractor.decodeHtmlEntities(normalized).trim()
        return decoded.takeIf { it.isNotBlank() && it != "null" }
    }
}
