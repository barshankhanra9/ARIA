package app.nocturne.player

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAudioStream
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.data.online.AudiusProvider
import app.nocturne.player.data.online.InvidiousProvider
import app.nocturne.player.data.online.JioSaavnProvider
import app.nocturne.player.data.online.OkHttpDownloader
import app.nocturne.player.data.online.OnlineHttpClient
import app.nocturne.player.data.online.PipedProvider
import app.nocturne.player.data.online.RemoteConfigRepository
import app.nocturne.player.data.online.StreamQualitySelector
import app.nocturne.player.data.playlist.PlaylistPortability
import app.nocturne.player.data.prefs.SettingsRepository
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.downloader.Request
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecurityAuditUnitTest {

    @Test
    fun urlValidation_blocksSsrfPrivateMetadataCleartextAndObfuscatedIps() {
        val blockedUrls = listOf(
            "http://example.com/audio.mp3",
            "https://127.0.0.1/admin",
            "https://localhost/config.json",
            "https://169.254.169.254/latest/meta-data/",
            "https://10.0.0.1/internal",
            "https://172.16.5.4/internal",
            "https://192.168.1.1/router",
            "https://100.64.0.1/cgnat",
            "https://[::1]/loopback",
            "https://[fc00::1]/ula",
            "https://[::ffff:127.0.0.1]/v4mapped",
            "https://2130706433/decimal-loopback",
            "https://0x7f.0.0.1/hex-loopback",
            "https://metadata.google.internal/computeMetadata/v1/",
            "https://user:secret@cdn.example.com/stream.m4a",
            "javascript:alert(1)",
            "file:///etc/passwd"
        )

        blockedUrls.forEach { bad ->
            assertNull("Expected URL to be blocked: $bad", InputAndUrlSecurity.validateRemoteHttpsUrl(bad))
        }

        val allowed = InputAndUrlSecurity.validateRemoteHttpsUrl("https://cdn.example.com/audio/stream.m4a?v=1#frag")
        assertEquals("https://cdn.example.com/audio/stream.m4a?v=1", allowed)
    }

    @Test
    fun youTubeHostCheck_rejectsDomainSpoofingAndDoesNotLeakRefererToThirdParties() {
        assertTrue(OnlineHttpClient.isYouTubeHost("youtube.com"))
        assertTrue(OnlineHttpClient.isYouTubeHost("www.youtube.com"))
        assertTrue(OnlineHttpClient.isYouTubeHost("rr1---sn-4g5e6ns7.googlevideo.com"))
        assertTrue(OnlineHttpClient.isYouTubeHost("i.ytimg.com"))

        assertFalse(OnlineHttpClient.isYouTubeHost("youtube.com.evil.example"))
        assertFalse(OnlineHttpClient.isYouTubeHost("notyoutube.com"))
        assertFalse(OnlineHttpClient.isYouTubeHost("169.254.169.254"))

        val thirdPartyHeaders = OnlineHttpClient.defaultMediaHeaders("https://cdn.jiosaavn.com/track.mp4")
        assertFalse(thirdPartyHeaders.containsKey("Referer"))
        assertFalse(thirdPartyHeaders.containsKey("Origin"))

        val ytHeaders = OnlineHttpClient.defaultMediaHeaders("https://rr1.googlevideo.com/videoplayback")
        assertEquals("https://www.youtube.com/", ytHeaders["Referer"])
    }

    @Test
    fun remoteConfigRepository_productionDefaultRejectsLoopbackAndInternalSsrfUrls() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"piped":["https://piped.ok.example"]}"""))
            val repo = RemoteConfigRepository(settingsRepo) // production defaults (allowTestLoopback = false)

            // Attempt SSRF against local test server / cloud metadata URL
            repo.refreshNow(customUrl = server.url("/providers.json").toString())
            assertEquals(0, server.requestCount)
            assertNotNull(repo.lastFetchError.value)

            repo.refreshNow(customUrl = "https://169.254.169.254/latest/meta-data/")
            assertNotNull(repo.lastFetchError.value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun okHttpDownloader_blocksCleartextAndPrivateUrlsBeforeDispatch() {
        val downloader = OkHttpDownloader(OnlineHttpClient.client)
        val blockedRequests = listOf(
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://169.254.169.254/latest/meta-data/",
            "https://127.0.0.1/secret"
        )
        blockedRequests.forEach { target ->
            val req = Request.Builder().get(target).build()
            var rejected = false
            try {
                downloader.execute(req)
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue("Expected OkHttpDownloader to block $target", rejected)
        }
    }

    @Test
    fun providers_rejectPathTraversalIdsAndUntrustedInstanceUrls() = runTest {
        val piped = PipedProvider()
        val invidious = InvidiousProvider()
        val jiosaavn = JioSaavnProvider()
        val audius = AudiusProvider()

        val traversalTrack = OnlineTrack(
            id = "test:bad",
            sourceId = "../../admin?secret=1",
            providerType = OnlineProviderType.PIPED,
            providerLabel = "Piped",
            sourceUrl = "https://169.254.169.254/watch?v=bad",
            title = "Bad Track",
            artist = "Attacker"
        )

        val streamResolvers = listOf<suspend () -> Any>(
            { piped.resolveStream(traversalTrack, NetworkSpeedTier.STANDARD) },
            { invidious.resolveStream(traversalTrack, NetworkSpeedTier.STANDARD) },
            { jiosaavn.resolveStream(traversalTrack, NetworkSpeedTier.STANDARD) },
            { audius.resolveStream(traversalTrack, NetworkSpeedTier.STANDARD) }
        )
        streamResolvers.forEach { call ->
            var failed = false
            try {
                call()
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue("Expected path-traversal sourceId to be rejected", failed)
        }
    }

    @Test
    fun streamQualitySelector_filtersOutHttpAndInternalSsrfStreamUrls() {
        val cleartext = OnlineAudioStream("http://cdn.example/a.mp3", 320, "audio/mpeg", "mp3", "MP3", 0L)
        val metadata = OnlineAudioStream("https://169.254.169.254/audio.mp4", 320, "audio/mp4", "aac", "AAC", 0L)
        val loopback = OnlineAudioStream("https://127.0.0.1/audio.webm", 256, "audio/webm", "opus", "WEBM", 0L)
        val safe = OnlineAudioStream("https://cdn.example/safe.m4a", 128, "audio/mp4", "mp4a.40.2", "M4A", 0L)

        val selected = StreamQualitySelector.selectBestAudioStream(
            streams = listOf(cleartext, metadata, loopback, safe),
            speedTier = NetworkSpeedTier.FAST_UNMETERED
        )
        assertEquals(safe, selected)
    }

    @Test
    fun sanitizationAndLocalUriGuards_stripInjectionsAndBlockPrivateAppDataPaths() {
        val dirtyQuery = "lofi\u0000beats\r\n<script>\u202E"
        val cleanQuery = InputAndUrlSecurity.sanitizeSearchQuery(dirtyQuery)
        assertFalse(cleanQuery.contains('\u0000'))
        assertFalse(cleanQuery.contains('\n'))
        assertFalse(cleanQuery.contains('\u202E'))

        val sampleTrack = Track(
            id = 1L,
            uriString = "content://media/external/audio/media/10",
            filePath = "/storage/emulated/0/Music/song.mp3",
            displayName = "song.mp3",
            title = "Good\n#EXTINF:0,Injected",
            artist = "Artist\r\nBad",
            album = "Album",
            albumArtist = "Artist",
            genre = "Pop",
            year = 2024,
            trackNumber = 1,
            discNumber = 1,
            composer = "",
            durationMs = 180_000L,
            sizeBytes = 1024L,
            dateAddedSec = 0L,
            dateModifiedSec = 0L,
            mimeType = "audio/mpeg",
            formatLabel = "MP3",
            bitrateKbps = 320,
            sampleRateHz = 44100,
            channelCount = 2,
            folderPath = "/storage/emulated/0/Music",
            folderName = "Music"
        )
        val m3u = PlaylistPortability.exportM3u8("My\n#EXTM3U_INJECT", listOf(sampleTrack))
        assertTrue(m3u.lines().none { it.startsWith("#EXTM3U_INJECT") || it == "#EXTINF:0,Injected" })

        assertFalse(InputAndUrlSecurity.isSafePlayableLocalUri(Uri.parse("file:///data/data/app.nocturne.player/shared_prefs/secret.xml")))
        assertFalse(InputAndUrlSecurity.isSafePlayableLocalUri(Uri.parse("file:///etc/hosts")))
        assertFalse(InputAndUrlSecurity.isSafePlayableLocalUri(Uri.parse("http://127.0.0.1/test.mp3")))
        assertTrue(InputAndUrlSecurity.isSafePlayableLocalUri(Uri.parse("content://media/external/audio/media/10")))

        val tamperedSettings = UserSettings(
            remoteProviderConfigUrl = "https://169.254.169.254/evil.json",
            includedSafFolderUris = listOf("file:///data/data/app.nocturne.player", "content://com.android.externalstorage/tree/primary%3AMusic"),
            playbackSpeed = 999f,
            crossfadeSeconds = -50
        )
        val clamped = InputAndUrlSecurity.sanitizeUserSettings(tamperedSettings)
        assertEquals(UserSettings.DEFAULT_REMOTE_CONFIG_URL, clamped.remoteProviderConfigUrl)
        assertEquals(listOf("content://com.android.externalstorage/tree/primary%3AMusic"), clamped.includedSafFolderUris)
        assertEquals(2.0f, clamped.playbackSpeed, 0.001f)
        assertEquals(0, clamped.crossfadeSeconds)
    }

    @Test
    fun readBodyBounded_rejectsOversizedResponses() {
        val oversized = ByteArray(4096) { 'a'.code.toByte() }.toResponseBody("application/json".toMediaType())
        var threw = false
        try {
            InputAndUrlSecurity.readBodyBounded(oversized, maxBytes = 1024L)
        } catch (_: IOException) {
            threw = true
        }
        assertTrue("Expected oversized response body to throw IOException", threw)
    }
}
