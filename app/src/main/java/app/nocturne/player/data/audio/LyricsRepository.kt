package app.nocturne.player.data.audio

import android.content.Context
import app.nocturne.player.data.audio.lyrics.JioSaavnLyricsAdapter
import app.nocturne.player.data.audio.lyrics.LiriQoLyricsAdapter
import app.nocturne.player.data.audio.lyrics.LrcLibLyricsAdapter
import app.nocturne.player.data.audio.lyrics.LyricsHttpUtil
import app.nocturne.player.data.audio.lyrics.LyricsProvider
import app.nocturne.player.data.audio.lyrics.LyricsQuery
import app.nocturne.player.data.audio.lyrics.MusixmatchLyricsAdapter
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.db.LyricsCacheEntity
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.online.OnlineHttpClient
import app.nocturne.player.data.online.ProviderHealthTracker
import app.nocturne.player.data.online.RemoteConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

typealias LyricsManager = LyricsRepository

class LyricsRepository(
    private val context: Context,
    private val db: AriaDatabase,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val providersOverride: List<LyricsProvider>? = null,
    private val healthTracker: ProviderHealthTracker = LyricsHttpUtil.defaultHealthTracker
) {
    private val cacheDao = db.lyricsCacheDao()

    private val liriqoAdapter by lazy {
        LiriQoLyricsAdapter(
            configProvider = { remoteConfigRepository.liriqoConfig() },
            client = client,
            healthTracker = healthTracker
        )
    }

    private val musixmatchAdapter by lazy {
        MusixmatchLyricsAdapter(
            configProvider = { remoteConfigRepository.musixmatchConfig() },
            client = client,
            healthTracker = healthTracker
        )
    }

    private val jiosaavnAdapter by lazy {
        JioSaavnLyricsAdapter(
            configProvider = { remoteConfigRepository.jiosaavnLyricsConfig() },
            jioSaavnEndpointsProvider = { remoteConfigRepository.jiosaavnEndpoints() },
            client = client,
            healthTracker = healthTracker
        )
    }

    private val lrclibAdapter by lazy {
        LrcLibLyricsAdapter(
            endpointsProvider = { remoteConfigRepository.lrclibEndpoints() },
            client = client,
            allowLoopback = false,
            configProvider = { remoteConfigRepository.lrclibConfig() },
            healthTracker = healthTracker
        )
    }

    private fun resolveOrderedProviders(): List<LyricsProvider> {
        providersOverride?.let { return it }
        if (baseUrlsOverride != null) {
            return listOf(
                LrcLibLyricsAdapter(
                    endpointsProvider = { baseUrlsOverride },
                    client = client,
                    allowLoopback = true,
                    healthTracker = healthTracker
                )
            )
        }
        val active = remoteConfigRepository.activeConfig.value
        val candidates = listOf(
            liriqoAdapter to (active.liriqoConfig()?.priority ?: 1),
            musixmatchAdapter to (active.musixmatchConfig()?.priority ?: 2),
            jiosaavnAdapter to (active.jiosaavnLyricsConfig()?.priority ?: 3),
            lrclibAdapter to (active.lrclibConfig()?.priority ?: 4)
        )
        return candidates.sortedBy { it.second }.map { it.first }
    }

    suspend fun loadLyricsForTrack(
        track: Track,
        includedSafFolders: List<String> = emptyList(),
        forceRemoteRefresh: Boolean = false
    ): LyricsData = withContext(Dispatchers.IO) {
        val cacheKey = buildCacheKey(track)
        val cleaningConfig = remoteConfigRepository.lyricsCleaningConfig()
        val cleaned = MetadataCleaner.clean(
            rawTitle = track.title,
            rawArtist = track.artist,
            config = cleaningConfig,
            providerHint = track.onlineProvider
        )
        val normalizedKey = buildNormalizedKey(
            cleaned.title.ifBlank { track.title },
            cleaned.artist.ifBlank { track.artist }
        )

        // 1. Offline local sources first (Manual override, Sidecar .lrc, Embedded SYLT/USLT/FLAC, SAF .lrc)
        if (!track.isOnline) {
            val override = db.trackTagOverrideDao().getByUri(track.uriString)
            var localResult = TagAndLyricsEngine.loadLyricsForTrack(
                context = context,
                uri = track.uri,
                filePath = track.filePath,
                manualOrOverrideLyrics = override?.lyrics
            )
            if (!localResult.hasContent && includedSafFolders.isNotEmpty()) {
                val safLrc = TagAndLyricsEngine.findSafSidecarLyrics(
                    context,
                    includedSafFolders,
                    track.displayName
                )
                if (!safLrc.isNullOrBlank()) {
                    localResult = LyricsData(
                        syncedLines = TagAndLyricsEngine.parseLrc(safLrc),
                        rawText = safLrc,
                        source = LyricsSource.SIDECAR_LRC
                    )
                }
            }
            if (localResult.hasContent) {
                return@withContext localResult
            }
        } else {
            val override = db.trackTagOverrideDao().getByUri(track.uriString)
            if (!override?.lyrics.isNullOrBlank()) {
                val manualText = override!!.lyrics!!
                return@withContext LyricsData(
                    syncedLines = TagAndLyricsEngine.parseLrc(manualText),
                    rawText = manualText,
                    source = LyricsSource.MANUAL
                )
            }
        }

        // 2. Room lyrics_cache lookup keyed by trackId / cacheKey
        if (!forceRemoteRefresh) {
            val cached = cacheDao.getByTrackIdOrNormalized(
                trackId = cacheKey,
                normalizedKey = normalizedKey
            )
            if (cached != null && (!cached.syncedLyrics.isNullOrBlank() || !cached.plainLyrics.isNullOrBlank() || cached.isInstrumental)) {
                return@withContext cached.toDomain(fromCache = true)
            }
        }

        // 3. Clean metadata and execute 4-tier fallback chain:
        //    1. LiriQo -> 2. Musixmatch -> 3. JioSaavn Lyrics (if Saavn track) -> 4. LRCLIB
        val cleanTitle = cleaned.title
        if (cleanTitle.isBlank() ||
            cleanTitle.equals("Online Track", ignoreCase = true) ||
            cleanTitle.equals("Nothing Playing", ignoreCase = true)
        ) {
            return@withContext LyricsData(source = LyricsSource.NONE)
        }

        val query = LyricsQuery(
            cleanedTitle = cleanTitle,
            cleanedArtist = cleaned.artist,
            album = sanitizeAlbum(track.album, track.onlineProvider),
            durationMs = track.durationMs,
            sourceId = track.onlineSourceId,
            onlineProvider = track.onlineProvider,
            isOnline = track.isOnline
        )

        var firstInstrumental: app.nocturne.player.data.audio.lyrics.ResolvedLyricsPayload? = null

        for (provider in resolveOrderedProviders()) {
            val payload = runCatching { provider.fetchLyrics(query) }.getOrNull() ?: continue
            if (!payload.hasContent()) continue
            if (payload.isInstrumental && payload.parsedLines.isEmpty() && payload.plainLyrics.isNullOrBlank()) {
                if (firstInstrumental == null) firstInstrumental = payload
                continue
            }

            persistLyricsToCache(cacheKey, track, normalizedKey, payload)
            return@withContext payload.toLyricsData(fromCache = false)
        }

        if (firstInstrumental != null) {
            persistLyricsToCache(cacheKey, track, normalizedKey, firstInstrumental)
            return@withContext firstInstrumental.toLyricsData(fromCache = false)
        }

        LyricsData(source = LyricsSource.NONE)
    }

    suspend fun clearCachedLyricsForTrack(track: Track) = withContext(Dispatchers.IO) {
        val key = buildCacheKey(track)
        cacheDao.deleteForTrack(track.onlineTrackId.ifBlank { track.uriString }, key)
    }

    private suspend fun persistLyricsToCache(
        cacheKey: String,
        track: Track,
        normalizedKey: String,
        payload: app.nocturne.player.data.audio.lyrics.ResolvedLyricsPayload
    ) {
        val syncedToSave = payload.syncedLrc?.takeIf { it.isNotBlank() }
            ?: payload.parsedLines.takeIf { it.isNotEmpty() }?.let(LyricsHttpUtil::serializeToEnhancedLrc)
        cacheDao.upsert(
            LyricsCacheEntity(
                cacheKey = cacheKey,
                trackId = track.onlineTrackId.ifBlank { track.uriString },
                normalizedKey = normalizedKey,
                title = track.title,
                artist = track.artist,
                syncedLyrics = syncedToSave,
                plainLyrics = payload.plainLyrics,
                isInstrumental = payload.isInstrumental,
                source = payload.source.name,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun LyricsCacheEntity.toDomain(fromCache: Boolean): LyricsData {
        val synced = syncedLyrics.orEmpty()
        val plain = plainLyrics.orEmpty()
        val parsed = if (synced.isNotBlank()) TagAndLyricsEngine.parseLrc(synced) else emptyList()
        val src = runCatching { LyricsSource.valueOf(source) }.getOrDefault(LyricsSource.LRCLIB)
        return LyricsData(
            syncedLines = parsed,
            rawText = synced.ifBlank { plain },
            source = src,
            isInstrumental = isInstrumental && synced.isBlank() && plain.isBlank(),
            fromCache = fromCache
        )
    }

    private fun sanitizeAlbum(album: String, providerName: String): String? {
        if (album.isBlank() ||
            album.equals("Unknown Album", ignoreCase = true) ||
            album.equals("Online Stream", ignoreCase = true) ||
            album.equals("JioSaavn", ignoreCase = true) ||
            album.equals("Audius", ignoreCase = true) ||
            album.contains("YouTube", ignoreCase = true) ||
            album.equals(providerName, ignoreCase = true)
        ) {
            return null
        }
        return album.trim()
    }

    companion object {
        const val LRCLIB_USER_AGENT = LrcLibLyricsAdapter.LRCLIB_USER_AGENT

        fun buildCacheKey(track: Track): String {
            return if (track.isOnline && track.onlineTrackId.isNotBlank()) {
                track.onlineTrackId
            } else {
                track.uriString
            }
        }

        fun buildNormalizedKey(title: String, artist: String): String {
            val t = normalizeToken(MetadataCleaner.cleanTitle(title))
            val a = normalizeToken(MetadataCleaner.cleanArtist(artist))
            return "$a::$t"
        }

        private fun normalizeToken(raw: String): String {
            return raw.lowercase().replace(Regex("[^a-z0-9]"), "")
        }
    }
}
