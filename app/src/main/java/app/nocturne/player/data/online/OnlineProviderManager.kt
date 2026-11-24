package app.nocturne.player.data.online

import android.content.Context
import app.nocturne.player.data.audio.MetadataCleaner
import app.nocturne.player.data.model.DynamicProviderConfig
import app.nocturne.player.data.model.LyricsCleaningConfig
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
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.model.ProviderConnectivityTestResult
import app.nocturne.player.data.model.ProviderDiagnosticsSnapshot
import app.nocturne.player.data.model.ProviderHealthSnapshot
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.prefs.SettingsRepository
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.ArrayDeque

data class HomeDiscoverySections(
    val trendingCharts: List<OnlineTrack> = emptyList(),
    val newReleases: List<OnlineTrack> = emptyList(),
    val curatedPlaylists: List<OnlineAlbumSummary> = emptyList(),
    val providerType: OnlineProviderType? = null
)

class OnlineProviderManager(
    private val providers: Map<OnlineProviderType, OnlineMusicProvider>? = null,
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    private val streamCache: StreamResolutionCache = StreamResolutionCache(),
    private val networkMonitor: NetworkMonitor? = null,
    private val remoteConfigRepository: RemoteConfigRepository? = null,
    private val httpClient: OkHttpClient = OnlineHttpClient.client,
    private val allowLoopbackForTests: Boolean = false
) {
    constructor(
        context: Context,
        remoteConfigRepository: RemoteConfigRepository = RemoteConfigRepository(
            SettingsRepository(context.applicationContext)
        )
    ) : this(
        providers = null,
        healthTracker = ProviderHealthTracker(),
        streamCache = StreamResolutionCache(),
        networkMonitor = NetworkMonitor(context.applicationContext),
        remoteConfigRepository = remoteConfigRepository,
        httpClient = OnlineHttpClient.client,
        allowLoopbackForTests = false
    )

    constructor(
        remoteConfigRepository: RemoteConfigRepository,
        httpClient: OkHttpClient = OnlineHttpClient.client,
        allowLoopbackForTests: Boolean = false,
        healthTracker: ProviderHealthTracker = ProviderHealthTracker()
    ) : this(
        providers = null,
        healthTracker = healthTracker,
        streamCache = StreamResolutionCache(),
        networkMonitor = null,
        remoteConfigRepository = remoteConfigRepository,
        httpClient = httpClient,
        allowLoopbackForTests = allowLoopbackForTests
    )

    private val sharedExecutor = DynamicHttpExecutor(
        baseClient = httpClient,
        healthTracker = healthTracker,
        allowLoopbackForTests = allowLoopbackForTests
    )

    private val playbackResolver = PlaybackResolver(
        healthTracker = healthTracker,
        validateUrl = ::validateResolvedStreamUrl,
        cleaningConfigProvider = { remoteConfigRepository?.lyricsCleaningConfig() ?: LyricsCleaningConfig() }
    )

    private val registryLock = Any()
    private var cachedConfigSnapshot: RemoteProvidersConfig? = null
    private var dynamicAdapters: Map<String, OnlineMusicProvider> = emptyMap()

    private suspend fun ensureDynamicRegistryReady(): Map<String, OnlineMusicProvider> {
        if (providers != null) {
            return providers.entries.associate { (_, adapter) ->
                adapter.providerId.lowercase() to adapter
            }
        }
        val repo = remoteConfigRepository ?: return emptyMap()
        if (repo.activeConfig.value.providers.isEmpty()) {
            repo.refreshNow(force = false)
        } else {
            repo.triggerStartupRefreshIfNeeded()
        }
        return currentAdaptersSnapshot()
    }

    private fun currentAdaptersSnapshot(): Map<String, OnlineMusicProvider> {
        if (providers != null) {
            return providers.entries.associate { (type, adapter) ->
                (adapter.providerId.ifBlank { type.defaultProviderId() }).lowercase() to adapter
            }
        }
        val repo = remoteConfigRepository ?: return emptyMap()
        val cfg = repo.activeConfig.value
        synchronized(registryLock) {
            if (cachedConfigSnapshot === cfg && dynamicAdapters.isNotEmpty()) {
                return dynamicAdapters
            }
            val built = linkedMapOf<String, OnlineMusicProvider>()
            for (providerCfg in cfg.enabledProviders()) {
                built[providerCfg.id.lowercase()] = instantiateAdapter(providerCfg, repo)
            }
            cachedConfigSnapshot = cfg
            dynamicAdapters = built
            return built
        }
    }

    private fun instantiateAdapter(
        providerCfg: DynamicProviderConfig,
        repo: RemoteConfigRepository
    ): OnlineMusicProvider {
        val id = providerCfg.id.lowercase()
        val configLookup: () -> DynamicProviderConfig? = {
            repo.activeConfig.value.providerById(id)
        }
        val endpointsLookup: () -> List<String> = {
            repo.activeConfig.value.endpointsForProvider(id)
        }
        return when (providerCfg.adapterType.lowercase()) {
            "jiosaavn" -> JioSaavnProvider(
                client = httpClient,
                baseUrlsOverride = endpointsLookup().takeIf { allowLoopbackForTests },
                dynamicBaseUrlsProvider = endpointsLookup,
                healthTracker = healthTracker,
                dynamicConfigProvider = configLookup,
                executorOverride = sharedExecutor
            )
            "piped" -> PipedProvider(
                client = httpClient,
                baseUrlsOverride = endpointsLookup().takeIf { allowLoopbackForTests },
                dynamicBaseUrlsProvider = endpointsLookup,
                healthTracker = healthTracker,
                dynamicConfigProvider = configLookup,
                executorOverride = sharedExecutor
            )
            "invidious" -> InvidiousProvider(
                client = httpClient,
                baseUrlsOverride = endpointsLookup().takeIf { allowLoopbackForTests },
                dynamicBaseUrlsProvider = endpointsLookup,
                healthTracker = healthTracker,
                dynamicConfigProvider = configLookup,
                executorOverride = sharedExecutor
            )
            "audius" -> AudiusProvider(
                client = httpClient,
                baseUrlsOverride = endpointsLookup().takeIf { allowLoopbackForTests },
                dynamicBaseUrlsProvider = endpointsLookup,
                healthTracker = healthTracker,
                dynamicConfigProvider = configLookup,
                executorOverride = sharedExecutor
            )
            "newpipe" -> {
                val pType = OnlineProviderType.fromProviderId(id).let {
                    if (it == OnlineProviderType.DYNAMIC) OnlineProviderType.YOUTUBE else it
                }
                NewPipeProvider(
                    providerType = pType,
                    dynamicConfigProvider = configLookup
                )
            }
            else -> DynamicApiProvider(
                configProvider = { configLookup() ?: providerCfg },
                executor = sharedExecutor,
                healthTracker = healthTracker,
                allowLoopbackForTests = allowLoopbackForTests
            )
        }
    }

    fun currentSpeedTier(): NetworkSpeedTier =
        networkMonitor?.currentSpeedTier() ?: NetworkSpeedTier.STANDARD

    fun getHealthSnapshots(): List<ProviderHealthSnapshot> {
        val adapters = currentAdaptersSnapshot()
        val types = adapters.values.map { it.providerType }.distinct()
        return healthTracker.snapshotAll(types)
    }

    fun getProviderDiagnostics(): List<ProviderDiagnosticsSnapshot> {
        val repo = remoteConfigRepository
        if (repo != null) {
            val cfg = repo.activeConfig.value
            return cfg.providers.sortedBy { it.priority }.map { providerCfg ->
                healthTracker.diagnosticsForProvider(providerCfg)
            }
        }
        return currentAdaptersSnapshot().values.mapIndexed { idx, adapter ->
            val fallbackCfg = adapter.currentConfig() ?: DynamicProviderConfig(
                id = adapter.providerId,
                displayName = adapter.displayName,
                adapterType = adapter.providerType.defaultProviderId(),
                priority = idx + 1
            )
            healthTracker.diagnosticsForProvider(fallbackCfg)
        }
    }

    fun recordStreamFailure(providerType: OnlineProviderType, providerId: String? = null) {
        val id = providerId?.lowercase() ?: providerType.defaultProviderId()
        healthTracker.recordProviderFailure(id, "Playback stream open failed")
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
        preferredProvider: OnlineProviderType? = null,
        preferredProviderId: String? = null
    ): OnlineSearchPage = withContext(Dispatchers.IO) {
        val cleanQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        require(cleanQuery.isNotBlank()) { "Query cannot be blank" }
        enforceRateLimit()

        val adapters = ensureDynamicRegistryReady()
        val explicitCompilation = isExplicitCompilationQuery(cleanQuery)
        val applySingleTrackFilter =
            (filter == OnlineSearchFilter.ALL || filter == OnlineSearchFilter.SONGS) && !explicitCompilation

        val candidateIds = selectSearchProviderOrder(
            adapters = adapters,
            filter = filter,
            explicitCompilation = explicitCompilation,
            preferredProviderId = preferredProviderId ?: preferredProvider?.defaultProviderId(),
            hasPageToken = pageToken != null
        )

        var lastError: Throwable? = null
        var firstFilteredEmptyPage: OnlineSearchPage? = null

        for (pid in candidateIds) {
            val adapter = adapters[pid] ?: continue
            val startMs = System.currentTimeMillis()
            try {
                val page = adapter.search(cleanQuery, filter, pageToken)
                val elapsed = System.currentTimeMillis() - startMs
                healthTracker.recordProviderSuccess(
                    providerId = pid,
                    latencyMs = elapsed,
                    instanceUrl = adapter.activeEndpointFor(OperationType.SEARCH),
                    operation = OperationType.SEARCH
                )
                val deduped = deduplicateTracks(page.tracks)
                val finalTracks = if (applySingleTrackFilter) {
                    filterSingleSongs(deduped, query = cleanQuery)
                } else {
                    deduped
                }
                val resolvedPage = page.copy(
                    tracks = finalTracks,
                    providerUsedId = pid,
                    providerUsedLabel = adapter.displayName
                )
                if (finalTracks.isNotEmpty() || page.artists.isNotEmpty() || page.albums.isNotEmpty() || pageToken != null) {
                    return@withContext resolvedPage
                }
                if (firstFilteredEmptyPage == null) {
                    firstFilteredEmptyPage = resolvedPage
                }
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordProviderFailure(pid, t.message)
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
        val adapters = ensureDynamicRegistryReady()
        val orderedIds = selectOrderedIdsByCapability(adapters, "suggestions")
        for (pid in orderedIds) {
            val adapter = adapters[pid] ?: continue
            val start = System.currentTimeMillis()
            try {
                val list = adapter.suggestions(clean)
                healthTracker.recordProviderSuccess(
                    providerId = pid,
                    latencyMs = System.currentTimeMillis() - start,
                    instanceUrl = adapter.activeEndpointFor(OperationType.SEARCH),
                    operation = OperationType.SEARCH
                )
                if (list.isNotEmpty()) return@withContext list.distinct().take(10)
            } catch (t: Throwable) {
                healthTracker.recordProviderFailure(pid, t.message)
            }
        }
        emptyList()
    }

    suspend fun trending(): Pair<List<OnlineTrack>, OnlineProviderType?> = withContext(Dispatchers.IO) {
        val adapters = ensureDynamicRegistryReady()
        val orderedIds = selectOrderedIdsByCapability(adapters, "trending")
        var lastError: Throwable? = null
        for (pid in orderedIds) {
            val adapter = adapters[pid] ?: continue
            val start = System.currentTimeMillis()
            try {
                val tracks = filterSingleSongs(
                    tracks = deduplicateTracks(adapter.trending()),
                    forceFilter = true
                )
                healthTracker.recordProviderSuccess(
                    providerId = pid,
                    latencyMs = System.currentTimeMillis() - start,
                    instanceUrl = adapter.activeEndpointFor(OperationType.SEARCH),
                    operation = OperationType.SEARCH
                )
                if (tracks.isNotEmpty()) {
                    return@withContext tracks to adapter.providerType
                }
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordProviderFailure(pid, t.message)
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
        val adapters = ensureDynamicRegistryReady()
        val baseIds = selectOrderedIdsByCapability(adapters, "search").let { ids ->
            if (preferAudiusFirst && "audius" in ids) {
                listOf("audius") + ids.filterNot { it == "audius" }
            } else {
                ids
            }
        }
        var lastError: Throwable? = null
        var partialMatch: Pair<List<OnlineTrack>, OnlineProviderType>? = null

        for (pid in baseIds) {
            val adapter = adapters[pid] ?: continue
            val start = System.currentTimeMillis()
            try {
                val page = adapter.search(cleanQuery, OnlineSearchFilter.SONGS, null)
                val filtered = filterSingleSongs(
                    tracks = deduplicateTracks(page.tracks),
                    forceFilter = true
                )
                healthTracker.recordProviderSuccess(
                    providerId = pid,
                    latencyMs = System.currentTimeMillis() - start,
                    instanceUrl = adapter.activeEndpointFor(OperationType.SEARCH),
                    operation = OperationType.SEARCH
                )
                if (filtered.size >= 3) {
                    return@withContext filtered to adapter.providerType
                }
                if (filtered.isNotEmpty() && partialMatch == null) {
                    partialMatch = filtered to adapter.providerType
                }
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordProviderFailure(pid, t.message)
            }
        }
        partialMatch?.let { return@withContext it.first to it.second }
        throw lastError ?: IllegalStateException("Unable to load $cleanQuery tracks")
    }

    suspend fun fetchPopularArtists(): Pair<List<OnlineArtistSummary>, OnlineProviderType?> =
        withContext(Dispatchers.IO) {
            val adapters = ensureDynamicRegistryReady()
            val orderedIds = selectOrderedIdsByCapability(adapters, "artist")
            var lastError: Throwable? = null
            for (pid in orderedIds) {
                val adapter = adapters[pid] ?: continue
                val start = System.currentTimeMillis()
                try {
                    val artistQuery = when (adapter.providerType) {
                        OnlineProviderType.JIOSAAVN -> "a"
                        OnlineProviderType.AUDIUS -> "music"
                        else -> "popular music artist"
                    }
                    val page = adapter.search(artistQuery, OnlineSearchFilter.ARTISTS, null)
                    healthTracker.recordProviderSuccess(
                        providerId = pid,
                        latencyMs = System.currentTimeMillis() - start,
                        instanceUrl = adapter.activeEndpointFor(OperationType.SEARCH),
                        operation = OperationType.SEARCH
                    )
                    val artists = page.artists
                        .filter { it.name.isNotBlank() }
                        .distinctBy { it.id }
                        .sortedByDescending { !it.thumbnailUrl.isNullOrBlank() }
                        .take(16)
                    if (artists.isNotEmpty()) {
                        return@withContext artists to adapter.providerType
                    }
                } catch (t: Throwable) {
                    lastError = t
                    healthTracker.recordProviderFailure(pid, t.message)
                }
            }
            if (lastError != null) throw lastError
            emptyList<OnlineArtistSummary>() to null
        }

    suspend fun fetchHomeDiscoverySections(): HomeDiscoverySections = withContext(Dispatchers.IO) {
        val adapters = ensureDynamicRegistryReady()
        val jioAdapter = adapters.values.filterIsInstance<JioSaavnProvider>().firstOrNull()
        coroutineScope {
            val trendingDeferred = async { runCatching { trending() }.getOrNull() }
            val newReleasesDeferred = async {
                runCatching {
                    if (jioAdapter != null) {
                        deduplicateTracks(jioAdapter.fetchNewReleases())
                    } else {
                        fetchDiscoveryFeed("latest new songs").first
                    }
                }.getOrDefault(emptyList())
            }
            val playlistsDeferred = async {
                runCatching {
                    if (jioAdapter != null) {
                        jioAdapter.fetchCuratedPlaylists().distinctBy { it.id }
                    } else {
                        val albumsAdapter = adapters["jiosaavn"] ?: adapters.values.firstOrNull { it.supportsCapability("album") }
                        albumsAdapter?.search("top charts", OnlineSearchFilter.ALBUMS, null)?.albums.orEmpty()
                    }
                }.getOrDefault(emptyList())
            }
            val trendingRes = trendingDeferred.await()
            HomeDiscoverySections(
                trendingCharts = trendingRes?.first.orEmpty(),
                newReleases = newReleasesDeferred.await(),
                curatedPlaylists = playlistsDeferred.await(),
                providerType = trendingRes?.second ?: jioAdapter?.providerType
            )
        }
    }

    suspend fun resolveStream(
        track: OnlineTrack,
        forceRefresh: Boolean = false
    ): OnlineStreamResolution = resolveStream(
        track = track,
        forceRefresh = forceRefresh,
        excludedProviders = emptySet(),
        excludedProviderIds = emptySet()
    )

    suspend fun resolveStream(
        track: OnlineTrack,
        forceRefresh: Boolean = false,
        excludedProviders: Set<OnlineProviderType> = emptySet(),
        excludedProviderIds: Set<String> = emptySet()
    ): OnlineStreamResolution = withContext(Dispatchers.IO) {
        val allExcludedIds = (excludedProviderIds.map { it.lowercase() } +
            excludedProviders.map { it.defaultProviderId() }).toSet()

        if (!forceRefresh && allExcludedIds.isEmpty()) {
            streamCache.getIfValid(track.id)?.let { return@withContext it }
        } else {
            streamCache.invalidate(track.id)
        }

        val adapters = ensureDynamicRegistryReady()
        val speedTier = currentSpeedTier()
        val resolved = playbackResolver.resolve(
            track = track,
            adapters = adapters,
            speedTier = speedTier,
            excludedIds = allExcludedIds
        )
        streamCache.put(track.id, resolved)
        resolved
    }

    private fun validateResolvedStreamUrl(url: String) {
        if (InputAndUrlSecurity.hasControlChars(url)) {
            throw IOException("Rejected malformed stream URL")
        }
        val allowedOrigin = url.takeIf { allowLoopbackForTests || providers != null }
        if (InputAndUrlSecurity.validateRemoteHttpsUrl(url, allowedOrigin) == null) {
            throw IOException("Rejected unsafe stream URL")
        }
    }

    suspend fun fetchArtist(
        artistIdOrUrl: String,
        hintProvider: OnlineProviderType,
        hintProviderId: String? = null
    ): OnlineArtistPage = withContext(Dispatchers.IO) {
        val cleanTarget = InputAndUrlSecurity.sanitizeSingleLineText(artistIdOrUrl, 300)
        require(cleanTarget.isNotBlank()) { "Artist identifier cannot be blank" }
        val adapters = ensureDynamicRegistryReady()
        val targetId = (hintProviderId ?: hintProvider.defaultProviderId()).lowercase()
        val candidates = selectMetadataCandidates(targetId, hintProvider, adapters)

        var lastError: Throwable? = null
        for (pid in candidates) {
            val adapter = adapters[pid] ?: continue
            val start = System.currentTimeMillis()
            try {
                val page = adapter.fetchArtist(cleanTarget)
                healthTracker.recordProviderSuccess(
                    providerId = pid,
                    latencyMs = System.currentTimeMillis() - start,
                    instanceUrl = adapter.activeEndpointFor(OperationType.METADATA),
                    operation = OperationType.METADATA
                )
                return@withContext page.copy(
                    tracks = deduplicateTracks(page.tracks),
                    providerId = pid,
                    providerLabel = page.providerLabel.ifBlank { adapter.displayName }
                )
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordProviderFailure(pid, t.message)
            }
        }
        throw lastError ?: IllegalStateException("Could not load artist details")
    }

    suspend fun fetchAlbumOrPlaylist(
        albumIdOrUrl: String,
        hintProvider: OnlineProviderType,
        hintProviderId: String? = null
    ): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val cleanTarget = InputAndUrlSecurity.sanitizeSingleLineText(albumIdOrUrl, 300)
        require(cleanTarget.isNotBlank()) { "Album or playlist identifier cannot be blank" }
        val adapters = ensureDynamicRegistryReady()
        val targetId = (hintProviderId ?: hintProvider.defaultProviderId()).lowercase()
        val candidates = selectMetadataCandidates(targetId, hintProvider, adapters)

        var lastError: Throwable? = null
        for (pid in candidates) {
            val adapter = adapters[pid] ?: continue
            val start = System.currentTimeMillis()
            try {
                val page = adapter.fetchAlbumOrPlaylist(cleanTarget)
                healthTracker.recordProviderSuccess(
                    providerId = pid,
                    latencyMs = System.currentTimeMillis() - start,
                    instanceUrl = adapter.activeEndpointFor(OperationType.METADATA),
                    operation = OperationType.METADATA
                )
                return@withContext page.copy(
                    tracks = deduplicateTracks(page.tracks),
                    providerId = pid,
                    providerLabel = page.providerLabel.ifBlank { adapter.displayName }
                )
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordProviderFailure(pid, t.message)
            }
        }
        throw lastError ?: IllegalStateException("Could not load album or playlist details")
    }

    suspend fun testProviderConnectivity(providerId: String): ProviderConnectivityTestResult =
        withContext(Dispatchers.IO) {
            val id = providerId.lowercase()
            val adapters = ensureDynamicRegistryReady()
            val cfg = remoteConfigRepository?.activeConfig?.value?.providerById(id)
            val adapter = adapters[id]
            val displayName = cfg?.displayName ?: adapter?.displayName ?: id.uppercase()

            if (cfg != null && !cfg.enabled) {
                return@withContext ProviderConnectivityTestResult(
                    providerId = id,
                    displayName = displayName,
                    success = false,
                    errorSummary = "Disabled in GitHub configuration",
                    details = "Provider is disabled remotely"
                )
            }
            if (adapter == null) {
                return@withContext ProviderConnectivityTestResult(
                    providerId = id,
                    displayName = displayName,
                    success = false,
                    errorSummary = "Provider not registered",
                    details = "No active adapter available"
                )
            }

            healthTracker.resetEndpointCooldowns(id)
            val startNs = System.nanoTime()
            try {
                val tracks = adapter.search("a", OnlineSearchFilter.SONGS, null).tracks
                val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val activeEp = adapter.activeEndpointFor(OperationType.SEARCH)
                healthTracker.recordProviderSuccess(id, latencyMs, activeEp, OperationType.SEARCH)
                ProviderConnectivityTestResult(
                    providerId = id,
                    displayName = displayName,
                    success = true,
                    testedEndpoint = activeEp,
                    latencyMs = latencyMs,
                    details = "Returned ${tracks.size} items via ${activeEp ?: displayName}"
                )
            } catch (t: Throwable) {
                val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val safeErr = InputAndUrlSecurity.sanitizeErrorForDiagnostics(t.message) ?: "Connection failed"
                healthTracker.recordProviderFailure(id, safeErr)
                ProviderConnectivityTestResult(
                    providerId = id,
                    displayName = displayName,
                    success = false,
                    testedEndpoint = adapter.activeEndpointFor(OperationType.SEARCH),
                    latencyMs = latencyMs,
                    errorSummary = safeErr,
                    details = safeErr
                )
            }
        }

    suspend fun testAllProvidersConnectivity(): List<ProviderConnectivityTestResult> =
        withContext(Dispatchers.IO) {
            val adapters = ensureDynamicRegistryReady()
            val ids = remoteConfigRepository?.activeConfig?.value?.providers?.map { it.id }
                ?: adapters.keys.toList()
            ids.map { pid -> testProviderConnectivity(pid) }
        }

    fun getCachedStream(trackId: String): OnlineStreamResolution? = streamCache.getIfValid(trackId)

    fun invalidateCachedStream(trackId: String) = streamCache.invalidate(trackId)

    private fun selectSearchProviderOrder(
        adapters: Map<String, OnlineMusicProvider>,
        filter: OnlineSearchFilter,
        explicitCompilation: Boolean,
        preferredProviderId: String?,
        hasPageToken: Boolean
    ): List<String> {
        if (hasPageToken && !preferredProviderId.isNullOrBlank()) {
            val id = preferredProviderId.lowercase()
            return if (adapters.containsKey(id)) listOf(id) else emptyList()
        }

        val matchingFilter = when (filter) {
            OnlineSearchFilter.SONGS -> {
                val jioKeys = adapters.filter { it.key == "jiosaavn" || it.value.providerType == OnlineProviderType.JIOSAAVN }.keys.toList()
                // Decouple Songs search to JioSaavn only when available so official metadata & 500x500 art are shown without duplicate YouTube videos
                jioKeys
            }
            OnlineSearchFilter.VIDEOS_REMIXES -> {
                listOf("piped", "invidious", "youtube").filter { adapters.containsKey(it) }
            }
            OnlineSearchFilter.JIOSAAVN -> adapters.filter { it.key == "jiosaavn" || it.value.providerType == OnlineProviderType.JIOSAAVN }.keys.toList()
            OnlineSearchFilter.AUDIUS -> adapters.filter { it.key == "audius" || it.value.providerType == OnlineProviderType.AUDIUS }.keys.toList()
            OnlineSearchFilter.SOUNDCLOUD -> adapters.filter { it.key == "soundcloud" || it.value.providerType == OnlineProviderType.SOUNDCLOUD }.keys.toList()
            OnlineSearchFilter.BANDCAMP -> adapters.filter { it.key == "bandcamp" || it.value.providerType == OnlineProviderType.BANDCAMP }.keys.toList()
            else -> emptyList()
        }
        if (matchingFilter.isNotEmpty()) {
            return healthTracker.selectOrderedProviderIds(matchingFilter)
        }
        if (filter == OnlineSearchFilter.VIDEOS_REMIXES) {
            return emptyList()
        }

        val capability = when (filter) {
            OnlineSearchFilter.ARTISTS -> "artist"
            OnlineSearchFilter.ALBUMS -> "album"
            else -> "search"
        }
        val capable = adapters.filterValues { it.supportsCapability(capability) }.keys.toList()
        val ordered = if (providers != null) {
            val legacyPriority = when (filter) {
                OnlineSearchFilter.ARTISTS, OnlineSearchFilter.ALBUMS -> listOf("jiosaavn", "audius", "youtube", "piped", "invidious", "soundcloud")
                OnlineSearchFilter.SONGS -> if (explicitCompilation) {
                    listOf("youtube", "piped", "invidious", "soundcloud", "jiosaavn", "audius")
                } else {
                    listOf("jiosaavn", "audius", "youtube", "piped", "invidious", "soundcloud")
                }
                else -> if (explicitCompilation) {
                    listOf("youtube", "piped", "invidious", "soundcloud", "bandcamp", "jiosaavn", "audius")
                } else {
                    listOf("jiosaavn", "audius", "youtube", "piped", "invidious", "soundcloud", "bandcamp")
                }
            }
            val prioritized = legacyPriority.filter { it in capable }
            val extras = capable.filter { it !in prioritized }
            prioritized + extras
        } else {
            // Respect GitHub configured priority order, pushing YouTube family higher only if explicit compilation query
            if (explicitCompilation) {
                val ytIds = capable.filter { it in setOf("youtube", "piped", "invidious") }
                ytIds + capable.filterNot { it in ytIds }
            } else {
                capable
            }
        }
        return healthTracker.selectOrderedProviderIds(ordered)
    }

    private fun selectOrderedIdsByCapability(
        adapters: Map<String, OnlineMusicProvider>,
        capability: String
    ): List<String> {
        val eligible = adapters.filterValues { it.supportsCapability(capability) }.keys.toList()
        val ordered = if (providers != null) {
            val legacyPriority = listOf("jiosaavn", "audius", "youtube", "piped", "invidious", "soundcloud", "bandcamp")
            val prioritized = legacyPriority.filter { it in eligible }
            prioritized + eligible.filterNot { it in prioritized }
        } else {
            eligible
        }
        return healthTracker.selectOrderedProviderIds(ordered)
    }

    private fun selectNativePlaybackCandidates(
        track: OnlineTrack,
        adapters: Map<String, OnlineMusicProvider>
    ): List<String> {
        val effId = track.effectiveProviderId().lowercase()
        val ytFamily = setOf("youtube", "piped", "invidious")
        val rawCandidates = if (effId in ytFamily || track.providerType in setOf(OnlineProviderType.YOUTUBE, OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS)) {
            listOf("youtube", "piped", "invidious").filter { adapters.containsKey(it) }
        } else if (adapters.containsKey(effId)) {
            listOf(effId)
        } else {
            adapters.filterValues { it.providerType == track.providerType }.keys.toList()
        }
        return healthTracker.selectOrderedProviderIds(rawCandidates)
    }

    private fun selectMetadataCandidates(
        targetId: String,
        hintProvider: OnlineProviderType,
        adapters: Map<String, OnlineMusicProvider>
    ): List<String> {
        val ytFamily = setOf("youtube", "piped", "invidious")
        val candidates = if (targetId in ytFamily || hintProvider in setOf(OnlineProviderType.YOUTUBE, OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS)) {
            listOf("youtube", "piped", "invidious").filter { adapters.containsKey(it) }
        } else if (adapters.containsKey(targetId)) {
            listOf(targetId)
        } else {
            adapters.filterValues { it.providerType == hintProvider }.keys.toList()
        }
        return healthTracker.selectOrderedProviderIds(candidates)
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
