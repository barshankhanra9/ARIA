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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

class InvidiousProvider(
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val dynamicBaseUrlsProvider: (() -> List<String>)? = null,
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    private val dynamicConfigProvider: (() -> DynamicProviderConfig?)? = null,
    executorOverride: DynamicHttpExecutor? = null
) : OnlineMusicProvider {

    override val providerType: OnlineProviderType = OnlineProviderType.INVIDIOUS
    override val providerId: String
        get() = currentConfig()?.id ?: "invidious"
    override val displayName: String
        get() = currentConfig()?.displayName ?: OnlineProviderType.INVIDIOUS.displayName
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
        val page = pageToken?.toIntOrNull()?.coerceIn(1, 100) ?: 1
        val typeParam = when (filter) {
            OnlineSearchFilter.ARTISTS -> "channel"
            OnlineSearchFilter.ALBUMS -> "playlist"
            OnlineSearchFilter.SONGS -> "video"
            else -> "all"
        }
        val encodedQ = Uri.encode(safeQuery)
        val resp = executeRawWithFallback(OperationType.SEARCH, "/api/v1/search?q=$encodedQ&page=$page&type=$typeParam")
        val parsed = json.parseElementOrNull(resp.body)
        val arr = parsed.asArrayOrNull()
            ?: parsed.asObjectOrNull()?.let { it.objArray("items") ?: it.objArray("results") ?: it.objArray("videos") }
            ?: JsonArray(emptyList())

        val tracks = mutableListOf<OnlineTrack>()
        val artists = mutableListOf<OnlineArtistSummary>()
        val albums = mutableListOf<OnlineAlbumSummary>()

        arr.forEach { el ->
            val obj = el.asObjectOrNull() ?: return@forEach
            when (obj.str("type")) {
                "video", null -> parseVideoObject(obj)?.let { tracks.add(it) }
                "channel" -> parseChannelObject(obj)?.let { artists.add(it) }
                "playlist" -> parsePlaylistObject(obj)?.let { albums.add(it) }
            }
        }

        val hasMore = arr.size >= 15
        OnlineSearchPage(
            query = query,
            tracks = tracks,
            artists = artists,
            albums = albums,
            nextPageToken = if (hasMore) (page + 1).toString() else null,
            hasMore = hasMore,
            providerUsed = OnlineProviderType.INVIDIOUS,
            providerUsedId = providerId,
            providerUsedLabel = displayName
        )
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (safeQuery.isBlank()) return@withContext emptyList()
        val encodedQ = Uri.encode(safeQuery)
        val resp = executeRawWithFallback(OperationType.SEARCH, "/api/v1/search/suggestions?q=$encodedQ")
        val obj = json.parseObjectOrNull(resp.body) ?: return@withContext emptyList()
        obj.objArray("suggestions")?.mapNotNull { it.extractText() } ?: emptyList()
    }

    override suspend fun trending(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val resp = runCatching {
            executeRawWithFallback(OperationType.SEARCH, "/api/v1/trending?type=music")
        }.getOrElse {
            executeRawWithFallback(OperationType.SEARCH, "/api/v1/popular")
        }
        val arr = json.parseArrayOrNull(resp.body) ?: JsonArray(emptyList())
        arr.mapNotNull { it.asObjectOrNull()?.let { v -> parseVideoObject(v) } }
    }

    override suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution = withContext(Dispatchers.IO) {
        val videoId = InputAndUrlSecurity.sanitizePathSegment(track.sourceId)
            ?: throw IllegalArgumentException("Invalid Invidious video ID")
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
        val path = "/api/v1/videos/${Uri.encode(videoId)}"
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
                val instanceUrl = resp.endpointUrl
                val obj = json.parseObjectOrNull(resp.body)
                    ?: throw IllegalStateException("Malformed stream JSON from $displayName")
                if (!obj.str("error").isNullOrBlank()) {
                    throw IllegalStateException(obj.str("error") ?: "Invidious stream error")
                }

                val allowedTestOrigin = instanceUrl.takeIf { baseUrlsOverride != null }
                val adaptive = obj.objArray("adaptiveFormats").orEmpty()
                val streams = adaptive.mapNotNull { item ->
                    val fmt = item.asObjectOrNull() ?: return@mapNotNull null
                    val type = fmt.str("type").orEmpty()
                    if (!type.startsWith("audio/")) return@mapNotNull null
                    val rawUrl = fmt.strUrl("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val resolvedUrl = resolveInstanceMediaUrl(instanceUrl, rawUrl) ?: return@mapNotNull null
                    val bitrateBits = fmt.str("bitrate")?.toIntOrNull() ?: fmt.int("bitrate") ?: 128_000
                    val kbps = (bitrateBits / 1000).coerceAtLeast(32)
                    val container = fmt.str("container").orEmpty().ifBlank {
                        if ("webm" in type) "WEBM" else "M4A"
                    }
                    val encoding = fmt.str("encoding").orEmpty().ifBlank {
                        type.substringAfter("codecs=\"").substringBefore("\"")
                    }
                    val cleanLabel = if (encoding.contains("opus", ignoreCase = true) || type.contains("webm", ignoreCase = true)) {
                        "Opus ${kbps}k"
                    } else {
                        "${container.uppercase()} ${kbps}k"
                    }
                    OnlineAudioStream(
                        url = resolvedUrl,
                        bitrateKbps = kbps,
                        mimeType = type.substringBefore(";"),
                        codec = encoding,
                        formatLabel = cleanLabel,
                        expiresAtMs = StreamQualitySelector.parseExpiryFromUrl(resolvedUrl)
                    )
                }

                val selected = StreamQualitySelector.selectBestAudioStream(
                    streams = streams,
                    speedTier = effectiveTier,
                    allowedLoopbackOrigin = allowedTestOrigin,
                    preferWebmOpus = true
                ) ?: throw IllegalStateException("No audio stream returned by $displayName")

                lastWorkingInstance = instanceUrl
                val title = obj.str("title") ?: track.title
                val author = obj.str("author") ?: track.artist
                val authorId = obj.str("authorId") ?: track.artistId
                val durSec = obj.long("lengthSeconds") ?: 0L
                val thumbs = obj.objArray("videoThumbnails")
                val thumb = pickInvidiousThumb(thumbs, instanceUrl) ?: track.thumbnailUrl
                val related = obj.objArray("recommendedVideos").orEmpty().mapNotNull {
                    it.asObjectOrNull()?.let { rec -> parseRecommendedObject(rec, instanceUrl) }
                }

                return@withContext OnlineStreamResolution(
                    track = track.copy(
                        title = title,
                        artist = author,
                        artistId = authorId,
                        artistUrl = authorId?.let { "https://www.youtube.com/channel/$it" } ?: track.artistUrl,
                        durationMs = if (durSec > 0L) durSec * 1000L else track.durationMs,
                        thumbnailUrl = thumb
                    ),
                    streams = streams,
                    selectedStream = selected,
                    relatedTracks = related,
                    resolvedBy = OnlineProviderType.INVIDIOUS,
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
        throw lastError ?: IllegalStateException("No audio stream returned by $displayName")
    }

    override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage = withContext(Dispatchers.IO) {
        val channelId = InputAndUrlSecurity.sanitizePathSegment(extractChannelId(artistIdOrUrl))
            ?: throw IllegalArgumentException("Invalid Invidious channel ID")
        val resp = executeRawWithFallback(OperationType.METADATA, "/api/v1/channels/${Uri.encode(channelId)}")
        val instanceUrl = resp.endpointUrl
        val obj = json.parseObjectOrNull(resp.body) ?: JsonObject(emptyMap())
        val videos = obj.objArray("latestVideos").orEmpty().mapNotNull {
            it.asObjectOrNull()?.let { v -> parseVideoObject(v, instanceUrl) }
        }
        val avatar = obj.objArray("authorThumbnails")?.lastOrNull().asObjectOrNull()?.strUrl("url")
            ?.let { resolveInstanceMediaUrl(instanceUrl, it) }
        val banner = obj.objArray("authorBanners")?.firstOrNull().asObjectOrNull()?.strUrl("url")
            ?.let { resolveInstanceMediaUrl(instanceUrl, it) }
        val subCount = obj.long("subCount") ?: 0L

        OnlineArtistPage(
            id = channelId,
            name = obj.str("author") ?: obj.str("name") ?: "Artist",
            url = "https://www.youtube.com/channel/${Uri.encode(channelId)}",
            avatarUrl = avatar,
            bannerUrl = banner,
            subscriberCountText = if (subCount > 0L) "${subCount / 1000}K" else "",
            description = obj.str("description").orEmpty(),
            tracks = videos,
            playlists = emptyList(),
            providerType = OnlineProviderType.INVIDIOUS,
            providerId = providerId
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage =
        fetchAlbumOrPlaylist(albumIdOrUrl, isPlaylist = true)

    override suspend fun fetchAlbumOrPlaylist(
        albumIdOrUrl: String,
        isPlaylist: Boolean
    ): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val playlistId = InputAndUrlSecurity.sanitizePathSegment(extractPlaylistId(albumIdOrUrl))
            ?: throw IllegalArgumentException("Invalid Invidious playlist ID")
        val cfg = currentConfig()
        val template = cfg?.routes?.playlistDetails ?: cfg?.routes?.albumDetails
        val resp = if (template != null && cfg != null) {
            val r = executor.executeTemplated(
                config = cfg,
                operation = OperationType.METADATA,
                template = template,
                variables = mapOf(
                    "id" to playlistId,
                    "playlistId" to playlistId,
                    "albumId" to playlistId,
                    "listId" to playlistId,
                    "browseId" to playlistId,
                    "url" to albumIdOrUrl
                ),
                endpointOverride = baseUrlsOverride
            )
            lastWorkingInstance = r.endpointUrl
            r
        } else {
            executeRawWithFallback(OperationType.METADATA, "/api/v1/playlists/${Uri.encode(playlistId)}")
        }
        val instanceUrl = resp.endpointUrl
        val obj = json.parseObjectOrNull(resp.body) ?: JsonObject(emptyMap())
        val videosArr = obj.objArray("videos")
            ?: obj.objArray("items")
            ?: obj.objArray("relatedStreams")
            ?: JsonArray(emptyList())
        val videos = videosArr.mapNotNull {
            it.asObjectOrNull()?.let { v -> parseVideoObject(v, instanceUrl) }
        }
        OnlineAlbumPage(
            id = playlistId,
            title = obj.str("title") ?: obj.str("name") ?: "Playlist",
            artist = obj.str("author") ?: obj.str("uploader") ?: displayName,
            url = "https://www.youtube.com/playlist?list=${Uri.encode(playlistId)}",
            thumbnailUrl = obj.strUrl("playlistThumbnail")?.let { resolveInstanceMediaUrl(instanceUrl, it) }
                ?: obj.strUrl("thumbnails")?.let { resolveInstanceMediaUrl(instanceUrl, it) }
                ?: videos.firstOrNull()?.thumbnailUrl,
            description = obj.str("description").orEmpty(),
            tracks = videos,
            providerType = OnlineProviderType.INVIDIOUS,
            providerId = providerId,
            isPlaylist = true
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

    private fun parseVideoObject(obj: JsonObject, baseUrl: String? = null): OnlineTrack? {
        val id = obj.str("videoId")?.takeIf { it.isNotBlank() } ?: return null
        val title = obj.str("title") ?: "Untitled"
        val author = obj.str("author") ?: "YouTube"
        val authorId = obj.str("authorId")
        val lengthSec = obj.long("lengthSeconds") ?: 0L
        val thumb = pickInvidiousThumb(obj.objArray("videoThumbnails"), baseUrl)
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return OnlineTrack(
            id = OnlineTrack.canonicalId(OnlineProviderType.YOUTUBE, id),
            sourceId = id,
            providerType = OnlineProviderType.INVIDIOUS,
            providerLabel = displayName,
            sourceUrl = "https://www.youtube.com/watch?v=$id",
            title = title,
            artist = author,
            artistId = authorId,
            artistUrl = authorId?.let { "https://www.youtube.com/channel/$it" },
            album = "YouTube · $displayName",
            durationMs = lengthSec * 1000L,
            thumbnailUrl = thumb,
            viewCount = obj.long("viewCount") ?: 0L,
            publishedText = obj.str("publishedText").orEmpty(),
            isLive = obj.bool("liveNow") ?: false,
            providerId = providerId
        )
    }

    private fun parseRecommendedObject(obj: JsonObject, baseUrl: String): OnlineTrack? {
        val id = obj.str("videoId")?.takeIf { it.isNotBlank() } ?: return null
        val title = obj.str("title") ?: return null
        val author = obj.str("author") ?: "YouTube"
        val lengthSec = obj.long("lengthSeconds") ?: 0L
        val thumb = pickInvidiousThumb(obj.objArray("videoThumbnails"), baseUrl)
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return OnlineTrack(
            id = OnlineTrack.canonicalId(OnlineProviderType.YOUTUBE, id),
            sourceId = id,
            providerType = OnlineProviderType.INVIDIOUS,
            providerLabel = displayName,
            sourceUrl = "https://www.youtube.com/watch?v=$id",
            title = title,
            artist = author,
            artistId = obj.str("authorId"),
            durationMs = lengthSec * 1000L,
            thumbnailUrl = thumb,
            providerId = providerId
        )
    }

    private fun parseChannelObject(obj: JsonObject): OnlineArtistSummary? {
        val id = obj.str("authorId")?.takeIf { it.isNotBlank() } ?: return null
        val thumbRaw = obj.objArray("authorThumbnails")?.lastOrNull().asObjectOrNull()?.strUrl("url")
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        val thumb = InputAndUrlSecurity.validateRemoteHttpsUrl(thumbRaw, thumbRaw?.takeIf { baseUrlsOverride != null })
        return OnlineArtistSummary(
            id = id,
            name = obj.str("author") ?: "Artist",
            url = "https://www.youtube.com/channel/$id",
            thumbnailUrl = thumb,
            subscriberText = obj.long("subCount")?.let { "${it / 1000}K" }.orEmpty(),
            providerType = OnlineProviderType.INVIDIOUS,
            providerId = providerId
        )
    }

    private fun parsePlaylistObject(obj: JsonObject): OnlineAlbumSummary? {
        val id = obj.str("playlistId")?.takeIf { it.isNotBlank() } ?: return null
        val thumbRaw = (obj.strUrl("playlistThumbnail") ?: obj.strUrl("thumbnails"))
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        val thumb = InputAndUrlSecurity.validateRemoteHttpsUrl(thumbRaw, thumbRaw?.takeIf { baseUrlsOverride != null })
        return OnlineAlbumSummary(
            id = id,
            title = obj.str("title") ?: obj.str("name") ?: "Playlist",
            artist = obj.str("author") ?: obj.str("uploader") ?: "YouTube",
            url = "https://www.youtube.com/playlist?list=$id",
            thumbnailUrl = thumb,
            trackCount = obj.int("videoCount") ?: 0,
            providerType = OnlineProviderType.INVIDIOUS,
            providerId = providerId,
            isPlaylist = true
        )
    }

    private fun pickInvidiousThumb(thumbs: JsonArray?, baseUrl: String?): String? {
        if (thumbs == null || thumbs.isEmpty()) return null
        val objects = thumbs.mapNotNull { it.asObjectOrNull() }
        val url = objects.firstOrNull { it.str("quality") in listOf("medium", "high", "sddefault", "hqdefault") }
            ?.strUrl("url")
            ?: objects.firstOrNull()?.strUrl("url")
            ?: thumbs.extractUrlString()
            ?: return null
        return if (baseUrl != null) {
            resolveInstanceMediaUrl(baseUrl, url)
        } else {
            val normalized = if (url.startsWith("//")) "https:$url" else url
            InputAndUrlSecurity.validateRemoteHttpsUrl(normalized)
        }
    }

    private fun resolveInstanceMediaUrl(baseUrl: String, url: String): String? {
        val candidate = when {
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "${baseUrl.trimEnd('/')}$url"
            else -> url
        }
        val allowedOrigin = baseUrl.takeIf { baseUrlsOverride != null }
        return InputAndUrlSecurity.validateRemoteHttpsUrl(candidate, allowedOrigin)
    }

    private fun extractChannelId(idOrUrl: String): String {
        return when {
            idOrUrl.contains("/channel/") -> idOrUrl.substringAfter("/channel/").substringBefore("?").substringBefore("/")
            else -> idOrUrl
        }
    }

    private fun extractPlaylistId(idOrUrl: String): String {
        val trimmed = idOrUrl.trim()
        if (!trimmed.contains('/') && !trimmed.contains('?') && !trimmed.contains('=')) {
            return trimmed
        }
        runCatching {
            val normalized = if (trimmed.startsWith("http", ignoreCase = true)) {
                trimmed
            } else {
                "https://www.youtube.com/${trimmed.trimStart('/')}"
            }
            val uri = Uri.parse(normalized)
            listOf("list", "id", "playlistId", "listId", "browseId").forEach { key ->
                uri.getQueryParameter(key)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val segments = uri.pathSegments.orEmpty().filter { it.isNotBlank() }
            val plIdx = segments.indexOfFirst {
                it.equals("playlists", ignoreCase = true) ||
                    it.equals("playlist", ignoreCase = true) ||
                    it.equals("browse", ignoreCase = true)
            }
            if (plIdx >= 0 && plIdx + 1 < segments.size) {
                return segments[plIdx + 1]
            }
        }
        return trimmed.trimEnd('/').substringAfterLast("/").substringBefore("?").substringBefore("#")
    }
}
