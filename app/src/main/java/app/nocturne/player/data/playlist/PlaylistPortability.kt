package app.nocturne.player.data.playlist

import android.content.Context
import android.net.Uri
import app.nocturne.player.data.model.AriaBackupBundle
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object PlaylistPortability {
    private const val MAX_IMPORT_BYTES = 5L * 1024L * 1024L
    private const val MAX_M3U_ENTRIES = 5_000

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportM3u8(playlistName: String, tracks: List<Track>): String {
        val safePlaylistName = InputAndUrlSecurity.sanitizeM3uField(playlistName, 80).ifBlank { "Playlist" }
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#PLAYLIST:$safePlaylistName")
        tracks.forEach { track ->
            val seconds = (track.durationMs / 1000L).coerceAtLeast(0L)
            val safeArtist = InputAndUrlSecurity.sanitizeM3uField(track.artist, 120)
            val safeTitle = InputAndUrlSecurity.sanitizeM3uField(track.title, 120)
            val safeLocation = InputAndUrlSecurity.sanitizeM3uField(track.filePath.ifBlank { track.uriString }, 512)
            if (safeLocation.isNotBlank()) {
                sb.appendLine("#EXTINF:$seconds,$safeArtist - $safeTitle")
                sb.appendLine(safeLocation)
            }
        }
        return sb.toString()
    }

    fun parseM3u8(content: String, libraryTracks: List<Track>): List<String> {
        val byPath = libraryTracks.filter { it.filePath.isNotBlank() }.associateBy { it.filePath }
        val byUri = libraryTracks.associateBy { it.uriString }
        val byName = libraryTracks.associateBy { it.displayName.lowercase() }

        val resolved = mutableListOf<String>()
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.length <= 1024 }
            .take(MAX_M3U_ENTRIES)
            .forEach { line ->
                val direct = byUri[line] ?: byPath[line] ?: byName[line.substringAfterLast('/').lowercase()]
                if (direct != null) {
                    resolved.add(direct.uriString)
                }
            }
        return resolved.distinct()
    }

    fun writeTextToUri(context: Context, uri: Uri, content: String): Boolean {
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } != null
        }.getOrDefault(false)
    }

    fun readTextFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_IMPORT_BYTES) {
                        return@runCatching null
                    }
                    out.write(buf, 0, read)
                }
                String(out.toByteArray(), StandardCharsets.UTF_8)
            }
        }.getOrNull()
    }

    fun encodeBackup(bundle: AriaBackupBundle): String = json.encodeToString(bundle)

    fun decodeBackup(raw: String): AriaBackupBundle? = runCatching {
        json.decodeFromString<AriaBackupBundle>(raw)
    }.getOrNull()
}
