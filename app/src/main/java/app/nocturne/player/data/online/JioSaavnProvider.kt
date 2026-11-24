package app.nocturne.player.data.online

import android.net.Uri
import android.util.LruCache
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.IOException

class JioSaavnProvider(
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val dynamicBaseUrlsProvider: (() -> List<String>)? = null,
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    private val dynamicConfigProvider: (() -> DynamicProviderConfig?)? = null,
    executorOverride: DynamicHttpExecutor? = null
) : OnlineMusicProvider {

    override val providerType: OnlineProviderType = OnlineProviderType.JIOSAAVN
    override val providerId: String
        get() = currentConfig()?.id ?: "jiosaavn"
    override val displayName: String
        get() = currentConfig()?.displayName ?: OnlineProviderType.JIOSAAVN.displayName
    override val supportsPagination: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }
    private val parsedStreamsCache = LruCache<String, List<OnlineAudioStream>>(160)
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
        val page = pageToken?.toIntOrNull()?.coerceIn(1, 100) ?: 1
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        val encodedQ = Uri.encode(safeQuery)
        val cfg = currentConfig()

        when (filter) {
            OnlineSearchFilter.ALBUMS -> {
                val resp = executeConfiguredOrPath(
                    operation = OperationType.SEARCH,
                    template = cfg?.routes?.searchAlbums,
                    variables = mapOf("query" to safeQuery, "page" to page.toString(), "limit" to "20"),
                    fallbackPath = "/search/albums?query=$encodedQ&page=$page&limit=20"
                )
                val results = extractResultsArray(resp.body, cfg?.mapping?.resultsPath)
                val albums = results.mapNotNull { parseAlbumItem(it.jsonObject) }
                OnlineSearchPage(
                    query = query,
                    albums = albums,
                    nextPageToken = if (albums.size >= 15) (page + 1).toString() else null,
                    hasMore = albums.size >= 15,
                    providerUsed = OnlineProviderType.JIOSAAVN,
                    providerUsedId = providerId,
                    providerUsedLabel = displayName
                )
            }
            OnlineSearchFilter.ARTISTS -> {
                val resp = executeConfiguredOrPath(
                    operation = OperationType.SEARCH,
                    template = cfg?.routes?.searchArtists,
                    variables = mapOf("query" to safeQuery, "page" to page.toString(), "limit" to "20"),
                    fallbackPath = "/search/artists?query=$encodedQ&page=$page&limit=20"
                )
                val results = extractResultsArray(resp.body, cfg?.mapping?.resultsPath)
                val artists = results.mapNotNull { parseArtistItem(it.jsonObject) }
                OnlineSearchPage(
                    query = query,
                    artists = artists,
                    nextPageToken = if (artists.size >= 15) (page + 1).toString() else null,
                    hasMore = artists.size >= 15,
                    providerUsed = OnlineProviderType.JIOSAAVN,
                    providerUsedId = providerId,
                    providerUsedLabel = displayName
                )
            }
            else -> {
                val resp = executeConfiguredOrPath(
                    operation = OperationType.SEARCH,
                    template = cfg?.routes?.searchSongs ?: cfg?.routes?.searchAll,
                    variables = mapOf("query" to safeQuery, "page" to page.toString(), "limit" to "20"),
                    fallbackPath = "/search/songs?query=$encodedQ&page=$page&limit=20"
                )
                val results = extractResultsArray(resp.body, cfg?.mapping?.resultsPath)
                val tracks = results.mapNotNull { parseSongItem(it.jsonObject) }
                OnlineSearchPage(
                    query = query,
                    tracks = tracks,
                    nextPageToken = if (tracks.size >= 15) (page + 1).toString() else null,
                    hasMore = tracks.size >= 15,
                    providerUsed = OnlineProviderType.JIOSAAVN,
                    providerUsedId = providerId,
                    providerUsedLabel = displayName
                )
            }
        }
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (safeQuery.isBlank()) return@withContext emptyList()
        val cfg = currentConfig()
        val resp = executeConfiguredOrPath(
            operation = OperationType.SEARCH,
            template = cfg?.routes?.suggestions ?: cfg?.routes?.searchSongs,
            variables = mapOf("query" to safeQuery, "page" to "1", "limit" to "8"),
            fallbackPath = "/search/songs?query=${Uri.encode(safeQuery)}&page=1&limit=8"
        )
        extractResultsArray(resp.body, cfg?.mapping?.resultsPath)
            .mapNotNull { el ->
                val obj = el.jsonObject
                (obj.str("name") ?: obj.str("title"))?.let(::decodeHtmlEntities)
            }
            .distinct()
            .take(8)
    }

    override suspend fun trending(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        val resp = executeConfiguredOrPath(
            operation = OperationType.SEARCH,
            template = cfg?.routes?.trending ?: cfg?.routes?.searchSongs,
            variables = mapOf("query" to "latest trending songs", "page" to "1", "limit" to "20"),
            fallbackPath = "/search/songs?query=${Uri.encode("latest trending songs")}&page=1&limit=20"
        )
        extractResultsArray(resp.body, cfg?.mapping?.resultsPath)
            .mapNotNull { parseSongItem(it.jsonObject) }
    }

    suspend fun fetchNewReleases(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        val resp = executeConfiguredOrPath(
            operation = OperationType.SEARCH,
            template = cfg?.routes?.searchSongs,
            variables = mapOf("query" to "new releases", "page" to "1", "limit" to "16"),
            fallbackPath = "/search/songs?query=${Uri.encode("new releases")}&page=1&limit=16"
        )
        extractResultsArray(resp.body, cfg?.mapping?.resultsPath)
            .mapNotNull { parseSongItem(it.jsonObject) }
    }

    suspend fun fetchCuratedPlaylists(): List<OnlineAlbumSummary> = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        val resp = runCatching {
            executeRawWithFallback(
                OperationType.SEARCH,
                "/search/playlists?query=${Uri.encode("trending top charts")}&page=1&limit=12"
            )
        }.getOrElse {
            executeConfiguredOrPath(
                operation = OperationType.SEARCH,
                template = cfg?.routes?.searchAlbums,
                variables = mapOf("query" to "top charts", "page" to "1", "limit" to "12"),
                fallbackPath = "/search/albums?query=${Uri.encode("top charts")}&page=1&limit=12"
            )
        }
        extractResultsArray(resp.body, cfg?.mapping?.resultsPath)
            .mapNotNull { parseAlbumItem(it.jsonObject) }
    }

    override suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution = withContext(Dispatchers.IO) {
        val safeSourceId = InputAndUrlSecurity.sanitizePathSegment(track.sourceId)
            ?: throw IllegalArgumentException("Invalid JioSaavn track ID")
        var streams = parsedStreamsCache.get(safeSourceId).orEmpty()
        var resolvedTrack = track
        val cfg = currentConfig()

        if (streams.isEmpty()) {
            val encodedId = Uri.encode(safeSourceId)
            val resp = if (cfg?.routes?.resolveStream != null) {
                executeConfiguredOrPath(
                    operation = OperationType.PLAYBACK,
                    template = cfg.routes.resolveStream,
                    variables = mapOf("id" to safeSourceId, "trackId" to safeSourceId),
                    fallbackPath = "/songs/$encodedId"
                )
            } else {
                runCatching {
                    executeRawWithFallback(OperationType.PLAYBACK, "/songs/$encodedId")
                }.getOrElse {
                    executeRawWithFallback(OperationType.PLAYBACK, "/songs?ids=$encodedId")
                }
            }
            val parsedObj = extractSongObject(resp.body)
            if (parsedObj != null) {
                parseSongItem(parsedObj)?.let { updated ->
                    resolvedTrack = updated.copy(
                        isFavorite = track.isFavorite,
                        playCount = track.playCount,
                        lastPlayedAt = track.lastPlayedAt,
                        totalListenMs = track.totalListenMs
                    )
                }
                streams = parsedStreamsCache.get(safeSourceId).orEmpty()
            }
        }

        val allowedOrigin = lastWorkingInstance?.takeIf { baseUrlsOverride != null }
        val effectiveTier = if (speedTier == NetworkSpeedTier.STANDARD) NetworkSpeedTier.FAST_UNMETERED else speedTier
        val selected = StreamQualitySelector.selectBestAudioStream(
            streams = streams,
            speedTier = effectiveTier,
            allowedLoopbackOrigin = allowedOrigin
        ) ?: throw IOException("No playable audio streams returned by $displayName for ${track.title}")

        val related = runCatching {
            val sugResp = executeRawWithFallback(
                OperationType.METADATA,
                "/songs/${Uri.encode(safeSourceId)}/suggestions?limit=10"
            )
            extractResultsArray(sugResp.body, cfg?.mapping?.resultsPath)
                .mapNotNull { parseSongItem(it.jsonObject) }
        }.getOrDefault(emptyList())

        OnlineStreamResolution(
            track = resolvedTrack,
            streams = streams,
            selectedStream = selected,
            relatedTracks = related,
            resolvedBy = OnlineProviderType.JIOSAAVN,
            resolvedByProviderId = providerId,
            resolvedByLabel = displayName
        )
    }

    override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage = withContext(Dispatchers.IO) {
        val rawId = artistIdOrUrl.trimEnd('/').substringAfterLast("/").substringBefore("?")
        val id = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid JioSaavn artist ID")
        val cfg = currentConfig()
        val resp = executeConfiguredOrPath(
            operation = OperationType.METADATA,
            template = cfg?.routes?.artistDetails,
            variables = mapOf("id" to id, "artistId" to id),
            fallbackPath = "/artists/${Uri.encode(id)}"
        )
        val dataObj = extractDataObject(resp.body)
        val topSongs = (dataObj?.get("topSongs") as? JsonArray).orEmpty()
            .mapNotNull { parseSongItem(it.jsonObject) }
        val topAlbums = (dataObj?.get("topAlbums") as? JsonArray).orEmpty()
            .mapNotNull { parseAlbumItem(it.jsonObject) }

        OnlineArtistPage(
            id = id,
            name = decodeHtmlEntities(dataObj?.str("name") ?: "Artist"),
            url = dataObj?.str("url") ?: artistIdOrUrl,
            avatarUrl = pickBestImageUrl(dataObj?.get("image")),
            subscriberCountText = dataObj?.str("followerCount") ?: "",
            description = "",
            tracks = topSongs,
            playlists = topAlbums,
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = providerId
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val rawId = albumIdOrUrl.trimEnd('/').substringAfterLast("/").substringBefore("?")
        val id = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid JioSaavn album ID")
        val cfg = currentConfig()
        val resp = if (cfg?.routes?.albumDetails != null) {
            executeConfiguredOrPath(
                operation = OperationType.METADATA,
                template = cfg.routes.albumDetails,
                variables = mapOf("id" to id, "albumId" to id),
                fallbackPath = "/albums?id=${Uri.encode(id)}"
            )
        } else {
            runCatching {
                executeRawWithFallback(OperationType.METADATA, "/albums?id=${Uri.encode(id)}")
            }.getOrElse {
                executeRawWithFallback(OperationType.METADATA, "/playlists?id=${Uri.encode(id)}")
            }
        }
        val dataObj = extractDataObject(resp.body)
        val songs = (dataObj?.get("songs") as? JsonArray).orEmpty()
            .mapNotNull { parseSongItem(it.jsonObject) }

        OnlineAlbumPage(
            id = id,
            title = decodeHtmlEntities(dataObj?.str("name") ?: dataObj?.str("title") ?: "Album"),
            artist = decodeHtmlEntities(
                extractPrimaryArtists(dataObj).ifBlank { displayName }
            ),
            url = dataObj?.str("url") ?: albumIdOrUrl,
            thumbnailUrl = pickBestImageUrl(dataObj?.get("image")) ?: songs.firstOrNull()?.thumbnailUrl,
            description = decodeHtmlEntities(dataObj?.str("description").orEmpty()),
            tracks = songs,
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = providerId
        )
    }

    private fun getEndpointConfigs(operation: OperationType): List<ProviderEndpointConfig> {
        if (baseUrlsOverride != null) {
            return baseUrlsOverride.mapIndexedNotNull { idx, raw ->
                InputAndUrlSecurity.validateRemoteBaseUrl(raw, allowLoopback = true)?.let { clean ->
                    ProviderEndpointConfig(url = clean, priority = idx + 1)
                }
            }
        }
        val cfg = currentConfig()
        if (cfg != null) {
            return cfg.endpointsFor(operation)
        }
        return dynamicBaseUrlsProvider?.invoke().orEmpty()
            .mapIndexedNotNull { idx, raw ->
                InputAndUrlSecurity.validateRemoteBaseUrl(raw, allowLoopback = false)?.let { clean ->
                    ProviderEndpointConfig(url = clean, priority = idx + 1)
                }
            }
            .distinctBy { it.url }
    }

    private suspend fun executeConfiguredOrPath(
        operation: OperationType,
        template: app.nocturne.player.data.model.EndpointOperationTemplate?,
        variables: Map<String, String>,
        fallbackPath: String
    ): ExecutedHttpResponse {
        val cfg = currentConfig()
        val resp = if (template != null && cfg != null && baseUrlsOverride == null) {
            executor.executeTemplated(
                config = cfg,
                operation = operation,
                template = template,
                variables = variables
            )
        } else {
            executeRawWithFallback(operation, fallbackPath)
        }
        lastWorkingInstance = resp.endpointUrl
        return resp
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

    private fun extractResultsArray(body: String, configuredPath: String? = null): JsonArray {
        val root = json.parseToJsonElement(body)
        if (!configuredPath.isNullOrBlank()) {
            val extracted = JsonPathExtractor.extractArray(root, configuredPath)
            if (extracted.isNotEmpty()) return JsonArray(extracted)
        }
        if (root is JsonArray) return root
        val obj = root.jsonObject
        val data = obj["data"]
        return when (data) {
            is JsonArray -> data
            is JsonObject -> (data["results"] as? JsonArray) ?: JsonArray(emptyList())
            else -> (obj["results"] as? JsonArray) ?: JsonArray(emptyList())
        }
    }

    private fun extractDataObject(body: String): JsonObject? {
        val root = json.parseToJsonElement(body).jsonObject
        return root["data"] as? JsonObject ?: root
    }

    private fun extractSongObject(body: String): JsonObject? {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]
        return when (data) {
            is JsonArray -> data.firstOrNull() as? JsonObject
            is JsonObject -> (data["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: data
            else -> root
        }
    }

    private fun parseSongItem(obj: JsonObject): OnlineTrack? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val title = decodeHtmlEntities(obj.str("name") ?: obj.str("title") ?: return null)
        val artist = decodeHtmlEntities(extractPrimaryArtists(obj).ifBlank { "JioSaavn Artist" })
        val artistId = ((obj["artists"] as? JsonObject)?.get("primary") as? JsonArray)
            ?.firstOrNull()?.let { it as? JsonObject }?.str("id")
        val artistUrl = ((obj["artists"] as? JsonObject)?.get("primary") as? JsonArray)
            ?.firstOrNull()?.let { it as? JsonObject }?.str("url")
        val albumObj = obj["album"] as? JsonObject
        val albumName = decodeHtmlEntities(
            albumObj?.str("name") ?: obj.str("album").takeIf { !it.isNullOrBlank() } ?: displayName
        )
        val albumUrl = albumObj?.str("url")
        val durationSec = parseFlexibleLong(obj["duration"])
        val thumb = pickBestImageUrl(obj["image"])
        val sourceUrl = obj.str("url") ?: "https://www.jiosaavn.com/song/$id"
        val saavnEncryptedUrl = obj.str("encrypted_media_url")?.takeIf { it.isNotBlank() }
        val streams = parseDownloadUrls(obj["downloadUrl"])
        if (streams.isNotEmpty()) {
            parsedStreamsCache.put(id, streams)
        }

        return OnlineTrack(
            id = OnlineTrack.canonicalId(providerId, id),
            sourceId = id,
            providerType = OnlineProviderType.JIOSAAVN,
            providerLabel = displayName,
            sourceUrl = sourceUrl,
            title = title,
            artist = artist,
            artistId = artistId,
            artistUrl = artistUrl,
            album = albumName,
            albumUrl = albumUrl,
            durationMs = if (durationSec > 0L) durationSec * 1000L else 0L,
            thumbnailUrl = thumb,
            viewCount = parseFlexibleLong(obj["playCount"]),
            publishedText = obj.str("year").orEmpty(),
            isLive = false,
            providerId = providerId,
            saavnEncryptedUrl = saavnEncryptedUrl
        )
    }

    private fun parseAlbumItem(obj: JsonObject): OnlineAlbumSummary? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return OnlineAlbumSummary(
            id = id,
            title = decodeHtmlEntities(obj.str("name") ?: obj.str("title") ?: "Album"),
            artist = decodeHtmlEntities(extractPrimaryArtists(obj).ifBlank { displayName }),
            url = obj.str("url") ?: id,
            thumbnailUrl = pickBestImageUrl(obj["image"]),
            trackCount = obj.int("songCount") ?: 0,
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = providerId
        )
    }

    private fun parseArtistItem(obj: JsonObject): OnlineArtistSummary? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return OnlineArtistSummary(
            id = id,
            name = decodeHtmlEntities(obj.str("name") ?: "Artist"),
            url = obj.str("url") ?: id,
            thumbnailUrl = pickBestImageUrl(obj["image"]),
            subscriberText = obj.str("role").orEmpty(),
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = providerId
        )
    }

    private fun parseDownloadUrls(el: JsonElement?): List<OnlineAudioStream> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val rawUrl = obj.str("url") ?: obj.str("link")
            val allowedOrigin = rawUrl?.takeIf { baseUrlsOverride != null }
            val url = InputAndUrlSecurity.validateRemoteHttpsUrl(rawUrl, allowedOrigin)
                ?: return@mapNotNull null
            val qualityStr = obj.str("quality").orEmpty()
            val inferredKbps = when {
                url.contains("_320.", ignoreCase = true) -> 320
                url.contains("_160.", ignoreCase = true) -> 160
                url.contains("_96.", ignoreCase = true) -> 96
                else -> 160
            }
            val kbps = Regex("""(\d+)""").find(qualityStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: inferredKbps
            val isMp3 = url.contains(".mp3", ignoreCase = true)
            OnlineAudioStream(
                url = url,
                bitrateKbps = kbps.coerceAtLeast(48),
                mimeType = if (isMp3) "audio/mpeg" else "audio/mp4",
                codec = if (isMp3) "mp3" else "mp4a.40.2",
                formatLabel = if (isMp3) "MP3 ${kbps}k" else "${kbps}kbps AAC",
                expiresAtMs = System.currentTimeMillis() + 3 * 60 * 60_000L
            )
        }.sortedByDescending { it.bitrateKbps }
    }

    private fun extractPrimaryArtists(obj: JsonObject?): String {
        if (obj == null) return ""
        val artistsObj = obj["artists"] as? JsonObject
        val primaryArr = artistsObj?.get("primary") as? JsonArray
        if (primaryArr != null && primaryArr.isNotEmpty()) {
            val names = primaryArr.mapNotNull { (it as? JsonObject)?.str("name")?.takeIf { n -> n.isNotBlank() } }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return obj.str("primaryArtists").orEmpty()
    }

    private fun pickBestImageUrl(el: JsonElement?): String? {
        val raw = when (el) {
            is JsonArray -> {
                val objects = el.mapNotNull { it as? JsonObject }
                val preferred = objects.firstOrNull {
                    val q = it.str("quality").orEmpty()
                    q.contains("500") || q.contains("high", ignoreCase = true)
                } ?: objects.lastOrNull()
                preferred?.let { it.str("url") ?: it.str("link") }
            }
            is JsonPrimitive -> el.contentOrNull
            else -> null
        } ?: return null

        val upgraded = raw
            .replace("150x150", "500x500")
            .replace("50x50", "500x500")
            .replace("_150.", "_500.")
            .replace("_50.", "_500.")
        val allowedOrigin = upgraded.takeIf { baseUrlsOverride != null }
        return InputAndUrlSecurity.validateRemoteHttpsUrl(upgraded, allowedOrigin)
    }

    private fun parseFlexibleLong(el: JsonElement?): Long {
        val prim = el as? JsonPrimitive ?: return 0L
        return prim.contentOrNull?.trim()?.toLongOrNull() ?: 0L
    }

    private fun decodeHtmlEntities(input: String): String =
        JsonPathExtractor.decodeHtmlEntities(input).trim()
}
