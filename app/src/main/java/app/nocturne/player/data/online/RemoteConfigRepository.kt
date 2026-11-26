package app.nocturne.player.data.online

import app.nocturne.player.data.model.DynamicLyricsProviderConfig
import app.nocturne.player.data.model.DynamicProviderConfig
import app.nocturne.player.data.model.LyricsCleaningConfig
import app.nocturne.player.data.model.ProviderLocalOverride
import app.nocturne.player.data.model.RemoteConfigErrorType
import app.nocturne.player.data.model.RemoteConfigStatus
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.data.prefs.SettingsRepository
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface ConfigOperationResult {
    data class Success(
        val url: String,
        val config: RemoteProvidersConfig,
        val latencyMs: Long,
        val wasNotModified304: Boolean = false
    ) : ConfigOperationResult

    data class Failure(
        val url: String,
        val errorType: RemoteConfigErrorType,
        val message: String
    ) : ConfigOperationResult
}

class RemoteConfigRepository(
    private val settingsRepository: SettingsRepository,
    baseClient: OkHttpClient = OnlineHttpClient.client,
    private val allowTestLoopback: Boolean = false
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val timeoutClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var rawActiveConfig: RemoteProvidersConfig = RemoteProvidersConfig.DEFAULT
    @Volatile
    private var rawPreviousConfig: RemoteProvidersConfig? = null
    @Volatile
    private var musicOverridesCache: Map<String, ProviderLocalOverride> = emptyMap()
    @Volatile
    private var lyricsOverridesCache: Map<String, ProviderLocalOverride> = emptyMap()

    private val _activeConfig = MutableStateFlow(RemoteProvidersConfig.DEFAULT)
    val activeConfig: StateFlow<RemoteProvidersConfig> = _activeConfig.asStateFlow()

    private val _previousConfig = MutableStateFlow<RemoteProvidersConfig?>(null)
    val previousConfig: StateFlow<RemoteProvidersConfig?> = _previousConfig.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastFetchError = MutableStateFlow<String?>(null)
    val lastFetchError: StateFlow<String?> = _lastFetchError.asStateFlow()

    private val _status = MutableStateFlow(RemoteConfigStatus())
    val status: StateFlow<RemoteConfigStatus> = _status.asStateFlow()

    private val startupTriggered = AtomicBoolean(false)
    private val lastRefreshMs = AtomicLong(0L)
    private val stateUpdatedExplicitly = AtomicBoolean(false)
    private val configMutex = Mutex()

    init {
        scope.launch {
            loadCachedState()
        }
    }

    private suspend fun resolveConfiguredUrl(): String {
        return runCatching {
            val configured = settingsRepository.getSettingsOnce().remoteProviderConfigUrl.trim()
            if (configured == UserSettings.LEGACY_REMOTE_CONFIG_URL || configured.isBlank()) {
                UserSettings.DEFAULT_REMOTE_CONFIG_URL
            } else {
                configured
            }
        }.getOrDefault(UserSettings.DEFAULT_REMOTE_CONFIG_URL)
    }

    private suspend fun loadCachedState() {
        if (stateUpdatedExplicitly.get()) return
        val cached = runCatching { settingsRepository.getCachedRemoteProvidersConfig() }.getOrNull()
        val prev = runCatching { settingsRepository.getPreviousRemoteProvidersConfig() }.getOrNull()
        val (etag, lastModified, lastSyncMs) = runCatching {
            settingsRepository.getRemoteConfigHttpMetadata()
        }.getOrDefault(Triple(null, null, 0L))
        val settings = runCatching { settingsRepository.getSettingsOnce() }.getOrDefault(UserSettings())
        val configuredUrl = settings.remoteProviderConfigUrl.trim().let {
            if (it == UserSettings.LEGACY_REMOTE_CONFIG_URL || it.isBlank()) UserSettings.DEFAULT_REMOTE_CONFIG_URL else it
        }

        val sanitizedRaw = cached?.let { RemoteConfigValidator.sanitizePersistedConfig(it, allowTestLoopback) }
            ?: RemoteProvidersConfig.DEFAULT
        val sanitizedPrevRaw = prev?.let { RemoteConfigValidator.sanitizePersistedConfig(it, allowTestLoopback) }

        configMutex.withLock {
            if (stateUpdatedExplicitly.get()) return
            musicOverridesCache = settings.musicProviderOverrides
            lyricsOverridesCache = settings.lyricsProviderOverrides
            rawActiveConfig = sanitizedRaw
            rawPreviousConfig = sanitizedPrevRaw
            val effectiveActive = applyLocalOverrides(sanitizedRaw, musicOverridesCache, lyricsOverridesCache)
            val effectivePrev = sanitizedPrevRaw?.let { applyLocalOverrides(it, musicOverridesCache, lyricsOverridesCache) }
            _activeConfig.value = effectiveActive
            _previousConfig.value = effectivePrev
            if (lastSyncMs > 0L) {
                lastRefreshMs.set(lastSyncMs)
            }
            updateStatus(
                config = effectiveActive,
                previous = effectivePrev,
                etag = etag ?: sanitizedRaw.etag,
                lastModified = lastModified ?: sanitizedRaw.lastModified,
                lastSyncedAtMs = lastSyncMs.takeIf { it > 0L } ?: sanitizedRaw.updatedAtMs,
                isFromCache = cached != null,
                was304 = false,
                error = _lastFetchError.value,
                errorType = RemoteConfigErrorType.NONE,
                sourceUrl = configuredUrl
            )
        }
    }

    fun jiosaavnEndpoints(): List<String> = _activeConfig.value.jiosaavn

    fun pipedEndpoints(): List<String> = _activeConfig.value.piped

    fun invidiousEndpoints(): List<String> = _activeConfig.value.invidious

    fun audiUsEndpoints(): List<String> = _activeConfig.value.audius

    fun lrclibEndpoints(): List<String> {
        val cfg = _activeConfig.value
        val hasExplicitLrclib = cfg.lyricsProviders.any {
            it.id.equals("lrclib", ignoreCase = true) || it.adapterType.equals("lrclib", ignoreCase = true)
        }
        return if (hasExplicitLrclib) cfg.lrclibEndpointsOnly() else cfg.lrclib
    }

    fun lrclibConfig(): DynamicLyricsProviderConfig? = _activeConfig.value.lrclibConfig()

    fun liriqoConfig(): DynamicLyricsProviderConfig? = _activeConfig.value.liriqoConfig()

    fun musixmatchConfig(): DynamicLyricsProviderConfig? = _activeConfig.value.musixmatchConfig()

    fun jiosaavnLyricsConfig(): DynamicLyricsProviderConfig? = _activeConfig.value.jiosaavnLyricsConfig()

    fun lyricsCleaningConfig(): LyricsCleaningConfig = _activeConfig.value.lyricsCleaning

    fun providerDefaults(): app.nocturne.player.data.model.ProviderDefaultsConfig = _activeConfig.value.providerDefaults

    fun enabledProviders(): List<DynamicProviderConfig> = _activeConfig.value.enabledProviders()

    fun hasMusicProviderOverride(providerId: String): Boolean =
        musicOverridesCache.containsKey(providerId.lowercase())

    fun hasLyricsProviderOverride(providerId: String): Boolean =
        lyricsOverridesCache.containsKey(providerId.lowercase())

    fun hasAnyLocalOverrides(): Boolean =
        musicOverridesCache.isNotEmpty() || lyricsOverridesCache.isNotEmpty()

    fun triggerStartupRefreshIfNeeded() {
        if (startupTriggered.compareAndSet(false, true)) {
            scope.launch {
                refreshNow(force = false)
            }
        }
    }

    suspend fun refreshIfStale(): RemoteProvidersConfig = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val intervalMs = (_activeConfig.value.refreshIntervalSeconds.coerceAtLeast(60)) * 1000L
        val last = lastRefreshMs.get()
        if (_activeConfig.value.providers.isEmpty() || last == 0L || (now - last) >= intervalMs) {
            refreshNow(force = true)
        } else {
            _activeConfig.value
        }
    }

    suspend fun testConnection(candidateUrl: String): ConfigOperationResult = withContext(Dispatchers.IO) {
        val trimmed = candidateUrl.trim()
        val allowedOrigin = if (allowTestLoopback) trimmed else null
        val targetUrl = when (val check = InputAndUrlSecurity.validateRemoteConfigUrlDetailed(trimmed, allowedOrigin)) {
            is InputAndUrlSecurity.RemoteConfigUrlValidationResult.Invalid -> {
                return@withContext ConfigOperationResult.Failure(
                    url = trimmed,
                    errorType = RemoteConfigErrorType.URL_ERROR,
                    message = check.reason
                )
            }
            is InputAndUrlSecurity.RemoteConfigUrlValidationResult.Valid -> check.normalizedUrl
        }

        val startNs = System.nanoTime()
        return@withContext try {
            val outcome = fetchAndValidateConfig(url = targetUrl)
            val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            when (outcome) {
                is FetchOutcome.Updated -> ConfigOperationResult.Success(
                    url = targetUrl,
                    config = outcome.config,
                    latencyMs = latencyMs,
                    wasNotModified304 = false
                )
                is FetchOutcome.NotModified -> ConfigOperationResult.Failure(
                    url = targetUrl,
                    errorType = RemoteConfigErrorType.NETWORK_ERROR,
                    message = "Unexpected HTTP 304 without conditional headers"
                )
            }
        } catch (t: Throwable) {
            val (errType, msg) = classifyError(t)
            ConfigOperationResult.Failure(
                url = targetUrl,
                errorType = errType,
                message = msg
            )
        }
    }

    suspend fun saveAndActivateUrl(
        candidateUrl: String,
        resetOverrides: Boolean = false
    ): ConfigOperationResult = withContext(Dispatchers.IO) {
        _isRefreshing.value = true
        try {
            val result = testConnection(candidateUrl)
            if (result !is ConfigOperationResult.Success) {
                return@withContext result
            }
            val now = System.currentTimeMillis()
            val fetchedRaw = result.config
            configMutex.withLock {
                stateUpdatedExplicitly.set(true)
                lastRefreshMs.set(now)
                if (resetOverrides) {
                    musicOverridesCache = emptyMap()
                    lyricsOverridesCache = emptyMap()
                }
                settingsRepository.updateSettings { cur ->
                    val withUrl = cur.copy(remoteProviderConfigUrl = result.url)
                    if (resetOverrides) {
                        withUrl.copy(
                            musicProviderOverrides = emptyMap(),
                            lyricsProviderOverrides = emptyMap()
                        )
                    } else {
                        withUrl
                    }
                }
                settingsRepository.saveRemoteProvidersConfig(fetchedRaw, sourceUrl = result.url)
                settingsRepository.saveRemoteConfigHttpMetadata(
                    etag = fetchedRaw.etag,
                    lastModified = fetchedRaw.lastModified,
                    syncedAtMs = fetchedRaw.updatedAtMs,
                    sourceUrl = result.url
                )
                val newPrevRaw = runCatching { settingsRepository.getPreviousRemoteProvidersConfig() }.getOrNull()
                rawActiveConfig = fetchedRaw
                rawPreviousConfig = newPrevRaw
                val effective = applyLocalOverrides(fetchedRaw, musicOverridesCache, lyricsOverridesCache)
                val effectivePrev = newPrevRaw?.let { applyLocalOverrides(it, musicOverridesCache, lyricsOverridesCache) }
                _activeConfig.value = effective
                _previousConfig.value = effectivePrev
                _lastFetchError.value = null
                updateStatus(
                    config = effective,
                    previous = effectivePrev,
                    etag = fetchedRaw.etag,
                    lastModified = fetchedRaw.lastModified,
                    lastSyncedAtMs = fetchedRaw.updatedAtMs,
                    isFromCache = false,
                    was304 = false,
                    error = null,
                    errorType = RemoteConfigErrorType.NONE,
                    sourceUrl = result.url
                )
            }
            result
        } finally {
            _isRefreshing.value = false
        }
    }

    suspend fun refreshNow(
        customUrl: String? = null,
        force: Boolean = true,
        resetOverrides: Boolean = false
    ): RemoteProvidersConfig = withContext(Dispatchers.IO) {
        _isRefreshing.value = true
        _lastFetchError.value = null

        if (resetOverrides) {
            configMutex.withLock {
                musicOverridesCache = emptyMap()
                lyricsOverridesCache = emptyMap()
                settingsRepository.updateSettings {
                    it.copy(
                        musicProviderOverrides = emptyMap(),
                        lyricsProviderOverrides = emptyMap()
                    )
                }
            }
        }

        val cachedRaw = runCatching { settingsRepository.getCachedRemoteProvidersConfig() }.getOrNull()
        val prevCachedRaw = runCatching { settingsRepository.getPreviousRemoteProvidersConfig() }.getOrNull()
        val (cachedEtag, cachedLastMod, cachedSyncMs) = runCatching {
            settingsRepository.getRemoteConfigHttpMetadata()
        }.getOrDefault(Triple(null, null, 0L))
        val cachedMetaUrl = runCatching { settingsRepository.getRemoteConfigMetadataSourceUrl() }.getOrNull()

        val rawBaseline = cachedRaw?.let { RemoteConfigValidator.sanitizePersistedConfig(it, allowTestLoopback) }
            ?: rawActiveConfig
        val baseline = applyLocalOverrides(rawBaseline, musicOverridesCache, lyricsOverridesCache)
        val effectivePrev = prevCachedRaw?.let {
            applyLocalOverrides(
                RemoteConfigValidator.sanitizePersistedConfig(it, allowTestLoopback),
                musicOverridesCache,
                lyricsOverridesCache
            )
        }
        rawActiveConfig = rawBaseline
        rawPreviousConfig = prevCachedRaw
        if (cachedRaw != null && _activeConfig.value != baseline) {
            _activeConfig.value = baseline
        }
        _previousConfig.value = effectivePrev

        val configuredUrl = resolveConfiguredUrl()
        val rawTargetUrl = (customUrl ?: configuredUrl).trim()

        val allowedOrigin = if (allowTestLoopback) rawTargetUrl else null
        val targetUrl = when (val urlCheck = InputAndUrlSecurity.validateRemoteConfigUrlDetailed(rawTargetUrl, allowedOrigin)) {
            is InputAndUrlSecurity.RemoteConfigUrlValidationResult.Invalid -> {
                _lastFetchError.value = urlCheck.reason
                _isRefreshing.value = false
                updateStatus(
                    config = baseline,
                    previous = effectivePrev,
                    etag = cachedEtag,
                    lastModified = cachedLastMod,
                    lastSyncedAtMs = cachedSyncMs,
                    isFromCache = cachedRaw != null,
                    was304 = false,
                    error = urlCheck.reason,
                    errorType = RemoteConfigErrorType.URL_ERROR,
                    sourceUrl = configuredUrl
                )
                return@withContext baseline
            }
            is InputAndUrlSecurity.RemoteConfigUrlValidationResult.Valid -> urlCheck.normalizedUrl
        }

        val now = System.currentTimeMillis()
        val prevAttempt = lastRefreshMs.get()
        if (!force && customUrl == null && prevAttempt > 0L && now - prevAttempt < MIN_AUTO_REFRESH_INTERVAL_MS) {
            _isRefreshing.value = false
            return@withContext baseline
        }
        lastRefreshMs.set(now)

        val canUseConditionalHeaders = customUrl == null && (cachedMetaUrl == null || cachedMetaUrl == targetUrl)

        return@withContext try {
            when (val result = fetchAndValidateConfig(
                url = targetUrl,
                currentEtag = if (canUseConditionalHeaders) (cachedEtag ?: rawBaseline.etag) else null,
                currentLastModified = if (canUseConditionalHeaders) (cachedLastMod ?: rawBaseline.lastModified) else null
            )) {
                is FetchOutcome.NotModified -> {
                    settingsRepository.saveRemoteConfigHttpMetadata(
                        etag = result.etag ?: cachedEtag,
                        lastModified = result.lastModified ?: cachedLastMod,
                        syncedAtMs = now,
                        sourceUrl = targetUrl
                    )
                    _lastFetchError.value = null
                    _isRefreshing.value = false
                    updateStatus(
                        config = baseline,
                        previous = effectivePrev,
                        etag = result.etag ?: cachedEtag,
                        lastModified = result.lastModified ?: cachedLastMod,
                        lastSyncedAtMs = now,
                        isFromCache = true,
                        was304 = true,
                        error = null,
                        errorType = RemoteConfigErrorType.NONE,
                        sourceUrl = targetUrl
                    )
                    baseline
                }
                is FetchOutcome.Updated -> {
                    val fetchedRaw = result.config
                    var resolvedEffective = fetchedRaw
                    configMutex.withLock {
                        stateUpdatedExplicitly.set(true)
                        settingsRepository.saveRemoteProvidersConfig(fetchedRaw, sourceUrl = targetUrl)
                        val newPrevRaw = runCatching { settingsRepository.getPreviousRemoteProvidersConfig() }.getOrNull()
                        rawActiveConfig = fetchedRaw
                        rawPreviousConfig = newPrevRaw
                        resolvedEffective = applyLocalOverrides(fetchedRaw, musicOverridesCache, lyricsOverridesCache)
                        val resolvedPrev = newPrevRaw?.let { applyLocalOverrides(it, musicOverridesCache, lyricsOverridesCache) }
                        _activeConfig.value = resolvedEffective
                        _previousConfig.value = resolvedPrev
                        _lastFetchError.value = null
                        _isRefreshing.value = false
                        updateStatus(
                            config = resolvedEffective,
                            previous = resolvedPrev,
                            etag = fetchedRaw.etag,
                            lastModified = fetchedRaw.lastModified,
                            lastSyncedAtMs = fetchedRaw.updatedAtMs,
                            isFromCache = false,
                            was304 = false,
                            error = null,
                            errorType = RemoteConfigErrorType.NONE,
                            sourceUrl = targetUrl
                        )
                    }
                    resolvedEffective
                }
            }
        } catch (t: Throwable) {
            val (errType, safeErr) = classifyError(t)
            _lastFetchError.value = safeErr
            _isRefreshing.value = false
            updateStatus(
                config = baseline,
                previous = effectivePrev,
                etag = cachedEtag,
                lastModified = cachedLastMod,
                lastSyncedAtMs = cachedSyncMs,
                isFromCache = cachedRaw != null,
                was304 = false,
                error = safeErr,
                errorType = errType,
                sourceUrl = configuredUrl
            )
            baseline
        }
    }

    suspend fun rollbackToPreviousConfig(): RemoteProvidersConfig? = withContext(Dispatchers.IO) {
        val configuredUrl = resolveConfiguredUrl()
        configMutex.withLock {
            stateUpdatedExplicitly.set(true)
            val restored = settingsRepository.rollbackRemoteProvidersConfig() ?: return@withContext null
            val sanitizedRaw = RemoteConfigValidator.sanitizePersistedConfig(restored, allowTestLoopback)
            val newPrevRaw = runCatching { settingsRepository.getPreviousRemoteProvidersConfig() }.getOrNull()
            rawActiveConfig = sanitizedRaw
            rawPreviousConfig = newPrevRaw
            val effective = applyLocalOverrides(sanitizedRaw, musicOverridesCache, lyricsOverridesCache)
            val effectivePrev = newPrevRaw?.let { applyLocalOverrides(it, musicOverridesCache, lyricsOverridesCache) }
            _activeConfig.value = effective
            _previousConfig.value = effectivePrev
            _lastFetchError.value = null
            updateStatus(
                config = effective,
                previous = effectivePrev,
                etag = sanitizedRaw.etag,
                lastModified = sanitizedRaw.lastModified,
                lastSyncedAtMs = sanitizedRaw.updatedAtMs,
                isFromCache = true,
                was304 = false,
                error = null,
                errorType = RemoteConfigErrorType.NONE,
                sourceUrl = configuredUrl
            )
            effective
        }
    }

    suspend fun toggleMusicProviderEnabled(providerId: String) = withContext(Dispatchers.IO) {
        val key = providerId.trim().lowercase()
        if (key.isBlank()) return@withContext
        configMutex.withLock {
            stateUpdatedExplicitly.set(true)
            val currentEffective = _activeConfig.value.providerById(key) ?: return@withContext
            val rawMatch = rawActiveConfig.providerById(key)
            val newEnabled = !currentEffective.enabled
            val updatedMap = musicOverridesCache.toMutableMap()
            val prevOv = updatedMap[key]
            val candidateOv = ProviderLocalOverride(
                enabled = if (rawMatch != null && newEnabled == rawMatch.enabled) null else newEnabled,
                priority = prevOv?.priority
            )
            if (candidateOv.enabled == null && candidateOv.priority == null) {
                updatedMap.remove(key)
            } else {
                updatedMap[key] = candidateOv
            }
            persistAndApplyOverridesLocked(musicOv = updatedMap, lyricsOv = lyricsOverridesCache)
        }
    }

    suspend fun moveMusicProviderPriority(providerId: String, delta: Int) = withContext(Dispatchers.IO) {
        val key = providerId.trim().lowercase()
        if (key.isBlank() || delta == 0) return@withContext
        configMutex.withLock {
            stateUpdatedExplicitly.set(true)
            val ordered = _activeConfig.value.providers.sortedBy { it.priority }.toMutableList()
            val idx = ordered.indexOfFirst { it.id.equals(key, ignoreCase = true) }
            if (idx < 0) return@withContext
            val targetIdx = (idx + if (delta < 0) -1 else 1).coerceIn(0, ordered.lastIndex)
            if (targetIdx == idx) return@withContext
            val moved = ordered.removeAt(idx)
            ordered.add(targetIdx, moved)

            val updatedMap = musicOverridesCache.toMutableMap()
            ordered.forEachIndexed { position, cfg ->
                val cid = cfg.id.lowercase()
                val normalizedPriority = (position + 1) * 10
                val prevOv = updatedMap[cid]
                updatedMap[cid] = ProviderLocalOverride(
                    enabled = prevOv?.enabled,
                    priority = normalizedPriority
                )
            }
            persistAndApplyOverridesLocked(musicOv = updatedMap, lyricsOv = lyricsOverridesCache)
        }
    }

    suspend fun toggleLyricsProviderEnabled(providerId: String) = withContext(Dispatchers.IO) {
        val key = providerId.trim().lowercase()
        if (key.isBlank()) return@withContext
        configMutex.withLock {
            stateUpdatedExplicitly.set(true)
            val currentList = _activeConfig.value.resolvedLyricsProvidersForDiagnostics()
            val currentEffective = currentList.firstOrNull { it.id.equals(key, ignoreCase = true) }
                ?: return@withContext
            val rawMatch = rawActiveConfig.resolvedLyricsProvidersForDiagnostics()
                .firstOrNull { it.id.equals(key, ignoreCase = true) }
            val newEnabled = !currentEffective.enabled
            val updatedMap = lyricsOverridesCache.toMutableMap()
            val prevOv = updatedMap[key]
            val isExplicitRemote = rawActiveConfig.lyricsProviders.any { it.id.equals(key, ignoreCase = true) }
            val candidateOv = ProviderLocalOverride(
                enabled = if (isExplicitRemote && rawMatch != null && newEnabled == rawMatch.enabled) null else newEnabled,
                priority = prevOv?.priority
            )
            if (candidateOv.enabled == null && candidateOv.priority == null) {
                updatedMap.remove(key)
            } else {
                updatedMap[key] = candidateOv
            }
            persistAndApplyOverridesLocked(musicOv = musicOverridesCache, lyricsOv = updatedMap)
        }
    }

    suspend fun moveLyricsProviderPriority(providerId: String, delta: Int) = withContext(Dispatchers.IO) {
        val key = providerId.trim().lowercase()
        if (key.isBlank() || delta == 0) return@withContext
        configMutex.withLock {
            stateUpdatedExplicitly.set(true)
            val ordered = _activeConfig.value.resolvedLyricsProvidersForDiagnostics().toMutableList()
            val idx = ordered.indexOfFirst { it.id.equals(key, ignoreCase = true) }
            if (idx < 0) return@withContext
            val targetIdx = (idx + if (delta < 0) -1 else 1).coerceIn(0, ordered.lastIndex)
            if (targetIdx == idx) return@withContext
            val moved = ordered.removeAt(idx)
            ordered.add(targetIdx, moved)

            val updatedMap = lyricsOverridesCache.toMutableMap()
            ordered.forEachIndexed { position, cfg ->
                val cid = cfg.id.lowercase()
                val normalizedPriority = (position + 1) * 10
                val prevOv = updatedMap[cid]
                updatedMap[cid] = ProviderLocalOverride(
                    enabled = prevOv?.enabled,
                    priority = normalizedPriority
                )
            }
            persistAndApplyOverridesLocked(musicOv = musicOverridesCache, lyricsOv = updatedMap)
        }
    }

    suspend fun resetProviderOverrides() = withContext(Dispatchers.IO) {
        configMutex.withLock {
            stateUpdatedExplicitly.set(true)
            persistAndApplyOverridesLocked(musicOv = emptyMap(), lyricsOv = emptyMap())
        }
    }

    private suspend fun persistAndApplyOverridesLocked(
        musicOv: Map<String, ProviderLocalOverride>,
        lyricsOv: Map<String, ProviderLocalOverride>
    ) {
        musicOverridesCache = musicOv
        lyricsOverridesCache = lyricsOv
        settingsRepository.updateSettings {
            it.copy(
                musicProviderOverrides = musicOv,
                lyricsProviderOverrides = lyricsOv
            )
        }
        val effective = applyLocalOverrides(rawActiveConfig, musicOv, lyricsOv)
        val effectivePrev = rawPreviousConfig?.let { applyLocalOverrides(it, musicOv, lyricsOv) }
        _activeConfig.value = effective
        _previousConfig.value = effectivePrev
        val curStatus = _status.value
        updateStatus(
            config = effective,
            previous = effectivePrev,
            etag = curStatus.etag,
            lastModified = curStatus.lastModified,
            lastSyncedAtMs = curStatus.lastSyncedAtMs,
            isFromCache = curStatus.isFromCache,
            was304 = curStatus.wasNotModified304,
            error = curStatus.lastError,
            errorType = curStatus.lastErrorType,
            sourceUrl = curStatus.sourceUrl
        )
    }

    private fun applyLocalOverrides(
        raw: RemoteProvidersConfig,
        musicOverrides: Map<String, ProviderLocalOverride>,
        lyricsOverrides: Map<String, ProviderLocalOverride>
    ): RemoteProvidersConfig {
        if (musicOverrides.isEmpty() && lyricsOverrides.isEmpty()) return raw

        val overriddenProviders = raw.providers.mapIndexed { idx, prov ->
            val ov = musicOverrides[prov.id.lowercase()]
            val next = if (ov != null) {
                prov.copy(
                    enabled = ov.enabled ?: prov.enabled,
                    priority = ov.priority ?: prov.priority
                )
            } else {
                prov
            }
            idx to next
        }.sortedWith(compareBy({ it.second.priority }, { it.first })).map { it.second }

        val baseLyrics = raw.lyricsProviders.toMutableList()
        val hasSaavnLyricsOv = lyricsOverrides.containsKey("jiosaavn_lyrics")
        val hasExplicitSaavn = baseLyrics.any {
            it.id.equals("jiosaavn_lyrics", ignoreCase = true) ||
                it.id.equals("jiosaavnlyrics", ignoreCase = true) ||
                it.adapterType.equals("jiosaavn_lyrics", ignoreCase = true) ||
                it.id.equals("jiosaavn", ignoreCase = true)
        }
        if (hasSaavnLyricsOv && !hasExplicitSaavn) {
            val saavnMusic = overriddenProviders.firstOrNull {
                it.id.equals("jiosaavn", ignoreCase = true) || it.adapterType.equals("jiosaavn", ignoreCase = true)
            }
            if (saavnMusic != null) {
                baseLyrics += DynamicLyricsProviderConfig(
                    id = "jiosaavn_lyrics",
                    displayName = "${saavnMusic.displayName} Lyrics",
                    category = "lyrics",
                    adapterType = "jiosaavn_lyrics",
                    enabled = saavnMusic.enabled,
                    priority = saavnMusic.priority,
                    capabilities = saavnMusic.capabilities,
                    endpoints = saavnMusic.endpoints,
                    instances = saavnMusic.baseUrls(),
                    timeoutMs = saavnMusic.timeoutMs,
                    maxRetries = saavnMusic.maxRetries,
                    cooldownMs = saavnMusic.cooldownMs,
                    endpointFailover = saavnMusic.endpointFailover,
                    headers = saavnMusic.headers,
                    params = saavnMusic.params
                )
            }
        }

        val overriddenLyrics = baseLyrics.mapIndexed { idx, lp ->
            val ov = lyricsOverrides[lp.id.lowercase()]
            val next = if (ov != null) {
                lp.copy(
                    enabled = ov.enabled ?: lp.enabled,
                    priority = ov.priority ?: lp.priority
                )
            } else {
                lp
            }
            idx to next
        }.sortedWith(compareBy({ it.second.priority }, { it.first })).map { it.second }

        return raw.copy(
            providers = overriddenProviders,
            lyricsProviders = overriddenLyrics
        )
    }

    internal sealed interface FetchOutcome {
        data class NotModified(val etag: String?, val lastModified: String?) : FetchOutcome
        data class Updated(val config: RemoteProvidersConfig) : FetchOutcome
    }

    internal fun fetchAndValidateConfig(
        url: String,
        currentEtag: String? = null,
        currentLastModified: String? = null
    ): FetchOutcome {
        val allowedOrigin = if (allowTestLoopback) url else null
        val safeUrl = when (val check = InputAndUrlSecurity.validateRemoteConfigUrlDetailed(url, allowedOrigin)) {
            is InputAndUrlSecurity.RemoteConfigUrlValidationResult.Invalid -> {
                throw RemoteConfigValidationException(RemoteConfigErrorType.URL_ERROR, check.reason)
            }
            is InputAndUrlSecurity.RemoteConfigUrlValidationResult.Valid -> check.normalizedUrl
        }

        val reqBuilder = Request.Builder()
            .url(safeUrl)
            .header("Accept", "application/json")
        if (!currentEtag.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", currentEtag)
        }
        if (!currentLastModified.isNullOrBlank()) {
            reqBuilder.header("If-Modified-Since", currentLastModified)
        }

        try {
            timeoutClient.newCall(reqBuilder.build()).execute().use { response ->
                val respEtag = response.header("ETag")
                val respLastMod = response.header("Last-Modified")
                if (response.code == 304) {
                    return FetchOutcome.NotModified(etag = respEtag, lastModified = respLastMod)
                }
                if (!response.isSuccessful) {
                    throw RemoteConfigValidationException(
                        RemoteConfigErrorType.NETWORK_ERROR,
                        "HTTP ${response.code} fetching remote providers config"
                    )
                }
                val body = InputAndUrlSecurity.readBodyBounded(response.body, MAX_CONFIG_BYTES)
                if (body.isBlank()) {
                    throw RemoteConfigValidationException(
                        RemoteConfigErrorType.JSON_PARSE_ERROR,
                        "Empty remote providers config response"
                    )
                }
                val parsed = parseProvidersJson(
                    rawJson = body,
                    etag = respEtag,
                    lastModified = respLastMod
                )
                return FetchOutcome.Updated(parsed)
            }
        } catch (e: RemoteConfigValidationException) {
            throw e
        } catch (e: IOException) {
            val detail = InputAndUrlSecurity.sanitizeErrorForDiagnostics(e.localizedMessage)
                ?: "Network error while fetching configuration"
            throw RemoteConfigValidationException(RemoteConfigErrorType.NETWORK_ERROR, detail, e)
        }
    }

    internal fun parseProvidersJson(
        rawJson: String,
        etag: String? = null,
        lastModified: String? = null
    ): RemoteProvidersConfig {
        return RemoteConfigValidator.parseAndValidate(
            rawJson = rawJson,
            allowLoopback = allowTestLoopback,
            etag = etag,
            lastModified = lastModified,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun classifyError(t: Throwable): Pair<RemoteConfigErrorType, String> {
        if (t is RemoteConfigValidationException) {
            val safeMsg = InputAndUrlSecurity.sanitizeErrorForDiagnostics(t.message) ?: t.errorType.label
            return t.errorType to safeMsg
        }
        val safeErr = InputAndUrlSecurity.sanitizeErrorForDiagnostics(t.localizedMessage)
            ?: "Network or configuration error"
        return RemoteConfigErrorType.NETWORK_ERROR to safeErr
    }

    private fun updateStatus(
        config: RemoteProvidersConfig,
        previous: RemoteProvidersConfig?,
        etag: String?,
        lastModified: String?,
        lastSyncedAtMs: Long,
        isFromCache: Boolean,
        was304: Boolean,
        error: String?,
        errorType: RemoteConfigErrorType = RemoteConfigErrorType.NONE,
        sourceUrl: String = _status.value.sourceUrl
    ) {
        val resolvedLyrics = config.resolvedLyricsProvidersForDiagnostics()
        _status.value = RemoteConfigStatus(
            sourceUrl = sourceUrl,
            schemaVersion = config.schemaVersion,
            configVersion = config.configVersion,
            etag = etag,
            lastModified = lastModified,
            lastSyncedAtMs = lastSyncedAtMs,
            isFromCache = isFromCache,
            wasNotModified304 = was304,
            canRollback = previous != null && previous.providers.isNotEmpty(),
            previousConfigVersion = previous?.configVersion,
            providerCount = config.providers.size,
            enabledProviderCount = config.providers.count { it.enabled },
            lyricsEndpointCount = config.lrclib.size,
            totalLyricsProviderCount = resolvedLyrics.size,
            enabledLyricsProviderCount = resolvedLyrics.count { it.enabled },
            hasLocalOverrides = musicOverridesCache.isNotEmpty() || lyricsOverridesCache.isNotEmpty(),
            categories = config.distinctCategories(),
            lastError = error,
            lastErrorType = errorType
        )
    }

    companion object {
        const val TIMEOUT_SECONDS = 6L
        private const val MAX_CONFIG_BYTES = 1024L * 1024L
        private const val MIN_AUTO_REFRESH_INTERVAL_MS = 5_000L
    }
}
