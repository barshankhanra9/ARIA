package app.nocturne.player.data.online

import android.net.Uri
import app.nocturne.player.data.model.DynamicProviderConfig
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAlbumPage
import app.nocturne.player.data.model.OnlineAlbumSummary
import app.nocturne.player.data.model.OnlineArtistPage
import app.nocturne.player.data.model.OnlineArtistSummary
import app.nocturne.player.data.model.OnlineAudioStream
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineSearchPage
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.model.ProviderEndpointConfig
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class PipedProvider(
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val dynamicBaseUrlsProvider: (() -> List<String>)? = null,
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    private val dynamicConfigProvider: (() -> DynamicProviderConfig?)? = null,
    executorOverride: DynamicHttpExecutor? = null
) : OnlineMusicProvider {

    override val providerType: OnlineProviderType = OnlineProviderType.PIPED
    override val providerId: String
        get() = currentConfig()?.id ?: "piped"
    override val displayName: String
        get() = currentConfig()?.displayName ?: OnlineProviderType.PIPED.displayName
    override val supportsPagination: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }
    private val executor: DynamicHttpExecutor = executorOverride ?: DynamicHttpExecutor(
        baseClient = client,
        healthTracker = healthTracker,
        allowLoopbackForTests = baseUrlsOverride != null
    )

    @Volatile
    var lastWorkingInstance: String? = null
        private set

    override fun currentConfig(): DynamicProviderConfig? = dynamicConfigProvider?.invoke()

    override fun supportsCapability(capability: String): Boolean =
        currentConfig()?.supportsCapability(capability) ?: true

    override fun activeEndpointFor(operation: OperationType): String? =
        healthTracker.activeEndpointFor(providerId, operation) ?: lastWorkingInstance

    override suspend fun search(
        query: String,
        filter: OnlineSearchFilter,
        pageToken: String?
    ): OnlineSearchPage = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        val filterParam = when (filter) {
            OnlineSearchFilter.SONGS -> "music_songs"
            OnlineSearchFilter.ARTISTS -> "channels"
            OnlineSearchFilter.ALBUMS -> "playlists"
            else -> "all"
        }
        val encodedQ = Uri.encode(safeQuery)
        val path = if (pageToken.isNullOrBlank()) {
            "/search?q=$encodedQ&filter=$filterParam"
        } else {
            "/nextpage/search?nextpage=${Uri.encode(pageToken.take(512))}&q=$encodedQ&filter=$filterParam"
        }

        val resp = executeRawWithFallback(OperationType.SEARCH, path)
        val obj = json.parseToJsonElement(resp.body).jsonObject
        val items = obj["items"]?.jsonArray.orEmpty()

        val tracks = mutableListOf<OnlineTrack>()
        val artists = mutableListOf<OnlineArtistSummary>()
        val albums = mutableListOf<OnlineAlbumSummary>()

        items.forEach { el ->
            val itemObj = el.jsonObject
            when (itemObj.str("type")) {
                "stream", null -> parseStreamItem(itemObj)?.let { tracks.add(it) }
                "channel" -> parseChannelItem(itemObj)?.let { artists.add(it) }
                "playlist" -> parsePlaylistItem(itemObj)?.let { albums.add(it) }
            }
        }

        val nextToken = obj.str("nextpage")?.takeIf { it.isNotBlank() && it != "null" }
        OnlineSearchPage(
            query = query,
            tracks = tracks,
            artists = artists,
            albums = albums,
            nextPageToken = nextToken,
            hasMore = nextToken != null,
            providerUsed = OnlineProviderType.PIPED,
            providerUsedId = providerId,
            providerUsedLabel = displayName
        )
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (safeQuery.isBlank()) return@withContext emptyList()
        val resp = executeRawWithFallback(OperationType.SEARCH, "/suggestions?query=${Uri.encode(safeQuery)}")
        json.parseToJsonElement(resp.body).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    override suspend fun trending(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val resp = executeRawWithFallback(OperationType.SEARCH, "/trending?region=US")
        val arr = json.parseToJsonElement(resp.body).jsonArray
        arr.mapNotNull { parseStreamItem(it.jsonObject) }
    }

    override suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution = withContext(Dispatchers.IO) {
        val videoId = InputAndUrlSecurity.sanitizePathSegment(track.sourceId)
            ?: throw IllegalArgumentException("Invalid Piped video ID")
        val endpoints = getEndpointConfigs(OperationType.PLAYBACK)
        val cfg = currentConfig()
        val endpointFailover = cfg?.endpointFailover ?: true
        val cooldownMs = cfg?.cooldownMs ?: 60_000L
        val orderedUrls = healthTracker.selectOrderedEndpoints(
            providerId = providerId,
            operation = OperationType.PLAYBACK,
            configuredEndpoints = endpoints.map { it.url }
        ).let { if (endpointFailover) it else it.take(1) }
        val epByUrl = endpoints.associateBy { it.url.trimEnd('/') }
        val path = "/streams/${Uri.encode(videoId)}"
        val effectiveTier = if (speedTier == NetworkSpeedTier.STANDARD) NetworkSpeedTier.FAST_UNMETERED else speedTier

        var lastError: Throwable? = null
        for (epUrl in orderedUrls) {
            val epCfg = epByUrl[epUrl] ?: ProviderEndpointConfig(url = epUrl)
            try {
                val resp = executor.executeRawGet(
                    providerId = providerId,
                    operation = OperationType.PLAYBACK,
                    configuredEndpoints = listOf(epCfg),
                    pathAndQuery = path,
                    headers = cfg?.headers ?: emptyMap(),
                    timeoutMs = cfg?.timeoutMs ?: 10_000L,
                    maxRetries = cfg?.maxRetries ?: 1,
                    cooldownMs = cooldownMs,
                    endpointFailover = false
                )
                val obj = json.parseToJsonElement(resp.body).jsonObject
                if (!obj.str("error").isNullOrBlank()) {
                    throw IllegalStateException(obj.str("error") ?: "Piped stream error")
                }

                val rawStreams = obj["audioStreams"]?.jsonArray.orEmpty()
                val streams = rawStreams.mapNotNull { el ->
                    val st = el.jsonObject
                    if (st.bool("videoOnly") == true) return@mapNotNull null
                    val rawUrl = st.str("url")
                    val allowedOrigin = rawUrl?.takeIf { baseUrlsOverride != null }
                    val url = InputAndUrlSecurity.validateRemoteHttpsUrl(rawUrl, allowedOrigin)
                        ?: return@mapNotNull null
                    val bps = st.int("bitrate") ?: 128_000
                    val kbps = if (bps > 1000) bps / 1000 else bps
                    val mime = st.str("mimeType").orEmpty().ifBlank { "audio/mp4" }
                    val codec = st.str("codec").orEmpty().ifBlank { st.str("format").orEmpty() }
                    val fmt = st.str("format").orEmpty().ifBlank { "AUDIO" }
                    val cleanLabel = if (codec.contains("opus", ignoreCase = true) || mime.contains("webm", ignoreCase = true)) {
                        "Opus ${kbps}k"
                    } else {
                        "${fmt.uppercase()} ${kbps}k"
                    }
                    OnlineAudioStream(
                        url = url,
                        bitrateKbps = kbps.coerceAtLeast(32),
                        mimeType = mime,
                        codec = codec,
                        formatLabel = cleanLabel,
                        expiresAtMs = StreamQualitySelector.parseExpiryFromUrl(url)
                    )
                }

                val allowedOrigin = resp.endpointUrl.takeIf { baseUrlsOverride != null }
                val selected = StreamQualitySelector.selectBestAudioStream(
                    streams = streams,
                    speedTier = effectiveTier,
                    allowedLoopbackOrigin = allowedOrigin,
                    preferWebmOpus = true
                ) ?: throw IllegalStateException("No playable audio streams returned by $displayName")

                lastWorkingInstance = resp.endpointUrl
                val title = obj.str("title") ?: track.title
                val uploader = obj.str("uploader") ?: track.artist
                val uploaderUrl = obj.str("uploaderUrl")?.let { normalizeYoutubeUrl(it) } ?: track.artistUrl
                val durationSec = obj.long("duration") ?: 0L
                val thumb = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnailUrl"), allowedOrigin) ?: track.thumbnailUrl
                val related = obj["relatedStreams"]?.jsonArray.orEmpty().mapNotNull {
                    parseStreamItem(it.jsonObject)
                }

                return@withContext OnlineStreamResolution(
                    track = track.copy(
                        title = title,
                        artist = uploader,
                        artistUrl = uploaderUrl,
                        durationMs = if (durationSec > 0L) durationSec * 1000L else track.durationMs,
                        thumbnailUrl = thumb,
                        isLive = obj.bool("livestream") ?: track.isLive
                    ),
                    streams = streams,
                    selectedStream = selected,
                    relatedTracks = related,
                    resolvedBy = OnlineProviderType.PIPED,
                    resolvedByProviderId = providerId,
                    resolvedByLabel = displayName
                )
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordEndpointFailure(
                    providerId = providerId,
                    operation = OperationType.PLAYBACK,
                    endpointUrl = epUrl,
                    error = t.message,
                    customCooldownMs = cooldownMs
                )
            }
        }
        throw lastError ?: IllegalStateException("No playable audio streams returned by $displayName")
    }

    override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage = withContext(Dispatchers.IO) {
        val channelId = InputAndUrlSecurity.sanitizePathSegment(extractChannelId(artistIdOrUrl))
            ?: throw IllegalArgumentException("Invalid Piped channel ID")
        val resp = executeRawWithFallback(OperationType.METADATA, "/channel/${Uri.encode(channelId)}")
        val obj = json.parseToJsonElement(resp.body).jsonObject
        val tracks = obj["relatedStreams"]?.jsonArray.orEmpty().mapNotNull {
            parseStreamItem(it.jsonObject)
        }
        val subCount = obj.long("subscriberCount") ?: 0L

        OnlineArtistPage(
            id = channelId,
            name = obj.str("name") ?: "Artist",
            url = "https://www.youtube.com/channel/${Uri.encode(channelId)}",
            avatarUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("avatarUrl")),
            bannerUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("bannerUrl")),
            subscriberCountText = if (subCount > 0L) "${subCount / 1000}K" else "",
            description = obj.str("description").orEmpty(),
            tracks = tracks,
            playlists = emptyList(),
            providerType = OnlineProviderType.PIPED,
            providerId = providerId
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val playlistId = InputAndUrlSecurity.sanitizePathSegment(extractPlaylistId(albumIdOrUrl))
            ?: throw IllegalArgumentException("Invalid Piped playlist ID")
        val resp = executeRawWithFallback(OperationType.METADATA, "/playlists/${Uri.encode(playlistId)}")
        val obj = json.parseToJsonElement(resp.body).jsonObject
        val tracks = obj["relatedStreams"]?.jsonArray.orEmpty().mapNotNull {
            parseStreamItem(it.jsonObject)
        }

        OnlineAlbumPage(
            id = playlistId,
            title = obj.str("name") ?: "Playlist",
            artist = obj.str("uploader") ?: displayName,
            url = "https://www.youtube.com/playlist?list=${Uri.encode(playlistId)}",
            thumbnailUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnailUrl"))
                ?: tracks.firstOrNull()?.thumbnailUrl,
            description = obj.str("description").orEmpty(),
            tracks = tracks,
            providerType = OnlineProviderType.PIPED,
            providerId = providerId
        )
    }

    private fun getEndpointConfigs(operation: OperationType): List<ProviderEndpointConfig> {
        val baseList = when {
            baseUrlsOverride != null -> baseUrlsOverride.mapIndexedNotNull { idx, raw ->
                InputAndUrlSecurity.validateRemoteBaseUrl(raw, allowLoopback = true)?.let { clean ->
                    ProviderEndpointConfig(url = clean, priority = idx + 1)
                }
            }
            currentConfig() != null -> currentConfig()!!.endpointsFor(operation)
            else -> dynamicBaseUrlsProvider?.invoke().orEmpty()
                .mapIndexedNotNull { idx, raw ->
                    InputAndUrlSecurity.validateRemoteBaseUrl(raw, allowLoopback = false)?.let { clean ->
                        ProviderEndpointConfig(url = clean, priority = idx + 1)
                    }
                }
                .distinctBy { it.url }
        }
        val stickyForOp = healthTracker.activeEndpointFor(providerId, operation)
        val preferred = if (stickyForOp == null) lastWorkingInstance else null
        return if (preferred != null && baseList.any { it.url == preferred }) {
            val reordered = listOf(baseList.first { it.url == preferred }) + baseList.filterNot { it.url == preferred }
            reordered.mapIndexed { idx, ep -> ep.copy(priority = idx + 1) }
        } else {
            baseList
        }
    }

    private suspend fun executeRawWithFallback(operation: OperationType, path: String): ExecutedHttpResponse {
        val endpoints = getEndpointConfigs(operation)
        val cfg = currentConfig()
        val resp = executor.executeRawGet(
            providerId = providerId,
            operation = operation,
            configuredEndpoints = endpoints,
            pathAndQuery = path,
            headers = cfg?.headers ?: emptyMap(),
            timeoutMs = cfg?.timeoutMs ?: 10_000L,
            maxRetries = cfg?.maxRetries ?: 1,
            cooldownMs = cfg?.cooldownMs ?: 60_000L,
            endpointFailover = cfg?.endpointFailover ?: true
        )
        lastWorkingInstance = resp.endpointUrl
        return resp
    }

    private fun parseStreamItem(obj: JsonObject): OnlineTrack? {
        val rawUrl = obj.str("url")?.takeIf { it.isNotBlank() } ?: return null
        val id = extractVideoId(rawUrl).takeIf { it.isNotBlank() } ?: return null
        val durationSec = obj.long("duration") ?: 0L
        val uploaderUrl = obj.str("uploaderUrl")?.let { normalizeYoutubeUrl(it) }
        val safeThumb = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnail"))
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return OnlineTrack(
            id = OnlineTrack.canonicalId(OnlineProviderType.YOUTUBE, id),
            sourceId = id,
            providerType = OnlineProviderType.PIPED,
            providerLabel = displayName,
            sourceUrl = "https://www.youtube.com/watch?v=$id",
            title = obj.str("title") ?: "Untitled",
            artist = obj.str("uploaderName") ?: "YouTube",
            artistId = uploaderUrl?.substringAfter("/channel/")?.substringBefore("/"),
            artistUrl = uploaderUrl,
            album = "YouTube · $displayName",
            durationMs = if (durationSec > 0L) durationSec * 1000L else 0L,
            thumbnailUrl = safeThumb,
            viewCount = obj.long("views") ?: 0L,
            publishedText = obj.str("uploadedDate").orEmpty(),
            isLive = durationSec < 0L,
            providerId = providerId
        )
    }

    private fun parseChannelItem(obj: JsonObject): OnlineArtistSummary? {
        val rawUrl = obj.str("url")?.takeIf { it.isNotBlank() } ?: return null
        val fullUrl = normalizeYoutubeUrl(rawUrl) ?: return null
        return OnlineArtistSummary(
            id = extractChannelId(fullUrl),
            name = obj.str("name") ?: "Artist",
            url = fullUrl,
            thumbnailUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnail")),
            subscriberText = obj.long("subscribers")?.takeIf { it > 0L }?.let { "${it / 1000}K" }.orEmpty(),
            providerType = OnlineProviderType.PIPED,
            providerId = providerId
        )
    }

    private fun parsePlaylistItem(obj: JsonObject): OnlineAlbumSummary? {
        val rawUrl = obj.str("url")?.takeIf { it.isNotBlank() } ?: return null
        val fullUrl = normalizeYoutubeUrl(rawUrl) ?: return null
        return OnlineAlbumSummary(
            id = extractPlaylistId(fullUrl),
            title = obj.str("name") ?: "Playlist",
            artist = obj.str("uploaderName") ?: "YouTube",
            url = fullUrl,
            thumbnailUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnail")),
            trackCount = obj.int("videos") ?: 0,
            providerType = OnlineProviderType.PIPED,
            providerId = providerId
        )
    }

    private fun normalizeYoutubeUrl(urlOrPath: String): String? {
        val candidate = if (urlOrPath.startsWith("http", ignoreCase = true)) {
            urlOrPath
        } else if (urlOrPath.startsWith("/")) {
            "https://www.youtube.com$urlOrPath"
        } else {
            "https://www.youtube.com/$urlOrPath"
        }
        return InputAndUrlSecurity.validateRemoteHttpsUrl(candidate)
    }

    private fun extractVideoId(url: String): String {
        return runCatching {
            val uri = Uri.parse(normalizeYoutubeUrl(url))
            uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }
                ?: if (url.contains("/shorts/")) url.substringAfter("/shorts/").substringBefore("?")
                else url.substringAfterLast("/")
        }.getOrDefault(url)
    }

    private fun extractChannelId(idOrUrl: String): String {
        return when {
            idOrUrl.contains("/channel/") -> idOrUrl.substringAfter("/channel/").substringBefore("?").substringBefore("/")
            else -> idOrUrl
        }
    }

    private fun extractPlaylistId(idOrUrl: String): String {
        return runCatching {
            Uri.parse(normalizeYoutubeUrl(idOrUrl)).getQueryParameter("list")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: idOrUrl
    }
}
