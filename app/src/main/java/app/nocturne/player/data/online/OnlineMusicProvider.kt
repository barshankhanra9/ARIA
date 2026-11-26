package app.nocturne.player.data.online

import app.nocturne.player.data.model.DynamicProviderConfig
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAlbumPage
import app.nocturne.player.data.model.OnlineArtistPage
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineSearchPage
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.OperationType

interface OnlineMusicProvider {
    val providerType: OnlineProviderType
    val providerId: String
        get() = providerType.defaultProviderId()
    val displayName: String
        get() = providerType.displayName
    val supportsPagination: Boolean

    fun supportsCapability(capability: String): Boolean = true

    fun activeEndpointFor(operation: OperationType): String? = null

    fun currentConfig(): DynamicProviderConfig? = null

    suspend fun search(
        query: String,
        filter: OnlineSearchFilter = OnlineSearchFilter.ALL,
        pageToken: String? = null
    ): OnlineSearchPage

    suspend fun suggestions(query: String): List<String>

    suspend fun trending(): List<OnlineTrack>

    suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution

    suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage

    suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage

    suspend fun fetchAlbumOrPlaylist(
        albumIdOrUrl: String,
        isPlaylist: Boolean
    ): OnlineAlbumPage = fetchAlbumOrPlaylist(albumIdOrUrl)

    suspend fun fetchRelatedTracks(track: OnlineTrack, limit: Int = 10): List<OnlineTrack> = emptyList()
}
