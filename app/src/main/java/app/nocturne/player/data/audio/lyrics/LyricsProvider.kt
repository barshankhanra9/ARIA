package app.nocturne.player.data.audio.lyrics

import app.nocturne.player.data.model.LrcLine
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.online.ProviderHealthTracker
import app.nocturne.player.data.security.InputAndUrlSecurity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class LyricsQuery(
    val cleanedTitle: String,
    val cleanedArtist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val sourceId: String = "",
    val onlineProvider: String = "",
    val isOnline: Boolean = false
)

data class ResolvedLyricsPayload(
    val source: LyricsSource,
    val syncedLrc: String? = null,
    val plainLyrics: String? = null,
    val parsedLines: List<LrcLine> = emptyList(),
    val isInstrumental: Boolean = false
) {
    fun hasContent(): Boolean =
        parsedLines.isNotEmpty() || !syncedLrc.isNullOrBlank() || !plainLyrics.isNullOrBlank() || isInstrumental

    fun toLyricsData(fromCache: Boolean = false): LyricsData {
        val raw = syncedLrc?.takeIf { it.isNotBlank() } ?: plainLyrics.orEmpty()
        return LyricsData(
            syncedLines = parsedLines,
            rawText = raw,
            source = source,
            isInstrumental = isInstrumental && parsedLines.isEmpty() && raw.isBlank(),
            fromCache = fromCache
        )
    }
}

interface LyricsProvider {
    val source: LyricsSource
    suspend fun fetchLyrics(query: LyricsQuery): ResolvedLyricsPayload?
}

internal object LyricsHttpUtil {
    private val clientCache = ConcurrentHashMap<Long, OkHttpClient>()
    val defaultHealthTracker = ProviderHealthTracker(failureThreshold = 1)

    sealed interface EndpointFetchResult {
        data class Success(val body: String) : EndpointFetchResult
        data class NotFound(val statusCode: Int) : EndpointFetchResult
        data class EndpointFailure(val reason: String) : EndpointFetchResult
    }

    fun clientWithTimeout(baseClient: OkHttpClient, timeoutMs: Long): OkHttpClient {
        val clamped = timeoutMs.coerceIn(1_000L, 30_000L)
        val key = (System.identityHashCode(baseClient).toLong() shl 32) or clamped
        return clientCache.computeIfAbsent(key) {
            baseClient.newBuilder()
                .connectTimeout(clamped, TimeUnit.MILLISECONDS)
                .readTimeout(clamped, TimeUnit.MILLISECONDS)
                .writeTimeout(clamped, TimeUnit.MILLISECONDS)
                .callTimeout(clamped + 2_000L, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    fun selectOrderedEndpoints(
        healthTracker: ProviderHealthTracker,
        providerId: String,
        endpoints: List<String>,
        endpointFailover: Boolean = true
    ): List<String> {
        val ordered = healthTracker.selectOrderedEndpoints(
            providerId = providerId,
            operation = OperationType.METADATA,
            configuredEndpoints = endpoints
        )
        return if (endpointFailover) ordered else ordered.take(1)
    }

    fun executeJsonGetWithHealth(
        healthTracker: ProviderHealthTracker,
        providerId: String,
        baseEndpoint: String,
        fullUrl: String,
        timedClient: OkHttpClient,
        headers: Map<String, String>,
        cookie: String? = null,
        maxRetries: Int = 1,
        cooldownMs: Long = 60_000L,
        maxBytes: Long = 1024L * 1024L,
        allowLoopback: Boolean = false
    ): EndpointFetchResult {
        val allowedOrigin = fullUrl.takeIf { allowLoopback }
        val safeUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(fullUrl, allowedOrigin)
            ?: return EndpointFetchResult.EndpointFailure("Unsafe URL")

        val boundedRetries = maxRetries.coerceIn(0, 3)
        var attempt = 0
        var lastError: String? = null

        while (attempt <= boundedRetries) {
            attempt++
            val startNs = System.nanoTime()
            try {
                val reqBuilder = Request.Builder()
                    .url(safeUrl)
                    .header("Accept", "application/json")
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                if (!cookie.isNullOrBlank() && !InputAndUrlSecurity.hasControlChars(cookie)) {
                    reqBuilder.header("Cookie", cookie)
                }
                timedClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val code = resp.code
                    if (code in 200..299) {
                        val body = InputAndUrlSecurity.readBodyBounded(resp.body, maxBytes)
                        if (body.isBlank() || body.trimStart().startsWith("<")) {
                            throw IOException("Invalid or HTML response from $baseEndpoint")
                        }
                        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                        healthTracker.recordEndpointSuccess(providerId, OperationType.METADATA, baseEndpoint, latencyMs)
                        return EndpointFetchResult.Success(body)
                    }
                    if (code == 404) {
                        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                        healthTracker.recordEndpointSuccess(providerId, OperationType.METADATA, baseEndpoint, latencyMs)
                        return EndpointFetchResult.NotFound(code)
                    }
                    lastError = "HTTP $code"
                    val isServerOrRateError = code >= 500 || code == 429 || code == 408
                    if (!isServerOrRateError) {
                        return EndpointFetchResult.NotFound(code)
                    }
                    // Retryable server/rate error falls through to failure handling below
                }
            } catch (e: IOException) {
                lastError = e.message ?: "IO error"
                if (attempt <= boundedRetries) {
                    continue
                }
            }
            if (attempt > boundedRetries) {
                break
            }
        }

        healthTracker.recordEndpointFailure(
            providerId = providerId,
            operation = OperationType.METADATA,
            endpointUrl = baseEndpoint,
            error = lastError,
            endpointFailureThreshold = 1,
            customCooldownMs = cooldownMs
        )
        return EndpointFetchResult.EndpointFailure(lastError ?: "Endpoint failed")
    }

    fun formatLrcTimestamp(timeMs: Long): String {
        val safe = timeMs.coerceAtLeast(0L)
        val totalSeconds = safe / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centis = (safe % 1000L) / 10L
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centis)
    }

    fun serializeToEnhancedLrc(lines: List<LrcLine>): String {
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n") { line ->
            val head = "[${formatLrcTimestamp(line.timeMs)}]"
            if (line.isExplicitWordSync && line.words.isNotEmpty()) {
                val wordPart = line.words.joinToString(" ") { w ->
                    "<${formatLrcTimestamp(w.startTimeMs)}>${w.text}"
                }
                "$head$wordPart"
            } else {
                "$head${line.text}"
            }
        }
    }
}
