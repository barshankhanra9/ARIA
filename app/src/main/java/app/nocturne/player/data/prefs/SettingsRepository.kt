package app.nocturne.player.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.ariaDataStore: DataStore<Preferences> by preferencesDataStore(name = "aria_paper_settings")

data class PersistedQueueState(
    val queueUris: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0
)

data class PersistedOnlineQueueState(
    val tracks: List<app.nocturne.player.data.model.OnlineTrack> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0
)

class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private object Keys {
        val USER_SETTINGS_JSON = stringPreferencesKey("user_settings_json")
        val QUEUE_URIS_JSON = stringPreferencesKey("queue_uris_json")
        val QUEUE_INDEX = intPreferencesKey("queue_index")
        val QUEUE_POSITION_MS = longPreferencesKey("queue_position_ms")
        val QUEUE_SHUFFLE = intPreferencesKey("queue_shuffle")
        val QUEUE_REPEAT = intPreferencesKey("queue_repeat")

        val ONLINE_QUEUE_TRACKS_JSON = stringPreferencesKey("online_queue_tracks_json")
        val ONLINE_QUEUE_INDEX = intPreferencesKey("online_queue_index")
        val ONLINE_QUEUE_POSITION_MS = longPreferencesKey("online_queue_position_ms")
        val ONLINE_QUEUE_SHUFFLE = intPreferencesKey("online_queue_shuffle")
        val ONLINE_QUEUE_REPEAT = intPreferencesKey("online_queue_repeat")
        val REMOTE_PROVIDERS_CONFIG_JSON = stringPreferencesKey("remote_providers_config_json")
        val REMOTE_PROVIDERS_PREVIOUS_JSON = stringPreferencesKey("remote_providers_previous_json")
        val REMOTE_CONFIG_ETAG = stringPreferencesKey("remote_config_etag")
        val REMOTE_CONFIG_LAST_MODIFIED = stringPreferencesKey("remote_config_last_modified")
        val REMOTE_CONFIG_LAST_SYNC_MS = longPreferencesKey("remote_config_last_sync_ms")
        val REMOTE_CONFIG_SOURCE_URL = stringPreferencesKey("remote_config_source_url")
    }

    val settingsFlow: Flow<UserSettings> = context.ariaDataStore.data.map { prefs ->
        val raw = prefs[Keys.USER_SETTINGS_JSON]
        if (raw.isNullOrBlank()) {
            UserSettings()
        } else {
            val decoded = runCatching { json.decodeFromString<UserSettings>(raw) }.getOrDefault(UserSettings())
            val trimmedUrl = decoded.remoteProviderConfigUrl.trim()
            if (trimmedUrl == UserSettings.LEGACY_REMOTE_CONFIG_URL || trimmedUrl.isBlank()) {
                decoded.copy(remoteProviderConfigUrl = UserSettings.DEFAULT_REMOTE_CONFIG_URL)
            } else {
                decoded
            }
        }
    }

    val remoteProvidersConfigFlow: Flow<RemoteProvidersConfig> = context.ariaDataStore.data.map { prefs ->
        val raw = prefs[Keys.REMOTE_PROVIDERS_CONFIG_JSON]
        if (raw.isNullOrBlank()) {
            RemoteProvidersConfig.DEFAULT
        } else {
            runCatching { json.decodeFromString<RemoteProvidersConfig>(raw) }
                .getOrDefault(RemoteProvidersConfig.DEFAULT)
        }
    }

    suspend fun getSettingsOnce(): UserSettings = settingsFlow.first()

    suspend fun getCachedRemoteProvidersConfig(): RemoteProvidersConfig? {
        val prefs = context.ariaDataStore.data.first()
        val raw = prefs[Keys.REMOTE_PROVIDERS_CONFIG_JSON]
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<RemoteProvidersConfig>(raw) }.getOrNull()
    }

    suspend fun getPreviousRemoteProvidersConfig(): RemoteProvidersConfig? {
        val prefs = context.ariaDataStore.data.first()
        val raw = prefs[Keys.REMOTE_PROVIDERS_PREVIOUS_JSON]
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<RemoteProvidersConfig>(raw) }.getOrNull()
    }

    suspend fun saveRemoteProvidersConfig(config: RemoteProvidersConfig, sourceUrl: String? = null) {
        context.ariaDataStore.edit { prefs ->
            val currentRaw = prefs[Keys.REMOTE_PROVIDERS_CONFIG_JSON]
            val encodedNew = json.encodeToString(config)
            if (!currentRaw.isNullOrBlank() && currentRaw != encodedNew) {
                val currentCfg = runCatching { json.decodeFromString<RemoteProvidersConfig>(currentRaw) }.getOrNull()
                if (currentCfg != null && currentCfg.providers.isNotEmpty()) {
                    prefs[Keys.REMOTE_PROVIDERS_PREVIOUS_JSON] = currentRaw
                }
            }
            prefs[Keys.REMOTE_PROVIDERS_CONFIG_JSON] = encodedNew
            if (!config.etag.isNullOrBlank()) {
                prefs[Keys.REMOTE_CONFIG_ETAG] = config.etag
            } else if (!sourceUrl.isNullOrBlank() && prefs[Keys.REMOTE_CONFIG_SOURCE_URL] != sourceUrl) {
                prefs.remove(Keys.REMOTE_CONFIG_ETAG)
            }
            if (!config.lastModified.isNullOrBlank()) {
                prefs[Keys.REMOTE_CONFIG_LAST_MODIFIED] = config.lastModified
            } else if (!sourceUrl.isNullOrBlank() && prefs[Keys.REMOTE_CONFIG_SOURCE_URL] != sourceUrl) {
                prefs.remove(Keys.REMOTE_CONFIG_LAST_MODIFIED)
            }
            if (config.updatedAtMs > 0L) {
                prefs[Keys.REMOTE_CONFIG_LAST_SYNC_MS] = config.updatedAtMs
            }
            if (!sourceUrl.isNullOrBlank()) {
                prefs[Keys.REMOTE_CONFIG_SOURCE_URL] = sourceUrl
            }
        }
    }

    suspend fun rollbackRemoteProvidersConfig(): RemoteProvidersConfig? {
        var restored: RemoteProvidersConfig? = null
        context.ariaDataStore.edit { prefs ->
            val prevRaw = prefs[Keys.REMOTE_PROVIDERS_PREVIOUS_JSON] ?: return@edit
            val prevCfg = runCatching { json.decodeFromString<RemoteProvidersConfig>(prevRaw) }.getOrNull()
                ?: return@edit
            val currentRaw = prefs[Keys.REMOTE_PROVIDERS_CONFIG_JSON]
            prefs[Keys.REMOTE_PROVIDERS_CONFIG_JSON] = prevRaw
            if (!currentRaw.isNullOrBlank()) {
                prefs[Keys.REMOTE_PROVIDERS_PREVIOUS_JSON] = currentRaw
            } else {
                prefs.remove(Keys.REMOTE_PROVIDERS_PREVIOUS_JSON)
            }
            restored = prevCfg
        }
        return restored
    }

    suspend fun getRemoteConfigHttpMetadata(): Triple<String?, String?, Long> {
        val prefs = context.ariaDataStore.data.first()
        return Triple(
            prefs[Keys.REMOTE_CONFIG_ETAG],
            prefs[Keys.REMOTE_CONFIG_LAST_MODIFIED],
            prefs[Keys.REMOTE_CONFIG_LAST_SYNC_MS] ?: 0L
        )
    }

    suspend fun getRemoteConfigMetadataSourceUrl(): String? {
        return context.ariaDataStore.data.first()[Keys.REMOTE_CONFIG_SOURCE_URL]
    }

    suspend fun saveRemoteConfigHttpMetadata(
        etag: String?,
        lastModified: String?,
        syncedAtMs: Long,
        sourceUrl: String? = null
    ) {
        context.ariaDataStore.edit { prefs ->
            if (!etag.isNullOrBlank()) prefs[Keys.REMOTE_CONFIG_ETAG] = etag
            if (!lastModified.isNullOrBlank()) prefs[Keys.REMOTE_CONFIG_LAST_MODIFIED] = lastModified
            prefs[Keys.REMOTE_CONFIG_LAST_SYNC_MS] = syncedAtMs
            if (!sourceUrl.isNullOrBlank()) {
                prefs[Keys.REMOTE_CONFIG_SOURCE_URL] = sourceUrl
            }
        }
    }

    suspend fun updateSettings(transform: (UserSettings) -> UserSettings) {
        context.ariaDataStore.edit { prefs ->
            val current = prefs[Keys.USER_SETTINGS_JSON]
                ?.let { runCatching { json.decodeFromString<UserSettings>(it) }.getOrNull() }
                ?: UserSettings()
            val updated = transform(current)
            prefs[Keys.USER_SETTINGS_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun saveQueueState(
        queueUris: List<String>,
        currentIndex: Int,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int
    ) {
        context.ariaDataStore.edit { prefs ->
            prefs[Keys.QUEUE_URIS_JSON] = json.encodeToString(queueUris)
            prefs[Keys.QUEUE_INDEX] = currentIndex
            prefs[Keys.QUEUE_POSITION_MS] = positionMs.coerceAtLeast(0L)
            prefs[Keys.QUEUE_SHUFFLE] = if (shuffleEnabled) 1 else 0
            prefs[Keys.QUEUE_REPEAT] = repeatMode
        }
    }

    suspend fun loadQueueState(): PersistedQueueState {
        val prefs = context.ariaDataStore.data.first()
        val uris = prefs[Keys.QUEUE_URIS_JSON]
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()
        return PersistedQueueState(
            queueUris = uris,
            currentIndex = prefs[Keys.QUEUE_INDEX] ?: 0,
            positionMs = prefs[Keys.QUEUE_POSITION_MS] ?: 0L,
            shuffleEnabled = (prefs[Keys.QUEUE_SHUFFLE] ?: 0) == 1,
            repeatMode = prefs[Keys.QUEUE_REPEAT] ?: 0
        )
    }

    suspend fun saveOnlineQueueState(
        tracks: List<app.nocturne.player.data.model.OnlineTrack>,
        currentIndex: Int,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int
    ) {
        context.ariaDataStore.edit { prefs ->
            prefs[Keys.ONLINE_QUEUE_TRACKS_JSON] = json.encodeToString(tracks)
            prefs[Keys.ONLINE_QUEUE_INDEX] = currentIndex
            prefs[Keys.ONLINE_QUEUE_POSITION_MS] = positionMs.coerceAtLeast(0L)
            prefs[Keys.ONLINE_QUEUE_SHUFFLE] = if (shuffleEnabled) 1 else 0
            prefs[Keys.ONLINE_QUEUE_REPEAT] = repeatMode
        }
    }

    suspend fun loadOnlineQueueState(): PersistedOnlineQueueState {
        val prefs = context.ariaDataStore.data.first()
        val tracks = prefs[Keys.ONLINE_QUEUE_TRACKS_JSON]
            ?.let { runCatching { json.decodeFromString<List<app.nocturne.player.data.model.OnlineTrack>>(it) }.getOrNull() }
            ?: emptyList()
        return PersistedOnlineQueueState(
            tracks = tracks,
            currentIndex = prefs[Keys.ONLINE_QUEUE_INDEX] ?: 0,
            positionMs = prefs[Keys.ONLINE_QUEUE_POSITION_MS] ?: 0L,
            shuffleEnabled = (prefs[Keys.ONLINE_QUEUE_SHUFFLE] ?: 0) == 1,
            repeatMode = prefs[Keys.ONLINE_QUEUE_REPEAT] ?: 0
        )
    }

    suspend fun migrateLegacyFavoritesIfNeeded(): List<String> {
        val legacyPrefs = context.getSharedPreferences("aria_player_preferences", Context.MODE_PRIVATE)
        val favs = legacyPrefs.getStringSet("favourite_uris", emptySet())?.toList().orEmpty()
        if (favs.isNotEmpty()) {
            legacyPrefs.edit().remove("favourite_uris").apply()
        }
        return favs
    }
}
