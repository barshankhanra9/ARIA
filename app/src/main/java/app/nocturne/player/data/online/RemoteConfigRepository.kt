package app.nocturne.player.data.online

import app.nocturne.player.data.model.DynamicProviderConfig
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
        val configuredUrl = resolveConfiguredUrl()

        val sanitizedActive = cached?.let { RemoteConfigValidator.sanitizePersistedConfig(it, allowTestLoopback) }
            ?: RemoteProvidersConfig.DEFAULT
        val sanitizedPrev = prev?.let { RemoteConfigValidator.sanitizePersistedConfig(it, allowTestLoopback) }

        configMutex.withLock {
            if (stateUpdatedExplicitly.get()) return
            _activeConfig.value = sanitizedActive
            _previousConfig.value = sanitizedPrev
            if (lastSyncMs > 0L) {
                lastRefreshMs.set(lastSyncMs)
            }
            updateStatus(
                config = sanitizedActive,
                previous = sanitizedPrev,
                etag = etag ?: sanitizedActive.etag,
                lastModified = lastModified ?: sanitizedActive.lastModified,
                lastSyncedAtMs = lastSyncMs.takeIf { it > 0L } ?: sanitizedActive.updatedAtMs,
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

    fun lrclibEndpoints(): List<String> = _activeConfig.value.lrclib

    fun enabledProviders(): List<DynamicProviderConfig> = _activeConfig.value.enabledProviders()

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

    suspend fun saveAndActivateUrl(candidateUrl: String): ConfigOperationResult = withContext(Dispatchers.IO) {
        _isRefreshing.value = true
        try {
            val result = testConnection(candidateUrl)
            if (result !is ConfigOperationResult.Success) {
                return@withContext result
            }
            val now = System.currentTimeMillis()
            val fetched = result.config
            configMutex.withLock {
                stateUpdatedExplicitly.set(true)
                lastRefreshMs.set(now)
                settingsRepository.updateSettings { it.copy(remoteProviderConfigUrl = result.url) }
                settingsRepository.saveRemoteProvidersConfig(fetched, sourceUrl = result.url)
                settingsRepository.saveRemoteConfigHttpMetadata(
                    etag = fetched.etag,
                    lastModified = fetched.lastModified,
                    syncedAtMs = fetched.updatedAtMs,
                    sourceUrl = result.url
                )
                val newPrev = runCatching { settingsRepository.getPreviousRemoteProvidersConfig() }.getOrNull()
                _activeConfig.value = fetched
                _previousConfig.value = newPrev
                _lastFetchError.value = null
                updateStatus(
                    config = fetched,
                    previous = newPrev,
                    etag = fetched.etag,
                    lastModified = fetched.lastModified,
                    lastSyncedAtMs = fetched.updatedAtMs,
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
        force: Boolean = true
    ): RemoteProvidersConfig = withContext(Dispatchers.IO) {
        _isRefreshing.value = true
        _lastFetchError.value = null

        val cached = runCatching { settingsRepository.getCachedRemoteProvidersConfig() }.getOrNull()
        val prevCached = runCatching { settingsRepository.getPreviousRemoteProvidersConfig() }.getOrNull()
        val (cachedEtag, cachedLastMod, cachedSyncMs) = runCatching {
            settingsRepository.getRemoteConfigHttpMetadata()
        }.getOrDefault(Triple(null, null, 0L))
        val cachedMetaUrl = runCatching { settingsRepository.getRemoteConfigMetadataSourceUrl() }.getOrNull()

        val baseline = cached?.let { RemoteConfigValidator.sanitizePersistedConfig(it, allowTestLoopback) }
            ?: _activeConfig.value
        if (cached != null && _activeConfig.value != baseline) {
            _activeConfig.value = baseline
        }
        _previousConfig.value = prevCached

        val configuredUrl = resolveConfiguredUrl()
        val rawTargetUrl = (customUrl ?: configuredUrl).trim()

        val allowedOrigin = if (allowTestLoopback) rawTargetUrl else null
        val targetUrl = when (val urlCheck = InputAndUrlSecurity.validateRemoteConfigUrlDetailed(rawTargetUrl, allowedOrigin)) {
            is InputAndUrlSecurity.RemoteConfigUrlValidationResult.Invalid -> {
                _lastFetchError.value = urlCheck.reason
                _isRefreshing.value = false
                updateStatus(
                    config = baseline,
                    previous = prevCached,
                    etag = cachedEtag,
                    lastModified = cachedLastMod,
                    lastSyncedAtMs = cachedSyncMs,
                    isFromCache = cached != null,
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
                currentEtag = if (canUseConditionalHeaders) (cachedEtag ?: baseline.etag) else null,
                currentLastModified = if (canUseConditionalHeaders) (cachedLastMod ?: baseline.lastModified) else null
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
                        previous = prevCached,
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
                    val fetched = result.config
                    configMutex.withLock {
                        stateUpdatedExplicitly.set(true)
                        settingsRepository.saveRemoteProvidersConfig(fetched, sourceUrl = targetUrl)
                        val newPrev = runCatching { settingsRepository.getPreviousRemoteProvidersConfig() }.getOrNull()
                        _activeConfig.value = fetched
                        _previousConfig.value = newPrev
                        _lastFetchError.value = null
                        _isRefreshing.value = false
                        updateStatus(
                            config = fetched,
                            previous = newPrev,
                            etag = fetched.etag,
                            lastModified = fetched.lastModified,
                            lastSyncedAtMs = fetched.updatedAtMs,
                            isFromCache = false,
                            was304 = false,
                            error = null,
                            errorType = RemoteConfigErrorType.NONE,
                            sourceUrl = targetUrl
                        )
                    }
                    fetched
                }
            }
        } catch (t: Throwable) {
            val (errType, safeErr) = classifyError(t)
            _lastFetchError.value = safeErr
            _isRefreshing.value = false
            updateStatus(
                config = baseline,
                previous = prevCached,
                etag = cachedEtag,
                lastModified = cachedLastMod,
                lastSyncedAtMs = cachedSyncMs,
                isFromCache = cached != null,
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
            val sanitized = RemoteConfigValidator.sanitizePersistedConfig(restored, allowTestLoopback)
            val newPrev = runCatching { settingsRepository.getPreviousRemoteProvidersConfig() }.getOrNull()
            _activeConfig.value = sanitized
            _previousConfig.value = newPrev
            _lastFetchError.value = null
            updateStatus(
                config = sanitized,
                previous = newPrev,
                etag = sanitized.etag,
                lastModified = sanitized.lastModified,
                lastSyncedAtMs = sanitized.updatedAtMs,
                isFromCache = true,
                was304 = false,
                error = null,
                errorType = RemoteConfigErrorType.NONE,
                sourceUrl = configuredUrl
            )
            sanitized
        }
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
