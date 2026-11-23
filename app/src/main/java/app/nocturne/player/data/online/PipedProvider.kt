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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class PipedProvider(
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val dynamicBaseUrlsProvider: (() -> List<String>)? = null
) : OnlineMusicProvider {

    override val providerType: OnlineProviderType = OnlineProviderType.PIPED
    override val supportsPagination: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }
    private val discoveryMutex = Mutex()
    private var discoveredInstances: List<String> = emptyList()
    private var lastDiscoveryAtMs: Long = 0L
    private val instanceCooldowns = ConcurrentHashMap<String, Long>()
    @Volatile
    var lastWorkingInstance: String? = null
        private set

    override suspend fun search(
        query: String,
        filter: OnlineSearchFilter,
        pageToken: String?
    ): OnlineSearchPage = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        val filterParam = when (filter) {
            OnlineSearchFilter.SONGS -> "music_songs"
            OnlineSearchFilter.ARTISTS -> "channels"
            OnlineSearchFilter.ALBUMS -> "playlists"
            else -> "all"
        }
        val encodedQ = Uri.encode(safeQuery)
        val path = if (pageToken.isNullOrBlank()) {
            "/search?q=$encodedQ&filter=$filterParam"
        } else {
            "/nextpage/search?nextpage=${Uri.encode(pageToken.take(512))}&q=$encodedQ&filter=$filterParam"
        }

        val body = executeWithInstanceFallback(path)
        val obj = json.parseToJsonElement(body).jsonObject
        val items = obj["items"]?.jsonArray.orEmpty()

        val tracks = mutableListOf<OnlineTrack>()
        val artists = mutableListOf<OnlineArtistSummary>()
        val albums = mutableListOf<OnlineAlbumSummary>()

        items.forEach { el ->
            val itemObj = el.jsonObject
            when (itemObj.str("type")) {
                "stream", null -> parseStreamItem(itemObj)?.let { tracks.add(it) }
                "channel" -> parseChannelItem(itemObj)?.let { artists.add(it) }
                "playlist" -> parsePlaylistItem(itemObj)?.let { albums.add(it) }
            }
        }

        val nextToken = obj.str("nextpage")?.takeIf { it.isNotBlank() && it != "null" }
        OnlineSearchPage(
            query = query,
            tracks = tracks,
            artists = artists,
            albums = albums,
            nextPageToken = nextToken,
            hasMore = nextToken != null,
            providerUsed = OnlineProviderType.PIPED
        )
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (safeQuery.isBlank()) return@withContext emptyList()
        val body = executeWithInstanceFallback("/suggestions?query=${Uri.encode(safeQuery)}")
        json.parseToJsonElement(body).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    override suspend fun trending(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val body = executeWithInstanceFallback("/trending?region=US")
        val arr = json.parseToJsonElement(body).jsonArray
        arr.mapNotNull { parseStreamItem(it.jsonObject) }
    }

    override suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution = withContext(Dispatchers.IO) {
        val videoId = InputAndUrlSecurity.sanitizePathSegment(track.sourceId)
            ?: throw IllegalArgumentException("Invalid Piped video ID")
        val body = executeWithInstanceFallback("/streams/${Uri.encode(videoId)}")
        val obj = json.parseToJsonElement(body).jsonObject

        val rawStreams = obj["audioStreams"]?.jsonArray.orEmpty()
        val streams = rawStreams.mapNotNull { el ->
            val st = el.jsonObject
            val url = InputAndUrlSecurity.validateRemoteHttpsUrl(st.str("url"))
                ?: return@mapNotNull null
            val bps = st.int("bitrate") ?: 128_000
            val kbps = if (bps > 1000) bps / 1000 else bps
            val mime = st.str("mimeType").orEmpty().ifBlank { "audio/mp4" }
            val codec = st.str("codec").orEmpty().ifBlank { st.str("format").orEmpty() }
            val fmt = st.str("format").orEmpty().ifBlank { "AUDIO" }
            OnlineAudioStream(
                url = url,
                bitrateKbps = kbps.coerceAtLeast(32),
                mimeType = mime,
                codec = codec,
                formatLabel = "${fmt.uppercase()} ${kbps}k",
                expiresAtMs = StreamQualitySelector.parseExpiryFromUrl(url)
            )
        }

        val selected = StreamQualitySelector.selectBestAudioStream(streams, speedTier)
            ?: throw IllegalStateException("No playable audio streams returned by Piped")

        val title = obj.str("title") ?: track.title
        val uploader = obj.str("uploader") ?: track.artist
        val uploaderUrl = obj.str("uploaderUrl")?.let { normalizeYoutubeUrl(it) } ?: track.artistUrl
        val durationSec = obj.long("duration") ?: 0L
        val thumb = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnailUrl")) ?: track.thumbnailUrl
        val related = obj["relatedStreams"]?.jsonArray.orEmpty().mapNotNull {
            parseStreamItem(it.jsonObject)
        }

        OnlineStreamResolution(
            track = track.copy(
                title = title,
                artist = uploader,
                artistUrl = uploaderUrl,
                durationMs = if (durationSec > 0L) durationSec * 1000L else track.durationMs,
                thumbnailUrl = thumb,
                isLive = obj.bool("livestream") ?: track.isLive
            ),
            streams = streams,
            selectedStream = selected,
            relatedTracks = related,
            resolvedBy = OnlineProviderType.PIPED
        )
    }

    override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage = withContext(Dispatchers.IO) {
        val channelId = InputAndUrlSecurity.sanitizePathSegment(extractChannelId(artistIdOrUrl))
            ?: throw IllegalArgumentException("Invalid Piped channel ID")
        val body = executeWithInstanceFallback("/channel/${Uri.encode(channelId)}")
        val obj = json.parseToJsonElement(body).jsonObject
        val tracks = obj["relatedStreams"]?.jsonArray.orEmpty().mapNotNull {
            parseStreamItem(it.jsonObject)
        }
        val subCount = obj.long("subscriberCount") ?: 0L

        OnlineArtistPage(
            id = channelId,
            name = obj.str("name") ?: "Artist",
            url = "https://www.youtube.com/channel/${Uri.encode(channelId)}",
            avatarUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("avatarUrl")),
            bannerUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("bannerUrl")),
            subscriberCountText = if (subCount > 0L) "${subCount / 1000}K" else "",
            description = obj.str("description").orEmpty(),
            tracks = tracks,
            playlists = emptyList(),
            providerType = OnlineProviderType.PIPED
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val playlistId = InputAndUrlSecurity.sanitizePathSegment(extractPlaylistId(albumIdOrUrl))
            ?: throw IllegalArgumentException("Invalid Piped playlist ID")
        val body = executeWithInstanceFallback("/playlists/${Uri.encode(playlistId)}")
        val obj = json.parseToJsonElement(body).jsonObject
        val tracks = obj["relatedStreams"]?.jsonArray.orEmpty().mapNotNull {
            parseStreamItem(it.jsonObject)
        }

        OnlineAlbumPage(
            id = playlistId,
            title = obj.str("name") ?: "Playlist",
            artist = obj.str("uploader") ?: "Piped",
            url = "https://www.youtube.com/playlist?list=${Uri.encode(playlistId)}",
            thumbnailUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnailUrl"))
                ?: tracks.firstOrNull()?.thumbnailUrl,
            description = obj.str("description").orEmpty(),
            tracks = tracks,
            providerType = OnlineProviderType.PIPED
        )
    }

    private suspend fun getCandidateInstances(): List<String> {
        if (baseUrlsOverride != null) {
            return baseUrlsOverride.mapNotNull {
                InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = true)
            }
        }
        val remoteConfigured = dynamicBaseUrlsProvider?.invoke().orEmpty()
            .mapNotNull { InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = false) }
        discoveryMutex.withLock {
            val now = System.currentTimeMillis()
            if (discoveredInstances.isEmpty() || now - lastDiscoveryAtMs > DISCOVERY_TTL_MS) {
                val fresh = runCatching { fetchInstancesFromRegistry() }.getOrDefault(emptyList())
                discoveredInstances = (remoteConfigured + fresh + DEFAULT_INSTANCES).distinct()
                lastDiscoveryAtMs = now
            }
        }
        val combined = (remoteConfigured + discoveredInstances).distinct()
        val now = System.currentTimeMillis()
        val ready = combined.filter { (instanceCooldowns[it] ?: 0L) <= now }
        return ready.ifEmpty { combined }
    }

    private fun fetchInstancesFromRegistry(): List<String> {
        val req = Request.Builder()
            .url("https://raw.githubusercontent.com/TeamPiped/piped-instances/main/instances.json")
            .header("User-Agent", OnlineHttpClient.USER_AGENT)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = InputAndUrlSecurity.readBodyBounded(resp.body, 1024L * 1024L)
            val arr = json.parseToJsonElement(body).jsonArray
            return arr.mapNotNull { el ->
                val obj = el.jsonObject
                val apiUrl = obj.str("api_url")
                val up = obj.bool("up_to_date") ?: true
                if (!up) null else InputAndUrlSecurity.validateRemoteBaseUrl(apiUrl, allowLoopback = false)
            }.take(10)
        }
    }

    private suspend fun executeWithInstanceFallback(path: String): String {
        val candidates = getCandidateInstances()
        var lastErr: Throwable? = null
        for (base in candidates) {
            val cleanBase = base.trimEnd('/')
            val url = "$cleanBase$path"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", OnlineHttpClient.USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} from $cleanBase")
                    }
                    val body = InputAndUrlSecurity.readBodyBounded(resp.body)
                    val trimmed = body.trimStart()
                    if (trimmed.isBlank() || trimmed.startsWith("<")) {
                        throw IOException("Invalid JSON payload from $cleanBase")
                    }
                    lastWorkingInstance = cleanBase
                    instanceCooldowns.remove(cleanBase)
                    return body
                }
            } catch (t: Throwable) {
                lastErr = t
                instanceCooldowns[cleanBase] = System.currentTimeMillis() + INSTANCE_COOLDOWN_MS
            }
        }
        throw lastErr ?: IOException("All Piped instances unavailable")
    }

    private fun parseStreamItem(obj: JsonObject): OnlineTrack? {
        val rawUrl = obj.str("url")?.takeIf { it.isNotBlank() } ?: return null
        val id = extractVideoId(rawUrl).takeIf { it.isNotBlank() } ?: return null
        val durationSec = obj.long("duration") ?: 0L
        val uploaderUrl = obj.str("uploaderUrl")?.let { normalizeYoutubeUrl(it) }
        val safeThumb = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnail"))
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return OnlineTrack(
            id = OnlineTrack.canonicalId(OnlineProviderType.YOUTUBE, id),
            sourceId = id,
            providerType = OnlineProviderType.PIPED,
            providerLabel = OnlineProviderType.PIPED.displayName,
            sourceUrl = "https://www.youtube.com/watch?v=$id",
            title = obj.str("title") ?: "Untitled",
            artist = obj.str("uploaderName") ?: "YouTube",
            artistId = uploaderUrl?.substringAfter("/channel/")?.substringBefore("/"),
            artistUrl = uploaderUrl,
            album = "YouTube · Piped",
            durationMs = if (durationSec > 0L) durationSec * 1000L else 0L,
            thumbnailUrl = safeThumb,
            viewCount = obj.long("views") ?: 0L,
            publishedText = obj.str("uploadedDate").orEmpty(),
            isLive = durationSec < 0L
        )
    }

    private fun parseChannelItem(obj: JsonObject): OnlineArtistSummary? {
        val rawUrl = obj.str("url")?.takeIf { it.isNotBlank() } ?: return null
        val fullUrl = normalizeYoutubeUrl(rawUrl) ?: return null
        return OnlineArtistSummary(
            id = extractChannelId(fullUrl),
            name = obj.str("name") ?: "Artist",
            url = fullUrl,
            thumbnailUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnail")),
            subscriberText = obj.long("subscribers")?.takeIf { it > 0L }?.let { "${it / 1000}K" }.orEmpty(),
            providerType = OnlineProviderType.PIPED
        )
    }

    private fun parsePlaylistItem(obj: JsonObject): OnlineAlbumSummary? {
        val rawUrl = obj.str("url")?.takeIf { it.isNotBlank() } ?: return null
        val fullUrl = normalizeYoutubeUrl(rawUrl) ?: return null
        return OnlineAlbumSummary(
            id = extractPlaylistId(fullUrl),
            title = obj.str("name") ?: "Playlist",
            artist = obj.str("uploaderName") ?: "YouTube",
            url = fullUrl,
            thumbnailUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("thumbnail")),
            trackCount = obj.int("videos") ?: 0,
            providerType = OnlineProviderType.PIPED
        )
    }

    private fun normalizeYoutubeUrl(urlOrPath: String): String? {
        val candidate = if (urlOrPath.startsWith("http", ignoreCase = true)) {
            urlOrPath
        } else if (urlOrPath.startsWith("/")) {
            "https://www.youtube.com$urlOrPath"
        } else {
            "https://www.youtube.com/$urlOrPath"
        }
        return InputAndUrlSecurity.validateRemoteHttpsUrl(candidate)
    }

    private fun extractVideoId(url: String): String {
        return runCatching {
            val uri = Uri.parse(normalizeYoutubeUrl(url))
            uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }
                ?: if (url.contains("/shorts/")) url.substringAfter("/shorts/").substringBefore("?")
                else url.substringAfterLast("/")
        }.getOrDefault(url)
    }

    private fun extractChannelId(idOrUrl: String): String {
        return when {
            idOrUrl.contains("/channel/") -> idOrUrl.substringAfter("/channel/").substringBefore("?").substringBefore("/")
            else -> idOrUrl
        }
    }

    private fun extractPlaylistId(idOrUrl: String): String {
        return runCatching {
            Uri.parse(normalizeYoutubeUrl(idOrUrl)).getQueryParameter("list")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: idOrUrl
    }

    companion object {
        private const val DISCOVERY_TTL_MS = 30 * 60_000L
        private const val INSTANCE_COOLDOWN_MS = 90_000L

        private val DEFAULT_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.r4fo.com",
            "https://api.piped.private.coffee",
            "https://pipedapi.darkness.services"
        )
    }
}
