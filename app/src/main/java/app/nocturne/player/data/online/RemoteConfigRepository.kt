package app.nocturne.player.data.online

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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RemoteConfigRepository(
    private val settingsRepository: SettingsRepository,
    baseClient: OkHttpClient = OnlineHttpClient.client,
    private val allowTestLoopback: Boolean = false
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val timeoutClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val _activeConfig = MutableStateFlow(RemoteProvidersConfig.DEFAULT)
    val activeConfig: StateFlow<RemoteProvidersConfig> = _activeConfig.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastFetchError = MutableStateFlow<String?>(null)
    val lastFetchError: StateFlow<String?> = _lastFetchError.asStateFlow()

    private val startupTriggered = AtomicBoolean(false)
    private val lastRefreshMs = AtomicLong(0L)

    init {
        scope.launch {
            settingsRepository.getCachedRemoteProvidersConfig()?.let { cached ->
                _activeConfig.value = sanitizeConfig(cached, RemoteProvidersConfig.DEFAULT)
            }
        }
    }

    fun jiosaavnEndpoints(): List<String> = _activeConfig.value.jiosaavn.ifEmpty {
        RemoteProvidersConfig.DEFAULT_JIOSAAVN_URLS
    }

    fun pipedEndpoints(): List<String> = _activeConfig.value.piped.ifEmpty {
        RemoteProvidersConfig.DEFAULT_PIPED_URLS
    }

    fun invidiousEndpoints(): List<String> = _activeConfig.value.invidious.ifEmpty {
        RemoteProvidersConfig.DEFAULT_INVIDIOUS_URLS
    }

    fun lrclibEndpoints(): List<String> = _activeConfig.value.lrclib.ifEmpty {
        RemoteProvidersConfig.DEFAULT_LRCLIB_URLS
    }

    fun triggerStartupRefreshIfNeeded() {
        if (startupTriggered.compareAndSet(false, true)) {
            scope.launch {
                refreshNow()
            }
        }
    }

    suspend fun refreshNow(customUrl: String? = null): RemoteProvidersConfig = withContext(Dispatchers.IO) {
        _isRefreshing.value = true
        _lastFetchError.value = null

        val cached = runCatching { settingsRepository.getCachedRemoteProvidersConfig() }.getOrNull()
        val baseline = sanitizeConfig(cached ?: _activeConfig.value, RemoteProvidersConfig.DEFAULT)
        if (_activeConfig.value != baseline && cached != null) {
            _activeConfig.value = baseline
        }

        val rawTargetUrl = (customUrl ?: runCatching {
            settingsRepository.getSettingsOnce().remoteProviderConfigUrl
        }.getOrDefault(UserSettings.DEFAULT_REMOTE_CONFIG_URL)).trim()

        val allowedOrigin = if (allowTestLoopback) rawTargetUrl else null
        val targetUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(rawTargetUrl, allowedOrigin)
        if (targetUrl == null) {
            _isRefreshing.value = false
            _lastFetchError.value = "Blocked invalid or non-HTTPS remote config URL"
            return@withContext baseline
        }

        val now = System.currentTimeMillis()
        val prev = lastRefreshMs.get()
        if (customUrl == null && prev > 0L && now - prev < MIN_AUTO_REFRESH_INTERVAL_MS) {
            _isRefreshing.value = false
            return@withContext baseline
        }
        lastRefreshMs.set(now)

        return@withContext try {
            val fetched = fetchAndParseConfig(targetUrl, fallback = baseline)
            settingsRepository.saveRemoteProvidersConfig(fetched)
            _activeConfig.value = fetched
            _lastFetchError.value = null
            _isRefreshing.value = false
            fetched
        } catch (t: Throwable) {
            _lastFetchError.value = t.localizedMessage ?: "Offline or unreachable remote config"
            _isRefreshing.value = false
            baseline
        }
    }

    internal fun fetchAndParseConfig(
        url: String,
        fallback: RemoteProvidersConfig = RemoteProvidersConfig.DEFAULT
    ): RemoteProvidersConfig {
        val allowedOrigin = if (allowTestLoopback) url else null
        val safeUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(url, allowedOrigin)
            ?: throw IOException("Blocked unsafe remote config URL")

        val request = Request.Builder()
            .url(safeUrl)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()

        timeoutClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching remote providers config")
            }
            val body = InputAndUrlSecurity.readBodyBounded(response.body, MAX_CONFIG_BYTES)
            if (body.isBlank()) {
                throw IOException("Empty remote providers config response")
            }
            return parseProvidersJson(body, fallback)
        }
    }

    internal fun parseProvidersJson(
        rawJson: String,
        fallback: RemoteProvidersConfig = RemoteProvidersConfig.DEFAULT
    ): RemoteProvidersConfig {
        val obj = json.parseToJsonElement(rawJson).jsonObject

        fun extractUrls(key: String): List<String> {
            val arr = obj[key]?.jsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                InputAndUrlSecurity.validateRemoteHttpsUrl(el.jsonPrimitive.contentOrNull)
                    ?.trimEnd('/')
            }.distinct().take(15)
        }

        val jiosaavn = extractUrls("jiosaavn")
        val piped = extractUrls("piped")
        val invidious = extractUrls("invidious")
        val lrclib = extractUrls("lrclib")

        if (jiosaavn.isEmpty() && piped.isEmpty() && invidious.isEmpty() && lrclib.isEmpty()) {
            throw IOException("Remote config JSON contained no valid HTTPS provider endpoint arrays")
        }

        return RemoteProvidersConfig(
            jiosaavn = jiosaavn.ifEmpty { fallback.jiosaavn.ifEmpty { RemoteProvidersConfig.DEFAULT_JIOSAAVN_URLS } },
            piped = piped.ifEmpty { fallback.piped.ifEmpty { RemoteProvidersConfig.DEFAULT_PIPED_URLS } },
            invidious = invidious.ifEmpty { fallback.invidious.ifEmpty { RemoteProvidersConfig.DEFAULT_INVIDIOUS_URLS } },
            lrclib = lrclib.ifEmpty { fallback.lrclib.ifEmpty { RemoteProvidersConfig.DEFAULT_LRCLIB_URLS } },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun sanitizeConfig(
        config: RemoteProvidersConfig,
        fallback: RemoteProvidersConfig
    ): RemoteProvidersConfig {
        fun cleanList(list: List<String>, fb: List<String>): List<String> {
            return list.mapNotNull {
                InputAndUrlSecurity.validateRemoteHttpsUrl(it)?.trimEnd('/')
            }.distinct().take(15).ifEmpty { fb }
        }
        return RemoteProvidersConfig(
            jiosaavn = cleanList(config.jiosaavn, fallback.jiosaavn),
            piped = cleanList(config.piped, fallback.piped),
            invidious = cleanList(config.invidious, fallback.invidious),
            lrclib = cleanList(config.lrclib, fallback.lrclib),
            updatedAtMs = config.updatedAtMs
        )
    }

    companion object {
        const val TIMEOUT_SECONDS = 4L
        private const val MAX_CONFIG_BYTES = 512L * 1024L
        private const val MIN_AUTO_REFRESH_INTERVAL_MS = 5_000L
    }
}
