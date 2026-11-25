package app.nocturne.player.data.recommendation

import app.nocturne.player.data.db.AffinityStateEntity
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.db.ArtistAffinityEntity
import app.nocturne.player.data.db.UserAffinityEntity
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.pow

class AffinityRepository(
    private val db: AriaDatabase,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val dao = db.userAffinityDao()
    private val onlineTrackDao = db.onlineTrackDao()
    private val writeMutex = Mutex()

    val onRepeatTracks: Flow<List<OnlineTrack>> = dao.observeOnRepeatTracks(12)
        .map { rows -> rows.map { it.toOnlineTrack() } }

    val jumpBackInTracks: Flow<List<OnlineTrack>> = dao.observeJumpBackInTracks(12)
        .map { rows -> rows.map { it.toOnlineTrack() } }

    val topArtists: Flow<List<ArtistAffinityEntity>> = dao.observeTopArtists(3)

    suspend fun applyWeeklyDecayIfDue(): Int = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            applyWeeklyDecayInternal()
        }
    }

    private suspend fun applyWeeklyDecayInternal(): Int {
        val now = clockMs()
        val state = dao.getAffinityState()
        if (state == null) {
            dao.upsertAffinityState(AffinityStateEntity(id = 1, lastDecayAt = now))
            return 0
        }
        val elapsedMs = now - state.lastDecayAt
        if (elapsedMs < WEEK_MS) return 0

        val weeksElapsed = (elapsedMs / WEEK_MS).toInt().coerceAtLeast(1)
        val factor = WEEKLY_DECAY_FACTOR.pow(weeksElapsed.toDouble())
        dao.applyDecayToTracks(factor, now)
        dao.applyDecayToArtists(factor, now)
        dao.upsertAffinityState(
            AffinityStateEntity(
                id = 1,
                lastDecayAt = state.lastDecayAt + weeksElapsed * WEEK_MS
            )
        )
        return weeksElapsed
    }

    /**
     * Marks a track as actively listened to (>= 5s), updating playCount and lastPlayedAt
     * so Jump Back In, On Repeat, and 2-hour duplicate filtering remain accurate even before 80% completion.
     */
    suspend fun recordTrackEngaged(track: OnlineTrack) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            applyWeeklyDecayInternal()
            val now = clockMs()
            val existing = dao.getByTrackId(track.id)
            val merged = mergeTrackEntity(
                existing = existing,
                track = track,
                now = now
            ).copy(
                playCount = (existing?.playCount ?: 0) + 1,
                lastPlayedAt = now
            )
            dao.upsertTrackAffinity(merged)

            val artistKey = normalizeArtistKey(track.artist)
            if (artistKey.isNotBlank()) {
                val existingArtist = dao.getArtistAffinity(artistKey)
                val mergedArtist = mergeArtistEntity(
                    existing = existingArtist,
                    artistKey = artistKey,
                    track = track,
                    now = now
                ).copy(
                    playCount = (existingArtist?.playCount ?: 0) + 1,
                    lastPlayedAt = now
                )
                dao.upsertArtistAffinity(mergedArtist)
            }
        }
    }

    /**
     * Awards +3.0 affinity when the listener completes >80% of a track.
     */
    suspend fun recordTrackCompleted(
        track: OnlineTrack,
        incrementPlayCount: Boolean = true
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            applyWeeklyDecayInternal()
            val now = clockMs()
            val existing = dao.getByTrackId(track.id)
            val base = mergeTrackEntity(existing = existing, track = track, now = now)
            dao.upsertTrackAffinity(
                base.copy(
                    playCount = if (incrementPlayCount) base.playCount + 1 else base.playCount,
                    lastPlayedAt = now,
                    affinityScore = base.affinityScore + SCORE_COMPLETED_80_PERCENT
                )
            )

            val artistKey = normalizeArtistKey(track.artist)
            if (artistKey.isNotBlank()) {
                val existingArtist = dao.getArtistAffinity(artistKey)
                val artistBase = mergeArtistEntity(
                    existing = existingArtist,
                    artistKey = artistKey,
                    track = track,
                    now = now
                )
                dao.upsertArtistAffinity(
                    artistBase.copy(
                        playCount = if (incrementPlayCount) artistBase.playCount + 1 else artistBase.playCount,
                        lastPlayedAt = now,
                        affinityScore = artistBase.affinityScore + SCORE_COMPLETED_80_PERCENT
                    )
                )
            }
        }
    }

    /**
     * Deducts -3.0 affinity when a track is skipped within the first 15 seconds.
     */
    suspend fun recordTrackSkippedEarly(track: OnlineTrack) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            applyWeeklyDecayInternal()
            val now = clockMs()
            val existing = dao.getByTrackId(track.id)
            val base = mergeTrackEntity(existing = existing, track = track, now = now)
            dao.upsertTrackAffinity(
                base.copy(
                    skipCount = base.skipCount + 1,
                    lastSkippedAt = now,
                    affinityScore = base.affinityScore + SCORE_EARLY_SKIP
                )
            )

            val artistKey = normalizeArtistKey(track.artist)
            if (artistKey.isNotBlank()) {
                val existingArtist = dao.getArtistAffinity(artistKey)
                val artistBase = mergeArtistEntity(
                    existing = existingArtist,
                    artistKey = artistKey,
                    track = track,
                    now = now
                )
                dao.upsertArtistAffinity(
                    artistBase.copy(
                        skipCount = artistBase.skipCount + 1,
                        affinityScore = artistBase.affinityScore + SCORE_EARLY_SKIP
                    )
                )
            }
        }
    }

    /**
     * Awards +5.0 affinity when liked (transition false -> true), or reverses -5.0 when unliked.
     */
    suspend fun recordTrackLiked(track: OnlineTrack, isLiked: Boolean) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            applyWeeklyDecayInternal()
            val now = clockMs()
            val existing = dao.getByTrackId(track.id)
            val wasLiked = existing?.isLiked ?: false
            val delta = when {
                isLiked && !wasLiked -> SCORE_LIKED_OR_PLAYLIST
                !isLiked && wasLiked -> -SCORE_LIKED_OR_PLAYLIST
                else -> 0.0
            }
            val base = mergeTrackEntity(existing = existing, track = track, now = now)
            dao.upsertTrackAffinity(
                base.copy(
                    isLiked = isLiked,
                    affinityScore = base.affinityScore + delta
                )
            )

            val artistKey = normalizeArtistKey(track.artist)
            if (artistKey.isNotBlank() && delta != 0.0) {
                val existingArtist = dao.getArtistAffinity(artistKey)
                val artistBase = mergeArtistEntity(
                    existing = existingArtist,
                    artistKey = artistKey,
                    track = track,
                    now = now
                )
                val likedDelta = if (isLiked) 1 else -1
                dao.upsertArtistAffinity(
                    artistBase.copy(
                        likedCount = (artistBase.likedCount + likedDelta).coerceAtLeast(0),
                        affinityScore = artistBase.affinityScore + delta
                    )
                )
            }
        }
    }

    /**
     * Awards +5.0 affinity when a user adds a track to a playlist.
     */
    suspend fun recordTrackAddedToPlaylist(track: OnlineTrack) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            applyWeeklyDecayInternal()
            val now = clockMs()
            val existing = dao.getByTrackId(track.id)
            val base = mergeTrackEntity(existing = existing, track = track, now = now)
            dao.upsertTrackAffinity(
                base.copy(
                    affinityScore = base.affinityScore + SCORE_LIKED_OR_PLAYLIST
                )
            )

            val artistKey = normalizeArtistKey(track.artist)
            if (artistKey.isNotBlank()) {
                val existingArtist = dao.getArtistAffinity(artistKey)
                val artistBase = mergeArtistEntity(
                    existing = existingArtist,
                    artistKey = artistKey,
                    track = track,
                    now = now
                )
                dao.upsertArtistAffinity(
                    artistBase.copy(
                        affinityScore = artistBase.affinityScore + SCORE_LIKED_OR_PLAYLIST
                    )
                )
            }
        }
    }

    suspend fun hasAffinityHistory(): Boolean = withContext(Dispatchers.IO) {
        dao.countTrackedEntries() > 0
    }

    suspend fun getTopAffinityTracks(limit: Int = 5): List<OnlineTrack> = withContext(Dispatchers.IO) {
        dao.getTopAffinityTracks(limit)
            .filter { it.affinityScore > 0.0 || it.playCount > 0 || it.isLiked }
            .map { it.toOnlineTrack() }
    }

    suspend fun getTopArtists(limit: Int = 3): List<ArtistAffinityEntity> = withContext(Dispatchers.IO) {
        dao.getTopArtists(limit)
            .filter { it.affinityScore > 0.0 || it.playCount > 0 || it.likedCount > 0 }
    }

    suspend fun getFamiliarFavorites(limit: Int = 25): List<OnlineTrack> = withContext(Dispatchers.IO) {
        dao.getFamiliarFavorites(limit).map { it.toOnlineTrack() }
    }

    suspend fun getOnRepeatTracksOnce(limit: Int = 12): List<OnlineTrack> = withContext(Dispatchers.IO) {
        dao.getOnRepeatTracksOnce(limit).map { it.toOnlineTrack() }
    }

    suspend fun getJumpBackInTracksOnce(limit: Int = 12): List<OnlineTrack> = withContext(Dispatchers.IO) {
        dao.getJumpBackInTracksOnce(limit).map { it.toOnlineTrack() }
    }

    suspend fun getRecentlySkippedTrackIds(windowMs: Long = RECENT_SKIP_WINDOW_MS): Set<String> =
        withContext(Dispatchers.IO) {
            val since = (clockMs() - windowMs).coerceAtLeast(0L)
            dao.getRecentlySkippedTrackIds(since).toSet()
        }

    suspend fun getRecentlyPlayedExclusionKeys(windowMs: Long = TWO_HOURS_MS): RecentPlayExclusions =
        withContext(Dispatchers.IO) {
            val since = (clockMs() - windowMs).coerceAtLeast(0L)
            val affinityRows = dao.getAllTrackAffinitiesOnce().filter { it.lastPlayedAt >= since }
            val historyIds = dao.getHistoryTrackIdsSince(since)
            val trackEntities = onlineTrackDao.getAllTracksOnce().filter {
                it.lastPlayedAt >= since || it.id in historyIds
            }

            val ids = LinkedHashSet<String>()
            val signatures = LinkedHashSet<String>()
            affinityRows.forEach { row ->
                ids.add(row.trackId)
                signatures.add(trackSignature(row.title, row.artist))
            }
            historyIds.forEach { ids.add(it) }
            trackEntities.forEach { ent ->
                ids.add(ent.id)
                signatures.add(trackSignature(ent.title, ent.artist))
            }
            RecentPlayExclusions(trackIds = ids, signatures = signatures)
        }

    private fun mergeTrackEntity(
        existing: UserAffinityEntity?,
        track: OnlineTrack,
        now: Long
    ): UserAffinityEntity {
        val primaryArtist = extractPrimaryArtistDisplay(track.artist)
        return if (existing != null) {
            existing.copy(
                artist = primaryArtist.ifBlank { existing.artist },
                artistId = track.artistId ?: existing.artistId,
                artistUrl = track.artistUrl ?: existing.artistUrl,
                title = track.title.ifBlank { existing.title },
                album = track.album.ifBlank { existing.album },
                albumUrl = track.albumUrl ?: existing.albumUrl,
                sourceId = track.sourceId.ifBlank { existing.sourceId },
                providerType = track.providerType.name,
                providerId = track.effectiveProviderId(),
                providerLabel = track.providerLabel.ifBlank { existing.providerLabel },
                sourceUrl = track.sourceUrl.ifBlank { existing.sourceUrl },
                durationMs = if (track.durationMs > 0L) track.durationMs else existing.durationMs,
                thumbnailUrl = track.thumbnailUrl ?: existing.thumbnailUrl
            )
        } else {
            UserAffinityEntity(
                trackId = track.id,
                artist = primaryArtist.ifBlank { "Unknown Artist" },
                artistId = track.artistId,
                artistUrl = track.artistUrl,
                title = track.title.ifBlank { "Untitled" },
                album = track.album.ifBlank { "Online Stream" },
                albumUrl = track.albumUrl,
                sourceId = track.sourceId,
                providerType = track.providerType.name,
                providerId = track.effectiveProviderId(),
                providerLabel = track.providerLabel.ifBlank { track.providerType.displayName },
                sourceUrl = track.sourceUrl,
                durationMs = track.durationMs,
                thumbnailUrl = track.thumbnailUrl,
                playCount = 0,
                skipCount = 0,
                isLiked = track.isFavorite,
                lastPlayedAt = 0L,
                lastSkippedAt = 0L,
                affinityScore = 0.0,
                lastDecayAt = now
            )
        }
    }

    private fun mergeArtistEntity(
        existing: ArtistAffinityEntity?,
        artistKey: String,
        track: OnlineTrack,
        now: Long
    ): ArtistAffinityEntity {
        val displayArtist = extractPrimaryArtistDisplay(track.artist)
        return if (existing != null) {
            existing.copy(
                artistName = displayArtist.ifBlank { existing.artistName },
                artistId = track.artistId ?: existing.artistId,
                artistUrl = track.artistUrl ?: existing.artistUrl,
                providerType = track.providerType.name,
                providerId = track.effectiveProviderId()
            )
        } else {
            ArtistAffinityEntity(
                artistKey = artistKey,
                artistName = displayArtist.ifBlank { "Unknown Artist" },
                artistId = track.artistId,
                artistUrl = track.artistUrl,
                providerType = track.providerType.name,
                providerId = track.effectiveProviderId(),
                playCount = 0,
                skipCount = 0,
                likedCount = 0,
                lastPlayedAt = 0L,
                affinityScore = 0.0,
                lastDecayAt = now
            )
        }
    }

    companion object {
        const val SCORE_COMPLETED_80_PERCENT = 3.0
        const val SCORE_LIKED_OR_PLAYLIST = 5.0
        const val SCORE_EARLY_SKIP = -3.0
        const val WEEKLY_DECAY_FACTOR = 0.95
        const val WEEK_MS = 7L * 24L * 60L * 60L * 1000L
        const val RECENT_SKIP_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
        const val TWO_HOURS_MS = 2L * 60L * 60L * 1000L

        fun extractPrimaryArtistDisplay(rawArtist: String): String {
            val cleaned = rawArtist
                .split(Regex("""\s*(?:,|&|feat\.|ft\.|featuring|\bx\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
                .firstOrNull { it.isNotBlank() }
                ?.trim()
            return cleaned?.takeIf { it.isNotBlank() } ?: rawArtist.trim()
        }

        fun normalizeArtistKey(rawArtist: String): String {
            return extractPrimaryArtistDisplay(rawArtist)
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun trackSignature(title: String, artist: String): String {
            val t = title.lowercase().replace(Regex("[^a-z0-9]"), "")
            val a = normalizeArtistKey(artist).replace(" ", "")
            return "$t|$a"
        }
    }
}

data class RecentPlayExclusions(
    val trackIds: Set<String> = emptySet(),
    val signatures: Set<String> = emptySet()
) {
    fun contains(track: OnlineTrack): Boolean {
        if (track.id in trackIds) return true
        return AffinityRepository.trackSignature(track.title, track.artist) in signatures
    }
}

fun UserAffinityEntity.toOnlineTrack(): OnlineTrack {
    val pType = runCatching { OnlineProviderType.valueOf(providerType) }
        .getOrDefault(OnlineProviderType.JIOSAAVN)
    return OnlineTrack(
        id = trackId,
        sourceId = sourceId,
        providerType = pType,
        providerLabel = providerLabel.ifBlank { pType.displayName },
        sourceUrl = sourceUrl,
        title = title,
        artist = artist,
        artistId = artistId,
        artistUrl = artistUrl,
        album = album,
        albumUrl = albumUrl,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        isFavorite = isLiked,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        providerId = providerId.ifBlank { pType.defaultProviderId() }
    )
}
