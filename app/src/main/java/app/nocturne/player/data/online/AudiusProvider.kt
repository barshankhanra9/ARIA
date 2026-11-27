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
import app.nocturne.player.data.model.StreamingQuality
import app.nocturne.player.data.model.ProviderEndpointConfig
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.IOException

class AudiusProvider(
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val dynamicBaseUrlsProvider: (() -> List<String>)? = null,
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    private val dynamicConfigProvider: (() -> DynamicProviderConfig?)? = null,
    executorOverride: DynamicHttpExecutor? = null
) : OnlineMusicProvider {

    override val providerType: OnlineProviderType = OnlineProviderType.AUDIUS
    override val providerId: String
        get() = currentConfig()?.id ?: "audius"
    override val displayName: String
        get() = currentConfig()?.displayName ?: OnlineProviderType.AUDIUS.displayName
    override val supportsPagination: Boolean = false

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
        val encodedQ = Uri.encode(InputAndUrlSecurity.sanitizeSearchQuery(query))
        when (filter) {
            OnlineSearchFilter.ARTISTS -> {
                val resp = executeRawWithFallback(OperationType.SEARCH, "/v1/users/search?query=$encodedQ&app_name=$APP_NAME")
                val data = json.parseObjectOrNull(resp.body)?.objArray("data").orEmpty()
                val artists = data.mapNotNull { it.asObjectOrNull()?.let(::parseUserItem) }
                OnlineSearchPage(
                    query = query,
                    artists = artists,
                    providerUsed = OnlineProviderType.AUDIUS,
                    providerUsedId = providerId,
                    providerUsedLabel = displayName
                )
            }
            OnlineSearchFilter.ALBUMS -> {
                val resp = executeRawWithFallback(OperationType.SEARCH, "/v1/playlists/search?query=$encodedQ&app_name=$APP_NAME")
                val data = json.parseObjectOrNull(resp.body)?.objArray("data").orEmpty()
                val albums = data.mapNotNull { it.asObjectOrNull()?.let(::parsePlaylistItem) }
                OnlineSearchPage(
                    query = query,
                    albums = albums,
                    providerUsed = OnlineProviderType.AUDIUS,
                    providerUsedId = providerId,
                    providerUsedLabel = displayName
                )
            }
            else -> {
                val resp = executeRawWithFallback(OperationType.SEARCH, "/v1/tracks/search?query=$encodedQ&app_name=$APP_NAME")
                val data = json.parseObjectOrNull(resp.body)?.objArray("data").orEmpty()
                val tracks = data.mapNotNull { it.asObjectOrNull()?.let(::parseTrackItem) }
                OnlineSearchPage(
                    query = query,
                    tracks = tracks,
                    providerUsed = OnlineProviderType.AUDIUS,
                    providerUsedId = providerId,
                    providerUsedLabel = displayName
                )
            }
        }
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (safeQuery.isBlank()) return@withContext emptyList()
        val resp = executeRawWithFallback(OperationType.SEARCH, "/v1/tracks/search?query=${Uri.encode(safeQuery)}&app_name=$APP_NAME")
        val data = json.parseObjectOrNull(resp.body)?.objArray("data").orEmpty()
        data.mapNotNull { it.asObjectOrNull()?.str("title") }.distinct().take(8)
    }

    override suspend fun trending(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val resp = executeRawWithFallback(OperationType.SEARCH, "/v1/tracks/trending?app_name=$APP_NAME")
        val data = json.parseObjectOrNull(resp.body)?.objArray("data").orEmpty()
        data.mapNotNull { it.asObjectOrNull()?.let(::parseTrackItem) }
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
            ?: throw IllegalArgumentException("Invalid Audius track ID")
        val endpoints = getEndpointConfigs(OperationType.PLAYBACK)
        if (endpoints.isEmpty()) throw IOException("No valid $displayName endpoints configured")
        val trackId = Uri.encode(safeSourceId)

        var resolvedTrack = track
        val infoResp = runCatching {
            executeRawWithFallback(OperationType.PLAYBACK, "/v1/tracks/$trackId?app_name=$APP_NAME")
        }.getOrNull()

        val activeHost = infoResp?.endpointUrl
            ?: healthTracker.selectOrderedEndpoints(providerId, OperationType.PLAYBACK, endpoints.map { it.url }).first()

        if (infoResp != null) {
            runCatching {
                val dataObj = json.parseObjectOrNull(infoResp.body)?.objObject("data")
                if (dataObj != null) {
                    parseTrackItem(dataObj)?.let { updated ->
                        resolvedTrack = updated.copy(
                            isFavorite = track.isFavorite,
                            playCount = track.playCount,
                            lastPlayedAt = track.lastPlayedAt,
                            totalListenMs = track.totalListenMs
                        )
                    }
                }
            }
        }

        val allowedTestOrigin = activeHost.takeIf { baseUrlsOverride != null }
        val streamUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(
            "$activeHost/v1/tracks/$trackId/stream?app_name=$APP_NAME",
            allowedLoopbackOrigin = allowedTestOrigin
        ) ?: throw IOException("Blocked unsafe Audius stream URL")

        val stream = OnlineAudioStream(
            url = streamUrl,
            bitrateKbps = 320,
            mimeType = "audio/mpeg",
            codec = "mp3",
            formatLabel = "MP3 320k",
            expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60_000L,
            headers = mapOf("User-Agent" to OnlineHttpClient.USER_AGENT)
        )
        val selected = StreamQualitySelector.selectBestAudioStream(
            streams = listOf(stream),
            speedTier = speedTier,
            allowedLoopbackOrigin = allowedTestOrigin,
            preferredQuality = preferredQuality
        ) ?: throw IOException("No valid Audius stream available")

        OnlineStreamResolution(
            track = resolvedTrack,
            streams = listOf(selected),
            selectedStream = selected,
            relatedTracks = emptyList(),
            resolvedBy = OnlineProviderType.AUDIUS,
            resolvedByProviderId = providerId,
            resolvedByLabel = displayName
        )
    }

    override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage = withContext(Dispatchers.IO) {
        val rawId = artistIdOrUrl.substringAfterLast("/").substringBefore("?")
        val userId = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid Audius artist ID")
        val encodedUserId = Uri.encode(userId)
        val tracksResp = executeRawWithFallback(OperationType.METADATA, "/v1/users/$encodedUserId/tracks?app_name=$APP_NAME")
        val tracksData = json.parseObjectOrNull(tracksResp.body)?.objArray("data").orEmpty()
        val tracks = tracksData.mapNotNull { it.asObjectOrNull()?.let(::parseTrackItem) }
        val userObj = runCatching {
            val uResp = executeRawWithFallback(OperationType.METADATA, "/v1/users/$encodedUserId?app_name=$APP_NAME")
            json.parseObjectOrNull(uResp.body)?.objObject("data")
        }.getOrNull()

        OnlineArtistPage(
            id = userId,
            name = userObj?.str("name") ?: tracks.firstOrNull()?.artist ?: "Audius Artist",
            url = "https://audius.co/${Uri.encode(userObj?.str("handle") ?: userId)}",
            avatarUrl = pickArtwork(userObj?.objObject("profile_picture")),
            subscriberCountText = userObj?.long("follower_count")?.takeIf { it > 0L }?.let { "${it / 1000}K" }.orEmpty(),
            description = userObj?.str("bio").orEmpty(),
            tracks = tracks,
            playlists = emptyList(),
            providerType = OnlineProviderType.AUDIUS,
            providerId = providerId
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage =
        fetchAlbumOrPlaylist(albumIdOrUrl, isPlaylist = true)

    override suspend fun fetchAlbumOrPlaylist(
        albumIdOrUrl: String,
        isPlaylist: Boolean
    ): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val rawId = albumIdOrUrl.trim().trimEnd('/').substringAfterLast("/").substringBefore("?")
        val plId = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid Audius playlist ID")
        val cfg = currentConfig()
        val template = cfg?.routes?.playlistDetails ?: cfg?.routes?.albumDetails
        val resp = if (template != null && cfg != null) {
            val r = executor.executeTemplated(
                config = cfg,
                operation = OperationType.METADATA,
                template = template,
                variables = mapOf(
                    "id" to plId,
                    "playlistId" to plId,
                    "albumId" to plId,
                    "listId" to plId,
                    "browseId" to plId,
                    "url" to albumIdOrUrl
                ),
                endpointOverride = baseUrlsOverride
            )
            lastWorkingInstance = r.endpointUrl
            r
        } else {
            executeRawWithFallback(OperationType.METADATA, "/v1/playlists/${Uri.encode(plId)}/tracks?app_name=$APP_NAME")
        }
        val rootObj = json.parseObjectOrNull(resp.body)
        val dataEl = rootObj?.get("data")
        val dataObj = dataEl.asObjectOrNull() ?: (dataEl.asArrayOrNull()?.firstOrNull().asObjectOrNull())
        val tracksArr = when (dataEl) {
            is JsonArray -> dataEl
            is JsonObject -> dataEl.objArray("tracks") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val tracks = tracksArr.mapNotNull { it.asObjectOrNull()?.let(::parseTrackItem) }
        OnlineAlbumPage(
            id = plId,
            title = dataObj?.str("playlist_name") ?: dataObj?.str("name") ?: "$displayName Collection",
            artist = dataObj?.objObject("user")?.str("name") ?: tracks.firstOrNull()?.artist ?: displayName,
            url = albumIdOrUrl,
            thumbnailUrl = pickArtwork(dataObj?.objObject("artwork")) ?: tracks.firstOrNull()?.thumbnailUrl,
            description = dataObj?.str("description").orEmpty(),
            tracks = tracks,
            providerType = OnlineProviderType.AUDIUS,
            providerId = providerId,
            isPlaylist = true
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

    private fun parseTrackItem(obj: JsonObject): OnlineTrack? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val isStreamable = obj.bool("is_streamable") ?: true
        if (!isStreamable) return null
        val title = obj.str("title")?.takeIf { it.isNotBlank() } ?: return null
        val user = obj["user"] as? JsonObject
        val artist = user?.str("name")?.takeIf { it.isNotBlank() } ?: "Audius Artist"
        val userId = user?.str("id")
        val handle = user?.str("handle")
        val durationSec = obj.long("duration") ?: 0L
        val permalink = obj.str("permalink")
        val sourceUrl = if (!permalink.isNullOrBlank()) {
            if (permalink.startsWith("http")) permalink else "https://audius.co$permalink"
        } else {
            "https://audius.co/tracks/$id"
        }

        return OnlineTrack(
            id = OnlineTrack.canonicalId(providerId, id),
            sourceId = id,
            providerType = OnlineProviderType.AUDIUS,
            providerLabel = displayName,
            sourceUrl = sourceUrl,
            title = title,
            artist = artist,
            artistId = userId,
            artistUrl = handle?.let { "https://audius.co/$it" } ?: userId,
            album = obj.str("genre").orEmpty().ifBlank { displayName },
            durationMs = if (durationSec > 0L) durationSec * 1000L else 0L,
            thumbnailUrl = pickArtwork(obj["artwork"] as? JsonObject),
            viewCount = obj.long("play_count") ?: 0L,
            publishedText = obj.str("release_date").orEmpty(),
            isLive = false,
            providerId = providerId
        )
    }

    private fun parseUserItem(obj: JsonObject): OnlineArtistSummary? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val handle = obj.str("handle")
        return OnlineArtistSummary(
            id = id,
            name = obj.str("name") ?: handle ?: "Artist",
            url = handle?.let { "https://audius.co/$it" } ?: id,
            thumbnailUrl = pickArtwork(obj["profile_picture"] as? JsonObject),
            subscriberText = obj.long("follower_count")?.takeIf { it > 0L }?.let { "${it / 1000}K" }.orEmpty(),
            providerType = OnlineProviderType.AUDIUS,
            providerId = providerId
        )
    }

    private fun parsePlaylistItem(obj: JsonObject): OnlineAlbumSummary? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val user = obj["user"] as? JsonObject
        return OnlineAlbumSummary(
            id = id,
            title = obj.str("playlist_name") ?: obj.str("name") ?: "Playlist",
            artist = user?.str("name") ?: displayName,
            url = obj.str("permalink")?.let { if (it.startsWith("http")) it else "https://audius.co$it" } ?: id,
            thumbnailUrl = pickArtwork(obj["artwork"] as? JsonObject),
            trackCount = obj.int("track_count") ?: 0,
            providerType = OnlineProviderType.AUDIUS,
            providerId = providerId,
            isPlaylist = true
        )
    }

    private fun pickArtwork(art: JsonObject?): String? {
        if (art == null) return null
        val raw = art.str("480x480") ?: art.str("1000x1000") ?: art.str("150x150")
        return InputAndUrlSecurity.validateRemoteHttpsUrl(raw)
    }

    companion object {
        private const val APP_NAME = "ARIA"
    }
}
