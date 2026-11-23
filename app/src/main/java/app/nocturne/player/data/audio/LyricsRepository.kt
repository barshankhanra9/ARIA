package app.nocturne.player.data.audio

import android.content.Context
import android.net.Uri
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.db.LyricsCacheEntity
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.online.OnlineHttpClient
import app.nocturne.player.data.online.OnlineProviderManager
import app.nocturne.player.data.online.RemoteConfigRepository
import app.nocturne.player.data.online.bool
import app.nocturne.player.data.online.long
import app.nocturne.player.data.online.str
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs

class LyricsRepository(
    private val context: Context,
    private val db: AriaDatabase,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDao = db.lyricsCacheDao()

    suspend fun loadLyricsForTrack(
        track: Track,
        includedSafFolders: List<String> = emptyList(),
        forceRemoteRefresh: Boolean = false
    ): LyricsData = withContext(Dispatchers.IO) {
        val cacheKey = buildCacheKey(track)
        val normalizedKey = buildNormalizedKey(track.title, track.artist)

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
            // Check manual override for online track if user saved custom lyrics
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

        // 2. Room lyrics_cache lookup
        if (!forceRemoteRefresh) {
            val cached = cacheDao.getByTrackIdOrNormalized(
                trackId = cacheKey,
                normalizedKey = normalizedKey
            )
            if (cached != null && (!cached.syncedLyrics.isNullOrBlank() || !cached.plainLyrics.isNullOrBlank() || cached.isInstrumental)) {
                return@withContext cached.toDomain(fromCache = true)
            }
        }

        // 3. Keyless LRCLIB fetch (/api/get with fallback to /api/search)
        val cleanTitle = OnlineProviderManager.cleanMetadataQuery(track.title)
        if (cleanTitle.isBlank() || cleanTitle.equals("Online Track", ignoreCase = true) || cleanTitle.equals("Nothing Playing", ignoreCase = true)) {
            return@withContext LyricsData(source = LyricsSource.NONE)
        }
        val cleanArtist = sanitizeArtist(track.artist, track.onlineProvider)

        val fetched = fetchFromLrclib(
            trackName = cleanTitle,
            artistName = cleanArtist,
            albumName = sanitizeAlbum(track.album, track.onlineProvider),
            durationSec = (track.durationMs / 1000L).takeIf { it > 0L }
        )

        if (fetched != null) {
            cacheDao.upsert(
                LyricsCacheEntity(
                    cacheKey = cacheKey,
                    trackId = track.onlineTrackId.ifBlank { track.uriString },
                    normalizedKey = normalizedKey,
                    title = track.title,
                    artist = track.artist,
                    syncedLyrics = fetched.syncedLyrics,
                    plainLyrics = fetched.plainLyrics,
                    isInstrumental = fetched.instrumental,
                    source = LyricsSource.LRCLIB.name,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@withContext fetched.toDomain()
        }

        LyricsData(source = LyricsSource.NONE)
    }

    suspend fun clearCachedLyricsForTrack(track: Track) = withContext(Dispatchers.IO) {
        val key = buildCacheKey(track)
        cacheDao.deleteForTrack(track.onlineTrackId.ifBlank { track.uriString }, key)
    }

    private data class LrclibRecord(
        val trackName: String,
        val artistName: String,
        val durationSec: Long,
        val instrumental: Boolean,
        val plainLyrics: String?,
        val syncedLyrics: String?
    ) {
        fun hasLyrics(): Boolean = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank() || instrumental

        fun toDomain(): LyricsData {
            val syncedText = syncedLyrics.orEmpty()
            val plainText = plainLyrics.orEmpty()
            val parsedLines = if (syncedText.isNotBlank()) TagAndLyricsEngine.parseLrc(syncedText) else emptyList()
            val raw = syncedText.ifBlank { plainText }
            return LyricsData(
                syncedLines = parsedLines,
                rawText = raw,
                source = LyricsSource.LRCLIB,
                isInstrumental = instrumental && syncedText.isBlank() && plainText.isBlank(),
                fromCache = false
            )
        }
    }

    private fun fetchFromLrclib(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSec: Long?
    ): LrclibRecord? {
        val endpoints = getLrclibEndpoints()
        for (baseUrl in endpoints) {
            val cleanBase = baseUrl.trimEnd('/')
            // 1. Exact lookup via /api/get when artist is known
            if (artistName.isNotBlank()) {
                val getUrl = buildString {
                    append("$cleanBase/api/get?track_name=${Uri.encode(trackName)}")
                    append("&artist_name=${Uri.encode(artistName)}")
                    if (!albumName.isNullOrBlank()) {
                        append("&album_name=${Uri.encode(albumName)}")
                    }
                    if (durationSec != null && durationSec > 0L) {
                        append("&duration=$durationSec")
                    }
                }
                val directMatch = executeJsonGet(getUrl)?.let { body ->
                    runCatching { parseRecord(json.parseToJsonElement(body).jsonObject) }.getOrNull()
                }
                if (directMatch != null && directMatch.hasLyrics()) {
                    return directMatch
                }
            }

            // 2. Fallback via /api/search
            val searchUrl = if (artistName.isNotBlank()) {
                "$cleanBase/api/search?track_name=${Uri.encode(trackName)}&artist_name=${Uri.encode(artistName)}"
            } else {
                "$cleanBase/api/search?q=${Uri.encode(trackName)}"
            }
            val searchBody = executeJsonGet(searchUrl)
            if (!searchBody.isNullOrBlank()) {
                val best = runCatching {
                    val arr = json.parseToJsonElement(searchBody).jsonArray
                    pickBestSearchMatch(arr, trackName, artistName, durationSec)
                }.getOrNull()
                if (best != null && best.hasLyrics()) {
                    return best
                }
            }

            // 3. Broad query fallback (?q=title artist) if structured search yielded nothing
            if (artistName.isNotBlank()) {
                val broadUrl = "$cleanBase/api/search?q=${Uri.encode("$trackName $artistName")}"
                val broadBody = executeJsonGet(broadUrl)
                if (!broadBody.isNullOrBlank()) {
                    val best = runCatching {
                        val arr = json.parseToJsonElement(broadBody).jsonArray
                        pickBestSearchMatch(arr, trackName, artistName, durationSec)
                    }.getOrNull()
                    if (best != null && best.hasLyrics()) {
                        return best
                    }
                }
            }
        }
        return null
    }

    private fun executeJsonGet(url: String): String? {
        val allowedOrigin = if (baseUrlsOverride != null) url else null
        val safeUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(url, allowedOrigin) ?: return null
        return runCatching {
            val req = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", LRCLIB_USER_AGENT)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else InputAndUrlSecurity.readBodyBounded(resp.body, 1024L * 1024L)
                    .takeIf { !it.trimStart().startsWith("<") }
            }
        }.getOrNull()
    }

    private fun parseRecord(obj: JsonObject): LrclibRecord? {
        val synced = obj.str("syncedLyrics")?.takeIf { it.isNotBlank() && it != "null" }
        val plain = obj.str("plainLyrics")?.takeIf { it.isNotBlank() && it != "null" }
        val instrumental = obj.bool("instrumental") ?: false
        if (synced.isNullOrBlank() && plain.isNullOrBlank() && !instrumental) return null
        return LrclibRecord(
            trackName = obj.str("trackName") ?: obj.str("name").orEmpty(),
            artistName = obj.str("artistName").orEmpty(),
            durationSec = obj.long("duration") ?: 0L,
            instrumental = instrumental,
            plainLyrics = plain,
            syncedLyrics = synced
        )
    }

    private fun pickBestSearchMatch(
        arr: JsonArray,
        targetTitle: String,
        targetArtist: String,
        durationSec: Long?
    ): LrclibRecord? {
        val records = arr.mapNotNull { el -> (el as? JsonObject)?.let(::parseRecord) }
        if (records.isEmpty()) return null

        val normTitle = normalizeToken(targetTitle)
        val normArtist = normalizeToken(targetArtist)

        return records.maxByOrNull { rec ->
            var score = 0
            if (!rec.syncedLyrics.isNullOrBlank()) score += 50
            else if (!rec.plainLyrics.isNullOrBlank()) score += 20

            val recTitle = normalizeToken(rec.trackName)
            val recArtist = normalizeToken(rec.artistName)
            if (recTitle == normTitle) score += 30
            else if (recTitle.contains(normTitle) || normTitle.contains(recTitle)) score += 15

            if (normArtist.isNotBlank() && (recArtist == normArtist || recArtist.contains(normArtist) || normArtist.contains(recArtist))) {
                score += 20
            }

            if (durationSec != null && durationSec > 0L && rec.durationSec > 0L) {
                val diff = abs(durationSec - rec.durationSec)
                if (diff <= 4L) score += 25
                else if (diff <= 12L) score += 10
                else if (diff > 35L) score -= 20
            }
            score
        }
    }

    private fun getLrclibEndpoints(): List<String> {
        if (baseUrlsOverride != null) {
            return baseUrlsOverride.mapNotNull {
                InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = true)
            }
        }
        return remoteConfigRepository.lrclibEndpoints().mapNotNull {
            InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = false)
        }
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

    private fun sanitizeArtist(artist: String, providerName: String): String {
        val cleaned = OnlineProviderManager.cleanMetadataQuery(artist)
        if (cleaned.isBlank() ||
            cleaned.equals("Unknown Artist", ignoreCase = true) ||
            cleaned.equals("YouTube", ignoreCase = true) ||
            cleaned.equals(providerName, ignoreCase = true)
        ) {
            return ""
        }
        return cleaned.removeSuffix(" - Topic").trim()
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
        const val LRCLIB_USER_AGENT = "ARIA-Player/2.0.0 (https://github.com/nocturne-player/aria)"

        fun buildCacheKey(track: Track): String {
            return if (track.isOnline && track.onlineTrackId.isNotBlank()) {
                track.onlineTrackId
            } else {
                track.uriString
            }
        }

        fun buildNormalizedKey(title: String, artist: String): String {
            val t = normalizeToken(OnlineProviderManager.cleanMetadataQuery(title))
            val a = normalizeToken(OnlineProviderManager.cleanMetadataQuery(artist))
            return "$a::$t"
        }

        private fun normalizeToken(raw: String): String {
            return raw.lowercase().replace(Regex("[^a-z0-9]"), "")
        }
    }
}
