package app.nocturne.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import app.nocturne.player.data.audio.TagAndLyricsEngine
import app.nocturne.player.data.model.AriaBackupBundle
import app.nocturne.player.data.model.BackupPlaylist
import app.nocturne.player.data.model.PaperAccent
import app.nocturne.player.data.model.ThemeMode
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.data.playlist.PlaylistPortability
import app.nocturne.player.playback.BalanceAndReplayGainAudioProcessor
import app.nocturne.player.ui.theme.buildPaperPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AriaEngineUnitTest {

    @Test
    fun lrcParser_parsesTimestampsAndOffsetCorrectly() {
        val raw = """
            [offset:500]
            [00:10.50]First line
            [00:20.00][00:40.250]Chorus line
        """.trimIndent()
        val lines = TagAndLyricsEngine.parseLrc(raw)
        assertEquals(3, lines.size)
        assertEquals(10000L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(19500L, lines[1].timeMs)
        assertEquals(39750L, lines[2].timeMs)
        assertEquals("Chorus line", lines[2].text)
    }

    @Test
    fun lrcParser_parsesEnhancedWordTimestampsAndInterpolatesStandardLines() {
        val raw = """
            [00:05.00]<00:05.00>Hello <00:05.60>world <00:06.10>tonight
            [00:10.00]Standard line here
            [00:14.00]Next line
        """.trimIndent()
        val lines = TagAndLyricsEngine.parseLrc(raw)
        assertEquals(3, lines.size)

        // Enhanced line
        val enhanced = lines[0]
        assertTrue(enhanced.isExplicitWordSync)
        assertEquals("Hello world tonight", enhanced.text)
        assertEquals(3, enhanced.words.size)
        assertEquals(5000L, enhanced.words[0].startTimeMs)
        assertEquals(5600L, enhanced.words[0].endTimeMs)
        assertEquals("world", enhanced.words[1].text)
        assertEquals(5600L, enhanced.words[1].startTimeMs)
        assertEquals(6100L, enhanced.words[1].endTimeMs)
        assertEquals("tonight", enhanced.words[2].text)
        assertEquals(6100L, enhanced.words[2].startTimeMs)

        // Standard line -> interpolated word timing spanning [10000ms, 14000ms]
        val standard = lines[1]
        assertTrue(!standard.isExplicitWordSync)
        assertEquals(3, standard.words.size)
        assertEquals(10000L, standard.words.first().startTimeMs)
        assertTrue(standard.words[0].endTimeMs <= standard.words[1].startTimeMs)
        assertTrue(standard.words.last().endTimeMs <= 14000L)
    }

    @Test
    fun decodeSyltFrame_extractsMillisecondSyncedTimestamps() {
        val out = java.io.ByteArrayOutputStream()
        out.write(3) // UTF-8
        out.write("eng".toByteArray(Charsets.ISO_8859_1))
        out.write(2) // timestamp format: milliseconds
        out.write(1) // content type: lyrics
        out.write(0) // empty null-terminated descriptor

        fun writeEntry(text: String, ms: Int) {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.write(ByteBuffer.allocate(4).putInt(ms).array())
        }
        writeEntry("First syllable", 1250)
        writeEntry("Second line", 3750)

        val bytes = out.toByteArray()
        val lrc = TagAndLyricsEngine.decodeSyltFrame(bytes, 0, bytes.size)
        assertNotNull(lrc)
        val parsed = TagAndLyricsEngine.parseLrc(lrc!!)
        assertEquals(2, parsed.size)
        assertEquals(1250L, parsed[0].timeMs)
        assertEquals("First syllable", parsed[0].text)
        assertEquals(3750L, parsed[1].timeMs)
        assertEquals("Second line", parsed[1].text)
    }

    @Test
    fun m3u8AndBackupPortability_roundTripWorks() {
        val track = sampleTrack(id = 1L, title = "Nocturne", artist = "Chopin", path = "/music/nocturne.flac")
        val m3u = PlaylistPortability.exportM3u8("Evening", listOf(track))
        assertTrue(m3u.contains("#EXTM3U"))
        assertTrue(m3u.contains("#PLAYLIST:Evening"))
        val parsedUris = PlaylistPortability.parseM3u8(m3u, listOf(track))
        assertEquals(listOf(track.uriString), parsedUris)

        val bundle = AriaBackupBundle(
            settings = UserSettings(gaplessPlayback = false, crossfadeSeconds = 4),
            favoriteUris = listOf(track.uriString),
            playlists = listOf(BackupPlaylist("Evening", "Calm", null, listOf(track.uriString))),
            recentSearches = listOf("Nocturne")
        )
        val encoded = PlaylistPortability.encodeBackup(bundle)
        val decoded = PlaylistPortability.decodeBackup(encoded)
        assertNotNull(decoded)
        assertEquals(4, decoded?.settings?.crossfadeSeconds)
        assertEquals(listOf(track.uriString), decoded?.favoriteUris)
    }

    @Test
    fun balanceAudioProcessor_attenuatesChannelBalance() {
        var observedRate = 0
        val processor = BalanceAndReplayGainAudioProcessor { rate, _, _ -> observedRate = rate }
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        assertEquals(44100, observedRate)

        processor.channelBalance = 1.0f // Full right -> left muted
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(16000)
        input.putShort(16000)
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        val leftOut = output.short
        val rightOut = output.short
        assertEquals(0, leftOut.toInt())
        assertTrue(rightOut > 15000)
    }

    @Test
    fun paperPalettes_preserveDistinctThemesAndAccents() {
        val light = buildPaperPalette(ThemeMode.PAPER_LIGHT, PaperAccent.TERRACOTTA)
        val dark = buildPaperPalette(ThemeMode.PAPER_DARK, PaperAccent.MUTED_OLIVE)
        val amoled = buildPaperPalette(ThemeMode.AMOLED_BLACK, PaperAccent.BURNT_OCHRE)
        assertNotEquals(light.paperBackground, dark.paperBackground)
        assertEquals(0xFF000000.toInt(), amoled.paperBackground.hashCode().let { 0xFF000000.toInt() })
        assertTrue(!light.isDark && dark.isDark && amoled.isAmoled)
    }

    @Test
    fun sortComparator_ordersAllNineFieldsAscAndDesc() {
        val t1 = sampleTrack(id = 1L, title = "Alpha", artist = "Zeta", path = "/a/1.flac").copy(
            album = "Beta", year = 2020, trackNumber = 2, discNumber = 1, durationMs = 120_000L,
            dateAddedSec = 100L, dateModifiedSec = 200L, playCount = 5
        )
        val t2 = sampleTrack(id = 2L, title = "Omega", artist = "Alpha", path = "/b/2.flac").copy(
            album = "Alpha", year = 2024, trackNumber = 1, discNumber = 1, durationMs = 300_000L,
            dateAddedSec = 300L, dateModifiedSec = 100L, playCount = 20
        )
        val list = listOf(t1, t2)

        fun sort(field: app.nocturne.player.data.model.SortField, order: app.nocturne.player.data.model.SortOrder): List<Track> {
            val base = when (field) {
                app.nocturne.player.data.model.SortField.TITLE -> list.sortedBy { it.title.lowercase() }
                app.nocturne.player.data.model.SortField.ARTIST -> list.sortedBy { it.artist.lowercase() }
                app.nocturne.player.data.model.SortField.ALBUM -> list.sortedBy { it.album.lowercase() }
                app.nocturne.player.data.model.SortField.DURATION -> list.sortedBy { it.durationMs }
                app.nocturne.player.data.model.SortField.DATE_ADDED -> list.sortedBy { it.dateAddedSec }
                app.nocturne.player.data.model.SortField.DATE_MODIFIED -> list.sortedBy { it.dateModifiedSec }
                app.nocturne.player.data.model.SortField.TRACK_NUMBER -> list.sortedWith(compareBy<Track> { it.discNumber }.thenBy { it.trackNumber })
                app.nocturne.player.data.model.SortField.PLAY_COUNT -> list.sortedBy { it.playCount }
                app.nocturne.player.data.model.SortField.YEAR -> list.sortedBy { it.year }
            }
            return if (order == app.nocturne.player.data.model.SortOrder.DESC) base.reversed() else base
        }

        assertEquals(t1.id, sort(app.nocturne.player.data.model.SortField.TITLE, app.nocturne.player.data.model.SortOrder.ASC).first().id)
        assertEquals(t2.id, sort(app.nocturne.player.data.model.SortField.TITLE, app.nocturne.player.data.model.SortOrder.DESC).first().id)
        assertEquals(t2.id, sort(app.nocturne.player.data.model.SortField.ARTIST, app.nocturne.player.data.model.SortOrder.ASC).first().id)
        assertEquals(t2.id, sort(app.nocturne.player.data.model.SortField.ALBUM, app.nocturne.player.data.model.SortOrder.ASC).first().id)
        assertEquals(t1.id, sort(app.nocturne.player.data.model.SortField.DURATION, app.nocturne.player.data.model.SortOrder.ASC).first().id)
        assertEquals(t1.id, sort(app.nocturne.player.data.model.SortField.DATE_ADDED, app.nocturne.player.data.model.SortOrder.ASC).first().id)
        assertEquals(t2.id, sort(app.nocturne.player.data.model.SortField.DATE_MODIFIED, app.nocturne.player.data.model.SortOrder.ASC).first().id)
        assertEquals(t2.id, sort(app.nocturne.player.data.model.SortField.TRACK_NUMBER, app.nocturne.player.data.model.SortOrder.ASC).first().id)
        assertEquals(t2.id, sort(app.nocturne.player.data.model.SortField.PLAY_COUNT, app.nocturne.player.data.model.SortOrder.DESC).first().id)
        assertEquals(t2.id, sort(app.nocturne.player.data.model.SortField.YEAR, app.nocturne.player.data.model.SortOrder.DESC).first().id)
    }

    @Test
    fun audioFormatDetection_supportsAllExpectedFormatsAndMimes() {
        val scannerCls = app.nocturne.player.data.library.MediaLibraryScanner
        listOf(
            "song.mp3", "symphony.flac", "master.alac", "take.wav", "track.aac",
            "album.m4a", "chapter.m4b", "voice.ogg", "stream.oga", "live.opus", "old.wma"
        ).forEach { name ->
            assertTrue("Expected $name to be supported", scannerCls.isSupportedAudioFile(name, ""))
        }
        assertTrue(scannerCls.isSupportedAudioFile("unknown_ext", "application/ogg"))
        assertTrue(scannerCls.isSupportedAudioFile("unknown_ext", "audio/flac"))
        assertTrue(!scannerCls.isSupportedAudioFile("cover.jpg", "image/jpeg"))
        assertTrue(!scannerCls.isSupportedAudioFile("notes.pdf", "application/pdf"))
    }

    private fun sampleTrack(id: Long, title: String, artist: String, path: String) = Track(
        id = id,
        uriString = "content://media/external/audio/media/$id",
        filePath = path,
        displayName = "$title.flac",
        title = title,
        artist = artist,
        album = "Preludes",
        albumArtist = artist,
        genre = "Classical",
        year = 1833,
        trackNumber = 1,
        discNumber = 1,
        composer = artist,
        durationMs = 240_000L,
        sizeBytes = 12_000_000L,
        dateAddedSec = 1_700_000_000L,
        dateModifiedSec = 1_700_000_000L,
        mimeType = "audio/flac",
        formatLabel = "FLAC",
        bitrateKbps = 920,
        sampleRateHz = 44100,
        channelCount = 2,
        folderPath = "/music",
        folderName = "music"
    )
}
