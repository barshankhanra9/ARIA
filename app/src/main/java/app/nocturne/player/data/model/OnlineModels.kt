package app.nocturne.player.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class OnlineProviderType(val displayName: String, val badge: String) {
    YOUTUBE("YouTube", "YOUTUBE"),
    JIOSAAVN("JioSaavn", "JIOSAAVN"),
    PIPED("Piped", "PIPED"),
    INVIDIOUS("Invidious", "INVIDIOUS"),
    AUDIUS("Audius", "AUDIUS"),
    SOUNDCLOUD("SoundCloud", "SOUNDCLOUD"),
    BANDCAMP("Bandcamp", "BANDCAMP"),
    DYNAMIC("Online", "STREAM");

    fun defaultProviderId(): String = name.lowercase()

    companion object {
        fun fromProviderId(id: String?): OnlineProviderType {
            val clean = id?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.name.equals(clean, ignoreCase = true) } ?: DYNAMIC
        }
    }
}

@Serializable
enum class OnlineSearchFilter(val label: String) {
    SONGS("Songs"),
    VIDEOS_REMIXES("Videos / Remixes"),
    ALL("All"),
    ARTISTS("Artists"),
    ALBUMS("Albums & Playlists"),
    JIOSAAVN("JioSaavn"),
    AUDIUS("Audius"),
    SOUNDCLOUD("SoundCloud"),
    BANDCAMP("Bandcamp")
}

@Serializable
enum class OnlineLibrarySection(val label: String) {
    FAVORITES("Favorites"),
    HISTORY("Listening History"),
    RECENTLY_PLAYED("Recently Played"),
    MOST_PLAYED("Most Played"),
    QUEUE("Current Queue"),
    ARTISTS("Artists"),
    PLAYLISTS("Playlists")
}

@Serializable
enum class OnlineSmartPlaylistType(val title: String, val subtitle: String) {
    FAVORITES("Online Favorites", "Hearted streams kept close"),
    RECENTLY_PLAYED("Recently Played", "Latest online listening footprints"),
    MOST_PLAYED("Most Played", "Heavy online rotation"),
    HISTORY("Listening History", "Full chronological stream journal")
}

@Serializable
enum class OnlineDiscoveryCategory(
    val label: String,
    val searchQuery: String? = null,
    val preferAudiusFirst: Boolean = false
) {
    TRENDING("Trending"),
    TOP_ARTISTS("Top Artists", searchQuery = "popular single songs"),
    NEW_RELEASES("New Releases", searchQuery = "latest new songs"),
    BOLLYWOOD("Bollywood", searchQuery = "bollywood songs"),
    GLOBAL_POP("Global Pop", searchQuery = "pop singles"),
    INDIE("Indie", searchQuery = "indie acoustic", preferAudiusFirst = true),
    LOFI_BEATS("Lo-Fi Beats", searchQuery = "lofi beats", preferAudiusFirst = true),
    HIP_HOP("Hip-Hop", searchQuery = "hip hop rap", preferAudiusFirst = true),
    CHILL("Chill", searchQuery = "chill vibes"),
    WORKOUT("Workout", searchQuery = "workout energy songs")
}

