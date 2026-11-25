package app.nocturne.player.data.online

import android.content.Context
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.db.OnlineHistoryEntity
import app.nocturne.player.data.db.OnlinePlaylistEntity
import app.nocturne.player.data.db.OnlineRecentSearchEntity
import app.nocturne.player.data.db.OnlineTrackEntity
import app.nocturne.player.data.model.OnlineHistoryEntry
import app.nocturne.player.data.model.OnlinePlaylist
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.recommendation.AffinityRepository
import app.nocturne.player.data.recommendation.RecommendationEngine
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class OnlineLibraryRepository(
    private val db: AriaDatabase,
    private val affinityRepo: AffinityRepository = AffinityRepository(db)
) {

    private val trackDao = db.onlineTrackDao()
    private val playlistDao = db.onlinePlaylistDao()
    private val searchDao = db.onlineSearchHistoryDao()

    val allTracks: Flow<List<OnlineTrack>> = trackDao.observeAllTracks()
        .map { list -> list.map { it.toDomain() } }

    val favorites: Flow<List<OnlineTrack>> = trackDao.observeFavorites()
        .map { list -> list.map { it.toDomain() } }

    val recentlyPlayed: Flow<List<OnlineTrack>> = trackDao.observeRecentlyPlayed()
        .map { list -> list.map { it.toDomain() } }

    val mostPlayed: Flow<List<OnlineTrack>> = trackDao.observeMostPlayed()
        .map { list -> list.map { it.toDomain() } }

    val history: Flow<List<OnlineHistoryEntry>> = combine(
        trackDao.observeHistory(),
        trackDao.observeAllTracks()
    ) { histRows, tracks ->
        val trackMap = tracks.associateBy { it.id }
        histRows.mapNotNull { row ->
            trackMap[row.trackId]?.let { entity ->
                OnlineHistoryEntry(
                    entryId = row.entryId,
                    track = entity.toDomain(),
                    playedAt = row.playedAt
                )
            }
        }
    }

    val playlists: Flow<List<OnlinePlaylist>> = combine(
        playlistDao.observePlaylists(),
        playlistDao.observeAllEntries(),
        trackDao.observeAllTracks()
    ) { entities, entries, tracks ->
        val trackMap = tracks.associate { it.id to it.toDomain() }
        val grouped = entries.groupBy { it.playlistId }
        entities.map { entity ->
            val plTracks = grouped[entity.id].orEmpty()
                .sortedBy { it.position }
                .mapNotNull { trackMap[it.trackId] }
            OnlinePlaylist(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                coverUrl = entity.coverUrl ?: plTracks.firstOrNull()?.thumbnailUrl,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                tracks = plTracks
            )
        }
    }

    val recentSearches: Flow<List<String>> = searchDao.observeRecentSearches()
        .map { list -> list.map { it.query } }

    suspend fun upsertTrackPreservingStats(track: OnlineTrack) = withContext(Dispatchers.IO) {
        val existing = trackDao.getById(track.id)
        val merged = if (existing != null) {
            existing.copy(
                title = track.title.ifBlank { existing.title },
                artist = track.artist.ifBlank { existing.artist },
                artistId = track.artistId ?: existing.artistId,
                artistUrl = track.artistUrl ?: existing.artistUrl,
                album = track.album.ifBlank { existing.album },
                albumUrl = track.albumUrl ?: existing.albumUrl,
                durationMs = if (track.durationMs > 0L) track.durationMs else existing.durationMs,
                thumbnailUrl = track.thumbnailUrl ?: existing.thumbnailUrl,
                sourceUrl = track.sourceUrl.ifBlank { existing.sourceUrl }
            )
        } else {
            track.toEntity()
        }
        trackDao.upsert(merged)
    }

    suspend fun toggleFavorite(track: OnlineTrack): Boolean = withContext(Dispatchers.IO) {
        val existing = trackDao.getById(track.id)
        val newFav = !(existing?.isFavorite ?: track.isFavorite)
        val updated = (existing ?: track.toEntity()).copy(
            title = track.title.ifBlank { existing?.title ?: "Untitled" },
            artist = track.artist.ifBlank { existing?.artist ?: "Unknown" },
            thumbnailUrl = track.thumbnailUrl ?: existing?.thumbnailUrl,
            isFavorite = newFav,
            favoritedAt = if (newFav) System.currentTimeMillis() else 0L
        )
        trackDao.upsert(updated)
        affinityRepo.recordTrackLiked(track, newFav)
        newFav
    }

    suspend fun recordTrackPlayed(track: OnlineTrack, initialListenMs: Long = 0L) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = trackDao.getById(track.id)
        val base = existing ?: track.toEntity()
        trackDao.upsert(
            base.copy(
                title = track.title.ifBlank { base.title },
                artist = track.artist.ifBlank { base.artist },
                thumbnailUrl = track.thumbnailUrl ?: base.thumbnailUrl,
                durationMs = if (track.durationMs > 0L) track.durationMs else base.durationMs,
                playCount = base.playCount + 1,
                lastPlayedAt = now,
                totalListenMs = base.totalListenMs + initialListenMs
            )
        )
        trackDao.insertHistory(OnlineHistoryEntity(trackId = track.id, playedAt = now))
    }

    suspend fun addListenTime(trackId: String, deltaMs: Long) = withContext(Dispatchers.IO) {
        val existing = trackDao.getById(trackId) ?: return@withContext
        trackDao.upsert(
            existing.copy(
                lastPlayedAt = System.currentTimeMillis(),
                totalListenMs = existing.totalListenMs + deltaMs
            )
        )
    }

    suspend fun clearListeningHistory() = withContext(Dispatchers.IO) {
        trackDao.clearHistory()
        trackDao.resetLastPlayedTimestamps()
    }

    suspend fun createPlaylist(
        name: String,
        description: String = "",
        tracks: List<OnlineTrack> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        val cleanName = InputAndUrlSecurity.sanitizeSingleLineText(name, 80)
        require(cleanName.isNotBlank()) { "Playlist name cannot be blank" }
        val cleanDesc = InputAndUrlSecurity.sanitizeMultilineText(description)
        val safeCover = InputAndUrlSecurity.validateRemoteHttpsUrl(tracks.firstOrNull()?.thumbnailUrl)
        val now = System.currentTimeMillis()
        tracks.forEach { upsertTrackPreservingStats(it) }
        val id = playlistDao.insertPlaylist(
            OnlinePlaylistEntity(
                name = cleanName,
                description = cleanDesc,
                coverUrl = safeCover,
                createdAt = now,
                updatedAt = now
            )
        )
        if (tracks.isNotEmpty()) {
            playlistDao.replaceEntries(id, tracks.map { it.id })
            tracks.forEach { affinityRepo.recordTrackAddedToPlaylist(it) }
        }
        id
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String, newDesc: String) = withContext(Dispatchers.IO) {
        val cleanName = InputAndUrlSecurity.sanitizeSingleLineText(newName, 80)
        if (cleanName.isBlank()) return@withContext
        val cleanDesc = InputAndUrlSecurity.sanitizeMultilineText(newDesc)
        val cur = playlistDao.getPlaylistsOnce().firstOrNull { it.id == playlistId } ?: return@withContext
        playlistDao.updatePlaylist(
            cur.copy(
                name = cleanName,
                description = cleanDesc,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTracksToPlaylist(playlistId: Long, tracks: List<OnlineTrack>) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        tracks.forEach { upsertTrackPreservingStats(it) }
        val existingIds = playlistDao.getEntriesForPlaylist(playlistId).map { it.trackId }
        val updated = existingIds + tracks.map { it.id }
        playlistDao.replaceEntries(playlistId, updated)
        tracks.forEach { affinityRepo.recordTrackAddedToPlaylist(it) }
        playlistDao.getPlaylistsOnce().firstOrNull { it.id == playlistId }?.let { pl ->
            playlistDao.updatePlaylist(
                pl.copy(
                    coverUrl = pl.coverUrl ?: InputAndUrlSecurity.validateRemoteHttpsUrl(tracks.firstOrNull()?.thumbnailUrl),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, index: Int) = withContext(Dispatchers.IO) {
        val entries = playlistDao.getEntriesForPlaylist(playlistId).map { it.trackId }.toMutableList()
        if (index in entries.indices) {
            entries.removeAt(index)
            playlistDao.replaceEntries(playlistId, entries)
        }
    }

    suspend fun reorderPlaylistTracks(playlistId: Long, fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        val entries = playlistDao.getEntriesForPlaylist(playlistId).map { it.trackId }.toMutableList()
        if (fromIndex in entries.indices && toIndex in entries.indices && fromIndex != toIndex) {
            val moved = entries.removeAt(fromIndex)
            entries.add(toIndex, moved)
            playlistDao.replaceEntries(playlistId, entries)
        }
    }

    suspend fun recordSearch(query: String) = withContext(Dispatchers.IO) {
        val clean = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (clean.isNotBlank()) {
            searchDao.upsert(OnlineRecentSearchEntity(query = clean, searchedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteRecentSearch(query: String) = withContext(Dispatchers.IO) {
        searchDao.delete(query)
    }

    suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        searchDao.clearAll()
    }
}

private fun OnlineTrackEntity.toDomain(): OnlineTrack {
    val pType = runCatching { OnlineProviderType.valueOf(providerType) }
        .getOrDefault(OnlineProviderType.YOUTUBE)
    val reconstructedProviderId = if (pType == OnlineProviderType.DYNAMIC && id.startsWith("DYNAMIC:")) {
        id.removePrefix("DYNAMIC:").substringBefore(":").ifBlank { pType.defaultProviderId() }
    } else {
        pType.defaultProviderId()
    }
    return OnlineTrack(
        id = id,
        sourceId = sourceId,
        providerType = pType,
        providerLabel = providerLabel,
        sourceUrl = sourceUrl,
        title = title,
        artist = artist,
        artistId = artistId,
        artistUrl = artistUrl,
        album = album,
        albumUrl = albumUrl,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        publishedText = publishedText,
        isLive = isLive,
        isFavorite = isFavorite,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        totalListenMs = totalListenMs,
        providerId = reconstructedProviderId
    )
}

private fun OnlineTrack.toEntity(): OnlineTrackEntity {
    return OnlineTrackEntity(
        id = id,
        sourceId = sourceId,
        providerType = providerType.name,
        providerLabel = providerLabel,
        sourceUrl = sourceUrl,
        title = title,
        artist = artist,
        artistId = artistId,
        artistUrl = artistUrl,
        album = album,
        albumUrl = albumUrl,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        publishedText = publishedText,
        isLive = isLive,
        isFavorite = isFavorite,
        favoritedAt = if (isFavorite) System.currentTimeMillis() else 0L,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        totalListenMs = totalListenMs
    )
}

object OnlineDependencies {
    @Volatile
    private var remoteConfigRepository: RemoteConfigRepository? = null

    @Volatile
    private var healthTracker: ProviderHealthTracker? = null

    @Volatile
    private var providerManager: OnlineProviderManager? = null

    @Volatile
    private var repository: OnlineLibraryRepository? = null

    @Volatile
    private var affinityRepository: AffinityRepository? = null

    @Volatile
    private var recommendationEngine: RecommendationEngine? = null

    fun getHealthTracker(): ProviderHealthTracker {
        return healthTracker ?: synchronized(this) {
            healthTracker ?: ProviderHealthTracker().also {
                healthTracker = it
            }
        }
    }

    fun getRemoteConfigRepository(context: Context): RemoteConfigRepository {
        return remoteConfigRepository ?: synchronized(this) {
            remoteConfigRepository ?: RemoteConfigRepository(
                app.nocturne.player.data.prefs.SettingsRepository(context.applicationContext)
            ).also {
                remoteConfigRepository = it
            }
        }
    }

    fun getProviderManager(context: Context): OnlineProviderManager {
        val appCtx = context.applicationContext
        val configRepo = getRemoteConfigRepository(appCtx)
        val tracker = getHealthTracker()
        return providerManager ?: synchronized(this) {
            providerManager ?: OnlineProviderManager(
                providers = null,
                healthTracker = tracker,
                networkMonitor = NetworkMonitor(appCtx),
                remoteConfigRepository = configRepo
            ).also {
                providerManager = it
            }
        }
    }

    fun getAffinityRepository(context: Context): AffinityRepository {
        val appCtx = context.applicationContext
        return affinityRepository ?: synchronized(this) {
            affinityRepository ?: AffinityRepository(AriaDatabase.getInstance(appCtx)).also {
                affinityRepository = it
            }
        }
    }

    fun getRecommendationEngine(context: Context): RecommendationEngine {
        val appCtx = context.applicationContext
        val aff = getAffinityRepository(appCtx)
        val pm = getProviderManager(appCtx)
        return recommendationEngine ?: synchronized(this) {
            recommendationEngine ?: RecommendationEngine(
                affinityRepository = aff,
                providerManager = pm
            ).also {
                recommendationEngine = it
            }
        }
    }

    fun getRepository(context: Context): OnlineLibraryRepository {
        val appCtx = context.applicationContext
        val aff = getAffinityRepository(appCtx)
        return repository ?: synchronized(this) {
            repository ?: OnlineLibraryRepository(
                db = AriaDatabase.getInstance(appCtx),
                affinityRepo = aff
            ).also {
                repository = it
            }
        }
    }
}
