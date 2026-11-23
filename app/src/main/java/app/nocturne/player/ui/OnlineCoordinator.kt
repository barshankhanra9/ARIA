package app.nocturne.player.ui

import android.app.Application
import android.content.Intent
import app.nocturne.player.PlaybackService
import app.nocturne.player.data.model.OnlineAlbumPage
import app.nocturne.player.data.model.OnlineAlbumSummary
import app.nocturne.player.data.model.OnlineArtistPage
import app.nocturne.player.data.model.OnlineArtistSummary
import app.nocturne.player.data.model.OnlineDiscoveryCategory
import app.nocturne.player.data.model.OnlineHistoryEntry
import app.nocturne.player.data.model.OnlinePlaylist
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineSmartPlaylistType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.ProviderHealthSnapshot
import app.nocturne.player.data.online.OnlineDependencies
import app.nocturne.player.data.online.OnlineProviderManager
import app.nocturne.player.data.security.InputAndUrlSecurity
import app.nocturne.player.playback.OnlineEngineSnapshot
import app.nocturne.player.playback.OnlinePlaybackBridge
import app.nocturne.player.playback.PlaybackRuntimeBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnlineSearchUiState(
    val query: String = "",
    val filter: OnlineSearchFilter = OnlineSearchFilter.ALL,
    val suggestions: List<String> = emptyList(),
    val tracks: List<OnlineTrack> = emptyList(),
    val artists: List<OnlineArtistSummary> = emptyList(),
    val albums: List<OnlineAlbumSummary> = emptyList(),
    val nextPageToken: String? = null,
    val hasMore: Boolean = false,
    val providerUsed: OnlineProviderType? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val hasSearched: Boolean = false
)