@Serializable
data class OnlineTrack(
    val id: String,
    val sourceId: String,
    val providerType: OnlineProviderType,
    val providerLabel: String = providerType.displayName,
    val sourceUrl: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val artistUrl: String? = null,
    val album: String = "Online Stream",
    val albumUrl: String? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
    val viewCount: Long = 0L,
    val publishedText: String = "",
    val isLive: Boolean = false,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val totalListenMs: Long = 0L,
    val providerId: String = providerType.name.lowercase(),
    val saavnEncryptedUrl: String? = null
) {
    fun toUnifiedTrack(): UnifiedTrack = UnifiedTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = thumbnailUrl,
        durationMs = durationMs,
        primarySource = providerType,
        sourceId = sourceId,
        saavnEncryptedUrl = saavnEncryptedUrl
    )

    fun effectiveProviderId(): String =
        providerId.ifBlank { providerType.name.lowercase() }

    fun effectiveBadge(): String =
        if (providerType == OnlineProviderType.DYNAMIC) {
            providerLabel.ifBlank { providerId }.uppercase()
        } else {
            providerLabel.ifBlank { providerType.badge }.uppercase()
        }

    fun toUiTrack(formatTag: String? = null): Track {
        val effProviderId = effectiveProviderId()
        val virtualUri = buildOnlineVirtualUri(effProviderId, sourceId, sourceUrl)
        return Track(
            id = id.hashCode().toLong(),
            uriString = virtualUri,
            filePath = "",
            displayName = title,
            title = title,
            artist = artist,
            album = album.ifBlank { providerLabel },
            albumArtist = artist,
            genre = providerLabel,
            year = 0,
            trackNumber = 0,
            discNumber = 0,
            composer = "",
            durationMs = durationMs,
            sizeBytes = 0L,
            dateAddedSec = 0L,
            dateModifiedSec = 0L,
            mimeType = "audio/*",
            formatLabel = formatTag ?: effectiveBadge(),
            bitrateKbps = 0,
            sampleRateHz = 0,
            channelCount = 0,
            folderPath = "",
            folderName = providerLabel,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt,
            isFavorite = isFavorite,
            totalListenMs = totalListenMs,
            customCoverUri = thumbnailUrl,
            isOnline = true,
            onlineTrackId = id,
            onlineSourceId = sourceId,
            onlineProvider = providerLabel,
            artworkUrl = thumbnailUrl,
            artistUrl = artistUrl,
            albumUrl = albumUrl
        )
    }

    companion object {
        const val ONLINE_SCHEME = "aria-online"

        fun buildOnlineVirtualUri(
            providerType: OnlineProviderType,
            sourceId: String,
            sourceUrl: String
        ): String = buildOnlineVirtualUri(providerType.name.lowercase(), sourceId, sourceUrl)

        fun buildOnlineVirtualUri(
            providerId: String,
            sourceId: String,
            sourceUrl: String
        ): String {
            val cleanPid = providerId.trim().lowercase().ifBlank { "youtube" }
            val encodedId = android.net.Uri.encode(sourceId)
            val encodedUrl = android.net.Uri.encode(sourceUrl)
            return "$ONLINE_SCHEME://$cleanPid/$encodedId?src=$encodedUrl"
        }

        fun canonicalId(providerType: OnlineProviderType, sourceId: String): String {
            val normalizedProvider = when (providerType) {
                OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS -> OnlineProviderType.YOUTUBE
                else -> providerType
            }
            return "${normalizedProvider.name}:$sourceId"
        }

        fun canonicalId(providerId: String, sourceId: String): String =
            canonicalId(providerId, OnlineProviderType.fromProviderId(providerId), sourceId)

        fun canonicalId(providerId: String, providerType: OnlineProviderType, sourceId: String): String {
            return if (providerType == OnlineProviderType.DYNAMIC) {
                "DYNAMIC:${providerId.trim().lowercase()}:$sourceId"
            } else {
                canonicalId(providerType, sourceId)
            }
        }
    }
}

@Serializable
data class UnifiedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "Online Stream",
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val primarySource: OnlineProviderType,
    val sourceId: String,
    val saavnEncryptedUrl: String? = null
) {
    fun toOnlineTrack(
        sourceUrl: String = when (primarySource) {
            OnlineProviderType.JIOSAAVN -> "https://www.jiosaavn.com/song/$sourceId"
            OnlineProviderType.YOUTUBE, OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS ->
                "https://www.youtube.com/watch?v=$sourceId"
            else -> ""
        },
        providerId: String = primarySource.defaultProviderId()
    ): OnlineTrack = OnlineTrack(
        id = id,
        sourceId = sourceId,
        providerType = primarySource,
        providerLabel = primarySource.displayName,
        sourceUrl = sourceUrl,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        thumbnailUrl = artworkUrl,
        providerId = providerId,
        saavnEncryptedUrl = saavnEncryptedUrl
    )
}

