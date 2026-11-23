package app.nocturne.player.data.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import app.nocturne.player.data.audio.TagAndLyricsEngine
import app.nocturne.player.data.db.TrackStatsEntity
import app.nocturne.player.data.db.TrackTagOverrideEntity
import app.nocturne.player.data.model.DuplicateGroup
import app.nocturne.player.data.model.StorageDiagnostics
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaLibraryScanner(private val context: Context) {

    companion object {
        val supportedExtensions = setOf(
            "mp3", "flac", "alac", "wav", "aac", "m4a", "m4b", "ogg", "oga", "opus", "wma", "amr"
        )

        private val extraAudioMimeTypes = setOf(
            "application/ogg",
            "application/x-ogg",
            "application/flac",
            "application/x-flac"
        )

        fun isSupportedAudioFile(
            displayName: String,
            mimeType: String,
            isMusic: Boolean = false
        ): Boolean {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            val cleanMime = mimeType.lowercase()
            return ext in supportedExtensions ||
                cleanMime.startsWith("audio/") ||
                cleanMime in extraAudioMimeTypes ||
                (isMusic && ext.isBlank())
        }
    }

    suspend fun scanAllAudio(
        settings: UserSettings,
        statsMap: Map<String, TrackStatsEntity>,
        overrideMap: Map<String, TrackTagOverrideEntity>,
        trackedUrisForMissingCheck: Set<String>
    ): Pair<List<Track>, StorageDiagnostics> = withContext(Dispatchers.IO) {
        val seenKeys = LinkedHashSet<String>()
        val tracks = mutableListOf<Track>()

        // 1. Query MediaStore across all external storage volumes (internal + SD/USB)
        queryMediaStoreTracks(settings, statsMap, overrideMap, seenKeys, tracks)

        // 2. Discover any audio files on readable device storage not yet indexed by MediaStore
        discoverUnindexedStorageFiles(settings, statsMap, overrideMap, seenKeys, tracks)

        // 3. Query user-added SAF folders
        settings.includedSafFolderUris.forEach { treeUriStr ->
            val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) }.getOrNull()
            if (root != null && root.exists()) {
                scanSafFolderRecursive(
                    dir = root,
                    folderLabel = root.name ?: "Selected Folder",
                    settings = settings,
                    statsMap = statsMap,
                    overrideMap = overrideMap,
                    seenKeys = seenKeys,
                    out = tracks,
                    depth = 0
                )
            }
        }

        // 4. Diagnostics: Duplicates & Missing files
        val duplicateGroups = detectDuplicates(tracks)
        val availableUriStrings = tracks.mapTo(HashSet()) { it.uriString }
        val missingUris = trackedUrisForMissingCheck.filter { uriStr ->
            uriStr !in availableUriStrings && !isUriReadable(uriStr)
        }

        val formatBreakdown = tracks.groupingBy { it.formatLabel }.eachCount()
        val diagnostics = StorageDiagnostics(
            totalTracks = tracks.size,
            totalSizeBytes = tracks.sumOf { it.sizeBytes },
            totalDurationMs = tracks.sumOf { it.durationMs },
            formatBreakdown = formatBreakdown,
            duplicateGroups = duplicateGroups,
            missingTrackUris = missingUris
        )

        tracks to diagnostics
    }

    private fun queryMediaStoreTracks(
        settings: UserSettings,
        statsMap: Map<String, TrackStatsEntity>,
        overrideMap: Map<String, TrackTagOverrideEntity>,
        seenKeys: MutableSet<String>,
        out: MutableList<Track>
    ) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.IS_MUSIC)
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaStore.Audio.Media.IS_PENDING)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.GENRE)
                add(MediaStore.Audio.Media.COMPOSER)
                add(MediaStore.Audio.Media.DISC_NUMBER)
                add(MediaStore.Audio.Media.BITRATE)
            }
        }.toTypedArray()

        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val addedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val modCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                val isPendingCol = if (Build.VERSION.SDK_INT >= 29) cursor.getColumnIndex(MediaStore.Audio.Media.IS_PENDING) else -1
                val albumArtistCol = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
                val genreCol = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
                val composerCol = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndex(MediaStore.Audio.Media.COMPOSER) else -1
                val discCol = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER) else -1
                val bitrateCol = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE) else -1

                while (cursor.moveToNext()) {
                    if (isPendingCol >= 0 && cursor.getInt(isPendingCol) != 0) continue

                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val uriStr = contentUri.toString()
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    val isMusic = isMusicCol >= 0 && cursor.getInt(isMusicCol) != 0
                    val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L

                    if (sizeBytes <= 0L && filePath.isNotBlank() && File(filePath).let { !it.exists() || it.length() <= 0L }) {
                        continue
                    }

                    val effectiveName = displayName.ifBlank { File(filePath).name }
                    if (!isSupportedAudioFile(effectiveName, mimeType, isMusic)) {
                        continue
                    }

                    if (filePath.startsWith("/system/") || filePath.startsWith("/product/") || filePath.startsWith("/vendor/")) {
                        continue
                    }

                    val folderPath = if (filePath.isNotBlank()) File(filePath).parent.orEmpty() else "Device Music"
                    val folderName = if (filePath.isNotBlank()) File(folderPath).name.ifBlank { "Root" } else "Device Music"

                    if (!settings.includeHiddenFolders && isHiddenPath(folderPath, effectiveName)) continue
                    if (isExcludedFolder(folderPath, settings.excludedFolders)) continue

                    val dedupeKey = filePath.takeIf { it.isNotBlank() }?.lowercase() ?: uriStr
                    if (!seenKeys.add(dedupeKey)) continue

                    val rawTitle = if (titleCol >= 0) cursor.getString(titleCol) else null
                    val rawArtist = if (artistCol >= 0) cursor.getString(artistCol) else null
                    val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol) else null
                    val rawAlbumArtist = if (albumArtistCol >= 0) cursor.getString(albumArtistCol) else null
                    val rawGenre = if (genreCol >= 0) cursor.getString(genreCol) else null
                    val rawComposer = if (composerCol >= 0) cursor.getString(composerCol) else null
                    val durationMs = if (durCol >= 0) cursor.getLong(durCol).coerceAtLeast(0L) else 0L
                    val dateAdded = if (addedCol >= 0) cursor.getLong(addedCol).coerceAtLeast(0L) else 0L
                    val dateMod = if (modCol >= 0) cursor.getLong(modCol).coerceAtLeast(0L) else 0L
                    val rawTrackNum = if (trackCol >= 0) cursor.getInt(trackCol) else 0
                    val trackNum = if (rawTrackNum > 1000) rawTrackNum % 1000 else rawTrackNum.coerceAtLeast(0)
                    val discNum = if (discCol >= 0) cursor.getInt(discCol).coerceAtLeast(0) else if (rawTrackNum > 1000) rawTrackNum / 1000 else 0
                    val year = if (yearCol >= 0) cursor.getInt(yearCol).coerceAtLeast(0) else 0
                    val bitrateKbps = if (bitrateCol >= 0) (cursor.getInt(bitrateCol) / 1000).coerceAtLeast(0) else 0

                    val stats = statsMap[uriStr]
                    val override = overrideMap[uriStr]

                    val cleanTitle = override?.title?.takeIf { it.isNotBlank() }
                        ?: rawTitle?.takeIf { it.isNotBlank() }
                        ?: effectiveName.substringBeforeLast('.').ifBlank { "Untitled Track" }
                    val cleanArtist = override?.artist?.takeIf { it.isNotBlank() }
                        ?: rawArtist?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Unknown Artist"
                    val cleanAlbum = override?.album?.takeIf { it.isNotBlank() }
                        ?: rawAlbum?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Unknown Album"
                    val cleanAlbumArtist = override?.albumArtist?.takeIf { it.isNotBlank() }
                        ?: rawAlbumArtist?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: cleanArtist
                    val cleanGenre = override?.genre?.takeIf { it.isNotBlank() }
                        ?: rawGenre?.takeIf { it.isNotBlank() }
                        ?: "Unclassified"
                    val cleanComposer = override?.composer?.takeIf { it.isNotBlank() }
                        ?: rawComposer?.takeIf { it.isNotBlank() }
                        ?: ""

                    out.add(
                        Track(
                            id = id,
                            uriString = uriStr,
                            filePath = filePath,
                            displayName = effectiveName.ifBlank { cleanTitle },
                            title = cleanTitle,
                            artist = cleanArtist,
                            album = cleanAlbum,
                            albumArtist = cleanAlbumArtist,
                            genre = cleanGenre,
                            year = override?.year ?: year,
                            trackNumber = override?.trackNumber ?: trackNum,
                            discNumber = override?.discNumber ?: discNum,
                            composer = cleanComposer,
                            durationMs = durationMs,
                            sizeBytes = sizeBytes,
                            dateAddedSec = dateAdded,
                            dateModifiedSec = dateMod,
                            mimeType = mimeType,
                            formatLabel = TagAndLyricsEngine.deriveFormatLabel(mimeType, effectiveName),
                            bitrateKbps = bitrateKbps,
                            sampleRateHz = 0,
                            channelCount = 0,
                            folderPath = folderPath,
                            folderName = folderName,
                            playCount = stats?.playCount ?: 0,
                            lastPlayedAt = stats?.lastPlayedAt ?: 0L,
                            isFavorite = stats?.isFavorite ?: false,
                            totalListenMs = stats?.totalListenMs ?: 0L,
                            customCoverUri = override?.customCoverUri,
                            embeddedLyrics = override?.lyrics
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun discoverUnindexedStorageFiles(
        settings: UserSettings,
        statsMap: Map<String, TrackStatsEntity>,
        overrideMap: Map<String, TrackTagOverrideEntity>,
        seenKeys: MutableSet<String>,
        out: MutableList<Track>
    ) {
        val roots = collectStorageRoots()
        if (roots.isEmpty()) return

        val pathsToIndex = mutableListOf<String>()
        roots.forEach { root ->
            walkStorageDirectory(
                dir = root,
                settings = settings,
                statsMap = statsMap,
                overrideMap = overrideMap,
                seenKeys = seenKeys,
                out = out,
                pathsToIndex = pathsToIndex,
                depth = 0
            )
        }

        if (pathsToIndex.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    pathsToIndex.toTypedArray(),
                    null,
                    null
                )
            }
        }
    }

    private fun collectStorageRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                storageManager?.storageVolumes?.forEach { volume ->
                    volume.directory?.takeIf { it.exists() && it.canRead() }?.let { roots.add(it) }
                }
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        }.getOrNull()?.takeIf { it.exists() && it.canRead() }?.let { roots.add(it) }
        return roots.toList()
    }

    private fun walkStorageDirectory(
        dir: File,
        settings: UserSettings,
        statsMap: Map<String, TrackStatsEntity>,
        overrideMap: Map<String, TrackTagOverrideEntity>,
        seenKeys: MutableSet<String>,
        out: MutableList<Track>,
        pathsToIndex: MutableList<String>,
        depth: Int
    ) {
        if (depth > 8 || !dir.exists() || !dir.canRead()) return
        val dirPath = dir.absolutePath
        if (!settings.includeHiddenFolders && dir.name.startsWith(".")) return
        if (isExcludedFolder(dirPath, settings.excludedFolders)) return
        // Skip Android app private sandbox directories
        if (dirPath.endsWith("/Android/data") || dirPath.endsWith("/Android/obb")) return

        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (entry in children) {
            if (entry.isDirectory) {
                walkStorageDirectory(
                    dir = entry,
                    settings = settings,
                    statsMap = statsMap,
                    overrideMap = overrideMap,
                    seenKeys = seenKeys,
                    out = out,
                    pathsToIndex = pathsToIndex,
                    depth = depth + 1
                )
            } else if (entry.isFile && entry.length() > 0L) {
                val name = entry.name
                if (!settings.includeHiddenFolders && name.startsWith(".")) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in supportedExtensions) continue

                val absPath = entry.absolutePath
                val dedupeKey = absPath.lowercase()
                if (!seenKeys.add(dedupeKey)) continue

                val fileUri = Uri.fromFile(entry)
                val uriStr = fileUri.toString()
                if (!seenKeys.add(uriStr)) continue

                pathsToIndex.add(absPath)

                val extTags = TagAndLyricsEngine.readExtendedTags(context, fileUri, "", name)
                val stats = statsMap[uriStr]
                val override = overrideMap[uriStr]
                val folderPath = entry.parent.orEmpty()
                val folderName = entry.parentFile?.name?.ifBlank { "Root" } ?: "Device"

                val title = override?.title?.takeIf { it.isNotBlank() }
                    ?: extTags.title
                    ?: name.substringBeforeLast('.')
                val artist = override?.artist?.takeIf { it.isNotBlank() }
                    ?: extTags.artist ?: "Unknown Artist"
                val album = override?.album?.takeIf { it.isNotBlank() }
                    ?: extTags.album ?: "Unknown Album"
                val albumArtist = override?.albumArtist?.takeIf { it.isNotBlank() }
                    ?: extTags.albumArtist ?: artist

                out.add(
                    Track(
                        id = absPath.hashCode().toLong(),
                        uriString = uriStr,
                        filePath = absPath,
                        displayName = name,
                        title = title,
                        artist = artist,
                        album = album,
                        albumArtist = albumArtist,
                        genre = override?.genre?.takeIf { it.isNotBlank() } ?: extTags.genre ?: "Unclassified",
                        year = override?.year ?: extTags.year,
                        trackNumber = override?.trackNumber ?: extTags.trackNumber,
                        discNumber = override?.discNumber ?: extTags.discNumber,
                        composer = override?.composer ?: extTags.composer.orEmpty(),
                        durationMs = extTags.durationMs,
                        sizeBytes = entry.length(),
                        dateAddedSec = entry.lastModified() / 1000L,
                        dateModifiedSec = entry.lastModified() / 1000L,
                        mimeType = "",
                        formatLabel = extTags.formatLabel,
                        bitrateKbps = extTags.bitrateKbps,
                        sampleRateHz = extTags.sampleRateHz,
                        channelCount = extTags.channelCount,
                        folderPath = folderPath,
                        folderName = folderName,
                        playCount = stats?.playCount ?: 0,
                        lastPlayedAt = stats?.lastPlayedAt ?: 0L,
                        isFavorite = stats?.isFavorite ?: false,
                        totalListenMs = stats?.totalListenMs ?: 0L,
                        replayGainTrackDb = extTags.replayGainTrackDb,
                        replayGainAlbumDb = extTags.replayGainAlbumDb,
                        customCoverUri = override?.customCoverUri,
                        embeddedLyrics = override?.lyrics ?: extTags.embeddedLyrics
                    )
                )
            }
        }
    }

    private fun scanSafFolderRecursive(
        dir: DocumentFile,
        folderLabel: String,
        settings: UserSettings,
        statsMap: Map<String, TrackStatsEntity>,
        overrideMap: Map<String, TrackTagOverrideEntity>,
        seenKeys: MutableSet<String>,
        out: MutableList<Track>,
        depth: Int
    ) {
        if (depth > 6) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        children.forEach { file ->
            val name = file.name ?: return@forEach
            if (!settings.includeHiddenFolders && name.startsWith(".")) return@forEach
            if (file.isDirectory) {
                val subPath = "$folderLabel/$name"
                if (!isExcludedFolder(subPath, settings.excludedFolders)) {
                    scanSafFolderRecursive(file, subPath, settings, statsMap, overrideMap, seenKeys, out, depth + 1)
                }
            } else if (file.isFile) {
                val mime = file.type.orEmpty()
                if (!isSupportedAudioFile(name, mime)) return@forEach
                val uriStr = file.uri.toString()
                if (!seenKeys.add(uriStr)) return@forEach

                val extTags = TagAndLyricsEngine.readExtendedTags(context, file.uri, mime, name)
                val stats = statsMap[uriStr]
                val override = overrideMap[uriStr]
                val title = override?.title?.takeIf { it.isNotBlank() }
                    ?: extTags.title
                    ?: name.substringBeforeLast('.')
                val artist = override?.artist?.takeIf { it.isNotBlank() }
                    ?: extTags.artist ?: "Unknown Artist"
                val album = override?.album?.takeIf { it.isNotBlank() }
                    ?: extTags.album ?: "Unknown Album"
                val albumArtist = override?.albumArtist?.takeIf { it.isNotBlank() }
                    ?: extTags.albumArtist ?: artist

                out.add(
                    Track(
                        id = uriStr.hashCode().toLong(),
                        uriString = uriStr,
                        filePath = "",
                        displayName = name,
                        title = title,
                        artist = artist,
                        album = album,
                        albumArtist = albumArtist,
                        genre = override?.genre?.takeIf { it.isNotBlank() } ?: extTags.genre ?: "Unclassified",
                        year = override?.year ?: extTags.year,
                        trackNumber = override?.trackNumber ?: extTags.trackNumber,
                        discNumber = override?.discNumber ?: extTags.discNumber,
                        composer = override?.composer ?: extTags.composer.orEmpty(),
                        durationMs = extTags.durationMs,
                        sizeBytes = file.length(),
                        dateAddedSec = file.lastModified() / 1000L,
                        dateModifiedSec = file.lastModified() / 1000L,
                        mimeType = mime,
                        formatLabel = extTags.formatLabel,
                        bitrateKbps = extTags.bitrateKbps,
                        sampleRateHz = extTags.sampleRateHz,
                        channelCount = extTags.channelCount,
                        folderPath = "saf://$folderLabel",
                        folderName = folderLabel.substringAfterLast('/'),
                        playCount = stats?.playCount ?: 0,
                        lastPlayedAt = stats?.lastPlayedAt ?: 0L,
                        isFavorite = stats?.isFavorite ?: false,
                        totalListenMs = stats?.totalListenMs ?: 0L,
                        replayGainTrackDb = extTags.replayGainTrackDb,
                        replayGainAlbumDb = extTags.replayGainAlbumDb,
                        customCoverUri = override?.customCoverUri,
                        embeddedLyrics = override?.lyrics ?: extTags.embeddedLyrics
                    )
                )
            }
        }
    }

    private fun isHiddenPath(folderPath: String, displayName: String): Boolean {
        if (displayName.startsWith(".")) return true
        return folderPath.split('/').any { it.startsWith(".") }
    }

    private fun isExcludedFolder(folderPath: String, excluded: List<String>): Boolean {
        if (folderPath.isBlank() || excluded.isEmpty()) return false
        return excluded.any { ex ->
            folderPath.equals(ex, ignoreCase = true) || folderPath.startsWith("$ex/", ignoreCase = true)
        }
    }

    private fun detectDuplicates(tracks: List<Track>): List<DuplicateGroup> {
        return tracks.groupBy { track ->
            val durBucket = (track.durationMs / 2000L)
            "${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}|$durBucket"
        }.values
            .filter { it.size > 1 }
            .map { group ->
                val first = group.first()
                DuplicateGroup(
                    keyLabel = "${first.title} — ${first.artist}",
                    tracks = group
                )
            }
    }

    private fun isUriReadable(uriStr: String): Boolean {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