data class OnlineTrendingUiState(
    val tracks: List<OnlineTrack> = emptyList(),
    val providerUsed: OnlineProviderType? = null,
    val selectedCategory: OnlineDiscoveryCategory = OnlineDiscoveryCategory.TRENDING,
    val popularArtists: List<OnlineArtistSummary> = emptyList(),
    val isArtistsLoading: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class OnlineArtistDetailState(
    val isLoading: Boolean = false,
    val page: OnlineArtistPage? = null,
    val errorMessage: String? = null
)

data class OnlineAlbumDetailState(
    val isLoading: Boolean = false,
    val page: OnlineAlbumPage? = null,
    val errorMessage: String? = null
)

class OnlineCoordinator(
    private val app: Application,
    private val scope: CoroutineScope,
    private val emitMessage: (String) -> Unit
) {
    private val providerManager = OnlineDependencies.getProviderManager(app)
    private val repository = OnlineDependencies.getRepository(app)

    val allTracks: StateFlow<List<OnlineTrack>> = repository.allTracks
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<List<OnlineTrack>> = repository.favorites
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentlyPlayed: StateFlow<List<OnlineTrack>> = repository.recentlyPlayed
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val mostPlayed: StateFlow<List<OnlineTrack>> = repository.mostPlayed
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val history: StateFlow<List<OnlineHistoryEntry>> = repository.history
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val playlists: StateFlow<List<OnlinePlaylist>> = repository.playlists
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentSearches: StateFlow<List<String>> = repository.recentSearches
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val snapshot: StateFlow<OnlineEngineSnapshot> = combine(
        PlaybackRuntimeBus.onlineSnapshot,
        allTracks
    ) { raw, saved ->
        val savedById = saved.associateBy { it.id }
        val enrichedQueue = raw.queue.map { t ->
            val st = savedById[t.id]
            if (st != null) {
                t.copy(
                    isFavorite = st.isFavorite,
                    playCount = st.playCount,
                    lastPlayedAt = st.lastPlayedAt,
                    totalListenMs = st.totalListenMs
                )
            } else t
        }
        val enrichedCurrent = raw.currentTrack?.let { cur ->
            val st = savedById[cur.id]
            if (st != null) {
                cur.copy(
                    isFavorite = st.isFavorite,
                    playCount = st.playCount,
                    lastPlayedAt = st.lastPlayedAt,
                    totalListenMs = st.totalListenMs
                )
            } else cur
        }
        raw.copy(
            currentTrack = enrichedCurrent,
            queue = enrichedQueue
        )
    }.stateIn(scope, SharingStarted.Eagerly, OnlineEngineSnapshot())

    private val _searchState = MutableStateFlow(OnlineSearchUiState())
    val searchState: StateFlow<OnlineSearchUiState> = _searchState.asStateFlow()

    private val _trendingState = MutableStateFlow(OnlineTrendingUiState())
    val trendingState: StateFlow<OnlineTrendingUiState> = _trendingState.asStateFlow()

    private val _artistDetailState = MutableStateFlow(OnlineArtistDetailState())
    val artistDetailState: StateFlow<OnlineArtistDetailState> = _artistDetailState.asStateFlow()

    private val _albumDetailState = MutableStateFlow(OnlineAlbumDetailState())
    val albumDetailState: StateFlow<OnlineAlbumDetailState> = _albumDetailState.asStateFlow()

    private val _providerHealth = MutableStateFlow<List<ProviderHealthSnapshot>>(emptyList())
    val providerHealth: StateFlow<List<ProviderHealthSnapshot>> = _providerHealth.asStateFlow()

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null
    private var trendingJob: Job? = null
    private var artistJob: Job? = null
    private var albumJob: Job? = null

    fun refreshProviderHealth() {
        _providerHealth.value = providerManager.getHealthSnapshots()
    }

    fun loadTrendingIfNeeded() {
        val cur = _trendingState.value
        if (cur.isLoading || cur.tracks.isNotEmpty()) return
        refreshTrending()
    }

    fun refreshTrending() {
        loadCategoryFeed(_trendingState.value.selectedCategory, clearExisting = false)
    }

    fun selectDiscoveryCategory(category: OnlineDiscoveryCategory) {
        val prev = _trendingState.value
        if (prev.selectedCategory == category && prev.tracks.isNotEmpty() && !prev.isLoading) {
            return
        }
        loadCategoryFeed(category, clearExisting = prev.selectedCategory != category)
    }

    private fun loadCategoryFeed(category: OnlineDiscoveryCategory, clearExisting: Boolean) {
        trendingJob?.cancel()
        trendingJob = scope.launch {
            _trendingState.update { cur ->
                cur.copy(
                    selectedCategory = category,
                    tracks = if (clearExisting) emptyList() else cur.tracks,
                    isLoading = true,
                    isArtistsLoading = category == OnlineDiscoveryCategory.TOP_ARTISTS,
                    errorMessage = null
                )
            }

            if (category == OnlineDiscoveryCategory.TOP_ARTISTS) {
                coroutineScope {
                    val tracksDeferred = async {
                        runCatching {
                            providerManager.fetchDiscoveryFeed(
                                query = category.searchQuery ?: "popular single songs",
                                preferAudiusFirst = category.preferAudiusFirst
                            )
                        }
                    }
                    val artistsDeferred = async {
                        runCatching { providerManager.fetchPopularArtists() }
                    }
                    val tracksResult = tracksDeferred.await()
                    val artistsResult = artistsDeferred.await()
                    val tracksPair = tracksResult.getOrNull()
                    val artistsPair = artistsResult.getOrNull()
                    val err = tracksResult.exceptionOrNull() ?: artistsResult.exceptionOrNull()

                    _trendingState.update { cur ->
                        cur.copy(
                            selectedCategory = category,
                            tracks = tracksPair?.first ?: cur.tracks,
                            popularArtists = artistsPair?.first ?: cur.popularArtists,
                            providerUsed = tracksPair?.second ?: artistsPair?.second ?: cur.providerUsed,
                            isLoading = false,
                            isArtistsLoading = false,
                            errorMessage = if ((tracksPair?.first.isNullOrEmpty()) && (artistsPair?.first.isNullOrEmpty())) {
                                err?.localizedMessage ?: "Unable to load Top Artists"
                            } else null
                        )
                    }
                    refreshProviderHealth()
                }
                return@launch
            }

            runCatching {
                if (category == OnlineDiscoveryCategory.TRENDING) {
                    providerManager.trending()
                } else {
                    providerManager.fetchDiscoveryFeed(
                        query = category.searchQuery ?: category.label,
                        preferAudiusFirst = category.preferAudiusFirst
                    )
                }
            }.onSuccess { (tracks, provider) ->
                _trendingState.update { cur ->
                    cur.copy(
                        selectedCategory = category,
                        tracks = tracks,
                        providerUsed = provider,
                        isLoading = false,
                        isArtistsLoading = false,
                        errorMessage = null
                    )
                }
                refreshProviderHealth()
            }.onFailure { err ->
                _trendingState.update {
                    it.copy(
                        selectedCategory = category,
                        isLoading = false,
                        isArtistsLoading = false,
                        errorMessage = err.localizedMessage ?: "Unable to load ${category.label} music"
                    )
                }
                refreshProviderHealth()
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val stripped = query
            .filter { !it.isISOControl() && it !in '\u202A'..'\u202E' && it !in '\u2066'..'\u2069' }
            .take(InputAndUrlSecurity.MAX_SEARCH_QUERY_LENGTH)
        _searchState.update { it.copy(query = stripped) }
        suggestionJob?.cancel()
        val sanitized = InputAndUrlSecurity.sanitizeSearchQuery(stripped)
        if (sanitized.length < 2) {
            _searchState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionJob = scope.launch {
            delay(280L)
            val list = runCatching { providerManager.suggestions(sanitized) }.getOrDefault(emptyList())
            if (_searchState.value.query == stripped) {
                _searchState.update { it.copy(suggestions = list) }
            }
        }
    }

    fun selectSearchFilter(filter: OnlineSearchFilter) {
        val curQuery = InputAndUrlSecurity.sanitizeSearchQuery(_searchState.value.query)
        _searchState.update { it.copy(filter = filter) }
        if (curQuery.isNotBlank() && _searchState.value.hasSearched) {
            executeSearch(curQuery, filter)
        }
    }

    fun executeSearch(query: String = _searchState.value.query, filter: OnlineSearchFilter = _searchState.value.filter) {
        val clean = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (clean.isBlank()) return

        suggestionJob?.cancel()
        searchJob?.cancel()
        _searchState.update {
            it.copy(
                query = clean,
                filter = filter,
                suggestions = emptyList(),
                isLoading = true,
                isLoadingMore = false,
                errorMessage = null,
                hasSearched = true
            )
        }

        scope.launch(Dispatchers.IO) {
            repository.recordSearch(clean)
        }

        searchJob = scope.launch {
            runCatching {
                providerManager.search(query = clean, filter = filter, pageToken = null)
            }.onSuccess { page ->
                _searchState.update {
                    it.copy(
                        tracks = page.tracks,
                        artists = page.artists,
                        albums = page.albums,
                        nextPageToken = page.nextPageToken,
                        hasMore = page.hasMore,
                        providerUsed = page.providerUsed,
                        isLoading = false,
                        errorMessage = null
                    )
                }
                refreshProviderHealth()
            }.onFailure { err ->
                _searchState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.localizedMessage ?: "Search failed across online providers"
                    )
                }
                refreshProviderHealth()
            }
        }
    }

    fun loadNextSearchPage() {
        val cur = _searchState.value
        val token = cur.nextPageToken
        if (cur.isLoading || cur.isLoadingMore || !cur.hasMore || token.isNullOrBlank()) return

        _searchState.update { it.copy(isLoadingMore = true) }
        searchJob = scope.launch {
            runCatching {
                providerManager.search(
                    query = cur.query.trim(),
                    filter = cur.filter,
                    pageToken = token,
                    preferredProvider = cur.providerUsed
                )
            }.onSuccess { page ->
                _searchState.update { state ->
                    val mergedTracks = OnlineProviderManager.deduplicateTracks(state.tracks + page.tracks)
                    val mergedArtists = (state.artists + page.artists).distinctBy { it.id }
                    val mergedAlbums = (state.albums + page.albums).distinctBy { it.id }
                    state.copy(
                        tracks = mergedTracks,
                        artists = mergedArtists,
                        albums = mergedAlbums,
                        nextPageToken = page.nextPageToken,
                        hasMore = page.hasMore,
                        isLoadingMore = false
                    )
                }
                refreshProviderHealth()
            }.onFailure {
                _searchState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        suggestionJob?.cancel()
        _searchState.value = OnlineSearchUiState(filter = _searchState.value.filter)
    }

    fun deleteRecentSearch(query: String) {
        scope.launch { repository.deleteRecentSearch(query) }
    }

    fun clearSearchHistory() {
        scope.launch { repository.clearSearchHistory() }
    }

    fun openArtist(idOrUrl: String, providerType: OnlineProviderType) {
        val cleanTarget = InputAndUrlSecurity.sanitizeSingleLineText(idOrUrl, 300)
        if (cleanTarget.isBlank()) return
        artistJob?.cancel()
        _artistDetailState.value = OnlineArtistDetailState(isLoading = true)
        artistJob = scope.launch {
            runCatching {
                providerManager.fetchArtist(cleanTarget, providerType)
            }.onSuccess { page ->
                _artistDetailState.value = OnlineArtistDetailState(isLoading = false, page = page)
                refreshProviderHealth()
            }.onFailure { err ->
                _artistDetailState.value = OnlineArtistDetailState(
                    isLoading = false,
                    errorMessage = err.localizedMessage ?: "Could not load artist page"
                )
            }
        }
    }

    fun clearArtistDetail() {
        artistJob?.cancel()
        _artistDetailState.value = OnlineArtistDetailState()
    }

    fun openAlbum(idOrUrl: String, providerType: OnlineProviderType) {
        val cleanTarget = InputAndUrlSecurity.sanitizeSingleLineText(idOrUrl, 300)
        if (cleanTarget.isBlank()) return
        albumJob?.cancel()
        _albumDetailState.value = OnlineAlbumDetailState(isLoading = true)
        albumJob = scope.launch {
            runCatching {
                providerManager.fetchAlbumOrPlaylist(cleanTarget, providerType)
            }.onSuccess { page ->
                _albumDetailState.value = OnlineAlbumDetailState(isLoading = false, page = page)
                refreshProviderHealth()
            }.onFailure { err ->
                _albumDetailState.value = OnlineAlbumDetailState(
                    isLoading = false,
                    errorMessage = err.localizedMessage ?: "Could not load album or playlist"
                )
            }
        }
    }

    fun clearAlbumDetail() {
        albumJob?.cancel()
        _albumDetailState.value = OnlineAlbumDetailState()
    }

    fun playTracks(tracks: List<OnlineTrack>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        scope.launch {
            tracks.take(40).forEach { repository.upsertTrackPreservingStats(it) }
            val bridge = ensureOnlineBridge()
            if (bridge != null) {
                bridge.playOnlineTracks(tracks, startIndex, shuffle)
            } else {
                // Fallback state reflection if service cannot bind (e.g. unit test environment)
                val safeIdx = startIndex.coerceIn(tracks.indices)
                PlaybackRuntimeBus.updateOnlineSnapshot {
                    it.copy(
                        currentTrack = tracks[safeIdx],
                        queue = tracks,
                        currentIndex = safeIdx,
                        isPlaying = false
                    )
                }
            }
        }
    }

    fun togglePlayPause() {
        scope.launch {
            ensureOnlineBridge()?.togglePlayPause()
        }
    }

    fun seekTo(positionMs: Long) {
        PlaybackRuntimeBus.onlineBridge?.seekTo(positionMs)
    }

    fun skipNext() {
        PlaybackRuntimeBus.onlineBridge?.skipNext()
    }

    fun skipPrevious() {
        PlaybackRuntimeBus.onlineBridge?.skipPrevious()
    }

    fun toggleShuffle() {
        PlaybackRuntimeBus.onlineBridge?.toggleShuffle()
    }

    fun cycleRepeatMode() {
        PlaybackRuntimeBus.onlineBridge?.cycleRepeatMode()
    }

    fun setPlaybackSpeed(speed: Float) {
        PlaybackRuntimeBus.onlineBridge?.setPlaybackSpeed(speed)
    }

    fun retryCurrentTrack() {
        PlaybackRuntimeBus.onlineBridge?.retryCurrentTrack()
    }

    fun addToQueue(tracks: List<OnlineTrack>) {
        if (tracks.isEmpty()) return
        scope.launch {
            tracks.forEach { repository.upsertTrackPreservingStats(it) }
            ensureOnlineBridge()?.addToQueue(tracks)
            emitMessage("Added ${tracks.size} to online queue")
        }
    }

    fun playNext(tracks: List<OnlineTrack>) {
        if (tracks.isEmpty()) return
        scope.launch {
            tracks.forEach { repository.upsertTrackPreservingStats(it) }
            ensureOnlineBridge()?.playNext(tracks)
            emitMessage("Added ${tracks.size} to play next")
        }
    }

    fun jumpToQueueIndex(index: Int) {
        PlaybackRuntimeBus.onlineBridge?.jumpToQueueIndex(index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        PlaybackRuntimeBus.onlineBridge?.moveQueueItem(fromIndex, toIndex)
    }

    fun removeQueueItem(index: Int) {
        PlaybackRuntimeBus.onlineBridge?.removeQueueItem(index)
    }

    fun clearQueue() {
        PlaybackRuntimeBus.onlineBridge?.clearQueue()
        emitMessage("Online queue cleared")
    }

    fun shuffleCurrentQueue() {
        PlaybackRuntimeBus.onlineBridge?.shuffleCurrentQueue()
        emitMessage("Online queue shuffled")
    }

    fun saveQueueAsPlaylist(name: String) {
        val queue = snapshot.value.queue
        if (queue.isEmpty()) return
        val cleanName = InputAndUrlSecurity.sanitizeSingleLineText(name, 80).ifBlank { "Online Queue" }
        createPlaylist(cleanName, "Saved from online stream queue", queue)
    }

    fun toggleFavorite(track: OnlineTrack) {
        scope.launch {
            val nowFav = repository.toggleFavorite(track)
            emitMessage(if (nowFav) "Saved to online favorites" else "Removed from online favorites")
        }
    }

    fun clearListeningHistory() {
        scope.launch {
            repository.clearListeningHistory()
            emitMessage("Online listening history cleared")
        }
    }

    fun createPlaylist(name: String, description: String = "", tracks: List<OnlineTrack> = emptyList()) {
        val cleanName = InputAndUrlSecurity.sanitizeSingleLineText(name, 80)
        if (cleanName.isBlank()) return
        val cleanDesc = InputAndUrlSecurity.sanitizeMultilineText(description)
        scope.launch {
            repository.createPlaylist(cleanName, cleanDesc, tracks)
            emitMessage("Created online playlist \"$cleanName\"")
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String, newDesc: String) {
        val cleanName = InputAndUrlSecurity.sanitizeSingleLineText(newName, 80)
        if (cleanName.isBlank()) return
        val cleanDesc = InputAndUrlSecurity.sanitizeMultilineText(newDesc)
        scope.launch {
            repository.renamePlaylist(playlistId, cleanName, cleanDesc)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        scope.launch {
            repository.deletePlaylist(playlistId)
            emitMessage("Online playlist deleted")
        }
    }

    fun addTracksToPlaylist(playlistId: Long, tracks: List<OnlineTrack>) {
        if (tracks.isEmpty()) return
        scope.launch {
            repository.addTracksToPlaylist(playlistId, tracks)
            emitMessage("Added ${tracks.size} track(s) to playlist")
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, index: Int) {
        scope.launch {
            repository.removeTrackFromPlaylist(playlistId, index)
        }
    }

    fun reorderPlaylistTracks(playlistId: Long, fromIndex: Int, toIndex: Int) {
        scope.launch {
            repository.reorderPlaylistTracks(playlistId, fromIndex, toIndex)
        }
    }

    fun resolveSmartPlaylist(type: OnlineSmartPlaylistType): List<OnlineTrack> {
        return when (type) {
            OnlineSmartPlaylistType.FAVORITES -> favorites.value
            OnlineSmartPlaylistType.RECENTLY_PLAYED -> recentlyPlayed.value
            OnlineSmartPlaylistType.MOST_PLAYED -> mostPlayed.value
            OnlineSmartPlaylistType.HISTORY -> history.value.map { it.track }
        }
    }

    fun resolveOnlineTrackById(id: String): OnlineTrack? {
        return allTracks.value.firstOrNull { it.id == id }
            ?: snapshot.value.queue.firstOrNull { it.id == id }
            ?: _trendingState.value.tracks.firstOrNull { it.id == id }
            ?: _searchState.value.tracks.firstOrNull { it.id == id }
            ?: snapshot.value.relatedTracks.firstOrNull { it.id == id }
    }

    private suspend fun ensureOnlineBridge(): OnlinePlaybackBridge? {
        PlaybackRuntimeBus.onlineBridge?.let { return it }
        runCatching {
            app.startService(Intent(app, PlaybackService::class.java))
        }
        repeat(24) {
            delay(50L)
            PlaybackRuntimeBus.onlineBridge?.let { return it }
        }
        return PlaybackRuntimeBus.onlineBridge
    }
}
