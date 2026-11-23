package app.nocturne.player.data.online

import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAlbumPage
import app.nocturne.player.data.model.OnlineArtistPage
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineSearchPage
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.OnlineTrack

interface OnlineMusicProvider {
    val providerType: OnlineProviderType
    val supportsPagination: Boolean

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
}
