package app.nocturne.player.data.online

import android.net.Uri
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAlbumPage
import app.nocturne.player.data.model.OnlineAlbumSummary
import app.nocturne.player.data.model.OnlineArtistPage
import app.nocturne.player.data.model.OnlineArtistSummary
import app.nocturne.player.data.model.OnlineAudioStream
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineSearchPage
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class AudiusProvider(
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null
) : OnlineMusicProvider {

    override val providerType: OnlineProviderType = OnlineProviderType.AUDIUS
    override val supportsPagination: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }
    private val discoveryMutex = Mutex()
    private var discoveredHosts: List<String> = emptyList()
    private var lastDiscoveryAtMs: Long = 0L
    private val hostCooldowns = ConcurrentHashMap<String, Long>()

    @Volatile
    var lastWorkingInstance: String? = null
        private set

    override suspend fun search(
        query: String,
        filter: OnlineSearchFilter,
        pageToken: String?
    ): OnlineSearchPage = withContext(Dispatchers.IO) {
        val encodedQ = Uri.encode(InputAndUrlSecurity.sanitizeSearchQuery(query))
        when (filter) {
            OnlineSearchFilter.ARTISTS -> {
                val (host, body) = executeWithHostFallback("/v1/users/search?query=$encodedQ&app_name=$APP_NAME")
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                val artists = data.mapNotNull { parseUserItem(it.jsonObject) }
                lastWorkingInstance = host
                OnlineSearchPage(
                    query = query,
                    artists = artists,
                    providerUsed = OnlineProviderType.AUDIUS
                )
            }
            OnlineSearchFilter.ALBUMS -> {
                val (host, body) = executeWithHostFallback("/v1/playlists/search?query=$encodedQ&app_name=$APP_NAME")
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                val albums = data.mapNotNull { parsePlaylistItem(it.jsonObject) }
                lastWorkingInstance = host
                OnlineSearchPage(
                    query = query,
                    albums = albums,
                    providerUsed = OnlineProviderType.AUDIUS
                )
            }
            else -> {
                val (host, body) = executeWithHostFallback("/v1/tracks/search?query=$encodedQ&app_name=$APP_NAME")
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                val tracks = data.mapNotNull { parseTrackItem(it.jsonObject) }
                lastWorkingInstance = host
                OnlineSearchPage(
                    query = query,
                    tracks = tracks,
                    providerUsed = OnlineProviderType.AUDIUS
                )
            }
        }
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (safeQuery.isBlank()) return@withContext emptyList()
        val (_, body) = executeWithHostFallback("/v1/tracks/search?query=${Uri.encode(safeQuery)}&app_name=$APP_NAME")
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
        data.mapNotNull { it.jsonObject.str("title") }.distinct().take(8)
    }

    override suspend fun trending(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val (_, body) = executeWithHostFallback("/v1/tracks/trending?app_name=$APP_NAME")
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
        data.mapNotNull { parseTrackItem(it.jsonObject) }
    }

    override suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution = withContext(Dispatchers.IO) {
        val hosts = getCandidateHosts()
        if (hosts.isEmpty()) throw IOException("No valid Audius endpoints available")
        val safeSourceId = InputAndUrlSecurity.sanitizePathSegment(track.sourceId)
            ?: throw IllegalArgumentException("Invalid Audius track ID")
        val trackId = Uri.encode(safeSourceId)

        var resolvedTrack = track
        var activeHost = lastWorkingInstance?.takeIf { it in hosts } ?: hosts.first()

        // Verify metadata and discover working host via lightweight track info call when possible
        for (host in hosts) {
            val clean = host.trimEnd('/')
            val infoResp = runCatching {
                val req = Request.Builder()
                    .url("$clean/v1/tracks/$trackId?app_name=$APP_NAME")
                    .header("User-Agent", OnlineHttpClient.USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else InputAndUrlSecurity.readBodyBounded(r.body)
                }
            }.getOrNull()

            if (!infoResp.isNullOrBlank() && !infoResp.trimStart().startsWith("<")) {
                activeHost = clean
                lastWorkingInstance = clean
                runCatching {
                    val dataObj = json.parseToJsonElement(infoResp).jsonObject["data"]?.jsonObject
                    if (dataObj != null) {
                        parseTrackItem(dataObj)?.let { updated ->
                            resolvedTrack = updated.copy(
                                isFavorite = track.isFavorite,
                                playCount = track.playCount,
                                lastPlayedAt = track.lastPlayedAt,
                                totalListenMs = track.totalListenMs
                            )
                        }
                    }
                }
                break
            }
        }

        val allowedTestOrigin = activeHost.takeIf { baseUrlsOverride != null }
        val streamUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(
            "$activeHost/v1/tracks/$trackId/stream?app_name=$APP_NAME",
            allowedLoopbackOrigin = allowedTestOrigin
        ) ?: throw IOException("Blocked unsafe Audius stream URL")

        val stream = OnlineAudioStream(
            url = streamUrl,
            bitrateKbps = 320,
            mimeType = "audio/mpeg",
            codec = "mp3",
            formatLabel = "MP3 320k",
            expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60_000L,
            headers = mapOf("User-Agent" to OnlineHttpClient.USER_AGENT)
        )
        val selected = StreamQualitySelector.selectBestAudioStream(
            streams = listOf(stream),
            speedTier = speedTier,
            allowedLoopbackOrigin = allowedTestOrigin
        ) ?: throw IOException("No valid Audius stream available")

        OnlineStreamResolution(
            track = resolvedTrack,
            streams = listOf(selected),
            selectedStream = selected,
            relatedTracks = emptyList(),
            resolvedBy = OnlineProviderType.AUDIUS
        )
    }

    override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage = withContext(Dispatchers.IO) {
        val rawId = artistIdOrUrl.substringAfterLast("/").substringBefore("?")
        val userId = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid Audius artist ID")
        val encodedUserId = Uri.encode(userId)
        val (_, tracksBody) = executeWithHostFallback("/v1/users/$encodedUserId/tracks?app_name=$APP_NAME")
        val tracksData = json.parseToJsonElement(tracksBody).jsonObject["data"]?.jsonArray.orEmpty()
        val tracks = tracksData.mapNotNull { parseTrackItem(it.jsonObject) }
        val userObj = runCatching {
            val (_, uBody) = executeWithHostFallback("/v1/users/$encodedUserId?app_name=$APP_NAME")
            json.parseToJsonElement(uBody).jsonObject["data"]?.jsonObject
        }.getOrNull()

        OnlineArtistPage(
            id = userId,
            name = userObj?.str("name") ?: tracks.firstOrNull()?.artist ?: "Audius Artist",
            url = "https://audius.co/${Uri.encode(userObj?.str("handle") ?: userId)}",
            avatarUrl = pickArtwork(userObj?.get("profile_picture") as? JsonObject),
            subscriberCountText = userObj?.long("follower_count")?.takeIf { it > 0L }?.let { "${it / 1000}K" }.orEmpty(),
            description = userObj?.str("bio").orEmpty(),
            tracks = tracks,
            playlists = emptyList(),
            providerType = OnlineProviderType.AUDIUS
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val rawId = albumIdOrUrl.substringAfterLast("/").substringBefore("?")
        val plId = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid Audius playlist ID")
        val (_, body) = executeWithHostFallback("/v1/playlists/${Uri.encode(plId)}/tracks?app_name=$APP_NAME")
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
        val tracks = data.mapNotNull { parseTrackItem(it.jsonObject) }
        OnlineAlbumPage(
            id = plId,
            title = "Audius Collection",
            artist = tracks.firstOrNull()?.artist ?: "Audius",
            url = albumIdOrUrl,
            thumbnailUrl = tracks.firstOrNull()?.thumbnailUrl,
            description = "",
            tracks = tracks,
            providerType = OnlineProviderType.AUDIUS
        )
    }

    private suspend fun getCandidateHosts(): List<String> {
        if (baseUrlsOverride != null) {
            return baseUrlsOverride.mapNotNull {
                InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = true)
            }
        }
        discoveryMutex.withLock {
            val now = System.currentTimeMillis()
            if (discoveredHosts.isEmpty() || now - lastDiscoveryAtMs > DISCOVERY_TTL_MS) {
                val fresh = runCatching { fetchDiscoveryHosts() }.getOrDefault(emptyList())
                discoveredHosts = (fresh + DEFAULT_HOSTS).distinct()
                lastDiscoveryAtMs = now
            }
        }
        val now = System.currentTimeMillis()
        val ready = discoveredHosts.filter { (hostCooldowns[it] ?: 0L) <= now }
        return ready.ifEmpty { discoveredHosts }
    }

    private fun fetchDiscoveryHosts(): List<String> {
        val req = Request.Builder()
            .url("https://api.audius.co")
            .header("User-Agent", OnlineHttpClient.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = InputAndUrlSecurity.readBodyBounded(resp.body, 1024L * 1024L)
            val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull { el ->
                InputAndUrlSecurity.validateRemoteBaseUrl(el.jsonPrimitive.contentOrNull, allowLoopback = false)
            }.take(8)
        }
    }

    private suspend fun executeWithHostFallback(path: String): Pair<String, String> {
        val hosts = getCandidateHosts()
        var lastErr: Throwable? = null
        for (host in hosts) {
            val clean = host.trimEnd('/')
            try {
                val req = Request.Builder()
                    .url("$clean$path")
                    .header("User-Agent", OnlineHttpClient.USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} from $clean")
                    }
                    val body = InputAndUrlSecurity.readBodyBounded(resp.body)
                    val trimmed = body.trimStart()
                    if (trimmed.isBlank() || trimmed.startsWith("<")) {
                        throw IOException("Invalid JSON payload from $clean")
                    }
                    lastWorkingInstance = clean
                    hostCooldowns.remove(clean)
                    return clean to body
                }
            } catch (t: Throwable) {
                lastErr = t
                hostCooldowns[clean] = System.currentTimeMillis() + HOST_COOLDOWN_MS
            }
        }
        throw lastErr ?: IOException("All Audius endpoints unavailable")
    }

    private fun parseTrackItem(obj: JsonObject): OnlineTrack? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val isStreamable = obj.bool("is_streamable") ?: true
        if (!isStreamable) return null
        val title = obj.str("title")?.takeIf { it.isNotBlank() } ?: return null
        val user = obj["user"] as? JsonObject
        val artist = user?.str("name")?.takeIf { it.isNotBlank() } ?: "Audius Artist"
        val userId = user?.str("id")
        val handle = user?.str("handle")
        val durationSec = obj.long("duration") ?: 0L
        val permalink = obj.str("permalink")
        val sourceUrl = if (!permalink.isNullOrBlank()) {
            if (permalink.startsWith("http")) permalink else "https://audius.co$permalink"
        } else {
            "https://audius.co/tracks/$id"
        }

        return OnlineTrack(
            id = OnlineTrack.canonicalId(OnlineProviderType.AUDIUS, id),
            sourceId = id,
            providerType = OnlineProviderType.AUDIUS,
            providerLabel = OnlineProviderType.AUDIUS.displayName,
            sourceUrl = sourceUrl,
            title = title,
            artist = artist,
            artistId = userId,
            artistUrl = handle?.let { "https://audius.co/$it" } ?: userId,
            album = obj.str("genre").orEmpty().ifBlank { "Audius" },
            durationMs = if (durationSec > 0L) durationSec * 1000L else 0L,
            thumbnailUrl = pickArtwork(obj["artwork"] as? JsonObject),
            viewCount = obj.long("play_count") ?: 0L,
            publishedText = obj.str("release_date").orEmpty(),
            isLive = false
        )
    }

    private fun parseUserItem(obj: JsonObject): OnlineArtistSummary? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val handle = obj.str("handle")
        return OnlineArtistSummary(
            id = id,
            name = obj.str("name") ?: handle ?: "Artist",
            url = handle?.let { "https://audius.co/$it" } ?: id,
            thumbnailUrl = pickArtwork(obj["profile_picture"] as? JsonObject),
            subscriberText = obj.long("follower_count")?.takeIf { it > 0L }?.let { "${it / 1000}K" }.orEmpty(),
            providerType = OnlineProviderType.AUDIUS
        )
    }

    private fun parsePlaylistItem(obj: JsonObject): OnlineAlbumSummary? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val user = obj["user"] as? JsonObject
        return OnlineAlbumSummary(
            id = id,
            title = obj.str("playlist_name") ?: obj.str("name") ?: "Playlist",
            artist = user?.str("name") ?: "Audius",
            url = obj.str("permalink")?.let { if (it.startsWith("http")) it else "https://audius.co$it" } ?: id,
            thumbnailUrl = pickArtwork(obj["artwork"] as? JsonObject),
            trackCount = obj.int("track_count") ?: 0,
            providerType = OnlineProviderType.AUDIUS
        )
    }

    private fun pickArtwork(art: JsonObject?): String? {
        if (art == null) return null
        val raw = art.str("480x480") ?: art.str("1000x1000") ?: art.str("150x150")
        return InputAndUrlSecurity.validateRemoteHttpsUrl(raw)
    }

    companion object {
        private const val APP_NAME = "ARIA"
        private const val DISCOVERY_TTL_MS = 30 * 60_000L
        private const val HOST_COOLDOWN_MS = 90_000L

        private val DEFAULT_HOSTS = listOf(
            "https://discoveryprovider.audius.co",
            "https://discoveryprovider2.audius.co",
            "https://audius.co"
        )
    }
}