data class OnlineAudioStream(
    val url: String,
    val bitrateKbps: Int,
    val mimeType: String,
    val codec: String,
    val formatLabel: String,
    val expiresAtMs: Long,
    val headers: Map<String, String> = emptyMap()
)

data class OnlineStreamResolution(
    val track: OnlineTrack,
    val streams: List<OnlineAudioStream>,
    val selectedStream: OnlineAudioStream,
    val relatedTracks: List<OnlineTrack> = emptyList(),
    val resolvedBy: OnlineProviderType,
    val resolvedByProviderId: String = resolvedBy.name.lowercase(),
    val resolvedByLabel: String = resolvedBy.displayName,
    val resolvedEndpointUrl: String? = null
)

data class OnlineArtistSummary(
    val id: String,
    val name: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val subscriberText: String = "",
    val providerType: OnlineProviderType,
    val providerId: String = providerType.name.lowercase()
)

data class OnlineAlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val trackCount: Int = 0,
    val providerType: OnlineProviderType,
    val providerId: String = providerType.name.lowercase(),
    val isPlaylist: Boolean = false
) {
    fun navigationTarget(): String {
        val cleanId = id.trim()
        val cleanUrl = url.trim()
        if (providerId.lowercase() in setOf("jiosaavn", "audius")) {
            return cleanId.ifBlank { cleanUrl }
        }
        return when (providerType) {
            OnlineProviderType.JIOSAAVN,
            OnlineProviderType.AUDIUS,
            OnlineProviderType.PIPED,
            OnlineProviderType.INVIDIOUS,
            OnlineProviderType.DYNAMIC -> cleanId.ifBlank { cleanUrl }
            OnlineProviderType.YOUTUBE -> cleanId.ifBlank { cleanUrl }
            OnlineProviderType.SOUNDCLOUD,
            OnlineProviderType.BANDCAMP -> cleanUrl.ifBlank { cleanId }
        }
    }
}

data class OnlineArtistPage(
    val id: String,
    val name: String,
    val url: String,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val subscriberCountText: String = "",
    val description: String = "",
    val tracks: List<OnlineTrack> = emptyList(),
    val playlists: List<OnlineAlbumSummary> = emptyList(),
    val providerType: OnlineProviderType,
    val providerId: String = providerType.name.lowercase(),
    val providerLabel: String = providerType.displayName
)

data class OnlineAlbumPage(
    val id: String,
    val title: String,
    val artist: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val description: String = "",
    val tracks: List<OnlineTrack> = emptyList(),
    val providerType: OnlineProviderType,
    val providerId: String = providerType.name.lowercase(),
    val providerLabel: String = providerType.displayName,
    val isPlaylist: Boolean = false
)

data class OnlineSearchPage(
    val query: String,
    val tracks: List<OnlineTrack> = emptyList(),
    val artists: List<OnlineArtistSummary> = emptyList(),
    val albums: List<OnlineAlbumSummary> = emptyList(),
    val nextPageToken: String? = null,
    val hasMore: Boolean = false,
    val providerUsed: OnlineProviderType,
    val providerUsedId: String = providerUsed.name.lowercase(),
    val providerUsedLabel: String = providerUsed.displayName
)

data class OnlinePlaylist(
    val id: Long,
    val name: String,
    val description: String,
    val coverUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val tracks: List<OnlineTrack>
)

data class OnlineHistoryEntry(
    val entryId: Long,
    val track: OnlineTrack,
    val playedAt: Long
)

data class ProviderHealthSnapshot(
    val providerType: OnlineProviderType,
    val isAvailable: Boolean,
    val ewmaLatencyMs: Long,
    val consecutiveFailures: Int,
    val cooldownRemainingMs: Long,
    val activeInstanceUrl: String? = null,
    val providerId: String = providerType.name.lowercase(),
    val displayName: String = providerType.displayName
)

enum class NetworkSpeedTier {
    FAST_UNMETERED,
    STANDARD,
    SLOW_OR_METERED,
    OFFLINE
}
