package app.nocturne.player

import android.app.Application
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nocturne.player.data.audio.LyricsRepository
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.db.DownloadedPlaylistEntity
import app.nocturne.player.data.db.DownloadedTrackEntity
import app.nocturne.player.data.downloads.OfflineDownloadManager
import app.nocturne.player.data.model.AnimationIntensity
import app.nocturne.player.data.model.ArtworkShape
import app.nocturne.player.data.model.AudioEngineTelemetry
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAudioStream
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.PaperAccent
import app.nocturne.player.data.model.StreamingQuality
import app.nocturne.player.data.model.ThemeMode
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.data.online.OnlineDependencies
import app.nocturne.player.data.online.StreamQualitySelector
import app.nocturne.player.data.security.InputAndUrlSecurity
import app.nocturne.player.playback.SleepTimerState
import app.nocturne.player.ui.PlaybackUiState
import app.nocturne.player.ui.screens.NowPlayingScreen
import app.nocturne.player.ui.theme.ModernPaperTheme
import app.nocturne.player.widget.NewspaperWidgetRenderer
import app.nocturne.player.widget.NewsprintWidgetKind
import app.nocturne.player.widget.WidgetButtonIcon
import app.nocturne.player.widget.WidgetPlaybackState
import app.nocturne.player.widget.WidgetStatePrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeaturesAndWidgetsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun streamQualitySelector_honors96_160_320kbpsSettings() {
        val streams = listOf(
            OnlineAudioStream(
                url = "https://aac.saavncdn.com/test_96.mp4",
                mimeType = "audio/mp4",
                bitrateKbps = 96,
                codec = "aac",
                formatLabel = "M4A · 96k",
                expiresAtMs = 0L
            ),
            OnlineAudioStream(
                url = "https://aac.saavncdn.com/test_160.mp4",
                mimeType = "audio/mp4",
                bitrateKbps = 160,
                codec = "aac",
                formatLabel = "M4A · 160k",
                expiresAtMs = 0L
            ),
            OnlineAudioStream(
                url = "https://aac.saavncdn.com/test_320.mp4",
                mimeType = "audio/mp4",
                bitrateKbps = 320,
                codec = "aac",
                formatLabel = "M4A · 320k",
                expiresAtMs = 0L
            )
        )

        val pickedLow = StreamQualitySelector.selectBestAudioStream(
            streams = streams,
            speedTier = NetworkSpeedTier.FAST_UNMETERED,
            preferredQuality = StreamingQuality.LOW_96
        )
        val pickedMed = StreamQualitySelector.selectBestAudioStream(
            streams = streams,
            speedTier = NetworkSpeedTier.FAST_UNMETERED,
            preferredQuality = StreamingQuality.MEDIUM_160
        )
        val pickedHigh = StreamQualitySelector.selectBestAudioStream(
            streams = streams,
            speedTier = NetworkSpeedTier.SLOW_OR_METERED,
            preferredQuality = StreamingQuality.HIGH_320
        )

        assertEquals(96, pickedLow?.bitrateKbps)
        assertEquals(160, pickedMed?.bitrateKbps)
        assertEquals(320, pickedHigh?.bitrateKbps)
    }

    @Test
    fun offlineDownloadDao_and_localLyricsResolution_workCompletelyOffline() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AriaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dlDir = File(app.filesDir, "downloads").apply { mkdirs() }
            val audioFile = File(dlDir, "audio/test_song.m4a").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(256) { 1 })
            }
            val artFile = File(dlDir, "art/test_cover.jpg").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(64) { 2 })
            }
            val lrcFile = File(dlDir, "lyrics/test_song.lrc").apply {
                parentFile?.mkdirs()
                writeText("[00:01.00]Midnight newsprint lines")
            }

            assertTrue(InputAndUrlSecurity.isSafeAppPrivateDownloadUri(app, Uri.fromFile(audioFile)))
            assertFalse(InputAndUrlSecurity.isSafeAppPrivateDownloadUri(app, Uri.parse("file:///data/user/0/other.pkg/files/a.mp3")))

            val entity = DownloadedTrackEntity(
                trackId = "jiosaavn:12345",
                sourceId = "12345",
                providerType = OnlineProviderType.JIOSAAVN.name,
                providerId = "jiosaavn",
                providerLabel = "JioSaavn",
                title = "Paper Moon",
                artist = "The Quartet",
                album = "Midnight Press",
                durationMs = 180_000L,
                localAudioPath = audioFile.absolutePath,
                localArtworkPath = artFile.absolutePath,
                localLyricsPath = lrcFile.absolutePath,
                mimeType = "audio/mp4",
                formatLabel = "M4A",
                bitrateKbps = 320,
                fileSizeBytes = audioFile.length(),
                status = DownloadedTrackEntity.STATUS_COMPLETED,
                progressPercent = 100
            )
            db.offlineDownloadDao().upsertTrack(entity)

            val pl = DownloadedPlaylistEntity(
                id = "pl_test",
                title = "Evening Jazz",
                subtitle = "Offline Picks",
                localCoverPath = artFile.absolutePath,
                providerType = OnlineProviderType.JIOSAAVN.name,
                providerId = "jiosaavn",
                trackCount = 1
            )
            db.offlineDownloadDao().replacePlaylist(pl, listOf(entity.trackId))

            val savedList = db.offlineDownloadDao().observeCompletedTracks().first()
            assertEquals(1, savedList.size)
            assertEquals("Paper Moon", savedList.first().title)

            val mappedOffline = OfflineDownloadManager.entityToOfflineTrack(entity)
            assertFalse(mappedOffline.isOnline)
            assertEquals(entity.trackId, mappedOffline.onlineTrackId)
            assertTrue(mappedOffline.embeddedLyrics?.contains("Midnight newsprint lines") == true)

            val lyricsRepo = LyricsRepository(
                context = app,
                db = db,
                remoteConfigRepository = OnlineDependencies.getRemoteConfigRepository(app)
            )
            val resolvedLyrics = lyricsRepo.loadLyricsForTrack(
                mappedOffline.copy(embeddedLyrics = null),
                forceRemoteRefresh = false
            )
            assertTrue(resolvedLyrics.isSynced)
            assertEquals("Midnight newsprint lines", resolvedLyrics.syncedLines.first().text)
        } finally {
            db.close()
        }
    }

    @Test
    fun vintageNewsprintWidgets_renderAllThreeEditions_andToggleCorners() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sampleState = WidgetPlaybackState(
            title = "Midnight Extra Edition",
            artist = "The Press Sextet",
            album = "Ink & Cotton",
            formatBadge = "320K · M4A",
            providerLabel = "JIOSAAVN",
            isPlaying = true,
            shuffleEnabled = true,
            isFavorite = true,
            positionMs = 42_000L,
            durationMs = 195_000L,
            activeLyricLine = "Paper lanterns glow beside the printing press",
            isOnline = true,
            trackId = "jiosaavn:99"
        )
        WidgetStatePrefs.saveState(app, sampleState)
        WidgetStatePrefs.setGlobalSharpCorners(app, true)
        assertTrue(WidgetStatePrefs.loadState(app).sharpCorners)
        assertEquals("Paper lanterns glow beside the printing press", WidgetStatePrefs.loadState(app).activeLyricLine)

        val stripSharp = NewspaperWidgetRenderer.renderWidgetBitmap(
            context = app,
            kind = NewsprintWidgetKind.DAILY_STRIP,
            state = sampleState,
            widthDp = 320,
            heightDp = 96,
            sharpCorners = true,
            artworkBitmap = null
        )
        val frontRounded = NewspaperWidgetRenderer.renderWidgetBitmap(
            context = app,
            kind = NewsprintWidgetKind.FRONT_PAGE,
            state = sampleState,
            widthDp = 210,
            heightDp = 210,
            sharpCorners = false,
            artworkBitmap = null
        )
        val broadsheetSharp = NewspaperWidgetRenderer.renderWidgetBitmap(
            context = app,
            kind = NewsprintWidgetKind.SUNDAY_BROADSHEET,
            state = sampleState,
            widthDp = 340,
            heightDp = 300,
            sharpCorners = true,
            artworkBitmap = null
        )
        val playBtn = NewspaperWidgetRenderer.renderButtonBitmap(app, WidgetButtonIcon.PAUSE, isActive = true, emphasizePrimary = true)

        assertTrue(stripSharp.width > 100 && stripSharp.height > 40)
        assertTrue(frontRounded.width > 100 && frontRounded.height > 100)
        assertTrue(broadsheetSharp.width > 100 && broadsheetSharp.height > 100)
        assertNotNull(playBtn)
        assertTrue(playBtn.width > 0)
    }

    @Test
    fun nowPlayingSleepTimerEndOfTrack_andDownloadButton_renderInUi() {
        var endOfTrackTriggered = false
        var downloadTriggered = false
        val track = Track(
            id = 42L,
            uriString = "https://example.com/stream",
            filePath = "",
            displayName = "track.m4a",
            title = "Gazette Nocturne",
            artist = "Aria Ensemble",
            album = "Paper Vol. 1",
            albumArtist = "Aria Ensemble",
            genre = "Jazz",
            year = 2026,
            trackNumber = 1,
            discNumber = 1,
            composer = "",
            durationMs = 180_000L,
            sizeBytes = 1024L,
            dateAddedSec = 0L,
            dateModifiedSec = 0L,
            mimeType = "audio/mp4",
            formatLabel = "M4A",
            bitrateKbps = 320,
            sampleRateHz = 44100,
            channelCount = 2,
            folderPath = "",
            folderName = "Online",
            isOnline = true,
            onlineTrackId = "jiosaavn:42",
            onlineProvider = "JioSaavn"
        )

        composeRule.setContent {
            ModernPaperTheme(
                themeMode = ThemeMode.PAPER_LIGHT,
                accent = PaperAccent.TERRACOTTA,
                paperTextureIntensity = 0.65f,
                artworkShape = ArtworkShape.PHOTO_PRINT,
                animationIntensity = AnimationIntensity.FULL,
                fontScaleMultiplier = 1.0f
            ) {
                NowPlayingScreen(
                    playback = PlaybackUiState(
                        isConnected = true,
                        isPlaying = true,
                        currentTrack = track,
                        currentUri = track.uriString,
                        queueTracks = listOf(track),
                        currentQueueIndex = 0,
                        positionMs = 30_000L,
                        durationMs = 180_000L,
                        shuffleEnabled = false,
                        repeatMode = Player.REPEAT_MODE_OFF
                    ),
                    settings = UserSettings(),
                    telemetry = AudioEngineTelemetry(),
                    sleepTimer = SleepTimerState(isActive = false),
                    lyrics = LyricsData(),
                    onDismiss = {},
                    onTogglePlay = {},
                    onNext = {},
                    onPrevious = {},
                    onSeek = {},
                    onToggleShuffle = {},
                    onCycleRepeat = {},
                    onToggleFavorite = {},
                    onToggleRemainingTime = {},
                    onChangeSpeed = {},
                    onStartSleepTimer = {},
                    onCancelSleepTimer = {},
                    onOpenQueue = {},
                    onOpenLyrics = {},
                    onOpenAudioFx = {},
                    onOpenTagEditor = {},
                    onOpenAddToPlaylist = {},
                    onOpenDetails = {},
                    onNavigateToAlbum = {},
                    onNavigateToArtist = {},
                    onDownloadTrack = { downloadTriggered = true },
                    onStartEndOfTrackSleepTimer = { endOfTrackTriggered = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("now_playing_download_btn").assertIsDisplayed().performClick()
        assertTrue(downloadTriggered)

        composeRule.onNodeWithTag("now_playing_sleep_timer_btn").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("15m").assertIsDisplayed()
        composeRule.onNodeWithText("30m").assertIsDisplayed()
        composeRule.onNodeWithText("45m").assertIsDisplayed()
        composeRule.onNodeWithText("60m").assertIsDisplayed()
        composeRule.onNodeWithText("Start 30 min Timer").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_timer_end_of_track")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stop at End of Track").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_timer_confirm_btn").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertTrue(endOfTrackTriggered)
    }
}
