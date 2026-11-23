package app.nocturne.player.data.online

import android.net.Uri
import android.util.LruCache
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
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class JioSaavnProvider(
    private val client: OkHttpClient = OnlineHttpClient.client,
    private val baseUrlsOverride: List<String>? = null,
    private val dynamicBaseUrlsProvider: (() -> List<String>)? = null
) : OnlineMusicProvider {

    override val providerType: OnlineProviderType = OnlineProviderType.JIOSAAVN
    override val supportsPagination: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }
    private val instanceCooldowns = ConcurrentHashMap<String, Long>()
    private val parsedStreamsCache = LruCache<String, List<OnlineAudioStream>>(160)

    @Volatile
    var lastWorkingInstance: String? = null
        private set

    override suspend fun search(
        query: String,
        filter: OnlineSearchFilter,
        pageToken: String?
    ): OnlineSearchPage = withContext(Dispatchers.IO) {
        val page = pageToken?.toIntOrNull()?.coerceIn(1, 100) ?: 1
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        val encodedQ = Uri.encode(safeQuery)

        when (filter) {
            OnlineSearchFilter.ALBUMS -> {
                val body = executeWithInstanceFallback("/search/albums?query=$encodedQ&page=$page&limit=20")
                val results = extractResultsArray(body)
                val albums = results.mapNotNull { parseAlbumItem(it.jsonObject) }
                return@withContext OnlineSearchPage(
                    query = query,
                    albums = albums,
                    nextPageToken = if (albums.size >= 15) (page + 1).toString() else null,
                    hasMore = albums.size >= 15,
                    providerUsed = OnlineProviderType.JIOSAAVN
                )
            }
            OnlineSearchFilter.ARTISTS -> {
                val body = executeWithInstanceFallback("/search/artists?query=$encodedQ&page=$page&limit=20")
                val results = extractResultsArray(body)
                val artists = results.mapNotNull { parseArtistItem(it.jsonObject) }
                return@withContext OnlineSearchPage(
                    query = query,
                    artists = artists,
                    nextPageToken = if (artists.size >= 15) (page + 1).toString() else null,
                    hasMore = artists.size >= 15,
                    providerUsed = OnlineProviderType.JIOSAAVN
                )
            }
            else -> {
                val body = executeWithInstanceFallback("/search/songs?query=$encodedQ&page=$page&limit=20")
                val results = extractResultsArray(body)
                val tracks = results.mapNotNull { parseSongItem(it.jsonObject) }
                return@withContext OnlineSearchPage(
                    query = query,
                    tracks = tracks,
                    nextPageToken = if (tracks.size >= 15) (page + 1).toString() else null,
                    hasMore = tracks.size >= 15,
                    providerUsed = OnlineProviderType.JIOSAAVN
                )
            }
        }
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val safeQuery = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (safeQuery.isBlank()) return@withContext emptyList()
        val body = executeWithInstanceFallback("/search/songs?query=${Uri.encode(safeQuery)}&page=1&limit=8")
        extractResultsArray(body)
            .mapNotNull { el ->
                val obj = el.jsonObject
                (obj.str("name") ?: obj.str("title"))?.let(::decodeHtmlEntities)
            }
            .distinct()
            .take(8)
    }

    override suspend fun trending(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val body = executeWithInstanceFallback("/search/songs?query=${Uri.encode("latest trending songs")}&page=1&limit=20")
        extractResultsArray(body).mapNotNull { parseSongItem(it.jsonObject) }
    }

    override suspend fun resolveStream(
        track: OnlineTrack,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution = withContext(Dispatchers.IO) {
        val safeSourceId = InputAndUrlSecurity.sanitizePathSegment(track.sourceId)
            ?: throw IllegalArgumentException("Invalid JioSaavn track ID")
        var streams = parsedStreamsCache.get(safeSourceId).orEmpty()
        var resolvedTrack = track

        if (streams.isEmpty()) {
            val encodedId = Uri.encode(safeSourceId)
            val body = runCatching {
                executeWithInstanceFallback("/songs/$encodedId")
            }.getOrElse {
                executeWithInstanceFallback("/songs?ids=$encodedId")
            }
            val parsedObj = extractSongObject(body)
            if (parsedObj != null) {
                parseSongItem(parsedObj)?.let { updated ->
                    resolvedTrack = updated.copy(
                        isFavorite = track.isFavorite,
                        playCount = track.playCount,
                        lastPlayedAt = track.lastPlayedAt,
                        totalListenMs = track.totalListenMs
                    )
                }
                streams = parsedStreamsCache.get(safeSourceId).orEmpty()
            }
        }

        val selected = StreamQualitySelector.selectBestAudioStream(streams, speedTier)
            ?: throw IOException("No playable audio streams returned by JioSaavn for ${track.title}")

        val related = runCatching {
            val sugBody = executeWithInstanceFallback("/songs/${Uri.encode(safeSourceId)}/suggestions?limit=10")
            extractResultsArray(sugBody).mapNotNull { parseSongItem(it.jsonObject) }
        }.getOrDefault(emptyList())

        OnlineStreamResolution(
            track = resolvedTrack,
            streams = streams,
            selectedStream = selected,
            relatedTracks = related,
            resolvedBy = OnlineProviderType.JIOSAAVN
        )
    }

    override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage = withContext(Dispatchers.IO) {
        val rawId = artistIdOrUrl.trimEnd('/').substringAfterLast("/").substringBefore("?")
        val id = InputAndUrlSecurity.sanitizePathSegment(rawId)
            ?: throw IllegalArgumentException("Invalid JioSaavn artist ID")
        val body = executeWithInstanceFallback("/artists/${Uri.encode(id)}")
        val dataObj = extractDataObject(body)
        val topSongs = (dataObj?.get("topSongs") as? JsonArray).orEmpty()
            .mapNotNull { parseSongItem(it.jsonObject) }
        val topAlbums = (dataObj?.get("topAlbums") as? JsonArray).orEmpty()
            .mapNotNull { parseAlbumItem(it.jsonObject) }

        OnlineArtistPage(
            id = id,
            name = decodeHtmlEntities(dataObj?.str("name") ?: "Artist"),
            url = dataObj?.str("url") ?: artistIdOrUrl,
            avatarUrl = pickBestImageUrl(dataObj?.get("image")),
            subscriberCountText = dataObj?.str("followerCount") ?: "",
            description = "",
            tracks = topSongs,
            playlists = topAlbums,
            providerType = OnlineProviderType.JIOSAAVN
        )
    }

    override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage = withContext(Dispatchers.IO) {
        val id = albumIdOrUrl.substringAfterLast("/").substringBefore("?")
        val body = runCatching {
            executeWithInstanceFallback("/albums?id=${Uri.encode(id)}")
        }.getOrElse {
            executeWithInstanceFallback("/playlists?id=${Uri.encode(id)}")
        }
        val dataObj = extractDataObject(body)
        val songs = (dataObj?.get("songs") as? JsonArray).orEmpty()
            .mapNotNull { parseSongItem(it.jsonObject) }

        OnlineAlbumPage(
            id = id,
            title = decodeHtmlEntities(dataObj?.str("name") ?: dataObj?.str("title") ?: "Album"),
            artist = decodeHtmlEntities(
                extractPrimaryArtists(dataObj).ifBlank { "JioSaavn" }
            ),
            url = dataObj?.str("url") ?: albumIdOrUrl,
            thumbnailUrl = pickBestImageUrl(dataObj?.get("image")) ?: songs.firstOrNull()?.thumbnailUrl,
            description = decodeHtmlEntities(dataObj?.str("description").orEmpty()),
            tracks = songs,
            providerType = OnlineProviderType.JIOSAAVN
        )
    }

    private fun getCandidateInstances(): List<String> {
        if (baseUrlsOverride != null) {
            return baseUrlsOverride.mapNotNull {
                InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = true)
            }
        }
        val configured = dynamicBaseUrlsProvider?.invoke().orEmpty()
        val all = (configured + RemoteProvidersConfig.DEFAULT_JIOSAAVN_URLS)
            .mapNotNull { InputAndUrlSecurity.validateRemoteBaseUrl(it, allowLoopback = false) }
            .distinct()
        val now = System.currentTimeMillis()
        val ready = all.filter { (instanceCooldowns[it] ?: 0L) <= now }
        return ready.ifEmpty { all }
    }

    private fun executeWithInstanceFallback(path: String): String {
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
        throw lastErr ?: IOException("All JioSaavn endpoints unavailable")
    }

    private fun extractResultsArray(body: String): JsonArray {
        val root = json.parseToJsonElement(body)
        if (root is JsonArray) return root
        val obj = root.jsonObject
        val data = obj["data"]
        return when (data) {
            is JsonArray -> data
            is JsonObject -> (data["results"] as? JsonArray) ?: JsonArray(emptyList())
            else -> (obj["results"] as? JsonArray) ?: JsonArray(emptyList())
        }
    }

    private fun extractDataObject(body: String): JsonObject? {
        val root = json.parseToJsonElement(body).jsonObject
        return root["data"] as? JsonObject ?: root
    }

    private fun extractSongObject(body: String): JsonObject? {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]
        return when (data) {
            is JsonArray -> data.firstOrNull() as? JsonObject
            is JsonObject -> (data["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: data
            else -> root
        }
    }

    private fun parseSongItem(obj: JsonObject): OnlineTrack? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val title = decodeHtmlEntities(obj.str("name") ?: obj.str("title") ?: return null)
        val artist = decodeHtmlEntities(extractPrimaryArtists(obj).ifBlank { "JioSaavn Artist" })
        val artistId = ((obj["artists"] as? JsonObject)?.get("primary") as? JsonArray)
            ?.firstOrNull()?.let { it as? JsonObject }?.str("id")
        val artistUrl = ((obj["artists"] as? JsonObject)?.get("primary") as? JsonArray)
            ?.firstOrNull()?.let { it as? JsonObject }?.str("url")
        val albumObj = obj["album"] as? JsonObject
        val albumName = decodeHtmlEntities(
            albumObj?.str("name") ?: obj.str("album").takeIf { !it.isNullOrBlank() } ?: "JioSaavn"
        )
        val albumUrl = albumObj?.str("url")
        val durationSec = parseFlexibleLong(obj["duration"])
        val thumb = pickBestImageUrl(obj["image"])
        val sourceUrl = obj.str("url") ?: "https://www.jiosaavn.com/song/$id"
        val streams = parseDownloadUrls(obj["downloadUrl"])
        if (streams.isNotEmpty()) {
            parsedStreamsCache.put(id, streams)
        }

        return OnlineTrack(
            id = OnlineTrack.canonicalId(OnlineProviderType.JIOSAAVN, id),
            sourceId = id,
            providerType = OnlineProviderType.JIOSAAVN,
            providerLabel = OnlineProviderType.JIOSAAVN.displayName,
            sourceUrl = sourceUrl,
            title = title,
            artist = artist,
            artistId = artistId,
            artistUrl = artistUrl,
            album = albumName,
            albumUrl = albumUrl,
            durationMs = if (durationSec > 0L) durationSec * 1000L else 0L,
            thumbnailUrl = thumb,
            viewCount = parseFlexibleLong(obj["playCount"]),
            publishedText = obj.str("year").orEmpty(),
            isLive = false
        )
    }

    private fun parseAlbumItem(obj: JsonObject): OnlineAlbumSummary? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return OnlineAlbumSummary(
            id = id,
            title = decodeHtmlEntities(obj.str("name") ?: obj.str("title") ?: "Album"),
            artist = decodeHtmlEntities(extractPrimaryArtists(obj).ifBlank { "JioSaavn" }),
            url = obj.str("url") ?: id,
            thumbnailUrl = pickBestImageUrl(obj["image"]),
            trackCount = obj.int("songCount") ?: 0,
            providerType = OnlineProviderType.JIOSAAVN
        )
    }

    private fun parseArtistItem(obj: JsonObject): OnlineArtistSummary? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return OnlineArtistSummary(
            id = id,
            name = decodeHtmlEntities(obj.str("name") ?: "Artist"),
            url = obj.str("url") ?: id,
            thumbnailUrl = pickBestImageUrl(obj["image"]),
            subscriberText = obj.str("role").orEmpty(),
            providerType = OnlineProviderType.JIOSAAVN
        )
    }

    private fun parseDownloadUrls(el: JsonElement?): List<OnlineAudioStream> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = InputAndUrlSecurity.validateRemoteHttpsUrl(obj.str("url") ?: obj.str("link"))
                ?: return@mapNotNull null
            val qualityStr = obj.str("quality").orEmpty()
            val kbps = Regex("""(\d+)""").find(qualityStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 160
            val isMp3 = url.contains(".mp3", ignoreCase = true)
            OnlineAudioStream(
                url = url,
                bitrateKbps = kbps.coerceAtLeast(48),
                mimeType = if (isMp3) "audio/mpeg" else "audio/mp4",
                codec = if (isMp3) "mp3" else "mp4a.40.2",
                formatLabel = if (isMp3) "MP3 ${kbps}k" else "MP4 ${kbps}k",
                expiresAtMs = System.currentTimeMillis() + 3 * 60 * 60_000L
            )
        }
    }

    private fun extractPrimaryArtists(obj: JsonObject?): String {
        if (obj == null) return ""
        val artistsObj = obj["artists"] as? JsonObject
        val primaryArr = artistsObj?.get("primary") as? JsonArray
        if (primaryArr != null && primaryArr.isNotEmpty()) {
            val names = primaryArr.mapNotNull { (it as? JsonObject)?.str("name")?.takeIf { n -> n.isNotBlank() } }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return obj.str("primaryArtists").orEmpty()
    }

    private fun pickBestImageUrl(el: JsonElement?): String? {
        val arr = el as? JsonArray ?: return InputAndUrlSecurity.validateRemoteHttpsUrl((el as? JsonPrimitive)?.contentOrNull)
        val objects = arr.mapNotNull { it as? JsonObject }
        val preferred = objects.firstOrNull {
            val q = it.str("quality").orEmpty()
            q.contains("500") || q.contains("high", ignoreCase = true)
        } ?: objects.lastOrNull()
        val raw = preferred?.let { it.str("url") ?: it.str("link") }
        return InputAndUrlSecurity.validateRemoteHttpsUrl(raw)
    }

    private fun parseFlexibleLong(el: JsonElement?): Long {
        val prim = el as? JsonPrimitive ?: return 0L
        return prim.contentOrNull?.trim()?.toLongOrNull() ?: 0L
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    companion object {
        private const val INSTANCE_COOLDOWN_MS = 90_000L
    }
}
