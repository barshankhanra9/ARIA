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
import app.nocturne.player.data.model.StreamingQuality
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NewPipeProvider(
    override val providerType: OnlineProviderType,
    private val dynamicConfigProvider: (() -> DynamicProviderConfig?)? = null
) : OnlineMusicProvider {

    override val providerId: String
        get() = currentConfig()?.id ?: providerType.defaultProviderId()

    override val displayName: String
        get() = currentConfig()?.displayName ?: providerType.displayName

    override val supportsPagination: Boolean = true

    override fun currentConfig(): DynamicProviderConfig? = dynamicConfigProvider?.invoke()

    override fun supportsCapability(capability: String): Boolean =
        currentConfig()?.supportsCapability(capability) ?: true

    override fun activeEndpointFor(operation: OperationType): String = "NewPipe Extractor"

    private data class PagedSession(
        val handler: SearchQueryHandler,
        val page: Page
    )

    private val pageCache = ConcurrentHashMap<String, PagedSession>()

    private fun getService(): StreamingService {
        OkHttpDownloader.ensureNewPipeInitialized()
        return when (providerType) {
            OnlineProviderType.YOUTUBE -> ServiceList.YouTube
            OnlineProviderType.SOUNDCLOUD -> ServiceList.SoundCloud
            OnlineProviderType.BANDCAMP -> ServiceList.Bandcamp
            else -> ServiceList.YouTube
        }
    }

    override suspend fun search(
        query: String,
        filter: OnlineSearchFilter,
        pageToken: String?
    ): OnlineSearchPage = withContext(Dispatchers.IO) {
        val service = getService()

        if (pageToken != null) {
            val session = pageCache.remove(pageToken)
                ?: throw IllegalStateException("Pagination token expired")
            val more = SearchInfo.getMoreItems(service, session.handler, session.page)
            val nextToken = more.nextPage?.let { np ->
                UUID.randomUUID().toString().also { token ->
                    pageCache[token] = PagedSession(session.handler, np)
                }
            }
            return@withContext mapItemsToSearchPage(query, more.items, nextToken)
        }

        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        val contentFilters = when {
            providerType == OnlineProviderType.YOUTUBE && filter == OnlineSearchFilter.SONGS -> listOf("videos")
            providerType == OnlineProviderType.YOUTUBE && filter == OnlineSearchFilter.ARTISTS -> listOf("channels")
            providerType == OnlineProviderType.YOUTUBE && filter == OnlineSearchFilter.ALBUMS -> listOf("playlists")
            providerType == OnlineProviderType.SOUNDCLOUD && filter == OnlineSearchFilter.SONGS -> listOf("tracks")
            providerType == OnlineProviderType.SOUNDCLOUD && filter == OnlineSearchFilter.ARTISTS -> listOf("users")
            providerType == OnlineProviderType.SOUNDCLOUD && filter == OnlineSearchFilter.ALBUMS -> listOf("playlists")
            else -> emptyList()
        }

        val handler = service.searchQHFactory.fromQuery(safeQuery, contentFilters, "")
        val info = SearchInfo.getInfo(service, handler)
        val nextToken = info.nextPage?.let { np ->
            UUID.randomUUID().toString().also { token ->
                pageCache[token] = PagedSession(handler, np)
            }
        }
        mapItemsToSearchPage(safeQuery, info.relatedItems, nextToken)
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (safeQuery.isBlank()) return@withContext emptyList()
        val service = getService()
        val extractor = service.suggestionExtractor ?: return@withContext emptyList()
        runCatching { extractor.suggestionList(safeQuery) }.getOrDefault(emptyList())
    }

    override suspend fun trending(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val service = getService()
        val kioskList = service.kioskList
        val availableIds = kioskList.availableKiosks
        val preferredKioskId = when (providerType) {
            OnlineProviderType.YOUTUBE -> availableIds.firstOrNull { it.contains("Music", true) }
                ?: kioskList.defaultKioskId
            OnlineProviderType.SOUNDCLOUD -> availableIds.firstOrNull { it.contains("Top", true) }
                ?: kioskList.defaultKioskId
            else -> kioskList.defaultKioskId
        }
        val extractor = kioskList.getExtractorById(preferredKioskId, null)
        val info = KioskInfo.getInfo(extractor)
        val tracks = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { mapStreamItem(it) }
        if (tracks.isNotEmpty()) tracks
        else if (providerType == OnlineProviderType.YOUTUBE) {
            // Music chart fallback query if kiosk returns empty
            search("top music hits", OnlineSearchFilter.SONGS, null).tracks
        } else {
            emptyList()
        }
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
        val service = getService()
        val rawTargetUrl = track.sourceUrl.ifBlank {
            if (providerType == OnlineProviderType.YOUTUBE) {
                val safeId = InputAndUrlSecurity.sanitizePathSegment(track.sourceId)
                    ?: throw IllegalArgumentException("Invalid YouTube track ID")
                "https://www.youtube.com/watch?v=${Uri.encode(safeId)}"
            } else {
                track.sourceId
            }
        }
        val targetUrl = validateProviderPageUrl(rawTargetUrl)
            ?: throw IllegalArgumentException("Untrusted or invalid ${providerType.displayName} track URL")
        val info = StreamInfo.getInfo(service, targetUrl)
        val mappedStreams = info.audioStreams.mapNotNull { mapAudioStream(it) }
        val selected = StreamQualitySelector.selectBestAudioStream(
            streams = mappedStreams,
            speedTier = speedTier,
            preferredQuality = preferredQuality
        )
            ?: throw IllegalStateException("No playable audio streams found via ${providerType.displayName}")

        val updatedTrack = track.copy(
            title = info.name?.takeIf { it.isNotBlank() } ?: track.title,
            artist = info.uploaderName?.takeIf { it.isNotBlank() } ?: track.artist,
            artistUrl = info.uploaderUrl?.takeIf { it.isNotBlank() } ?: track.artistUrl,
            durationMs = if (info.duration > 0L) info.duration * 1000L else track.durationMs,
            thumbnailUrl = pickBestImage(info.thumbnails) ?: track.thumbnailUrl,
            isLive = info.streamType == StreamType.LIVE_STREAM
        )
        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { mapStreamItem(it) }

        OnlineStreamResolution(
            track = updatedTrack,
            streams = mappedStreams,
            selectedStream = selected,
            relatedTracks = related,
            resolvedBy = providerType,
            resolvedByProviderId = providerId,
            resolvedByLabel = displayName
        )
    }

    override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage = withContext(Dispatchers.IO) {
        val service = getService()
        val url = normalizeArtistUrl(artistIdOrUrl)
            ?: throw IllegalArgumentException("Untrusted or invalid artist URL")
        val info = ChannelInfo.getInfo(service, url)

        val tracks = mutableListOf<OnlineTrack>()
        val playlists = mutableListOf<OnlineAlbumSummary>()

        for (tab in info.tabs) {
            runCatching {
                val tabInfo = ChannelTabInfo.getInfo(service, tab)
                tabInfo.relatedItems.forEach { item ->
                    when (item) {
                        is StreamInfoItem -> mapStreamItem(item)?.let { tracks.add(it) }
                        is PlaylistInfoItem -> mapPlaylistItem(item)?.let { playlists.add(it) }
                    }
                }
            }
            if (tracks.size >= 25) break
        }

        OnlineArtistPage(
            id = artistIdOrUrl,
            name = info.name.orEmpty().ifBlank { "Artist" },
            url = info.url.orEmpty().ifBlank { url },
            avatarUrl = pickBestImage(info.avatars),
            bannerUrl = pickBestImage(info.banners),
            subscriberCountText = if (info.subscriberCount > 0L) formatSubscribers(info.subscriberCount) else "",
            description = info.description.orEmpty(),
            tracks = tracks.distinctBy { it.id },
            playlists = playlists.distinctBy { it.id },
            providerType = providerType,
            providerId = providerId
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage =
        fetchAlbumOrPlaylist(albumIdOrUrl, isPlaylist = true)

    override suspend fun fetchAlbumOrPlaylist(
        albumIdOrUrl: String,
        isPlaylist: Boolean
    ): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val service = getService()
        val url = normalizePlaylistUrl(albumIdOrUrl)
            ?: throw IllegalArgumentException("Untrusted or invalid album or playlist URL")
        val info = PlaylistInfo.getInfo(service, url)
        val tracks = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { mapStreamItem(it) }

        OnlineAlbumPage(
            id = albumIdOrUrl,
            title = info.name.orEmpty().ifBlank { "Playlist" },
            artist = info.uploaderName.orEmpty().ifBlank { providerType.displayName },
            url = info.url.orEmpty().ifBlank { url },
            thumbnailUrl = pickBestImage(info.thumbnails) ?: tracks.firstOrNull()?.thumbnailUrl,
            description = "",
            tracks = tracks,
            providerType = providerType,
            providerId = providerId,
            isPlaylist = isPlaylist || url.contains("playlist", ignoreCase = true) || url.contains("list=", ignoreCase = true)
        )
    }

    private fun mapItemsToSearchPage(
        query: String,
        items: List<InfoItem>,
        nextPageToken: String?
    ): OnlineSearchPage {
        val tracks = mutableListOf<OnlineTrack>()
        val artists = mutableListOf<OnlineArtistSummary>()
        val albums = mutableListOf<OnlineAlbumSummary>()

        items.forEach { item ->
            when (item) {
                is StreamInfoItem -> mapStreamItem(item)?.let { tracks.add(it) }
                is ChannelInfoItem -> mapChannelItem(item)?.let { artists.add(it) }
                is PlaylistInfoItem -> mapPlaylistItem(item)?.let { albums.add(it) }
            }
        }

        return OnlineSearchPage(
            query = query,
            tracks = tracks,
            artists = artists,
            albums = albums,
            nextPageToken = nextPageToken,
            hasMore = nextPageToken != null,
            providerUsed = providerType,
            providerUsedId = providerId,
            providerUsedLabel = displayName
        )
    }

    private fun mapStreamItem(item: StreamInfoItem): OnlineTrack? {
        val itemUrl = item.url?.takeIf { it.isNotBlank() } ?: return null
        val sourceId = extractSourceId(itemUrl)
        val canonical = OnlineTrack.canonicalId(providerType, sourceId)
        val isLive = item.streamType == StreamType.LIVE_STREAM
        val durMs = if (item.duration > 0L) item.duration * 1000L else 0L
        return OnlineTrack(
            id = canonical,
            sourceId = sourceId,
            providerType = providerType,
            providerLabel = providerType.displayName,
            sourceUrl = itemUrl,
            title = item.name.orEmpty().ifBlank { "Untitled Track" },
            artist = item.uploaderName.orEmpty().ifBlank { providerType.displayName },
            artistId = item.uploaderUrl?.let { extractChannelId(it) },
            artistUrl = item.uploaderUrl,
            album = providerType.displayName,
            durationMs = durMs,
            thumbnailUrl = pickBestImage(item.thumbnails),
            viewCount = item.viewCount.coerceAtLeast(0L),
            publishedText = item.textualUploadDate.orEmpty(),
            isLive = isLive,
            providerId = providerId
        )
    }

    private fun mapChannelItem(item: ChannelInfoItem): OnlineArtistSummary? {
        val url = item.url?.takeIf { it.isNotBlank() } ?: return null
        return OnlineArtistSummary(
            id = extractChannelId(url),
            name = item.name.orEmpty().ifBlank { "Artist" },
            url = url,
            thumbnailUrl = pickBestImage(item.thumbnails),
            subscriberText = if (item.subscriberCount > 0L) formatSubscribers(item.subscriberCount) else "",
            providerType = providerType,
            providerId = providerId
        )
    }

    private fun mapPlaylistItem(item: PlaylistInfoItem): OnlineAlbumSummary? {
        val url = item.url?.takeIf { it.isNotBlank() } ?: return null
        return OnlineAlbumSummary(
            id = extractPlaylistId(url),
            title = item.name.orEmpty().ifBlank { "Playlist" },
            artist = item.uploaderName.orEmpty().ifBlank { displayName },
            url = url,
            thumbnailUrl = pickBestImage(item.thumbnails),
            trackCount = item.streamCount.toInt().coerceAtLeast(0),
            providerType = providerType,
            providerId = providerId,
            isPlaylist = true
        )
    }

    private fun mapAudioStream(audio: AudioStream): OnlineAudioStream? {
        val rawUrl = audio.content?.takeIf { audio.isUrl } ?: return null
        val url = InputAndUrlSecurity.validateRemoteHttpsUrl(rawUrl) ?: return null
        val kbps = when {
            audio.averageBitrate > 0 -> audio.averageBitrate
            audio.bitrate > 0 -> audio.bitrate / 1000
            else -> 128
        }
        val mediaFormat = audio.format
        val mime = mediaFormat?.mimeType.orEmpty().ifBlank { "audio/mp4" }
        val codec = audio.codec.orEmpty().ifBlank { mediaFormat?.name ?: "AAC" }
        val formatLabel = buildString {
            append(mediaFormat?.suffix?.uppercase() ?: "AUDIO")
            if (kbps > 0) append(" ${kbps}k")
        }
        return OnlineAudioStream(
            url = url,
            bitrateKbps = kbps,
            mimeType = mime,
            codec = codec,
            formatLabel = formatLabel,
            expiresAtMs = StreamQualitySelector.parseExpiryFromUrl(url)
        )
    }

    private fun extractSourceId(url: String): String {
        if (providerType == OnlineProviderType.YOUTUBE) {
            runCatching {
                val uri = Uri.parse(url)
                uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let { return it }
                if (uri.host?.contains("youtu.be") == true) {
                    uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let { return it }
                }
                if (url.contains("/shorts/")) {
                    return url.substringAfter("/shorts/").substringBefore("?").substringBefore("/")
                }
            }
        }
        return url
    }

    private fun extractChannelId(url: String): String {
        return when {
            url.contains("/channel/") -> url.substringAfter("/channel/").substringBefore("?").substringBefore("/")
            else -> url
        }
    }

    private fun extractPlaylistId(url: String): String {
        return runCatching {
            Uri.parse(url).getQueryParameter("list")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: url
    }

    private fun validateProviderPageUrl(rawUrl: String): String? {
        val validUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(rawUrl) ?: return null
        val host = runCatching { Uri.parse(validUrl).host }.getOrNull()
        val allowedDomains = when (providerType) {
            OnlineProviderType.YOUTUBE -> setOf("youtube.com", "youtu.be")
            OnlineProviderType.SOUNDCLOUD -> setOf("soundcloud.com")
            OnlineProviderType.BANDCAMP -> setOf("bandcamp.com")
            else -> setOf("youtube.com", "youtu.be")
        }
        return validUrl.takeIf { InputAndUrlSecurity.isExactOrSubdomain(host, allowedDomains) }
    }

    private fun normalizeArtistUrl(idOrUrl: String): String? {
        val candidate = if (idOrUrl.startsWith("http://", ignoreCase = true) ||
            idOrUrl.startsWith("https://", ignoreCase = true)
        ) {
            idOrUrl
        } else if (providerType == OnlineProviderType.YOUTUBE) {
            val safeId = InputAndUrlSecurity.sanitizePathSegment(idOrUrl) ?: return null
            "https://www.youtube.com/channel/${Uri.encode(safeId)}"
        } else {
            return null
        }
        return validateProviderPageUrl(candidate)
    }

    private fun normalizePlaylistUrl(idOrUrl: String): String? {
        val candidate = if (idOrUrl.startsWith("http://", ignoreCase = true) ||
            idOrUrl.startsWith("https://", ignoreCase = true)
        ) {
            idOrUrl
        } else if (providerType == OnlineProviderType.YOUTUBE) {
            val safeId = InputAndUrlSecurity.sanitizePathSegment(idOrUrl) ?: return null
            "https://www.youtube.com/playlist?list=${Uri.encode(safeId)}"
        } else {
            return null
        }
        return validateProviderPageUrl(candidate)
    }

    private fun pickBestImage(images: List<Image>?): String? {
        if (images.isNullOrEmpty()) return null
        val preferred = images.maxByOrNull { it.width * it.height }?.url ?: images.firstOrNull()?.url
        return InputAndUrlSecurity.validateRemoteHttpsUrl(preferred)
    }

    private fun formatSubscribers(count: Long): String {
        return when {
            count >= 1_000_000L -> "%.1fM".format(count / 1_000_000.0)
            count >= 1_000L -> "%.1fK".format(count / 1_000.0)
            else -> count.toString()
        }
    }
}
