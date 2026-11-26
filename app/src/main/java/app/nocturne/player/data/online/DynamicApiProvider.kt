package app.nocturne.player.data.online

import android.net.Uri
import android.util.LruCache
import app.nocturne.player.data.model.DynamicProviderConfig
import app.nocturne.player.data.model.EndpointOperationTemplate
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
import app.nocturne.player.data.model.PaginationRule
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException

class DynamicApiProvider(
    private val configProvider: () -> DynamicProviderConfig,
    private val executor: DynamicHttpExecutor,
    private val healthTracker: ProviderHealthTracker,
    private val allowLoopbackForTests: Boolean = false
) : OnlineMusicProvider {

    private val cfg: DynamicProviderConfig
        get() = configProvider()

    override val providerType: OnlineProviderType
        get() = OnlineProviderType.fromProviderId(cfg.id)

    override val providerId: String
        get() = cfg.id

    override val displayName: String
        get() = cfg.displayName

    override val supportsPagination: Boolean
        get() {
            val route = cfg.routes.searchSongs ?: cfg.routes.searchAll
            val pType = route?.pagination?.type?.lowercase().orEmpty()
            return pType.isNotBlank() && pType != "none"
        }

    private val parsedStreamsCache = LruCache<String, List<OnlineAudioStream>>(160)

    override fun supportsCapability(capability: String): Boolean = cfg.supportsCapability(capability)

    override fun activeEndpointFor(operation: OperationType): String? =
        healthTracker.activeEndpointFor(cfg.id, operation)

    override fun currentConfig(): DynamicProviderConfig = cfg

    override suspend fun search(
        query: String,
        filter: OnlineSearchFilter,
        pageToken: String?
    ): OnlineSearchPage = withContext(Dispatchers.IO) {
        val currentCfg = cfg
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        val template = when (filter) {
            OnlineSearchFilter.ARTISTS -> currentCfg.routes.searchArtists ?: currentCfg.routes.searchAll
            OnlineSearchFilter.ALBUMS -> currentCfg.routes.searchAlbums ?: currentCfg.routes.searchAll
            OnlineSearchFilter.SONGS -> currentCfg.routes.searchSongs ?: currentCfg.routes.searchAll
            else -> currentCfg.routes.searchAll ?: currentCfg.routes.searchSongs
        } ?: throw IOException("${currentCfg.displayName} has no search route configured")

        val pagination = template.pagination ?: PaginationRule()
        val vars = buildPaginationVars(safeQuery, pageToken, pagination)

        val response = executor.executeTemplated(
            config = currentCfg,
            operation = OperationType.SEARCH,
            template = template,
            variables = vars
        )
        val root = executor.parseJson(response.body)
        val resultsPath = template.resultsPath ?: currentCfg.mapping.resultsPath
        val items = JsonPathExtractor.extractArray(root, resultsPath)

        val tracks = mutableListOf<OnlineTrack>()
        val artists = mutableListOf<OnlineArtistSummary>()
        val albums = mutableListOf<OnlineAlbumSummary>()

        when (filter) {
            OnlineSearchFilter.ARTISTS -> {
                items.mapNotNull { parseArtistSummary(it, currentCfg) }.let { artists.addAll(it) }
            }
            OnlineSearchFilter.ALBUMS -> {
                items.mapNotNull { parseAlbumSummary(it, currentCfg) }.let { albums.addAll(it) }
            }
            else -> {
                items.mapNotNull { parseTrackItem(it, currentCfg, response.endpointUrl) }.let { tracks.addAll(it) }
            }
        }

        val itemCount = maxOf(tracks.size, artists.size, albums.size)
        val nextToken = computeNextPageToken(root, currentCfg, pagination, pageToken, itemCount)

        OnlineSearchPage(
            query = query,
            tracks = tracks,
            artists = artists,
            albums = albums,
            nextPageToken = nextToken,
            hasMore = nextToken != null,
            providerUsed = OnlineProviderType.fromProviderId(currentCfg.id),
            providerUsedId = currentCfg.id,
            providerUsedLabel = currentCfg.displayName
        )
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val currentCfg = cfg
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (safeQuery.isBlank()) return@withContext emptyList()
        val template = currentCfg.routes.suggestions ?: return@withContext emptyList()
        val response = executor.executeTemplated(
            config = currentCfg,
            operation = OperationType.SEARCH,
            template = template,
            variables = mapOf("query" to safeQuery, "limit" to "8")
        )
        val root = executor.parseJson(response.body)
        val path = template.resultsPath ?: currentCfg.mapping.suggestionsPath ?: currentCfg.mapping.resultsPath
        val arr = JsonPathExtractor.extractArray(root, path)
        val itemField = currentCfg.mapping.suggestionsItemField
        arr.mapNotNull { el ->
            val raw = if (!itemField.isNullOrBlank()) {
                JsonPathExtractor.extractString(el, itemField)
            } else {
                JsonPathExtractor.extractString(el, "$|title|name")
            }
            raw?.let { maybeDecodeHtml(it, currentCfg.mapping.decodeHtmlEntities) }?.trim()?.takeIf { it.isNotBlank() }
        }.distinct().take(8)
    }

    override suspend fun trending(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val currentCfg = cfg
        val template = currentCfg.routes.trending
            ?: currentCfg.routes.searchSongs
            ?: currentCfg.routes.searchAll
            ?: throw IOException("${currentCfg.displayName} has no trending or search route configured")

        val response = executor.executeTemplated(
            config = currentCfg,
            operation = OperationType.SEARCH,
            template = template,
            variables = mapOf("query" to "trending songs", "page" to "1", "offset" to "0", "limit" to "20")
        )
        val root = executor.parseJson(response.body)
        val resultsPath = template.resultsPath ?: currentCfg.mapping.resultsPath
        JsonPathExtractor.extractArray(root, resultsPath)
            .mapNotNull { parseTrackItem(it, currentCfg, response.endpointUrl) }
    }

    override suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution = withContext(Dispatchers.IO) {
        val currentCfg = cfg
        val safeId = InputAndUrlSecurity.sanitizePathSegment(track.sourceId)
            ?: throw IllegalArgumentException("Invalid track ID for ${currentCfg.displayName}")

        var resolvedTrack = track
        val cachedKey = "${currentCfg.id}:$safeId"
        var streams = parsedStreamsCache.get(cachedKey).orEmpty()
        var lastEndpointUsed: String? = null
        val defaultLoopbackAnchor = currentCfg.endpointsFor(OperationType.PLAYBACK).firstOrNull()?.url
            ?: currentCfg.endpoints.firstOrNull()?.url

        if (streams.isNotEmpty()) {
            val preAllowedOrigin = (defaultLoopbackAnchor ?: streams.firstOrNull()?.url)?.takeIf { allowLoopbackForTests }
            val preSelected = StreamQualitySelector.selectBestAudioStream(
                streams = streams,
                speedTier = speedTier,
                allowedLoopbackOrigin = preAllowedOrigin
            )
            val inlineUsable = if (preSelected == null) {
                false
            } else if (currentCfg.verifyStreamReachable) {
                executor.probeUrlReachable(preSelected.url, currentCfg.headers)
            } else {
                true
            }
            if (!inlineUsable) {
                parsedStreamsCache.remove(cachedKey)
                streams = emptyList()
            }
        }

        val resolveTemplate = currentCfg.routes.resolveStream ?: currentCfg.routes.trackDetails
        if (streams.isEmpty() && resolveTemplate != null) {
            val response = executor.executeTemplated(
                config = currentCfg,
                operation = OperationType.PLAYBACK,
                template = resolveTemplate,
                variables = mapOf(
                    "id" to safeId,
                    "trackId" to safeId,
                    "sourceId" to safeId
                )
            )
            lastEndpointUsed = response.endpointUrl
            val root = executor.parseJson(response.body)
            val sMap = currentCfg.mapping.streamResolution
            val itemEl = sMap.itemPath?.let { JsonPathExtractor.extractElement(root, it) }
                ?: currentCfg.mapping.itemPath?.let { JsonPathExtractor.extractElement(root, it) }
                ?: root

            val parsedStreams = extractStreamsFromElement(
                el = itemEl,
                baseUrl = response.endpointUrl,
                streamUrlPath = sMap.streamUrlPath,
                streamUrlTemplate = sMap.streamUrlTemplate,
                streamsArrayPath = sMap.streamsArrayPath,
                itemUrlPath = sMap.streamItemUrlPath,
                bitratePath = sMap.streamItemBitratePath,
                bitrateUnit = sMap.streamItemBitrateUnit,
                qualityPath = sMap.streamItemQualityPath,
                mimePath = sMap.streamItemMimePath,
                codecPath = sMap.streamItemCodecPath,
                vars = mapOf("id" to safeId, "trackId" to safeId, "endpoint" to response.endpointUrl)
            )
            streams = parsedStreams

            val title = sMap.titlePath?.let { JsonPathExtractor.extractString(itemEl, it) }
                ?.let { maybeDecodeHtml(it, currentCfg.mapping.decodeHtmlEntities) }
                ?: track.title
            val artist = sMap.artistPath?.let { JsonPathExtractor.extractString(itemEl, it) }
                ?.let { maybeDecodeHtml(it, currentCfg.mapping.decodeHtmlEntities) }
                ?: track.artist
            val durRaw = sMap.durationPath?.let { JsonPathExtractor.extractLong(itemEl, it) }
            val durationMs = if (durRaw != null && durRaw > 0L) {
                if (sMap.durationUnit.equals("ms", ignoreCase = true)) durRaw else durRaw * 1000L
            } else {
                track.durationMs
            }
            val thumb = sMap.thumbnailPath?.let { extractValidUrl(itemEl, it, response.endpointUrl) } ?: track.thumbnailUrl
            resolvedTrack = track.copy(
                title = title,
                artist = artist,
                durationMs = durationMs,
                thumbnailUrl = thumb
            )
        } else if (streams.isEmpty() && !currentCfg.mapping.streamResolution.streamUrlTemplate.isNullOrBlank()) {
            // Template-only stream URL resolution (e.g., "{endpoint}/v1/tracks/{id}/stream")
            val orderedEndpoints = healthTracker.selectOrderedEndpoints(
                providerId = currentCfg.id,
                operation = OperationType.PLAYBACK,
                configuredEndpoints = currentCfg.endpointsFor(OperationType.PLAYBACK).map { it.url }
            )
            val sMap = currentCfg.mapping.streamResolution
            val tpl = sMap.streamUrlTemplate!!
            for (ep in orderedEndpoints) {
                val expanded = tpl
                    .replace("{endpoint}", ep.trimEnd('/'))
                    .replace("{id}", Uri.encode(safeId))
                    .replace("{trackId}", Uri.encode(safeId))
                val allowedOrigin = expanded.takeIf { allowLoopbackForTests }
                val validUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(expanded, allowedOrigin) ?: continue
                if (currentCfg.verifyStreamReachable && !executor.probeUrlReachable(validUrl, currentCfg.headers)) {
                    healthTracker.recordEndpointFailure(
                        providerId = currentCfg.id,
                        operation = OperationType.PLAYBACK,
                        endpointUrl = ep,
                        error = "Stream URL unreachable",
                        customCooldownMs = currentCfg.cooldownMs
                    )
                    continue
                }
                lastEndpointUsed = ep
                streams = listOf(
                    OnlineAudioStream(
                        url = validUrl,
                        bitrateKbps = 192,
                        mimeType = "audio/mpeg",
                        codec = "audio",
                        formatLabel = "STREAM",
                        expiresAtMs = StreamQualitySelector.parseExpiryFromUrl(validUrl),
                        headers = currentCfg.headers
                    )
                )
                healthTracker.recordEndpointSuccess(currentCfg.id, OperationType.PLAYBACK, ep, 100L)
                break
            }
        }

        val allowedOrigin = (lastEndpointUsed ?: defaultLoopbackAnchor ?: streams.firstOrNull()?.url)
            ?.takeIf { allowLoopbackForTests }
        val selected = StreamQualitySelector.selectBestAudioStream(
            streams = streams,
            speedTier = speedTier,
            allowedLoopbackOrigin = allowedOrigin
        ) ?: throw IOException("No playable audio streams returned by ${currentCfg.displayName}")

        if (currentCfg.verifyStreamReachable) {
            val reachable = executor.probeUrlReachable(selected.url, currentCfg.headers)
            if (!reachable) {
                lastEndpointUsed?.let { ep ->
                    healthTracker.recordEndpointFailure(
                        providerId = currentCfg.id,
                        operation = OperationType.PLAYBACK,
                        endpointUrl = ep,
                        error = "Selected stream URL failed reachability check",
                        customCooldownMs = currentCfg.cooldownMs
                    )
                }
                throw IOException("Selected stream URL unreachable from ${currentCfg.displayName}")
            }
        }

        OnlineStreamResolution(
            track = resolvedTrack,
            streams = streams,
            selectedStream = selected,
            relatedTracks = emptyList(),
            resolvedBy = OnlineProviderType.fromProviderId(currentCfg.id),
            resolvedByProviderId = currentCfg.id,
            resolvedByLabel = currentCfg.displayName
        )
    }

    override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage = withContext(Dispatchers.IO) {
        val currentCfg = cfg
        val template = currentCfg.routes.artistDetails
            ?: throw IOException("${currentCfg.displayName} does not support artist details")
        val rawId = artistIdOrUrl.trimEnd('/').substringAfterLast("/").substringBefore("?")
        val safeId = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid artist ID for ${currentCfg.displayName}")

        val response = executor.executeTemplated(
            config = currentCfg,
            operation = OperationType.METADATA,
            template = template,
            variables = mapOf("id" to safeId, "artistId" to safeId)
        )
        val root = executor.parseJson(response.body)
        val itemEl = currentCfg.mapping.itemPath?.let { JsonPathExtractor.extractElement(root, it) } ?: root
        val aMap = currentCfg.mapping.artist
        val decodeHtml = currentCfg.mapping.decodeHtmlEntities

        val tracks = aMap.tracksPath?.let { path ->
            JsonPathExtractor.extractArray(itemEl, path)
                .mapNotNull { parseTrackItem(it, currentCfg, response.endpointUrl) }
        }.orEmpty()
        val albums = aMap.albumsPath?.let { path ->
            JsonPathExtractor.extractArray(itemEl, path)
                .mapNotNull { parseAlbumSummary(it, currentCfg) }
        }.orEmpty()

        OnlineArtistPage(
            id = safeId,
            name = (JsonPathExtractor.extractString(itemEl, aMap.namePath) ?: "Artist")
                .let { maybeDecodeHtml(it, decodeHtml) },
            url = extractValidUrl(itemEl, aMap.urlPath, response.endpointUrl) ?: artistIdOrUrl,
            avatarUrl = extractValidUrl(itemEl, aMap.avatarPath, response.endpointUrl),
            bannerUrl = extractValidUrl(itemEl, aMap.bannerPath, response.endpointUrl),
            subscriberCountText = JsonPathExtractor.extractString(itemEl, aMap.subscriberPath).orEmpty(),
            description = JsonPathExtractor.extractString(itemEl, aMap.descriptionPath)
                ?.let { maybeDecodeHtml(it, decodeHtml) }.orEmpty(),
            tracks = tracks,
            playlists = albums,
            providerType = OnlineProviderType.fromProviderId(currentCfg.id),
            providerId = currentCfg.id,
            providerLabel = currentCfg.displayName
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage =
        fetchAlbumOrPlaylist(albumIdOrUrl, isPlaylist = false)

    override suspend fun fetchAlbumOrPlaylist(
        albumIdOrUrl: String,
        isPlaylist: Boolean
    ): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val currentCfg = cfg
        val trimmedInput = albumIdOrUrl.trim()
        val preferPlaylist = isPlaylist ||
            trimmedInput.contains("playlist", ignoreCase = true) ||
            trimmedInput.contains("list=", ignoreCase = true) ||
            trimmedInput.contains("/browse/", ignoreCase = true)

        val primaryTemplate = if (preferPlaylist) {
            currentCfg.routes.playlistDetails ?: currentCfg.routes.albumDetails
        } else {
            currentCfg.routes.albumDetails ?: currentCfg.routes.playlistDetails
        } ?: throw IOException("${currentCfg.displayName} does not support album or playlist details")

        val fallbackTemplate = if (preferPlaylist) {
            currentCfg.routes.albumDetails?.takeIf { it != primaryTemplate }
        } else {
            currentCfg.routes.playlistDetails?.takeIf { it != primaryTemplate }
        }

        val rawId = extractCollectionId(trimmedInput)
        val safeId = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid album ID for ${currentCfg.displayName}")

        val vars = buildMap {
            put("id", safeId)
            put("albumId", safeId)
            put("playlistId", safeId)
            put("listId", safeId)
            put("browseId", safeId)
            put("sourceId", safeId)
            if (trimmedInput.startsWith("https://", ignoreCase = true) ||
                trimmedInput.startsWith("http://", ignoreCase = true)
            ) {
                put("url", trimmedInput)
                put("link", trimmedInput)
            }
        }

        val templates = listOfNotNull(primaryTemplate, fallbackTemplate)
        var firstPage: OnlineAlbumPage? = null
        var firstError: Throwable? = null

        for (template in templates) {
            val respResult = runCatching {
                executor.executeTemplated(
                    config = currentCfg,
                    operation = OperationType.METADATA,
                    template = template,
                    variables = vars
                )
            }
            val response = respResult.getOrNull()
            if (response == null) {
                if (firstError == null) firstError = respResult.exceptionOrNull()
                continue
            }
            val root = executor.parseJson(response.body)
            val itemEl = currentCfg.mapping.itemPath?.let { JsonPathExtractor.extractElement(root, it) } ?: root
            val alMap = currentCfg.mapping.album
            val decodeHtml = currentCfg.mapping.decodeHtmlEntities

            val tracks = alMap.tracksPath?.let { path ->
                JsonPathExtractor.extractArray(itemEl, path)
                    .mapNotNull { parseTrackItem(it, currentCfg, response.endpointUrl) }
            }.orEmpty()

            val page = OnlineAlbumPage(
                id = JsonPathExtractor.extractString(itemEl, alMap.idPath)?.trim()?.takeIf { it.isNotBlank() } ?: safeId,
                title = (JsonPathExtractor.extractString(itemEl, alMap.titlePath)
                    ?: if (preferPlaylist) "Playlist" else "Album")
                    .let { maybeDecodeHtml(it, decodeHtml) },
                artist = (JsonPathExtractor.extractString(itemEl, alMap.artistPath) ?: currentCfg.displayName)
                    .let { maybeDecodeHtml(it, decodeHtml) },
                url = extractValidUrl(itemEl, alMap.urlPath, response.endpointUrl) ?: trimmedInput,
                thumbnailUrl = extractValidUrl(itemEl, alMap.thumbnailPath, response.endpointUrl)
                    ?: tracks.firstOrNull()?.thumbnailUrl,
                description = JsonPathExtractor.extractString(itemEl, alMap.descriptionPath)
                    ?.let { maybeDecodeHtml(it, decodeHtml) }.orEmpty(),
                tracks = tracks,
                providerType = OnlineProviderType.fromProviderId(currentCfg.id),
                providerId = currentCfg.id,
                providerLabel = currentCfg.displayName,
                isPlaylist = preferPlaylist
            )
            if (page.tracks.isNotEmpty()) {
                return@withContext page
            }
            if (firstPage == null) {
                firstPage = page
            }
        }

        firstPage ?: throw (firstError ?: IOException("Could not load ${currentCfg.displayName} collection $safeId"))
    }

    private fun extractCollectionId(input: String): String {
        val trimmed = input.trim()
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

    private fun parseTrackItem(
        el: JsonElement,
        cfg: DynamicProviderConfig,
        baseUrl: String
    ): OnlineTrack? {
        val tMap = cfg.mapping.track
        val decodeHtml = cfg.mapping.decodeHtmlEntities
        val id = JsonPathExtractor.extractString(el, tMap.idPath)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val title = JsonPathExtractor.extractString(el, tMap.titlePath)?.trim()?.takeIf { it.isNotBlank() }
            ?.let { maybeDecodeHtml(it, decodeHtml) } ?: return null
        val artist = JsonPathExtractor.extractString(el, tMap.artistPath)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { maybeDecodeHtml(it, decodeHtml) }
            ?: "Unknown Artist"

        val durRaw = JsonPathExtractor.extractLong(el, tMap.durationPath) ?: 0L
        val durationMs = if (durRaw > 0L) {
            if (tMap.durationUnit.equals("ms", ignoreCase = true)) durRaw else durRaw * 1000L
        } else 0L

        val sourceUrlRaw = JsonPathExtractor.extractString(el, tMap.sourceUrlPath)
        val sourceUrl = when {
            !sourceUrlRaw.isNullOrBlank() && sourceUrlRaw.startsWith("https://", ignoreCase = true) ->
                InputAndUrlSecurity.validateRemoteHttpsUrl(sourceUrlRaw, sourceUrlRaw.takeIf { allowLoopbackForTests }) ?: ""
            !tMap.sourceUrlPrefix.isNullOrBlank() -> "${tMap.sourceUrlPrefix.trimEnd('/')}/${Uri.encode(id)}"
            else -> "${baseUrl.trimEnd('/')}/track/${Uri.encode(id)}"
        }

        val embeddedStreams = extractStreamsFromElement(
            el = el,
            baseUrl = baseUrl,
            streamUrlPath = tMap.streamUrlPath,
            streamUrlTemplate = null,
            streamsArrayPath = tMap.streamsArrayPath,
            itemUrlPath = tMap.streamItemUrlPath,
            bitratePath = tMap.streamItemBitratePath,
            bitrateUnit = tMap.streamItemBitrateUnit,
            qualityPath = tMap.streamItemQualityPath,
            mimePath = tMap.streamItemMimePath,
            codecPath = tMap.streamItemCodecPath,
            vars = mapOf("id" to id, "endpoint" to baseUrl)
        )
        if (embeddedStreams.isNotEmpty()) {
            parsedStreamsCache.put("${cfg.id}:$id", embeddedStreams)
        }

        return OnlineTrack(
            id = OnlineTrack.canonicalId(cfg.id, id),
            sourceId = id,
            providerType = OnlineProviderType.fromProviderId(cfg.id),
            providerLabel = cfg.displayName,
            sourceUrl = sourceUrl,
            title = title,
            artist = artist,
            artistId = JsonPathExtractor.extractString(el, tMap.artistIdPath),
            artistUrl = extractValidUrl(el, tMap.artistUrlPath, baseUrl),
            album = JsonPathExtractor.extractString(el, tMap.albumPath)
                ?.let { maybeDecodeHtml(it, decodeHtml) }
                ?: cfg.displayName,
            albumUrl = extractValidUrl(el, tMap.albumUrlPath, baseUrl),
            durationMs = durationMs,
            thumbnailUrl = extractValidUrl(el, tMap.thumbnailPath, baseUrl),
            viewCount = JsonPathExtractor.extractLong(el, tMap.viewCountPath) ?: 0L,
            publishedText = JsonPathExtractor.extractString(el, tMap.publishedPath).orEmpty(),
            isLive = JsonPathExtractor.extractBoolean(el, tMap.isLivePath) ?: false,
            providerId = cfg.id
        )
    }

    private fun parseArtistSummary(el: JsonElement, cfg: DynamicProviderConfig): OnlineArtistSummary? {
        val aMap = cfg.mapping.artist
        val decodeHtml = cfg.mapping.decodeHtmlEntities
        val id = JsonPathExtractor.extractString(el, aMap.idPath)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val name = JsonPathExtractor.extractString(el, aMap.namePath)?.let { maybeDecodeHtml(it, decodeHtml) } ?: "Artist"
        return OnlineArtistSummary(
            id = id,
            name = name,
            url = JsonPathExtractor.extractString(el, aMap.urlPath) ?: id,
            thumbnailUrl = extractValidUrl(el, aMap.avatarPath, null),
            subscriberText = JsonPathExtractor.extractString(el, aMap.subscriberPath).orEmpty(),
            providerType = OnlineProviderType.fromProviderId(cfg.id),
            providerId = cfg.id
        )
    }

    private fun parseAlbumSummary(el: JsonElement, cfg: DynamicProviderConfig): OnlineAlbumSummary? {
        val alMap = cfg.mapping.album
        val decodeHtml = cfg.mapping.decodeHtmlEntities
        val id = (JsonPathExtractor.extractString(el, alMap.idPath)
            ?: JsonPathExtractor.extractString(el, "id|albumId|playlistId|browseId"))?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val url = JsonPathExtractor.extractString(el, alMap.urlPath) ?: id
        val typeHint = JsonPathExtractor.extractString(el, "type|kind|itemType").orEmpty()
        val isPlaylist = typeHint.contains("playlist", ignoreCase = true) ||
            url.contains("playlist", ignoreCase = true) ||
            url.contains("list=", ignoreCase = true)
        val title = (JsonPathExtractor.extractString(el, alMap.titlePath)
            ?: JsonPathExtractor.extractString(el, "title|name"))?.let { maybeDecodeHtml(it, decodeHtml) }
            ?: if (isPlaylist) "Playlist" else "Album"
        return OnlineAlbumSummary(
            id = id,
            title = title,
            artist = (JsonPathExtractor.extractString(el, alMap.artistPath)
                ?: JsonPathExtractor.extractString(el, "artist|author"))?.let { maybeDecodeHtml(it, decodeHtml) }
                ?: cfg.displayName,
            url = url,
            thumbnailUrl = extractValidUrl(el, alMap.thumbnailPath, null),
            trackCount = JsonPathExtractor.extractInt(el, alMap.trackCountPath) ?: 0,
            providerType = OnlineProviderType.fromProviderId(cfg.id),
            providerId = cfg.id,
            isPlaylist = isPlaylist
        )
    }

    private fun extractStreamsFromElement(
        el: JsonElement,
        baseUrl: String,
        streamUrlPath: String?,
        streamUrlTemplate: String?,
        streamsArrayPath: String?,
        itemUrlPath: String,
        bitratePath: String?,
        bitrateUnit: String,
        qualityPath: String?,
        mimePath: String?,
        codecPath: String?,
        vars: Map<String, String>
    ): List<OnlineAudioStream> {
        val allowedOrigin = baseUrl.takeIf { allowLoopbackForTests }
        val result = mutableListOf<OnlineAudioStream>()

        if (!streamsArrayPath.isNullOrBlank()) {
            val arr = JsonPathExtractor.extractArray(el, streamsArrayPath)
            arr.forEach { stEl ->
                val rawUrl = JsonPathExtractor.extractString(stEl, itemUrlPath) ?: return@forEach
                val normalized = normalizeUrlCandidate(rawUrl, baseUrl)
                val safeUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(normalized, normalized.takeIf { allowLoopbackForTests } ?: allowedOrigin)
                    ?: return@forEach
                val rawBitrate = JsonPathExtractor.extractInt(stEl, bitratePath)
                val qStr = JsonPathExtractor.extractString(stEl, qualityPath).orEmpty()
                val kbps = when {
                    rawBitrate != null && rawBitrate > 0 ->
                        if (bitrateUnit.equals("bps", ignoreCase = true) || rawBitrate > 2_000) rawBitrate / 1000 else rawBitrate
                    qStr.isNotBlank() ->
                        Regex("""(\d+)""").find(qStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 160
                    else -> 160
                }.coerceAtLeast(32)
                val mime = JsonPathExtractor.extractString(stEl, mimePath)?.substringBefore(";")?.takeIf { it.isNotBlank() }
                    ?: if (safeUrl.contains(".mp3", ignoreCase = true)) "audio/mpeg" else "audio/mp4"
                val codec = JsonPathExtractor.extractString(stEl, codecPath).orEmpty()
                val label = if (mime.contains("mpeg", true)) "MP3 ${kbps}k" else "AUDIO ${kbps}k"
                result += OnlineAudioStream(
                    url = safeUrl,
                    bitrateKbps = kbps,
                    mimeType = mime,
                    codec = codec,
                    formatLabel = label,
                    expiresAtMs = StreamQualitySelector.parseExpiryFromUrl(safeUrl)
                )
            }
        }

        if (result.isEmpty() && !streamUrlPath.isNullOrBlank()) {
            val direct = JsonPathExtractor.extractString(el, streamUrlPath)
            if (!direct.isNullOrBlank()) {
                val normalized = normalizeUrlCandidate(direct, baseUrl)
                val safeUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(normalized, normalized.takeIf { allowLoopbackForTests } ?: allowedOrigin)
                if (safeUrl != null) {
                    result += OnlineAudioStream(
                        url = safeUrl,
                        bitrateKbps = 192,
                        mimeType = if (safeUrl.contains(".mp3", true)) "audio/mpeg" else "audio/mp4",
                        codec = "audio",
                        formatLabel = "STREAM 192k",
                        expiresAtMs = StreamQualitySelector.parseExpiryFromUrl(safeUrl)
                    )
                }
            }
        }

        if (result.isEmpty() && !streamUrlTemplate.isNullOrBlank()) {
            var expanded: String = streamUrlTemplate
            vars.forEach { (k, v) -> expanded = expanded.replace("{$k}", v) }
            val safeUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(expanded, expanded.takeIf { allowLoopbackForTests } ?: allowedOrigin)
            if (safeUrl != null) {
                result += OnlineAudioStream(
                    url = safeUrl,
                    bitrateKbps = 192,
                    mimeType = "audio/mpeg",
                    codec = "audio",
                    formatLabel = "STREAM",
                    expiresAtMs = StreamQualitySelector.parseExpiryFromUrl(safeUrl)
                )
            }
        }

        return result
    }

    private fun extractValidUrl(el: JsonElement, pathSpec: String?, baseUrl: String?): String? {
        val raw = JsonPathExtractor.extractString(el, pathSpec) ?: return null
        val normalized = if (baseUrl != null) normalizeUrlCandidate(raw, baseUrl) else raw
        val allowedOrigin = normalized.takeIf { allowLoopbackForTests }
        return InputAndUrlSecurity.validateRemoteHttpsUrl(normalized, allowedOrigin)
    }

    private fun normalizeUrlCandidate(raw: String, baseUrl: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "${baseUrl.trimEnd('/')}$trimmed"
            else -> trimmed
        }
    }

    private fun maybeDecodeHtml(input: String, decode: Boolean): String =
        if (decode) JsonPathExtractor.decodeHtmlEntities(input).trim() else input.trim()

    private fun buildPaginationVars(
        query: String,
        pageToken: String?,
        rule: PaginationRule
    ): Map<String, String> {
        val page = pageToken?.toIntOrNull()?.coerceIn(1, 200) ?: rule.startValue
        val offset = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val map = mutableMapOf(
            "query" to query,
            "limit" to rule.pageSize.coerceIn(1, 100).toString(),
            "page" to page.toString(),
            "offset" to offset.toString(),
            "pageToken" to pageToken.orEmpty()
        )
        if (!pageToken.isNullOrBlank()) {
            map["cursor"] = pageToken
            map[rule.paramName] = pageToken
        } else {
            map[rule.paramName] = if (rule.type.equals("offset", true)) "0" else rule.startValue.toString()
        }
        return map
    }

    private fun computeNextPageToken(
        root: JsonElement,
        cfg: DynamicProviderConfig,
        rule: PaginationRule,
        currentToken: String?,
        itemCount: Int
    ): String? {
        return when (rule.type.lowercase()) {
            "cursor" -> {
                val tokenPath = rule.nextTokenPath ?: cfg.mapping.nextPageTokenPath
                JsonPathExtractor.extractString(root, tokenPath)?.takeIf { it.isNotBlank() && it != "null" }
            }
            "page" -> {
                val curr = currentToken?.toIntOrNull() ?: rule.startValue
                val hasMore = rule.hasMorePath?.let { JsonPathExtractor.extractBoolean(root, it) }
                val shouldContinue = hasMore ?: (itemCount >= rule.minResultsForNextPage)
                if (shouldContinue) (curr + 1).toString() else null
            }
            "offset" -> {
                val currOffset = currentToken?.toIntOrNull() ?: 0
                val hasMore = rule.hasMorePath?.let { JsonPathExtractor.extractBoolean(root, it) }
                val shouldContinue = hasMore ?: (itemCount >= rule.minResultsForNextPage)
                if (shouldContinue) (currOffset + rule.pageSize).toString() else null
            }
            else -> null
        }
    }
}
