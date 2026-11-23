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
    BANDCAMP("Bandcamp", "BANDCAMP")
}

@Serializable
enum class OnlineSearchFilter(val label: String) {
    ALL("All"),
    SONGS("Songs"),
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
    val totalListenMs: Long = 0L
) {
    fun toUiTrack(formatTag: String? = null): Track {
        val virtualUri = buildOnlineVirtualUri(providerType, sourceId, sourceUrl)
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
            formatLabel = formatTag ?: providerType.badge,
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
        ): String {
            val encodedId = android.net.Uri.encode(sourceId)
            val encodedUrl = android.net.Uri.encode(sourceUrl)
            return "$ONLINE_SCHEME://${providerType.name.lowercase()}/$encodedId?src=$encodedUrl"
        }

        fun canonicalId(providerType: OnlineProviderType, sourceId: String): String {
            val normalizedProvider = when (providerType) {
                OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS -> OnlineProviderType.YOUTUBE
                else -> providerType
            }
            return "${normalizedProvider.name}:$sourceId"
        }
    }
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
    val resolvedBy: OnlineProviderType
)

data class OnlineArtistSummary(
    val id: String,
    val name: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val subscriberText: String = "",
    val providerType: OnlineProviderType
)

data class OnlineAlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val trackCount: Int = 0,
    val providerType: OnlineProviderType
)

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
    val providerType: OnlineProviderType
)

data class OnlineAlbumPage(
    val id: String,
    val title: String,
    val artist: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val description: String = "",
    val tracks: List<OnlineTrack> = emptyList(),
    val providerType: OnlineProviderType
)

data class OnlineSearchPage(
    val query: String,
    val tracks: List<OnlineTrack> = emptyList(),
    val artists: List<OnlineArtistSummary> = emptyList(),
    val albums: List<OnlineAlbumSummary> = emptyList(),
    val nextPageToken: String? = null,
    val hasMore: Boolean = false,
    val providerUsed: OnlineProviderType
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
    val activeInstanceUrl: String? = null
)

enum class NetworkSpeedTier {
    FAST_UNMETERED,
    STANDARD,
    SLOW_OR_METERED,
    OFFLINE
}
