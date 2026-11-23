package app.nocturne.player.data.audio

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import app.nocturne.player.data.model.LrcLine
import app.nocturne.player.data.model.LrcWord
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.security.InputAndUrlSecurity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

data class ExtendedAudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val composer: String? = null,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val durationMs: Long = 0L,
    val formatLabel: String = "",
    val embeddedLyrics: String? = null,
    val replayGainTrackDb: Float? = null,
    val replayGainAlbumDb: Float? = null
)

data class EditableTagPayload(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val composer: String,
    val lyrics: String,
    val artworkBytes: ByteArray? = null
)

sealed interface TagWriteResult {
    data object SavedToFileAndIndex : TagWriteResult
    data object SavedToIndexOnly : TagWriteResult
    data class RequiresSystemWritePermission(val pendingIntent: PendingIntent) : TagWriteResult
    data class Failed(val reason: String) : TagWriteResult
}

object TagAndLyricsEngine {

    private val lrcTimeRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val enhancedWordTimeRegex = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val lrcOffsetRegex = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val replayGainRegex = Regex("""([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)

    private fun parseTimestampMs(minStr: String, secStr: String, fracStr: String, offsetMs: Long): Long {
        val min = minStr.toLongOrNull() ?: 0L
        val sec = secStr.toLongOrNull() ?: 0L
        val millis = when (fracStr.length) {
            0 -> 0L
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            else -> fracStr.take(3).toLongOrNull() ?: 0L
        }
        return ((min * 60L + sec) * 1000L + millis - offsetMs).coerceAtLeast(0L)
    }

    fun parseLrc(raw: String): List<LrcLine> {
        if (raw.isBlank()) return emptyList()
        var offsetMs = 0L
        lrcOffsetRegex.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
            offsetMs = it
        }
        val rawLines = mutableListOf<LrcLine>()
        raw.lineSequence().forEach { line ->
            val matches = lrcTimeRegex.findAll(line).toList()
            if (matches.isNotEmpty()) {
                val bodyWithoutLineTags = line.replace(lrcTimeRegex, "").trim()
                val wordMatches = enhancedWordTimeRegex.findAll(bodyWithoutLineTags).toList()

                matches.forEach { match ->
                    val totalMs = parseTimestampMs(
                        match.groupValues[1],
                        match.groupValues[2],
                        match.groupValues.getOrNull(3).orEmpty(),
                        offsetMs
                    )
                    if (wordMatches.isNotEmpty() && matches.size == 1) {
                        val parsedWords = parseEnhancedWords(bodyWithoutLineTags, wordMatches, totalMs, offsetMs)
                        val cleanText = parsedWords.joinToString(" ") { it.text }.trim().ifEmpty { "♪" }
                        rawLines.add(
                            LrcLine(
                                timeMs = totalMs,
                                text = cleanText,
                                words = parsedWords,
                                isExplicitWordSync = parsedWords.isNotEmpty()
                            )
                        )
                    } else {
                        val cleanText = bodyWithoutLineTags
                            .replace(enhancedWordTimeRegex, "")
                            .replace(Regex("""\s+"""), " ")
                            .trim()
                        rawLines.add(LrcLine(timeMs = totalMs, text = cleanText.ifEmpty { "♪" }))
                    }
                }
            }
        }

        val sorted = rawLines.sortedBy { it.timeMs }
        return sorted.mapIndexed { idx, line ->
            val nextStart = sorted.getOrNull(idx + 1)?.timeMs
            val lineEnd = if (nextStart != null) {
                nextStart.coerceAtLeast(line.timeMs + 400L)
            } else {
                line.timeMs + 4000L
            }
            val finalWords = if (line.words.isNotEmpty()) {
                line.words.mapIndexed { wIdx, word ->
                    if (wIdx == line.words.lastIndex) {
                        val capEnd = minOf(lineEnd, word.startTimeMs + 2500L).coerceAtLeast(word.startTimeMs + 300L)
                        word.copy(endTimeMs = capEnd)
                    } else word
                }
            } else {
                interpolateWords(line.timeMs, lineEnd, line.text)
            }
            line.copy(endTimeMs = lineEnd, words = finalWords)
        }
    }

    private fun parseEnhancedWords(
        body: String,
        wordMatches: List<MatchResult>,
        lineStartMs: Long,
        offsetMs: Long
    ): List<LrcWord> {
        val rawEntries = mutableListOf<Pair<Long, String>>()

        val prefix = body.substring(0, wordMatches.first().range.first).trim()
        if (prefix.isNotBlank()) {
            prefix.split(Regex("""\s+""")).filter { it.isNotBlank() }.forEach { token ->
                rawEntries.add(lineStartMs to token)
            }
        }

        wordMatches.forEachIndexed { idx, match ->
            val ts = parseTimestampMs(
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues.getOrNull(3).orEmpty(),
                offsetMs
            )
            val segStart = match.range.last + 1
            val segEnd = if (idx < wordMatches.lastIndex) wordMatches[idx + 1].range.first else body.length
            val segment = if (segStart < segEnd) body.substring(segStart, segEnd).trim() else ""
            if (segment.isNotBlank()) {
                val subTokens = segment.split(Regex("""\s+""")).filter { it.isNotBlank() }
                if (subTokens.size == 1) {
                    rawEntries.add(ts to subTokens.first())
                } else {
                    // Multiple space-separated words under one word tag: split proportionally until next tag
                    val nextTs = if (idx < wordMatches.lastIndex) {
                        parseTimestampMs(
                            wordMatches[idx + 1].groupValues[1],
                            wordMatches[idx + 1].groupValues[2],
                            wordMatches[idx + 1].groupValues.getOrNull(3).orEmpty(),
                            offsetMs
                        ).coerceAtLeast(ts + 250L)
                    } else {
                        ts + subTokens.size * 350L
                    }
                    val step = ((nextTs - ts) / subTokens.size.toLong()).coerceAtLeast(80L)
                    subTokens.forEachIndexed { subIdx, token ->
                        rawEntries.add((ts + subIdx * step) to token)
                    }
                }
            }
        }

        return rawEntries.mapIndexed { idx, (start, token) ->
            val end = if (idx < rawEntries.lastIndex) {
                rawEntries[idx + 1].first.coerceAtLeast(start + 80L)
            } else {
                start + 600L
            }
            LrcWord(startTimeMs = start, endTimeMs = end, text = token)
        }
    }

    fun interpolateWords(lineStartMs: Long, lineEndMs: Long, text: String): List<LrcWord> {
        val clean = text.trim()
        if (clean.isBlank() || clean == "♪") return emptyList()
        val tokens = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val activeWindowMs = (lineEndMs - lineStartMs).coerceIn(400L, 8000L)
        val totalWeight = tokens.sumOf { it.length + 1 }.coerceAtLeast(tokens.size)
        var cursor = lineStartMs

        return tokens.mapIndexed { idx, token ->
            val weight = token.length + 1
            val shareMs = if (idx == tokens.lastIndex) {
                (lineStartMs + activeWindowMs - cursor).coerceAtLeast(100L)
            } else {
                ((activeWindowMs * weight) / totalWeight).coerceAtLeast(80L)
            }
            val start = cursor
            val end = (start + shareMs).coerceAtMost(lineStartMs + activeWindowMs)
            cursor = end
            LrcWord(startTimeMs = start, endTimeMs = end.coerceAtLeast(start + 60L), text = token)
        }
    }

    fun loadLyricsForTrack(
        context: Context,
        uri: Uri,
        filePath: String,
        manualOrOverrideLyrics: String?
    ): LyricsData {
        // 1. Check user-edited or DB override first if non-blank
        if (!manualOrOverrideLyrics.isNullOrBlank()) {
            val synced = parseLrc(manualOrOverrideLyrics)
            return LyricsData(
                syncedLines = synced,
                rawText = manualOrOverrideLyrics,
                source = LyricsSource.MANUAL
            )
        }

        // 2. Check sidecar .lrc file on disk
        if (filePath.isNotBlank()) {
            val audioFile = File(filePath)
            val baseName = audioFile.nameWithoutExtension
            val parent = audioFile.parentFile
            if (parent != null && parent.exists()) {
                val lrcFile = File(parent, "$baseName.lrc")
                if (lrcFile.exists() && lrcFile.canRead()) {
                    val text = runCatching { lrcFile.readText(StandardCharsets.UTF_8) }.getOrNull()
                    if (!text.isNullOrBlank()) {
                        val synced = parseLrc(text)
                        return LyricsData(syncedLines = synced, rawText = text, source = LyricsSource.SIDECAR_LRC)
                    }
                }
            }
        }

        // 3. Check embedded lyrics inside file headers (ID3v2 USLT / FLAC Vorbis)
        val embedded = readEmbeddedLyricsAndReplayGain(context, uri).first
        if (!embedded.isNullOrBlank()) {
            val synced = parseLrc(embedded)
            return LyricsData(syncedLines = synced, rawText = embedded, source = LyricsSource.EMBEDDED)
        }

        return LyricsData(source = LyricsSource.NONE)
    }

    fun readExtendedTags(
        context: Context,
        uri: Uri,
        mimeType: String,
        displayName: String
    ): ExtendedAudioTags {
        if (!InputAndUrlSecurity.isSafePlayableLocalUri(uri)) {
            return ExtendedAudioTags(formatLabel = deriveFormatLabel(mimeType, displayName))
        }
        val retriever = MediaMetadataRetriever()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var year = 0
        var trackNum = 0
        var discNum = 0
        var composer: String? = null
        var bitrateKbps = 0
        var sampleRateHz = 0
        var durationMs = 0L

        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.takeIf { it.isNotBlank() }
            genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() }
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0
            trackNum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            discNum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.takeIf { it.isNotBlank() }
            bitrateKbps = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            if (Build.VERSION.SDK_INT >= 31) {
                sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        var channelCount = 0
        if (sampleRateHz == 0 || channelCount == 0) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                if (extractor.trackCount > 0) {
                    val format = extractor.getTrackFormat(0)
                    if (sampleRateHz == 0 && format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { extractor.release() }
            }
        }

        val (lyrics, rg) = readEmbeddedLyricsAndReplayGain(context, uri)
        val formatLabel = deriveFormatLabel(mimeType, displayName)

        return ExtendedAudioTags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = trackNum,
            discNumber = discNum,
            composer = composer,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            durationMs = durationMs,
            formatLabel = formatLabel,
            embeddedLyrics = lyrics,
            replayGainTrackDb = rg.first,
            replayGainAlbumDb = rg.second
        )
    }

    fun deriveFormatLabel(mimeType: String, displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").uppercase()
        return when {
            mimeType.contains("flac", true) || ext == "FLAC" -> "FLAC"
            mimeType.contains("alac", true) || ext == "ALAC" -> "ALAC"
            mimeType.contains("opus", true) || ext == "OPUS" -> "OPUS"
            mimeType.contains("ogg", true) || mimeType.contains("vorbis", true) || ext == "OGG" -> "OGG"
            mimeType.contains("wav", true) || mimeType.contains("x-wav", true) || ext == "WAV" -> "WAV"
            mimeType.contains("aac", true) || ext == "AAC" -> "AAC"
            mimeType.contains("mp4", true) || mimeType.contains("m4a", true) || ext == "M4A" -> "M4A"
            mimeType.contains("wma", true) || ext == "WMA" -> "WMA"
            mimeType.contains("mpeg", true) || ext == "MP3" -> "MP3"
            ext.isNotBlank() -> ext
            else -> "AUDIO"
        }
    }

    private fun readEmbeddedLyricsAndReplayGain(
        context: Context,
        uri: Uri
    ): Pair<String?, Pair<Float?, Float?>> {
        var lyrics: String? = null
        var trackGain: Float? = null
        var albumGain: Float? = null

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(10)
                val read = stream.read(header)
                if (read == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                    val version = header[3].toInt() and 0xFF
                    val tagSize = syncSafeToInt(header, 6).coerceAtMost(2 * 1024 * 1024)
                    if (tagSize > 0) {
                        val tagData = ByteArray(tagSize)
                        val totalRead = readExact(stream, tagData)
                        if (totalRead > 10) {
                            var offset = 0
                            while (offset + 10 <= totalRead) {
                                val frameId = String(tagData, offset, 4, StandardCharsets.ISO_8859_1)
                                if (frameId[0] == '\u0000') break
                                val frameSize = if (version >= 4) {
                                    syncSafeToInt(tagData, offset + 4)
                                } else {
                                    bigEndianToInt(tagData, offset + 4)
                                }
                                offset += 10
                                if (frameSize <= 0 || offset + frameSize > totalRead) break
                                if (frameId == "SYLT") {
                                    decodeSyltFrame(tagData, offset, frameSize)?.takeIf { it.isNotBlank() }?.let {
                                        lyrics = it
                                    }
                                } else if (frameId == "USLT") {
                                    if (lyrics.isNullOrBlank()) {
                                        lyrics = decodeUsltFrame(tagData, offset, frameSize)?.takeIf { it.isNotBlank() }
                                    }
                                } else if (frameId == "TXXX") {
                                    val (desc, value) = decodeTxxxFrame(tagData, offset, frameSize)
                                    if (desc.equals("REPLAYGAIN_TRACK_GAIN", ignoreCase = true)) {
                                        trackGain = parseGainDb(value) ?: trackGain
                                    } else if (desc.equals("REPLAYGAIN_ALBUM_GAIN", ignoreCase = true)) {
                                        albumGain = parseGainDb(value) ?: albumGain
                                    }
                                }
                                offset += frameSize
                            }
                        }
                    }
                } else if (read >= 4 && header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()
                ) {
                    // FLAC metadata blocks
                    val rest = ByteArray(128 * 1024)
                    val restRead = stream.read(rest).coerceAtLeast(0)
                    val combined = ByteArray(read + restRead)
                    System.arraycopy(header, 0, combined, 0, read)
                    System.arraycopy(rest, 0, combined, read, restRead)
                    var pos = 4
                    while (pos + 4 <= combined.size) {
                        val blockHeader = combined[pos].toInt() and 0xFF
                        val isLast = (blockHeader and 0x80) != 0
                        val blockType = blockHeader and 0x7F
                        val blockLen = ((combined[pos + 1].toInt() and 0xFF) shl 16) or
                            ((combined[pos + 2].toInt() and 0xFF) shl 8) or
                            (combined[pos + 3].toInt() and 0xFF)
                        pos += 4
                        if (blockLen < 0 || pos + blockLen > combined.size) break
                        if (blockType == 4) { // VORBIS_COMMENT
                            parseVorbisComments(combined, pos, blockLen).forEach { (k, v) ->
                                when {
                                    k.equals("LYRICS", true) || k.equals("UNSYNCEDLYRICS", true) ->
                                        if (lyrics.isNullOrBlank()) lyrics = v
                                    k.equals("REPLAYGAIN_TRACK_GAIN", true) ->
                                        trackGain = parseGainDb(v) ?: trackGain
                                    k.equals("REPLAYGAIN_ALBUM_GAIN", true) ->
                                        albumGain = parseGainDb(v) ?: albumGain
                                }
                            }
                        }
                        pos += blockLen
                        if (isLast) break
                    }
                }
            }
        } catch (_: Exception) {
        }

        return lyrics to (trackGain to albumGain)
    }

    private fun parseVorbisComments(data: ByteArray, start: Int, len: Int): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val end = start + len
        var pos = start
        if (pos + 4 > end) return result
        val vendorLen = littleEndianToInt(data, pos)
        pos += 4 + vendorLen.coerceAtLeast(0)
        if (pos + 4 > end) return result
        val count = littleEndianToInt(data, pos).coerceIn(0, 512)
        pos += 4
        repeat(count) {
            if (pos + 4 > end) return result
            val cLen = littleEndianToInt(data, pos)
            pos += 4
            if (cLen < 0 || pos + cLen > end) return result
            val comment = String(data, pos, cLen, StandardCharsets.UTF_8)
            pos += cLen
            val eqIdx = comment.indexOf('=')
            if (eqIdx > 0) {
                result.add(comment.substring(0, eqIdx) to comment.substring(eqIdx + 1))
            }
        }
        return result
    }

    private fun decodeUsltFrame(data: ByteArray, start: Int, len: Int): String? {
        if (len < 5) return null
        val encoding = data[start].toInt() and 0xFF
        var pos = start + 4 // skip encoding + 3-byte language
        val end = start + len
        // skip short content descriptor (null terminated)
        pos = skipNullTerminated(data, pos, end, encoding)
        if (pos >= end) return null
        return decodeTextBytes(data, pos, end - pos, encoding).trimEnd('\u0000')
    }

    internal fun decodeSyltFrame(data: ByteArray, start: Int, len: Int): String? {
        if (len < 8) return null
        val encoding = data[start].toInt() and 0xFF
        val timeStampFormat = data[start + 4].toInt() and 0xFF // 2 == milliseconds
        val end = start + len
        var pos = start + 6
        // skip content descriptor
        pos = skipNullTerminated(data, pos, end, encoding)
        if (pos >= end) return null

        val sb = StringBuilder()
        while (pos < end) {
            val textEnd = findNullTerminator(data, pos, end, encoding)
            val syllableOrLine = decodeTextBytes(data, pos, (textEnd - pos).coerceAtLeast(0), encoding)
                .replace("\r", "")
                .replace("\n", " ")
                .trim()
            val afterNull = skipNullTerminatorBytes(data, textEnd, end, encoding)
            if (afterNull + 4 > end) break
            val rawTimestamp = bigEndianToInt(data, afterNull).toLong() and 0xFFFFFFFFL
            pos = afterNull + 4
            if (syllableOrLine.isNotBlank()) {
                val tsMs = if (timeStampFormat == 2) rawTimestamp else rawTimestamp * 26L
                val totalSec = tsMs / 1000L
                val mins = totalSec / 60L
                val secs = totalSec % 60L
                val centis = (tsMs % 1000L) / 10L
                sb.append("[%02d:%02d.%02d]%s\n".format(mins, secs, centis, syllableOrLine))
            }
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun decodeTxxxFrame(data: ByteArray, start: Int, len: Int): Pair<String, String> {
        if (len < 2) return "" to ""
        val encoding = data[start].toInt() and 0xFF
        val end = start + len
        val descEnd = findNullTerminator(data, start + 1, end, encoding)
        val desc = decodeTextBytes(data, start + 1, (descEnd - (start + 1)).coerceAtLeast(0), encoding)
        val valStart = skipNullTerminatorBytes(data, descEnd, end, encoding)
        val value = if (valStart < end) {
            decodeTextBytes(data, valStart, end - valStart, encoding).trimEnd('\u0000')
        } else ""
        return desc.trimEnd('\u0000') to value
    }

    private fun skipNullTerminated(data: ByteArray, start: Int, end: Int, encoding: Int): Int {
        val term = findNullTerminator(data, start, end, encoding)
        return skipNullTerminatorBytes(data, term, end, encoding)
    }

    private fun findNullTerminator(data: ByteArray, start: Int, end: Int, encoding: Int): Int {
        var i = start
        if (encoding == 1 || encoding == 2) {
            while (i + 1 < end) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
                i += 2
            }
            return end
        }
        while (i < end) {
            if (data[i] == 0.toByte()) return i
            i++
        }
        return end
    }

    private fun skipNullTerminatorBytes(data: ByteArray, pos: Int, end: Int, encoding: Int): Int {
        if (pos >= end) return end
        return if (encoding == 1 || encoding == 2) (pos + 2).coerceAtMost(end) else (pos + 1).coerceAtMost(end)
    }

    private fun decodeTextBytes(data: ByteArray, start: Int, len: Int, encoding: Int): String {
        if (len <= 0) return ""
        return when (encoding) {
            1 -> String(data, start, len, StandardCharsets.UTF_16)
            2 -> String(data, start, len, StandardCharsets.UTF_16BE)
            3 -> String(data, start, len, StandardCharsets.UTF_8)
            else -> String(data, start, len, StandardCharsets.ISO_8859_1)
        }
    }

    private fun parseGainDb(raw: String): Float? {
        val m = replayGainRegex.find(raw)
        return (m?.groupValues?.getOrNull(1) ?: raw.trim()).toFloatOrNull()
    }

    fun writeMp3Id3TagsSafely(
        context: Context,
        uri: Uri,
        filePath: String,
        payload: EditableTagPayload
    ): TagWriteResult {
        val isMp3 = filePath.endsWith(".mp3", ignoreCase = true) ||
            context.contentResolver.getType(uri)?.contains("mpeg", ignoreCase = true) == true
        if (!isMp3) {
            return TagWriteResult.SavedToIndexOnly
        }

        val newTagBytes = buildId3v23Tag(payload)

        // Attempt direct write via ContentResolver ParcelFileDescriptor (or request write permission on API 30+)
        try {
            val tempFile = File.createTempFile("aria_tag_", ".mp3", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val header = ByteArray(10)
                    val headRead = input.read(header)
                    var skipExistingTag = 0
                    val hasId3 = headRead == 10 &&
                        header[0] == 'I'.code.toByte() &&
                        header[1] == 'D'.code.toByte() &&
                        header[2] == '3'.code.toByte()
                    if (hasId3) {
                        skipExistingTag = syncSafeToInt(header, 6)
                    }

                    FileOutputStream(tempFile).use { out ->
                        out.write(newTagBytes)
                        if (hasId3) {
                            var remainingToSkip = skipExistingTag.toLong()
                            val discard = ByteArray(8192)
                            while (remainingToSkip > 0) {
                                val r = input.read(discard, 0, minOf(discard.size.toLong(), remainingToSkip).toInt())
                                if (r <= 0) break
                                remainingToSkip -= r
                            }
                        } else if (headRead > 0) {
                            out.write(header, 0, headRead)
                        }
                        input.copyTo(out, 16384)
                    }
                } ?: return TagWriteResult.SavedToIndexOnly

                if (tempFile.length() < newTagBytes.size) {
                    return TagWriteResult.Failed("Audio stream validation failed; original file untouched.")
                }

                context.contentResolver.openFileDescriptor(uri, "rwt")?.use { pfd ->
                    FileInputStream(tempFile).use { src ->
                        FileOutputStream(pfd.fileDescriptor).use { dst ->
                            dst.channel.truncate(0L)
                            src.copyTo(dst, 16384)
                            dst.flush()
                        }
                    }
                } ?: return TagWriteResult.SavedToIndexOnly

                if (filePath.isNotBlank()) {
                    MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                }
                return TagWriteResult.SavedToFileAndIndex
            } finally {
                runCatching { tempFile.delete() }
            }
        } catch (sec: SecurityException) {
            if (Build.VERSION.SDK_INT >= 30 && uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val pending = runCatching {
                    MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                }.getOrNull()
                if (pending != null) {
                    return TagWriteResult.RequiresSystemWritePermission(pending)
                }
            } else if (Build.VERSION.SDK_INT == 29 && sec is RecoverableSecurityException) {
                return TagWriteResult.RequiresSystemWritePermission(sec.userAction.actionIntent)
            }
            return TagWriteResult.SavedToIndexOnly
        } catch (e: Exception) {
            return TagWriteResult.Failed(e.message ?: "Unable to write file tags safely")
        }
    }

    private fun buildId3v23Tag(payload: EditableTagPayload): ByteArray {
        val frames = ByteArrayOutputStream()
        fun writeTextFrame(id: String, value: String) {
            if (value.isBlank()) return
            val encoded = value.toByteArray(StandardCharsets.UTF_16)
            val size = 1 + encoded.size
            frames.write(id.toByteArray(StandardCharsets.ISO_8859_1))
            frames.write(intToBigEndian(size))
            frames.write(0)
            frames.write(0)
            frames.write(1) // UTF-16 with BOM
            frames.write(encoded)
        }

        writeTextFrame("TIT2", payload.title)
        writeTextFrame("TPE1", payload.artist)
        writeTextFrame("TALB", payload.album)
        writeTextFrame("TPE2", payload.albumArtist)
        writeTextFrame("TCON", payload.genre)
        if (payload.year > 0) writeTextFrame("TYER", payload.year.toString())
        if (payload.trackNumber > 0) writeTextFrame("TRCK", payload.trackNumber.toString())
        if (payload.discNumber > 0) writeTextFrame("TPOS", payload.discNumber.toString())
        writeTextFrame("TCOM", payload.composer)

        if (payload.lyrics.isNotBlank()) {
            val desc = ByteArray(2) // empty UTF-16 descriptor + BOM? Use UTF-16 null terminator 0x00 0x00
            val textBytes = payload.lyrics.toByteArray(StandardCharsets.UTF_16)
            val size = 1 + 3 + desc.size + textBytes.size
            frames.write("USLT".toByteArray(StandardCharsets.ISO_8859_1))
            frames.write(intToBigEndian(size))
            frames.write(0)
            frames.write(0)
            frames.write(1) // UTF-16
            frames.write("eng".toByteArray(StandardCharsets.ISO_8859_1))
            frames.write(desc)
            frames.write(textBytes)
        }

        if (payload.artworkBytes != null && payload.artworkBytes.isNotEmpty()) {
            val mime = "image/jpeg".toByteArray(StandardCharsets.ISO_8859_1)
            val size = 1 + mime.size + 1 + 1 + 1 + payload.artworkBytes.size
            frames.write("APIC".toByteArray(StandardCharsets.ISO_8859_1))
            frames.write(intToBigEndian(size))
            frames.write(0)
            frames.write(0)
            frames.write(0) // ISO-8859-1
            frames.write(mime)
            frames.write(0)
            frames.write(3) // Cover (front)
            frames.write(0) // Empty description
            frames.write(payload.artworkBytes)
        }

        val frameBytes = frames.toByteArray()
        val padding = 256
        val totalTagSize = frameBytes.size + padding
        val out = ByteArrayOutputStream(10 + totalTagSize)
        out.write("ID3".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(3) // v2.3
        out.write(0)
        out.write(0) // flags
        out.write(intToSyncSafe(totalTagSize))
        out.write(frameBytes)
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun readExact(stream: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = stream.read(buffer, total, buffer.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun syncSafeToInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun intToSyncSafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun bigEndianToInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).int

    private fun intToBigEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).putInt(value).array()

    private fun littleEndianToInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun findSafSidecarLyrics(context: Context, treeUris: List<String>, trackDisplayName: String): String? {
        val base = trackDisplayName.substringBeforeLast('.')
        treeUris.forEach { treeStr ->
            val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeStr)) }.getOrNull() ?: return@forEach
            val match = findFileRecursive(root, "$base.lrc", depth = 0)
            if (match != null) {
                val text = runCatching {
                    context.contentResolver.openInputStream(match.uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    private fun findFileRecursive(dir: DocumentFile, targetName: String, depth: Int): DocumentFile? {
        if (depth > 4) return null
        dir.listFiles().forEach { child ->
            if (child.isDirectory) {
                val found = findFileRecursive(child, targetName, depth + 1)
                if (found != null) return found
            } else if (child.name.equals(targetName, ignoreCase = true)) {
                return child
            }
        }
        return null
    }
}
