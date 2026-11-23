package app.nocturne.player.data.online

import android.content.Context
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAlbumPage
import app.nocturne.player.data.model.OnlineAlbumSummary
import app.nocturne.player.data.model.OnlineArtistPage
import app.nocturne.player.data.model.OnlineArtistSummary
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineSearchPage
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.ProviderHealthSnapshot
import app.nocturne.player.data.prefs.SettingsRepository
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.abs

class OnlineProviderManager(
    private val providers: Map<OnlineProviderType, OnlineMusicProvider>,
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    private val streamCache: StreamResolutionCache = StreamResolutionCache(),
    private val networkMonitor: NetworkMonitor? = null
) {
    constructor(
        context: Context,
        remoteConfigRepository: RemoteConfigRepository = RemoteConfigRepository(
            SettingsRepository(context.applicationContext)
        )
    ) : this(
        providers = mapOf(
            OnlineProviderType.JIOSAAVN to JioSaavnProvider(
                dynamicBaseUrlsProvider = { remoteConfigRepository.jiosaavnEndpoints() }
            ),
            OnlineProviderType.YOUTUBE to NewPipeProvider(OnlineProviderType.YOUTUBE),
            OnlineProviderType.PIPED to PipedProvider(
                dynamicBaseUrlsProvider = { remoteConfigRepository.pipedEndpoints() }
            ),
            OnlineProviderType.INVIDIOUS to InvidiousProvider(
                dynamicBaseUrlsProvider = { remoteConfigRepository.invidiousEndpoints() }
            ),
            OnlineProviderType.AUDIUS to AudiusProvider(),
            OnlineProviderType.SOUNDCLOUD to NewPipeProvider(OnlineProviderType.SOUNDCLOUD),
            OnlineProviderType.BANDCAMP to NewPipeProvider(OnlineProviderType.BANDCAMP)
        ),
        healthTracker = ProviderHealthTracker(),
        streamCache = StreamResolutionCache(),
        networkMonitor = NetworkMonitor(context.applicationContext)
    )

    fun currentSpeedTier(): NetworkSpeedTier =
        networkMonitor?.currentSpeedTier() ?: NetworkSpeedTier.STANDARD

    fun getHealthSnapshots(): List<ProviderHealthSnapshot> =
        healthTracker.snapshotAll(providers.keys.toList())

    fun recordStreamFailure(providerType: OnlineProviderType) {
        healthTracker.recordFailure(providerType)
    }

    private val requestTimestampsMs = ArrayDeque<Long>()

    private fun enforceRateLimit() {
        val now = System.currentTimeMillis()
        synchronized(requestTimestampsMs) {
            while (requestTimestampsMs.isNotEmpty() && now - requestTimestampsMs.first() > RATE_LIMIT_WINDOW_MS) {
                requestTimestampsMs.removeFirst()
            }
            if (requestTimestampsMs.size >= MAX_REQUESTS_PER_WINDOW) {
                throw IOException("Online query rate limit exceeded; please wait a moment")
            }
            requestTimestampsMs.addLast(now)
        }
    }

    suspend fun search(
        query: String,
        filter: OnlineSearchFilter = OnlineSearchFilter.ALL,
        pageToken: String? = null,
        preferredProvider: OnlineProviderType? = null
    ): OnlineSearchPage = withContext(Dispatchers.IO) {
        val cleanQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        require(cleanQuery.isNotBlank()) { "Query cannot be blank" }
        enforceRateLimit()
        val explicitCompilation = isExplicitCompilationQuery(cleanQuery)
        val applySingleTrackFilter =
            (filter == OnlineSearchFilter.ALL || filter == OnlineSearchFilter.SONGS) && !explicitCompilation

        val candidateOrder = if (pageToken != null && preferredProvider != null) {
            listOf(preferredProvider)
        } else {
            when (filter) {
                OnlineSearchFilter.JIOSAAVN -> listOf(OnlineProviderType.JIOSAAVN)
                OnlineSearchFilter.AUDIUS -> listOf(OnlineProviderType.AUDIUS)
                OnlineSearchFilter.SOUNDCLOUD -> listOf(OnlineProviderType.SOUNDCLOUD)
                OnlineSearchFilter.BANDCAMP -> listOf(OnlineProviderType.BANDCAMP)
                OnlineSearchFilter.ARTISTS, OnlineSearchFilter.ALBUMS ->
                    healthTracker.selectOrderedCandidates(
                        listOf(
                            OnlineProviderType.JIOSAAVN,
                            OnlineProviderType.AUDIUS,
                            OnlineProviderType.YOUTUBE,
                            OnlineProviderType.PIPED,
                            OnlineProviderType.INVIDIOUS,
                            OnlineProviderType.SOUNDCLOUD
                        )
                    )
                OnlineSearchFilter.SONGS ->
                    healthTracker.selectOrderedCandidates(
                        if (explicitCompilation) {
                            listOf(
                                OnlineProviderType.YOUTUBE,
                                OnlineProviderType.PIPED,
                                OnlineProviderType.INVIDIOUS,
                                OnlineProviderType.SOUNDCLOUD,
                                OnlineProviderType.JIOSAAVN,
                                OnlineProviderType.AUDIUS
                            )
                        } else {
                            listOf(
                                OnlineProviderType.JIOSAAVN,
                                OnlineProviderType.AUDIUS,
                                OnlineProviderType.YOUTUBE,
                                OnlineProviderType.PIPED,
                                OnlineProviderType.INVIDIOUS,
                                OnlineProviderType.SOUNDCLOUD
                            )
                        }
                    )
                OnlineSearchFilter.ALL ->
                    healthTracker.selectOrderedCandidates(
                        if (explicitCompilation) {
                            listOf(
                                OnlineProviderType.YOUTUBE,
                                OnlineProviderType.PIPED,
                                OnlineProviderType.INVIDIOUS,
                                OnlineProviderType.SOUNDCLOUD,
                                OnlineProviderType.BANDCAMP,
                                OnlineProviderType.JIOSAAVN,
                                OnlineProviderType.AUDIUS
                            )
                        } else {
                            listOf(
                                OnlineProviderType.JIOSAAVN,
                                OnlineProviderType.AUDIUS,
                                OnlineProviderType.YOUTUBE,
                                OnlineProviderType.PIPED,
                                OnlineProviderType.INVIDIOUS,
                                OnlineProviderType.SOUNDCLOUD,
                                OnlineProviderType.BANDCAMP
                            )
                        }
                    )
            }
        }

        var lastError: Throwable? = null
        var firstFilteredEmptyPage: OnlineSearchPage? = null
        for (providerType in candidateOrder) {
            val adapter = providers[providerType] ?: continue
            val startMs = System.currentTimeMillis()
            try {
                val page = adapter.search(cleanQuery, filter, pageToken)
                val elapsed = System.currentTimeMillis() - startMs
                healthTracker.recordSuccess(providerType, elapsed, activeInstanceUrlOf(adapter))
                val deduped = deduplicateTracks(page.tracks)
                val finalTracks = if (applySingleTrackFilter) {
                    filterSingleSongs(deduped, query = cleanQuery)
                } else {
                    deduped
                }
                val resolvedPage = page.copy(tracks = finalTracks)
                if (finalTracks.isNotEmpty() || page.artists.isNotEmpty() || page.albums.isNotEmpty() || pageToken != null) {
                    return@withContext resolvedPage
                }
                if (firstFilteredEmptyPage == null) {
                    firstFilteredEmptyPage = resolvedPage
                }
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordFailure(providerType)
            }
        }
        if (firstFilteredEmptyPage != null) {
            return@withContext firstFilteredEmptyPage
        }
        throw lastError ?: IllegalStateException("No online music provider could fulfill search")
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val clean = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (clean.length < 2) return@withContext emptyList()
        enforceRateLimit()
        val ordered = healthTracker.selectOrderedCandidates(
            listOf(
                OnlineProviderType.YOUTUBE,
                OnlineProviderType.JIOSAAVN,
                OnlineProviderType.PIPED,
                OnlineProviderType.INVIDIOUS,
                OnlineProviderType.AUDIUS,
                OnlineProviderType.SOUNDCLOUD
            )
        )
        for (providerType in ordered) {
            val adapter = providers[providerType] ?: continue
            val start = System.currentTimeMillis()
            try {
                val list = adapter.suggestions(clean)
                healthTracker.recordSuccess(providerType, System.currentTimeMillis() - start, activeInstanceUrlOf(adapter))
                if (list.isNotEmpty()) return@withContext list.distinct().take(10)
            } catch (_: Throwable) {
                healthTracker.recordFailure(providerType)
            }
        }
        emptyList()
    }

    suspend fun trending(): Pair<List<OnlineTrack>, OnlineProviderType?> = withContext(Dispatchers.IO) {
        val ordered = healthTracker.selectOrderedCandidates(
            listOf(
                OnlineProviderType.JIOSAAVN,
                OnlineProviderType.AUDIUS,
                OnlineProviderType.PIPED,
                OnlineProviderType.INVIDIOUS,
                OnlineProviderType.YOUTUBE,
                OnlineProviderType.SOUNDCLOUD
            )
        )
        var lastError: Throwable? = null
        for (providerType in ordered) {
            val adapter = providers[providerType] ?: continue
            val start = System.currentTimeMillis()
            try {
                val tracks = filterSingleSongs(
                    tracks = deduplicateTracks(adapter.trending()),
                    forceFilter = true
                )
                healthTracker.recordSuccess(providerType, System.currentTimeMillis() - start, activeInstanceUrlOf(adapter))
                if (tracks.isNotEmpty()) {
                    return@withContext tracks to providerType
                }
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordFailure(providerType)
            }
        }
        throw lastError ?: IllegalStateException("Unable to load trending music from any provider")
    }

    suspend fun fetchDiscoveryFeed(
        query: String,
        preferAudiusFirst: Boolean = false
    ): Pair<List<OnlineTrack>, OnlineProviderType?> = withContext(Dispatchers.IO) {
        val cleanQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        require(cleanQuery.isNotBlank()) { "Discovery query cannot be blank" }
        enforceRateLimit()
        val baseOrder = if (preferAudiusFirst) {
            listOf(
                OnlineProviderType.AUDIUS,
                OnlineProviderType.JIOSAAVN,
                OnlineProviderType.PIPED,
                OnlineProviderType.INVIDIOUS,
                OnlineProviderType.YOUTUBE,
                OnlineProviderType.SOUNDCLOUD
            )
        } else {
            listOf(
                OnlineProviderType.JIOSAAVN,
                OnlineProviderType.AUDIUS,
                OnlineProviderType.PIPED,
                OnlineProviderType.INVIDIOUS,
                OnlineProviderType.YOUTUBE,
                OnlineProviderType.SOUNDCLOUD
            )
        }
        val ordered = healthTracker.selectOrderedCandidates(baseOrder)
        var lastError: Throwable? = null
        var partialMatch: Pair<List<OnlineTrack>, OnlineProviderType>? = null

        for (providerType in ordered) {
            val adapter = providers[providerType] ?: continue
            val start = System.currentTimeMillis()
            try {
                val page = adapter.search(cleanQuery, OnlineSearchFilter.SONGS, null)
                val filtered = filterSingleSongs(
                    tracks = deduplicateTracks(page.tracks),
                    forceFilter = true
                )
                healthTracker.recordSuccess(providerType, System.currentTimeMillis() - start, activeInstanceUrlOf(adapter))
                if (filtered.size >= 3) {
                    return@withContext filtered to providerType
                }
                if (filtered.isNotEmpty() && partialMatch == null) {
                    partialMatch = filtered to providerType
                }
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordFailure(providerType)
            }
        }
        partialMatch?.let { return@withContext it.first to it.second }
        throw lastError ?: IllegalStateException("Unable to load $cleanQuery tracks")
    }

    suspend fun fetchPopularArtists(): Pair<List<OnlineArtistSummary>, OnlineProviderType?> =
        withContext(Dispatchers.IO) {
            val ordered = healthTracker.selectOrderedCandidates(
                listOf(
                    OnlineProviderType.JIOSAAVN,
                    OnlineProviderType.AUDIUS,
                    OnlineProviderType.YOUTUBE,
                    OnlineProviderType.PIPED,
                    OnlineProviderType.INVIDIOUS
                )
            )
            var lastError: Throwable? = null
            for (providerType in ordered) {
                val adapter = providers[providerType] ?: continue
                val start = System.currentTimeMillis()
                try {
                    val artistQuery = when (providerType) {
                        OnlineProviderType.JIOSAAVN -> "a"
                        OnlineProviderType.AUDIUS -> "music"
                        else -> "popular music artist"
                    }
                    val page = adapter.search(artistQuery, OnlineSearchFilter.ARTISTS, null)
                    healthTracker.recordSuccess(providerType, System.currentTimeMillis() - start, activeInstanceUrlOf(adapter))
                    val artists = page.artists
                        .filter { it.name.isNotBlank() }
                        .distinctBy { it.id }
                        .sortedByDescending { !it.thumbnailUrl.isNullOrBlank() }
                        .take(16)
                    if (artists.isNotEmpty()) {
                        return@withContext artists to providerType
                    }
                } catch (t: Throwable) {
                    lastError = t
                    healthTracker.recordFailure(providerType)
                }
            }
            if (lastError != null) throw lastError
            emptyList<OnlineArtistSummary>() to null
        }

    suspend fun resolveStream(
        track: OnlineTrack,
        forceRefresh: Boolean = false
    ): OnlineStreamResolution = resolveStream(
        track = track,
        forceRefresh = forceRefresh,
        excludedProviders = emptySet()
    )

    suspend fun resolveStream(
        track: OnlineTrack,
        forceRefresh: Boolean = false,
        excludedProviders: Set<OnlineProviderType> = emptySet()
    ): OnlineStreamResolution = withContext(Dispatchers.IO) {
        if (!forceRefresh && excludedProviders.isEmpty()) {
            streamCache.getIfValid(track.id)?.let { return@withContext it }
        } else {
            streamCache.invalidate(track.id)
        }

        val speedTier = currentSpeedTier()
        val nativeCandidates = when (track.providerType) {
            OnlineProviderType.SOUNDCLOUD -> listOf(OnlineProviderType.SOUNDCLOUD)
            OnlineProviderType.BANDCAMP -> listOf(OnlineProviderType.BANDCAMP)
            OnlineProviderType.JIOSAAVN -> listOf(OnlineProviderType.JIOSAAVN)
            OnlineProviderType.AUDIUS -> listOf(OnlineProviderType.AUDIUS)
            OnlineProviderType.YOUTUBE, OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS -> {
                healthTracker.selectOrderedCandidates(
                    listOf(
                        OnlineProviderType.YOUTUBE,
                        OnlineProviderType.PIPED,
                        OnlineProviderType.INVIDIOUS
                    )
                )
            }
        }.filter { it !in excludedProviders }

        var lastError: Throwable? = null
        val tried = excludedProviders.toMutableSet()

        for (providerType in nativeCandidates) {
            val adapter = providers[providerType] ?: continue
            tried.add(providerType)
            val start = System.currentTimeMillis()
            try {
                val resolved = adapter.resolveStream(track, speedTier)
                if (InputAndUrlSecurity.hasControlChars(resolved.selectedStream.url)) {
                    throw IOException("Rejected malformed stream URL")
                }
                healthTracker.recordSuccess(providerType, System.currentTimeMillis() - start, activeInstanceUrlOf(adapter))
                streamCache.put(track.id, resolved)
                return@withContext resolved
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordFailure(providerType)
            }
        }

        // Cross-provider failover matching by title + artist when native providers fail or throw 403/429
        val fallbackResolution = resolveCrossProviderMatch(
            track = track,
            alreadyTried = tried,
            speedTier = speedTier
        )
        if (fallbackResolution != null) {
            streamCache.put(track.id, fallbackResolution)
            return@withContext fallbackResolution
        }

        throw lastError ?: IllegalStateException("Unable to resolve stream for ${track.title}")
    }

    private suspend fun resolveCrossProviderMatch(
        track: OnlineTrack,
        alreadyTried: Set<OnlineProviderType>,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution? {
        val cleanTitle = cleanMetadataQuery(track.title)
        if (cleanTitle.isBlank() || cleanTitle.equals("Online Track", ignoreCase = true)) {
            return null
        }
        val cleanArtist = cleanMetadataQuery(track.artist)
            .takeUnless { it.equals(track.providerLabel, ignoreCase = true) || it.equals("YouTube", ignoreCase = true) }
            .orEmpty()
        val searchQuery = listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (searchQuery.isBlank()) return null

        val fallbackPool = listOf(
            OnlineProviderType.JIOSAAVN,
            OnlineProviderType.AUDIUS,
            OnlineProviderType.PIPED,
            OnlineProviderType.INVIDIOUS,
            OnlineProviderType.SOUNDCLOUD,
            OnlineProviderType.YOUTUBE
        ).filter { it !in alreadyTried && providers.containsKey(it) }

        if (fallbackPool.isEmpty()) return null
        val orderedFallbacks = healthTracker.selectOrderedCandidates(fallbackPool)

        for (providerType in orderedFallbacks) {
            val adapter = providers[providerType] ?: continue
            val start = System.currentTimeMillis()
            try {
                val page = adapter.search(searchQuery, OnlineSearchFilter.SONGS, null)
                val bestCandidate = pickBestMetadataMatch(track, page.tracks)
                if (bestCandidate != null) {
                    val resolved = adapter.resolveStream(bestCandidate, speedTier)
                    healthTracker.recordSuccess(
                        providerType,
                        System.currentTimeMillis() - start,
                        activeInstanceUrlOf(adapter)
                    )
                    // Preserve the queued track's canonical ID so queue/history/favorites stay intact
                    return resolved.copy(
                        track = track.copy(
                            title = track.title.ifBlank { resolved.track.title },
                            artist = track.artist.ifBlank { resolved.track.artist },
                            thumbnailUrl = track.thumbnailUrl ?: resolved.track.thumbnailUrl,
                            durationMs = if (track.durationMs > 0L) track.durationMs else resolved.track.durationMs
                        )
                    )
                }
            } catch (_: Throwable) {
                healthTracker.recordFailure(providerType)
            }
        }
        return null
    }

    private fun pickBestMetadataMatch(target: OnlineTrack, candidates: List<OnlineTrack>): OnlineTrack? {
        if (candidates.isEmpty()) return null
        val targetTitleTokens = tokenizeNormalized(cleanMetadataQuery(target.title))
        val targetArtistTokens = tokenizeNormalized(cleanMetadataQuery(target.artist))
        if (targetTitleTokens.isEmpty()) return candidates.firstOrNull()

        val scored = candidates.mapNotNull { candidate ->
            val candTitleTokens = tokenizeNormalized(cleanMetadataQuery(candidate.title))
            if (candTitleTokens.isEmpty()) return@mapNotNull null
            val titleOverlap = targetTitleTokens.count { it in candTitleTokens }.toFloat() / targetTitleTokens.size.toFloat()
            if (titleOverlap < 0.4f) return@mapNotNull null

            val candArtistTokens = tokenizeNormalized(cleanMetadataQuery(candidate.artist))
            val artistOverlap = if (targetArtistTokens.isNotEmpty() && candArtistTokens.isNotEmpty()) {
                targetArtistTokens.count { it in candArtistTokens || it in candTitleTokens }.toFloat() /
                    targetArtistTokens.size.toFloat()
            } else 0.5f

            val durationBonus = if (target.durationMs > 0L && candidate.durationMs > 0L) {
                val diffMs = abs(target.durationMs - candidate.durationMs)
                when {
                    diffMs <= 5_000L -> 0.4f
                    diffMs <= 15_000L -> 0.2f
                    diffMs > 45_000L -> -0.35f
                    else -> 0f
                }
            } else 0f

            val score = titleOverlap * 2.0f + artistOverlap * 1.2f + durationBonus
            candidate to score
        }

        return scored.maxByOrNull { it.second }?.takeIf { it.second >= 1.1f }?.first
    }

    fun getCachedStream(trackId: String): OnlineStreamResolution? = streamCache.getIfValid(trackId)

    fun invalidateCachedStream(trackId: String) = streamCache.invalidate(trackId)

    suspend fun fetchArtist(artistIdOrUrl: String, hintProvider: OnlineProviderType): OnlineArtistPage =
        withContext(Dispatchers.IO) {
            val cleanTarget = InputAndUrlSecurity.sanitizeSingleLineText(artistIdOrUrl, 300)
            require(cleanTarget.isNotBlank()) { "Artist identifier cannot be blank" }
            val candidates = when (hintProvider) {
                OnlineProviderType.SOUNDCLOUD -> listOf(OnlineProviderType.SOUNDCLOUD)
                OnlineProviderType.BANDCAMP -> listOf(OnlineProviderType.BANDCAMP)
                OnlineProviderType.JIOSAAVN -> listOf(OnlineProviderType.JIOSAAVN)
                OnlineProviderType.AUDIUS -> listOf(OnlineProviderType.AUDIUS)
                else -> healthTracker.selectOrderedCandidates(
                    listOf(OnlineProviderType.YOUTUBE, OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS)
                )
            }
            var lastError: Throwable? = null
            for (providerType in candidates) {
                val adapter = providers[providerType] ?: continue
                val start = System.currentTimeMillis()
                try {
                    val page = adapter.fetchArtist(cleanTarget)
                    healthTracker.recordSuccess(providerType, System.currentTimeMillis() - start, activeInstanceUrlOf(adapter))
                    return@withContext page.copy(tracks = deduplicateTracks(page.tracks))
                } catch (t: Throwable) {
                    lastError = t
                    healthTracker.recordFailure(providerType)
                }
            }
            throw lastError ?: IllegalStateException("Could not load artist details")
        }

    suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String, hintProvider: OnlineProviderType): OnlineAlbumPage =
        withContext(Dispatchers.IO) {
            val cleanTarget = InputAndUrlSecurity.sanitizeSingleLineText(albumIdOrUrl, 300)
            require(cleanTarget.isNotBlank()) { "Album or playlist identifier cannot be blank" }
            val candidates = when (hintProvider) {
                OnlineProviderType.SOUNDCLOUD -> listOf(OnlineProviderType.SOUNDCLOUD)
                OnlineProviderType.BANDCAMP -> listOf(OnlineProviderType.BANDCAMP)
                OnlineProviderType.JIOSAAVN -> listOf(OnlineProviderType.JIOSAAVN)
                OnlineProviderType.AUDIUS -> listOf(OnlineProviderType.AUDIUS)
                else -> healthTracker.selectOrderedCandidates(
                    listOf(OnlineProviderType.YOUTUBE, OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS)
                )
            }
            var lastError: Throwable? = null
            for (providerType in candidates) {
                val adapter = providers[providerType] ?: continue
                val start = System.currentTimeMillis()
                try {
                    val page = adapter.fetchAlbumOrPlaylist(cleanTarget)
                    healthTracker.recordSuccess(providerType, System.currentTimeMillis() - start, activeInstanceUrlOf(adapter))
                    return@withContext page.copy(tracks = deduplicateTracks(page.tracks))
                } catch (t: Throwable) {
                    lastError = t
                    healthTracker.recordFailure(providerType)
                }
            }
            throw lastError ?: IllegalStateException("Could not load album or playlist details")
        }

    private fun activeInstanceUrlOf(adapter: OnlineMusicProvider): String? = when (adapter) {
        is PipedProvider -> adapter.lastWorkingInstance
        is InvidiousProvider -> adapter.lastWorkingInstance
        is JioSaavnProvider -> adapter.lastWorkingInstance
        is AudiusProvider -> adapter.lastWorkingInstance
        else -> null
    }

    companion object {
        const val MAX_SINGLE_TRACK_DURATION_MS = 600_000L // 10 minutes (600 seconds)
        private const val RATE_LIMIT_WINDOW_MS = 2_000L
        private const val MAX_REQUESTS_PER_WINDOW = 25

        private val noiseBracketRegex = Regex("""(\[[^\]]*]|\([^)]*(official|video|audio|lyrics|lyric|hd|4k|visualizer|remaster|live|feat\.?)[^)]*\))""", RegexOption.IGNORE_CASE)

        private val COMPILATION_TITLE_REGEXES = listOf(
            Regex("""\btop\s+hits\b""", RegexOption.IGNORE_CASE),
            Regex("""\bnon[\s-]?stop\b""", RegexOption.IGNORE_CASE),
            Regex("""\bjukebox\b""", RegexOption.IGNORE_CASE),
            Regex("""\b[1-9]\s*(?:hr|hrs|hour|hours)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bplaylist\b""", RegexOption.IGNORE_CASE),
            Regex("""\bfull\s+album\b""", RegexOption.IGNORE_CASE),
            Regex("""\bmegamix\b""", RegexOption.IGNORE_CASE),
            Regex("""\bmashup\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcompilation\b""", RegexOption.IGNORE_CASE),
            Regex("""\bmix(?:es)?\b""", RegexOption.IGNORE_CASE)
        )

        private val EXPLICIT_COMPILATION_QUERY_REGEXES = listOf(
            Regex("""\bmix(?:es)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\bplaylist(?:s)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\bjukebox\b""", RegexOption.IGNORE_CASE),
            Regex("""\bnon[\s-]?stop\b""", RegexOption.IGNORE_CASE),
            Regex("""\bfull\s+album\b""", RegexOption.IGNORE_CASE),
            Regex("""\bmegamix\b""", RegexOption.IGNORE_CASE),
            Regex("""\bmashup\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcompilation\b""", RegexOption.IGNORE_CASE),
            Regex("""\b[1-9]\s*(?:hr|hrs|hour|hours)\b""", RegexOption.IGNORE_CASE)
        )

        fun isExplicitCompilationQuery(query: String): Boolean {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return false
            return EXPLICIT_COMPILATION_QUERY_REGEXES.any { it.containsMatchIn(trimmed) }
        }

        fun isSingleTrackCandidate(track: OnlineTrack): Boolean {
            if (track.isLive) return false
            if (track.durationMs > MAX_SINGLE_TRACK_DURATION_MS) return false
            val title = track.title.trim()
            if (title.isBlank()) return false
            if (COMPILATION_TITLE_REGEXES.any { it.containsMatchIn(title) }) return false
            return true
        }

        fun filterSingleSongs(
            tracks: List<OnlineTrack>,
            query: String = "",
            forceFilter: Boolean = false
        ): List<OnlineTrack> {
            if (!forceFilter && isExplicitCompilationQuery(query)) {
                return tracks
            }
            return tracks.filter(::isSingleTrackCandidate)
        }

        fun cleanMetadataQuery(raw: String): String {
            return raw
                .replace(noiseBracketRegex, " ")
                .replace(Regex("""\b(ft\.?|feat\.?|featuring)\b.*""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        private fun tokenizeNormalized(text: String): Set<String> {
            return text.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 2 }
                .toSet()
        }

        fun deduplicateTracks(tracks: List<OnlineTrack>): List<OnlineTrack> {
            val seenIds = HashSet<String>(tracks.size)
            val seenSignatures = HashSet<String>(tracks.size)
            val result = ArrayList<OnlineTrack>(tracks.size)
            for (t in tracks) {
                val normTitle = t.title.lowercase().replace(Regex("[^a-z0-9]"), "")
                val normArtist = t.artist.lowercase().replace(Regex("[^a-z0-9]"), "")
                val signature = "$normTitle|$normArtist|${t.durationMs / 5000L}"
                if (seenIds.add(t.id) && seenSignatures.add(signature)) {
                    result.add(t)
                }
            }
            return result
        }
    }
}
