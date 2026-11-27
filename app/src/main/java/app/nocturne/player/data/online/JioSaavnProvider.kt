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
import app.nocturne.player.data.model.StreamingQuality
import app.nocturne.player.data.model.ProviderEndpointConfig
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
            OnlineSearchFilter.ALBUMS -> coroutineScope {
                val playlistsDeferred = if (page == 1 && baseUrlsOverride == null && cfg?.routes?.searchAlbums == null) {
                    async<List<OnlineAlbumSummary>> {
                        runCatching {
                            val plResp = executeRawWithFallback(
                                OperationType.SEARCH,
                                "/search/playlists?query=$encodedQ&page=$page&limit=12"
                            )
                            extractResultsArray(plResp.body, cfg?.mapping?.resultsPath)
                                .mapNotNull { it.asObjectOrNull()?.let { obj -> parseAlbumItem(obj, isPlaylistHint = true) } }
                        }.getOrDefault(emptyList())
                    }
                } else null

                val resp = executeConfiguredOrPath(
                    operation = OperationType.SEARCH,
                    template = cfg?.routes?.searchAlbums,
                    variables = mapOf("query" to safeQuery, "page" to page.toString(), "limit" to "20"),
                    fallbackPath = "/search/albums?query=$encodedQ&page=$page&limit=20"
                )
                val results = extractResultsArray(resp.body, cfg?.mapping?.resultsPath)
                val baseAlbums = results.mapNotNull { it.asObjectOrNull()?.let { obj -> parseAlbumItem(obj) } }
                val merged = (playlistsDeferred?.await().orEmpty() + baseAlbums)
                    .distinctBy { "${it.providerType.name}:${it.id}" }
                OnlineSearchPage(
                    query = query,
                    albums = merged,
                    nextPageToken = if (baseAlbums.size >= 15) (page + 1).toString() else null,
                    hasMore = baseAlbums.size >= 15,
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
                val artists = results.mapNotNull { it.asObjectOrNull()?.let(::parseArtistItem) }
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
                val tracks = results.mapNotNull { it.asObjectOrNull()?.let(::parseSongItem) }
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
                val obj = el.asObjectOrNull() ?: return@mapNotNull el.extractText()?.let(::decodeHtmlEntities)
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
            .mapNotNull { it.asObjectOrNull()?.let(::parseSongItem) }
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
            .mapNotNull { it.asObjectOrNull()?.let(::parseSongItem) }
    }

    suspend fun fetchCuratedPlaylists(): List<OnlineAlbumSummary> = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        val playlistAttempt = runCatching {
            val resp = executeRawWithFallback(
                OperationType.SEARCH,
                "/search/playlists?query=${Uri.encode("trending top charts")}&page=1&limit=12"
            )
            extractResultsArray(resp.body, cfg?.mapping?.resultsPath)
                .mapNotNull { it.asObjectOrNull()?.let { obj -> parseAlbumItem(obj, isPlaylistHint = true) } }
        }.getOrDefault(emptyList())
        if (playlistAttempt.isNotEmpty()) return@withContext playlistAttempt

        val albumResp = executeConfiguredOrPath(
            operation = OperationType.SEARCH,
            template = cfg?.routes?.searchAlbums,
            variables = mapOf("query" to "top charts", "page" to "1", "limit" to "12"),
            fallbackPath = "/search/albums?query=${Uri.encode("top charts")}&page=1&limit=12"
        )
        extractResultsArray(albumResp.body, cfg?.mapping?.resultsPath)
            .mapNotNull { it.asObjectOrNull()?.let { obj -> parseAlbumItem(obj) } }
    }

    suspend fun fetchSongSuggestions(songId: String, limit: Int = 10): List<OnlineTrack> =
        withContext(Dispatchers.IO) {
            val safeSourceId = InputAndUrlSecurity.sanitizePathSegment(songId)
                ?: return@withContext emptyList()
            val cfg = currentConfig()
            val boundedLimit = limit.coerceIn(1, 30)
            val sugResp = executeRawWithFallback(
                OperationType.METADATA,
                "/songs/${Uri.encode(safeSourceId)}/suggestions?limit=$boundedLimit"
            )
            extractResultsArray(sugResp.body, cfg?.mapping?.resultsPath)
                .mapNotNull { it.asObjectOrNull()?.let(::parseSongItem) }
        }

    override suspend fun fetchRelatedTracks(track: OnlineTrack, limit: Int): List<OnlineTrack> {
        if (track.providerType != OnlineProviderType.JIOSAAVN &&
            !track.effectiveProviderId().equals(providerId, ignoreCase = true)
        ) {
            return emptyList()
        }
        return fetchSongSuggestions(track.sourceId, limit)
    }

    override suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution = resolveStream(track, speedTier, null)

    override suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier,
        preferredQuality: StreamingQuality?
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
        val effectiveTier = if (preferredQuality == null && speedTier == NetworkSpeedTier.STANDARD) {
            NetworkSpeedTier.FAST_UNMETERED
        } else {
            speedTier
        }
        val selected = StreamQualitySelector.selectBestAudioStream(
            streams = streams,
            speedTier = effectiveTier,
            allowedLoopbackOrigin = allowedOrigin,
            preferredQuality = preferredQuality
        ) ?: throw IOException("No playable audio streams returned by $displayName for ${track.title}")

        val related = runCatching {
            fetchSongSuggestions(safeSourceId, 10)
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
        val topSongs = dataObj?.objArray("topSongs").orEmpty()
            .mapNotNull { it.asObjectOrNull()?.let(::parseSongItem) }
        val topAlbums = dataObj?.objArray("topAlbums").orEmpty()
            .mapNotNull { it.asObjectOrNull()?.let(::parseAlbumItem) }

        OnlineArtistPage(
            id = id,
            name = decodeHtmlEntities(dataObj?.str("name") ?: dataObj?.str("title") ?: "Artist"),
            url = dataObj?.strUrl("url") ?: artistIdOrUrl,
            avatarUrl = pickBestImageUrl(dataObj?.get("image")),
            subscriberCountText = dataObj?.str("followerCount") ?: "",
            description = "",
            tracks = topSongs,
            playlists = topAlbums,
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = providerId
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage =
        fetchAlbumOrPlaylist(albumIdOrUrl, isPlaylist = false)

    override suspend fun fetchAlbumOrPlaylist(
        albumIdOrUrl: String,
        isPlaylist: Boolean
    ): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val trimmedInput = albumIdOrUrl.trim()
        val rawId = extractCollectionId(trimmedInput)
        val id = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid JioSaavn collection ID")
        val cfg = currentConfig()
        val encodedId = Uri.encode(id)
        val isFullUrl = trimmedInput.startsWith("https://", ignoreCase = true) ||
            trimmedInput.startsWith("http://", ignoreCase = true)
        val preferPlaylist = isPlaylist ||
            trimmedInput.contains("/featured/", ignoreCase = true) ||
            trimmedInput.contains("/playlist", ignoreCase = true) ||
            trimmedInput.contains("playlistId=", ignoreCase = true) ||
            trimmedInput.contains("list=", ignoreCase = true)

        val variables = buildMap {
            put("id", id)
            put("albumId", id)
            put("playlistId", id)
            put("listId", id)
            put("browseId", id)
            put("sourceId", id)
            if (isFullUrl) {
                put("url", trimmedInput)
                put("link", trimmedInput)
            }
        }

        val attempts = buildList<suspend () -> ExecutedHttpResponse> {
            val playlistRoute = cfg?.routes?.playlistDetails
            val albumRoute = cfg?.routes?.albumDetails
            val addPlaylistAttempt = {
                add {
                    if (playlistRoute != null && cfg != null) {
                        executeConfiguredOrPath(
                            operation = OperationType.METADATA,
                            template = playlistRoute,
                            variables = variables,
                            fallbackPath = "/playlists?id=$encodedId"
                        )
                    } else {
                        executeRawWithFallback(OperationType.METADATA, "/playlists?id=$encodedId")
                    }
                }
            }
            val addAlbumAttempt = {
                add {
                    if (albumRoute != null && cfg != null) {
                        executeConfiguredOrPath(
                            operation = OperationType.METADATA,
                            template = albumRoute,
                            variables = variables,
                            fallbackPath = "/albums?id=$encodedId"
                        )
                    } else {
                        executeRawWithFallback(OperationType.METADATA, "/albums?id=$encodedId")
                    }
                }
            }

            if (preferPlaylist) {
                addPlaylistAttempt()
                if (isFullUrl) {
                    add { executeRawWithFallback(OperationType.METADATA, "/playlists?link=${Uri.encode(trimmedInput)}") }
                }
                addAlbumAttempt()
            } else {
                addAlbumAttempt()
                if (isFullUrl) {
                    add { executeRawWithFallback(OperationType.METADATA, "/albums?link=${Uri.encode(trimmedInput)}") }
                }
                addPlaylistAttempt()
            }

            if (baseUrlsOverride == null) {
                val hasNoApiSuffix = getEndpointConfigs(OperationType.METADATA)
                    .none { it.url.trimEnd('/').endsWith("/api", ignoreCase = true) }
                if (hasNoApiSuffix) {
                    if (preferPlaylist && playlistRoute == null) {
                        add { executeRawWithFallback(OperationType.METADATA, "/api/playlists?id=$encodedId") }
                    } else if (!preferPlaylist && albumRoute == null) {
                        add { executeRawWithFallback(OperationType.METADATA, "/api/albums?id=$encodedId") }
                    }
                }
            }
        }

        var firstParsedPage: OnlineAlbumPage? = null
        var firstError: Throwable? = null
        for (attempt in attempts) {
            val respResult = runCatching { attempt() }
            val resp = respResult.getOrNull()
            if (resp == null) {
                if (firstError == null) firstError = respResult.exceptionOrNull()
                continue
            }
            val page = parseCollectionResponse(
                body = resp.body,
                requestedId = id,
                fallbackUrl = trimmedInput,
                configuredResultsPath = cfg?.mapping?.resultsPath,
                isPlaylist = preferPlaylist
            )
            if (page.tracks.isNotEmpty()) {
                return@withContext page
            }
            if (firstParsedPage == null && (extractDataObject(resp.body) != null || page.title != "Album" && page.title != "Playlist")) {
                firstParsedPage = page
            }
        }

        firstParsedPage ?: throw (firstError ?: IllegalStateException("Could not load JioSaavn collection $id"))
    }

    private fun extractCollectionId(albumIdOrUrl: String): String {
        val trimmed = albumIdOrUrl.trim()
        if (!trimmed.contains('/') && !trimmed.contains('?') && !trimmed.contains('=')) {
            return trimmed
        }
        runCatching {
            val normalized = if (trimmed.startsWith("http", ignoreCase = true)) {
                trimmed
            } else {
                "https://placeholder.local/${trimmed.trimStart('/')}"
            }
            val uri = Uri.parse(normalized)
            listOf("id", "playlistId", "albumId", "list", "listId", "browseId").forEach { key ->
                uri.getQueryParameter(key)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return trimmed.trimEnd('/').substringAfterLast("/").substringBefore("?").substringBefore("#")
    }

    private fun parseCollectionResponse(
        body: String,
        requestedId: String,
        fallbackUrl: String,
        configuredResultsPath: String?,
        isPlaylist: Boolean
    ): OnlineAlbumPage {
        val dataObj = extractDataObject(body)
        val songsArr = (dataObj?.objArray("songs")
            ?: dataObj?.objArray("list")
            ?: dataObj?.objArray("tracks"))
            ?: extractResultsArray(body, configuredResultsPath)
        val songs = songsArr.mapNotNull { it.asObjectOrNull()?.let(::parseSongItem) }
        val resolvedTypePlaylist = isPlaylist ||
            dataObj?.str("type").equals("playlist", ignoreCase = true)

        return OnlineAlbumPage(
            id = dataObj?.str("id")?.takeIf { it.isNotBlank() } ?: requestedId,
            title = decodeHtmlEntities(
                dataObj?.str("name")
                    ?: dataObj?.str("title")
                    ?: dataObj?.str("listname")
                    ?: if (resolvedTypePlaylist) "Playlist" else "Album"
            ),
            artist = decodeHtmlEntities(
                extractPrimaryArtists(dataObj)
                    .ifBlank { dataObj?.str("subtitle").orEmpty() }
                    .ifBlank { displayName }
            ),
            url = dataObj?.strUrl("url") ?: dataObj?.strUrl("perma_url") ?: fallbackUrl,
            thumbnailUrl = pickBestImageUrl(dataObj?.get("image")) ?: songs.firstOrNull()?.thumbnailUrl,
            description = decodeHtmlEntities(dataObj?.str("description") ?: dataObj?.str("subtitle").orEmpty()),
            tracks = songs,
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = providerId,
            isPlaylist = resolvedTypePlaylist
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
        val resp = if (template != null && cfg != null) {
            executor.executeTemplated(
                config = cfg,
                operation = operation,
                template = template,
                variables = variables,
                endpointOverride = baseUrlsOverride
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
        val root = json.parseElementOrNull(body) ?: return JsonArray(emptyList())
        if (!configuredPath.isNullOrBlank()) {
            val extracted = JsonPathExtractor.extractArray(root, configuredPath)
            if (extracted.isNotEmpty()) return JsonArray(extracted)
        }
        if (root is JsonArray) return root
        val obj = root.asObjectOrNull() ?: return JsonArray(emptyList())
        val data = obj["data"]
        return when (data) {
            is JsonArray -> data
            is JsonObject -> data.objArray("results")
                ?: data.objArray("songs")
                ?: data.objArray("list")
                ?: JsonArray(emptyList())
            else -> obj.objArray("results")
                ?: obj.objArray("songs")
                ?: obj.objArray("list")
                ?: JsonArray(emptyList())
        }
    }

    private fun extractDataObject(body: String): JsonObject? {
        val root = json.parseElementOrNull(body) ?: return null
        val obj = root.asObjectOrNull() ?: return (root as? JsonArray)?.firstOrNull().asObjectOrNull()
        return when (val data = obj["data"]) {
            is JsonObject -> data
            is JsonArray -> data.firstOrNull().asObjectOrNull()
            is JsonNull -> null
            else -> obj
        }
    }

    private fun extractSongObject(body: String): JsonObject? {
        val root = json.parseObjectOrNull(body) ?: return null
        val data = root["data"]
        return when (data) {
            is JsonArray -> data.firstOrNull().asObjectOrNull()
            is JsonObject -> data.objArray("results")?.firstOrNull().asObjectOrNull() ?: data
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

    private fun parseAlbumItem(obj: JsonObject, isPlaylistHint: Boolean = false): OnlineAlbumSummary? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val url = obj.str("url") ?: obj.str("perma_url") ?: id
        val typeStr = obj.str("type").orEmpty()
        val isPlaylist = isPlaylistHint ||
            typeStr.equals("playlist", ignoreCase = true) ||
            url.contains("/featured/", ignoreCase = true) ||
            url.contains("/playlist", ignoreCase = true)
        return OnlineAlbumSummary(
            id = id,
            title = decodeHtmlEntities(
                obj.str("name") ?: obj.str("title") ?: obj.str("listname") ?: if (isPlaylist) "Playlist" else "Album"
            ),
            artist = decodeHtmlEntities(
                extractPrimaryArtists(obj).ifBlank { obj.str("subtitle").orEmpty() }.ifBlank { displayName }
            ),
            url = url,
            thumbnailUrl = pickBestImageUrl(obj["image"]),
            trackCount = obj.int("songCount") ?: obj.int("list_count") ?: 0,
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = providerId,
            isPlaylist = isPlaylist
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
        val arr = el.asArrayOrNull() ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item.asObjectOrNull() ?: return@mapNotNull null
            val rawUrl = obj.strUrl("url") ?: obj.strUrl("link")
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
        val artistsObj = obj.objObject("artists")
        val primaryArr = artistsObj?.objArray("primary") ?: obj.objArray("primaryArtists")
        if (primaryArr != null && primaryArr.isNotEmpty()) {
            val names = primaryArr.mapNotNull { item ->
                (item.asObjectOrNull()?.str("name") ?: item.extractText())?.takeIf { it.isNotBlank() }
            }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return obj.str("primaryArtists")
            ?: obj.str("artists")
            ?: obj.str("subtitle")
            ?: ""
    }

    private fun pickBestImageUrl(el: JsonElement?): String? {
        val raw = when (el) {
            is JsonArray -> {
                val objects = el.mapNotNull { it.asObjectOrNull() }
                val preferred = objects.firstOrNull {
                    val q = it.str("quality").orEmpty()
                    q.contains("500") || q.contains("high", ignoreCase = true)
                } ?: objects.lastOrNull()
                preferred?.let { it.strUrl("url") ?: it.strUrl("link") }
                    ?: el.lastOrNull()?.extractUrlString()
            }
            is JsonObject -> el.strUrl("url") ?: el.strUrl("link") ?: el.extractUrlString()
            else -> el.extractUrlString()
        } ?: return null

        val upgraded = raw
            .replace("150x150", "500x500")
            .replace("50x50", "500x500")
            .replace("_150.", "_500.")
            .replace("_50.", "_500.")
        val allowedOrigin = upgraded.takeIf { baseUrlsOverride != null }
        return InputAndUrlSecurity.validateRemoteHttpsUrl(upgraded, allowedOrigin)
    }

    private fun parseFlexibleLong(el: JsonElement?): Long =
        el.extractLongSafe() ?: 0L

    private fun decodeHtmlEntities(input: String): String =
        JsonPathExtractor.decodeHtmlEntities(input).trim()
}
