package app.nocturne.player.data.downloads

import android.content.Context
import android.net.Uri
import app.nocturne.player.data.audio.LyricsRepository
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.db.DownloadedPlaylistEntity
import app.nocturne.player.data.db.DownloadedPlaylistEntryEntity
import app.nocturne.player.data.db.DownloadedTrackEntity
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.online.OnlineHttpClient
import app.nocturne.player.data.online.OnlineProviderManager
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class DownloadedPlaylistWithTracks(
    val id: String,
    val title: String,
    val subtitle: String,
    val localCoverUri: String?,
    val providerType: OnlineProviderType,
    val providerId: String,
    val tracks: List<DownloadedTrackEntity>,
    val createdAt: Long
)

class OfflineDownloadManager(
    context: Context,
    private val db: AriaDatabase,
    private val providerManager: OnlineProviderManager,
    private val lyricsRepository: LyricsRepository,
    private val httpClient: OkHttpClient = OnlineHttpClient.client
) {
    private val appContext = context.applicationContext
    private val dao = db.offlineDownloadDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadSemaphore = Semaphore(2)
    private val activeTrackJobs = ConcurrentHashMap<String, Boolean>()

    private val rootDir = File(appContext.filesDir, "downloads").apply { mkdirs() }
    private val audioDir = File(rootDir, "audio").apply { mkdirs() }
    private val artDir = File(rootDir, "art").apply { mkdirs() }
    private val lyricsDir = File(rootDir, "lyrics").apply { mkdirs() }

    val completedDownloads: Flow<List<DownloadedTrackEntity>> = dao.observeCompletedTracks()
    val allDownloadStates: Flow<Map<String, DownloadedTrackEntity>> =
        dao.observeAllTracks().map { list -> list.associateBy { it.trackId } }

    val totalDownloadBytes: Flow<Long> = dao.observeCompletedTracks().map { list ->
        list.sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
    }

    val downloadedPlaylists: Flow<List<DownloadedPlaylistWithTracks>> = combine(
        dao.observeDownloadedPlaylists(),
        dao.observeAllPlaylistEntries(),
        dao.observeCompletedTracks()
    ) { playlists, entries, completed ->
        val byId = completed.associateBy { it.trackId }
        val entriesByPlaylist = entries.groupBy { it.playlistId }
        playlists.map { pl ->
            val tracks = entriesByPlaylist[pl.id]
                .orEmpty()
                .sortedBy { it.position }
                .mapNotNull { byId[it.trackId] }
            val coverUri = pl.localCoverPath?.let { path ->
                val f = File(path)
                if (f.exists() && f.length() > 0L) Uri.fromFile(f).toString() else null
            } ?: tracks.firstOrNull { !it.localArtworkPath.isNullOrBlank() }?.let { first ->
                first.localArtworkPath?.let { Uri.fromFile(File(it)).toString() }
            } ?: pl.coverUrl
            DownloadedPlaylistWithTracks(
                id = pl.id,
                title = pl.title,
                subtitle = pl.subtitle,
                localCoverUri = coverUri,
                providerType = runCatching { OnlineProviderType.valueOf(pl.providerType) }
                    .getOrDefault(OnlineProviderType.JIOSAAVN),
                providerId = pl.providerId,
                tracks = tracks,
                createdAt = pl.createdAt
            )
        }
    }

    suspend fun getCompletedDownload(trackId: String): DownloadedTrackEntity? = withContext(Dispatchers.IO) {
        val entity = dao.getCompletedTrackOnce(trackId) ?: return@withContext null
        val audioFile = File(entity.localAudioPath)
        if (audioFile.exists() && audioFile.length() > 0L) {
            entity
        } else {
            dao.deleteTrack(trackId)
            null
        }
    }

    suspend fun isDownloaded(trackId: String): Boolean = getCompletedDownload(trackId) != null

    suspend fun getLocalLyricsRaw(trackId: String): String? = withContext(Dispatchers.IO) {
        val entity = dao.getCompletedTrackOnce(trackId) ?: return@withContext null
        val path = entity.localLyricsPath?.takeIf { it.isNotBlank() } ?: return@withContext null
        val file = File(path)
        if (file.exists() && file.canRead()) {
            runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        } else null
    }

    fun downloadTrack(track: OnlineTrack) {
        if (activeTrackJobs.putIfAbsent(track.id, true) != null) return
        scope.launch {
            try {
                downloadTrackInternal(track)
            } finally {
                activeTrackJobs.remove(track.id)
            }
        }
    }

    fun downloadPlaylist(
        id: String,
        title: String,
        subtitle: String,
        coverUrl: String?,
        providerType: OnlineProviderType,
        providerId: String,
        tracks: List<OnlineTrack>
    ) {
        if (tracks.isEmpty()) return
        scope.launch {
            val cleanId = "pl_${sha256Key(id)}"
            val localCover = coverUrl?.let { url ->
                downloadArtworkFile(url, "pl_${sha256Key(id)}")
            }
            val playlistEntity = DownloadedPlaylistEntity(
                id = cleanId,
                title = InputAndUrlSecurity.sanitizeSingleLineText(title, 120).ifBlank { "Offline Playlist" },
                subtitle = InputAndUrlSecurity.sanitizeSingleLineText(subtitle, 120),
                coverUrl = coverUrl,
                localCoverPath = localCover,
                providerType = providerType.name,
                providerId = providerId,
                trackCount = tracks.size,
                createdAt = System.currentTimeMillis()
            )
            dao.replacePlaylist(playlistEntity, tracks.map { it.id })
            tracks.forEach { t ->
                if (getCompletedDownload(t.id) == null) {
                    downloadTrack(t)
                }
            }
        }
    }

    suspend fun downloadTrackInternal(track: OnlineTrack): Boolean = withContext(Dispatchers.IO) {
        downloadSemaphore.withPermit {
            val existing = getCompletedDownload(track.id)
            if (existing != null) return@withPermit true

            val safeKey = sha256Key(track.id)
            dao.upsertTrack(
                DownloadedTrackEntity(
                    trackId = track.id,
                    sourceId = track.sourceId,
                    providerType = track.providerType.name,
                    providerId = track.effectiveProviderId(),
                    providerLabel = track.providerLabel,
                    sourceUrl = track.sourceUrl,
                    title = track.title,
                    artist = track.artist,
                    artistId = track.artistId,
                    artistUrl = track.artistUrl,
                    album = track.album,
                    albumUrl = track.albumUrl,
                    durationMs = track.durationMs,
                    thumbnailUrl = track.thumbnailUrl,
                    status = DownloadedTrackEntity.STATUS_DOWNLOADING,
                    progressPercent = 2
                )
            )

            try {
                // 1. Resolve playable stream at active Wi-Fi / Mobile quality setting
                val resolution = providerManager.resolveStream(track, forceRefresh = true)
                val stream = resolution.selectedStream
                val ext = extensionForStream(stream.mimeType, stream.codec, stream.formatLabel)
                val targetAudio = File(audioDir, "$safeKey.$ext")
                val tempAudio = File(audioDir, "$safeKey.$ext.part")
                if (tempAudio.exists()) tempAudio.delete()

                val request = Request.Builder()
                    .url(stream.url)
                    .apply {
                        val mergedHeaders = OnlineHttpClient.defaultMediaHeaders(stream.url) + stream.headers
                        mergedHeaders.forEach { (k, v) -> header(k, v) }
                    }
                    .get()
                    .build()

                var bytesWritten = 0L
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Download failed with HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty audio response body")
                    val contentLength = body.contentLength()
                    val buf = ByteArray(16 * 1024)
                    var lastReportedPct = 5
                    body.byteStream().use { input ->
                        tempAudio.outputStream().use { output ->
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                bytesWritten += read
                                if (contentLength > 0L) {
                                    val pct = ((bytesWritten * 85L) / contentLength).toInt().coerceIn(5, 88)
                                    if (pct - lastReportedPct >= 5) {
                                        lastReportedPct = pct
                                        dao.updateProgress(track.id, pct)
                                    }
                                }
                            }
                        }
                    }
                }

                if (bytesWritten <= 0L || !tempAudio.exists()) {
                    throw IOException("Downloaded audio file was empty")
                }
                if (targetAudio.exists()) targetAudio.delete()
                if (!tempAudio.renameTo(targetAudio)) {
                    tempAudio.copyTo(targetAudio, overwrite = true)
                    tempAudio.delete()
                }
                dao.updateProgress(track.id, 90)

                // 2. Save artwork locally
                val artUrl = resolution.track.thumbnailUrl ?: track.thumbnailUrl
                val localArtPath = if (!artUrl.isNullOrBlank()) {
                    downloadArtworkFile(artUrl, safeKey)
                } else null
                dao.updateProgress(track.id, 95)

                // 3. Save lyrics locally (to file + lyrics_cache Room table via LyricsRepository)
                val uiTrack = resolution.track.toUiTrack(stream.formatLabel)
                val lyrics = runCatching {
                    lyricsRepository.loadLyricsForTrack(uiTrack, forceRemoteRefresh = false)
                }.getOrNull()
                val localLyricsPath = if (lyrics != null && lyrics.hasContent && !lyrics.isInstrumental) {
                    val rawLrc = lyrics.rawText.takeIf { it.isNotBlank() }
                        ?: lyrics.syncedLines.joinToString("\n") { line ->
                            val min = (line.timeMs / 60_000L)
                            val sec = (line.timeMs % 60_000L) / 1000L
                            val cs = (line.timeMs % 1000L) / 10L
                            "[%02d:%02d.%02d]%s".format(min, sec, cs, line.text)
                        }
                    if (rawLrc.isNotBlank()) {
                        val lrcFile = File(lyricsDir, "$safeKey.lrc")
                        runCatching {
                            lrcFile.writeText(rawLrc)
                            lrcFile.absolutePath
                        }.getOrNull()
                    } else null
                } else null

                dao.upsertTrack(
                    DownloadedTrackEntity(
                        trackId = track.id,
                        sourceId = track.sourceId,
                        providerType = resolution.resolvedBy.name,
                        providerId = track.effectiveProviderId(),
                        providerLabel = resolution.track.providerLabel.ifBlank { track.providerLabel },
                        sourceUrl = resolution.track.sourceUrl.ifBlank { track.sourceUrl },
                        title = resolution.track.title.ifBlank { track.title },
                        artist = resolution.track.artist.ifBlank { track.artist },
                        artistId = resolution.track.artistId ?: track.artistId,
                        artistUrl = resolution.track.artistUrl ?: track.artistUrl,
                        album = resolution.track.album.ifBlank { track.album },
                        albumUrl = resolution.track.albumUrl ?: track.albumUrl,
                        durationMs = resolution.track.durationMs.takeIf { it > 0L } ?: track.durationMs,
                        thumbnailUrl = artUrl,
                        localAudioPath = targetAudio.absolutePath,
                        localArtworkPath = localArtPath,
                        localLyricsPath = localLyricsPath,
                        mimeType = stream.mimeType.ifBlank { "audio/mp4" },
                        formatLabel = stream.formatLabel.ifBlank { "M4A" },
                        bitrateKbps = stream.bitrateKbps,
                        fileSizeBytes = targetAudio.length(),
                        status = DownloadedTrackEntity.STATUS_COMPLETED,
                        progressPercent = 100,
                        errorMessage = null,
                        downloadedAt = System.currentTimeMillis()
                    )
                )
                true
            } catch (t: Throwable) {
                dao.upsertTrack(
                    DownloadedTrackEntity(
                        trackId = track.id,
                        sourceId = track.sourceId,
                        providerType = track.providerType.name,
                        providerId = track.effectiveProviderId(),
                        providerLabel = track.providerLabel,
                        sourceUrl = track.sourceUrl,
                        title = track.title,
                        artist = track.artist,
                        artistId = track.artistId,
                        artistUrl = track.artistUrl,
                        album = track.album,
                        albumUrl = track.albumUrl,
                        durationMs = track.durationMs,
                        thumbnailUrl = track.thumbnailUrl,
                        status = DownloadedTrackEntity.STATUS_FAILED,
                        progressPercent = 0,
                        errorMessage = InputAndUrlSecurity.sanitizeErrorForDiagnostics(t.message) ?: "Download failed"
                    )
                )
                false
            }
        }
    }

    suspend fun deleteTrack(trackId: String) = withContext(Dispatchers.IO) {
        val existing = dao.getByTrackId(trackId)
        if (existing != null) {
            if (existing.localAudioPath.isNotBlank()) {
                runCatching { File(existing.localAudioPath).delete() }
            }
            if (!existing.localArtworkPath.isNullOrBlank()) {
                runCatching { File(existing.localArtworkPath).delete() }
            }
            if (!existing.localLyricsPath.isNullOrBlank()) {
                runCatching { File(existing.localLyricsPath).delete() }
            }
        }
        dao.deleteTrack(trackId)
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        dao.deletePlaylist(playlistId)
    }

    private fun downloadArtworkFile(url: String, key: String): String? {
        val validUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(url) ?: return null
        return runCatching {
            val req = Request.Builder().url(validUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                val ct = body.contentType()?.subtype?.lowercase().orEmpty()
                val ext = when {
                    "png" in ct -> "png"
                    "webp" in ct -> "webp"
                    else -> "jpg"
                }
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) return@use null
                val target = File(artDir, "$key.$ext")
                target.writeBytes(bytes)
                target.absolutePath
            }
        }.getOrNull()
    }

    private fun extensionForStream(mimeType: String, codec: String, formatLabel: String): String {
        val m = mimeType.lowercase()
        val c = codec.lowercase()
        val f = formatLabel.lowercase()
        return when {
            "webm" in m || "opus" in c -> "webm"
            "mpeg" in m || "mp3" in c || "mp3" in f -> "mp3"
            "flac" in m || "flac" in c -> "flac"
            "ogg" in m || "vorbis" in c -> "ogg"
            else -> "m4a"
        }
    }

    private fun sha256Key(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun entityToOnlineTrack(entity: DownloadedTrackEntity): OnlineTrack {
            val pType = runCatching { OnlineProviderType.valueOf(entity.providerType) }
                .getOrDefault(OnlineProviderType.JIOSAAVN)
            val artUri = entity.localArtworkPath?.let { path ->
                val f = File(path)
                if (f.exists() && f.length() > 0L) Uri.fromFile(f).toString() else null
            } ?: entity.thumbnailUrl
            return OnlineTrack(
                id = entity.trackId,
                sourceId = entity.sourceId,
                providerType = pType,
                providerLabel = entity.providerLabel,
                sourceUrl = entity.sourceUrl,
                title = entity.title,
                artist = entity.artist,
                artistId = entity.artistId,
                artistUrl = entity.artistUrl,
                album = entity.album,
                albumUrl = entity.albumUrl,
                durationMs = entity.durationMs,
                thumbnailUrl = artUri,
                providerId = entity.providerId
            )
        }

        fun entityToOfflineTrack(entity: DownloadedTrackEntity): Track {
            val audioUri = Uri.fromFile(File(entity.localAudioPath)).toString()
            val artUri = entity.localArtworkPath?.let { path ->
                val f = File(path)
                if (f.exists() && f.length() > 0L) Uri.fromFile(f).toString() else null
            } ?: entity.thumbnailUrl
            val lyricsRaw = entity.localLyricsPath?.let { path ->
                val f = File(path)
                if (f.exists() && f.canRead()) runCatching { f.readText() }.getOrNull() else null
            }
            return Track(
                id = entity.trackId.hashCode().toLong(),
                uriString = audioUri,
                filePath = entity.localAudioPath,
                displayName = File(entity.localAudioPath).name,
                title = entity.title,
                artist = entity.artist,
                album = entity.album,
                albumArtist = entity.artist,
                genre = entity.providerLabel,
                year = 0,
                trackNumber = 0,
                discNumber = 0,
                composer = "",
                durationMs = entity.durationMs,
                sizeBytes = entity.fileSizeBytes,
                dateAddedSec = entity.downloadedAt / 1000L,
                dateModifiedSec = entity.downloadedAt / 1000L,
                mimeType = entity.mimeType,
                formatLabel = "OFFLINE · ${entity.formatLabel}".trimEnd(' ', '·'),
                bitrateKbps = entity.bitrateKbps,
                sampleRateHz = 44100,
                channelCount = 2,
                folderPath = File(entity.localAudioPath).parent.orEmpty(),
                folderName = "Downloads",
                customCoverUri = artUri,
                embeddedLyrics = lyricsRaw,
                isOnline = false,
                onlineTrackId = entity.trackId,
                onlineSourceId = entity.sourceId,
                onlineProvider = entity.providerLabel,
                artworkUrl = artUri
            )
        }
    }
}
