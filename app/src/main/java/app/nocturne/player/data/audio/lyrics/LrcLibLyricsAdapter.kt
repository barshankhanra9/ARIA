package app.nocturne.player.data.audio.lyrics

import android.net.Uri
import app.nocturne.player.data.audio.TagAndLyricsEngine
import app.nocturne.player.data.model.DynamicLyricsProviderConfig
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.online.OnlineHttpClient
import app.nocturne.player.data.online.ProviderHealthTracker
import app.nocturne.player.data.online.bool
import app.nocturne.player.data.online.long
import app.nocturne.player.data.online.parseArrayOrNull
import app.nocturne.player.data.online.str
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import kotlin.math.abs

/**
 * Priority 4 lyrics adapter querying LRCLIB directly via `/api/search?q=<cleaned_title>+<cleaned_artist>`
 * and picking the closest duration match within +/- 3 seconds.
 */
class LrcLibLyricsAdapter(
    private val endpointsProvider: () -> List<String>,
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val allowLoopback: Boolean = false,
    private val configProvider: (() -> DynamicLyricsProviderConfig?)? = null,
    private val healthTracker: ProviderHealthTracker = LyricsHttpUtil.defaultHealthTracker
) : LyricsProvider {

    override val source: LyricsSource = LyricsSource.LRCLIB
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchLyrics(query: LyricsQuery): ResolvedLyricsPayload? = withContext(Dispatchers.IO) {
        val cfg = configProvider?.invoke()
        if (cfg != null && !cfg.enabled) return@withContext null

        val endpoints = endpointsProvider().mapNotNull {
            InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = allowLoopback)
        }
        if (endpoints.isEmpty()) return@withContext null

        val searchTerms = listOf(query.cleanedTitle, query.cleanedArtist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (searchTerms.isBlank()) return@withContext null

        val providerId = cfg?.id ?: "lrclib"
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
        val headers = buildMap {
            put("User-Agent", cfg?.userAgent?.takeIf { it.isNotBlank() } ?: LRCLIB_USER_AGENT)
            putAll(InputAndUrlSecurity.sanitizeRemoteHeaderMap(cfg?.headers ?: emptyMap()))
        }
        val durationSec = (query.durationMs / 1000L).takeIf { it > 0L }

        for (baseUrl in orderedEndpoints) {
            val cleanBase = baseUrl.trimEnd('/')
            val searchUrl = "$cleanBase/api/search?q=${Uri.encode(searchTerms)}"
            val res = LyricsHttpUtil.executeJsonGetWithHealth(
                healthTracker = healthTracker,
                providerId = providerId,
                baseEndpoint = cleanBase,
                fullUrl = searchUrl,
                timedClient = timedClient,
                headers = headers,
                maxRetries = maxRetries,
                cooldownMs = cooldownMs,
                maxBytes = 1024L * 1024L,
                allowLoopback = allowLoopback
            )
            val body = (res as? LyricsHttpUtil.EndpointFetchResult.Success)?.body ?: continue
            val best = runCatching {
                val arr = json.parseArrayOrNull(body) ?: return@runCatching null
                pickBestMatch(arr, query.cleanedTitle, query.cleanedArtist, durationSec)
            }.getOrNull()

            if (best != null && best.hasLyrics()) {
                val syncedText = best.syncedLyrics.orEmpty()
                val plainText = best.plainLyrics.orEmpty()
                val parsed = if (syncedText.isNotBlank()) TagAndLyricsEngine.parseLrc(syncedText) else emptyList()
                return@withContext ResolvedLyricsPayload(
                    source = LyricsSource.LRCLIB,
                    syncedLrc = syncedText.takeIf { it.isNotBlank() },
                    plainLyrics = plainText.takeIf { it.isNotBlank() },
                    parsedLines = parsed,
                    isInstrumental = best.instrumental
                )
            }
        }
        null
    }

    private data class LrclibRecord(
        val trackName: String,
        val artistName: String,
        val durationSec: Long,
        val instrumental: Boolean,
        val plainLyrics: String?,
        val syncedLyrics: String?
    ) {
        fun hasLyrics(): Boolean = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank() || instrumental
    }

    private fun parseRecord(obj: JsonObject): LrclibRecord? {
        val synced = obj.str("syncedLyrics")?.takeIf { it.isNotBlank() && it != "null" }
        val plain = obj.str("plainLyrics")?.takeIf { it.isNotBlank() && it != "null" }
        val instrumental = obj.bool("instrumental") ?: false
        if (synced.isNullOrBlank() && plain.isNullOrBlank() && !instrumental) return null
        return LrclibRecord(
            trackName = obj.str("trackName") ?: obj.str("name").orEmpty(),
            artistName = obj.str("artistName").orEmpty(),
            durationSec = obj.long("duration") ?: 0L,
            instrumental = instrumental,
            plainLyrics = plain,
            syncedLyrics = synced
        )
    }

    private fun pickBestMatch(
        arr: JsonArray,
        targetTitle: String,
        targetArtist: String,
        durationSec: Long?
    ): LrclibRecord? {
        val records = arr.mapNotNull { el -> (el as? JsonObject)?.let(::parseRecord) }
        if (records.isEmpty()) return null

        val normTitle = normalizeToken(targetTitle)
        val normArtist = normalizeToken(targetArtist)

        val withinTolerance = if (durationSec != null && durationSec > 0L) {
            records.filter { rec ->
                rec.durationSec <= 0L || abs(durationSec - rec.durationSec) <= DURATION_TOLERANCE_SEC
            }
        } else {
            records
        }
        val pool = withinTolerance.ifEmpty { records }

        return pool.maxByOrNull { rec ->
            var score = 0
            if (!rec.syncedLyrics.isNullOrBlank()) score += 50
            else if (!rec.plainLyrics.isNullOrBlank()) score += 20

            val recTitle = normalizeToken(rec.trackName)
            val recArtist = normalizeToken(rec.artistName)
            if (recTitle == normTitle) score += 30
            else if (recTitle.contains(normTitle) || normTitle.contains(recTitle)) score += 15

            if (normArtist.isNotBlank() && (recArtist == normArtist || recArtist.contains(normArtist) || normArtist.contains(recArtist))) {
                score += 20
            }

            if (durationSec != null && durationSec > 0L && rec.durationSec > 0L) {
                val diff = abs(durationSec - rec.durationSec)
                if (diff <= DURATION_TOLERANCE_SEC) {
                    score += (35 - diff.toInt() * 5)
                } else if (diff <= 10L) {
                    score += 8
                } else if (diff > 30L) {
                    score -= 30
                }
            }
            score
        }
    }

    private fun normalizeToken(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9]"), "")

    companion object {
        const val LRCLIB_USER_AGENT = "ARIA-Player/2.0.0 (https://github.com/nocturne-player/aria)"
        const val DURATION_TOLERANCE_SEC = 3L
    }
}
