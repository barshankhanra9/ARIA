package app.nocturne.player

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nocturne.player.data.audio.LyricsRepository
import app.nocturne.player.data.audio.MetadataCleaner
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAlbumPage
import app.nocturne.player.data.model.OnlineAlbumSummary
import app.nocturne.player.data.model.OnlineArtistPage
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineSearchPage
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.online.AudiusProvider
import app.nocturne.player.data.online.DynamicHttpExecutor
import app.nocturne.player.data.online.InvidiousProvider
import app.nocturne.player.data.online.JioSaavnProvider
import app.nocturne.player.data.online.OnlineMusicProvider
import app.nocturne.player.data.online.OnlineProviderManager
import app.nocturne.player.data.online.PipedProvider
import app.nocturne.player.data.online.ProviderHealthTracker
import app.nocturne.player.data.online.RemoteConfigRepository
import app.nocturne.player.data.prefs.SettingsRepository
import app.nocturne.player.ui.OnlineCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CollectionAndOverridesTest {

    @Test
    fun strictTrackDedupKey_andDeduplicateTracks_collapseVariants() {
        val key1 = MetadataCleaner.strictTrackDedupKey(
            title = "Midnight City (Official Audio) [HD]",
            artist = "M83"
        )
        val key2 = MetadataCleaner.strictTrackDedupKey(
            title = "midnight city (Lyrics)",
            artist = "m83 feat. Someone Else"
        )
        assertEquals(key1, key2)

        val keyDifferent = MetadataCleaner.strictTrackDedupKey(
            title = "Wait",
            artist = "M83"
        )
        assertNotEquals(key1, keyDifferent)

        val baseTrack = OnlineTrack(
            id = "jiosaavn:101",
            sourceId = "101",
            sourceUrl = "https://www.jiosaavn.com/song/101",
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationMs = 200_000L,
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = "jiosaavn"
        )
        val sameIdDuplicate = baseTrack.copy(title = "Blinding Lights Variant")
        val sameSourceDuplicate = baseTrack.copy(id = "other:101", title = "Blinding Lights Another")
        val sameNormalizedTitleArtist = OnlineTrack(
            id = "piped:abc999",
            sourceId = "abc999",
            sourceUrl = "https://www.youtube.com/watch?v=abc999",
            title = "Blinding Lights (Official Audio) [4K]",
            artist = "The Weeknd",
            durationMs = 201_000L,
            providerType = OnlineProviderType.PIPED,
            providerId = "piped"
        )
        val distinctTrack = OnlineTrack(
            id = "jiosaavn:202",
            sourceId = "202",
            sourceUrl = "https://www.jiosaavn.com/song/202",
            title = "Starboy",
            artist = "The Weeknd",
            durationMs = 230_000L,
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = "jiosaavn"
        )

        val deduped = OnlineProviderManager.deduplicateTracks(
            listOf(baseTrack, sameIdDuplicate, sameSourceDuplicate, sameNormalizedTitleArtist, distinctTrack)
        )
        assertEquals(2, deduped.size)
        assertEquals("jiosaavn:101", deduped[0].id)
        assertEquals("jiosaavn:202", deduped[1].id)
    }

    @Test
    fun playlistAndAlbumParsing_handlesJsonObjectAndMalformedFieldsSafelyAcrossProviders() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/api/v1/playlists/") -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "title": { "runs": [ { "text": "Late Night " }, { "text": "Synthwave" } ] },
                              "author": { "simpleText": "Invidious Curator" },
                              "description": { "runs": [ { "text": "Nested playlist description" } ] },
                              "playlistThumbnail": { "url": "/vi/pl1/hq.jpg" },
                              "videos": [
                                {
                                  "videoId": "vid_01",
                                  "title": { "runs": [ { "text": "Neon Drive" } ] },
                                  "author": { "simpleText": "Arcade" },
                                  "lengthSeconds": { "text": "215" },
                                  "videoThumbnails": [ { "url": "/vi/vid_01/mq.jpg" } ]
                                },
                                {
                                  "videoId": "",
                                  "title": { "runs": [] }
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                        path.startsWith("/playlists/piped_pl_1") -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "name": { "simpleText": "Piped Chill Collection" },
                              "uploader": { "name": "Piped Host" },
                              "description": { "content": "Warm ambient tape" },
                              "thumbnailUrl": { "url": "https://cdn.example/piped_cover.jpg" },
                              "relatedStreams": [
                                {
                                  "url": "/watch?v=piped_t1",
                                  "title": { "runs": [ { "text": "Glass Waves" } ] },
                                  "uploaderName": { "simpleText": "Lumen" },
                                  "duration": { "seconds": 180 }
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                        path.startsWith("/albums") -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "status": "SUCCESS",
                              "data": {
                                "name": { "text": "JioSaavn Nested Album" },
                                "description": { "runs": [ { "text": "Acoustic hits" } ] },
                                "artists": { "primary": [ { "name": "Arijit" } ] },
                                "songs": [
                                  {
                                    "id": "saavn_s1",
                                    "name": { "text": "Kesariya" },
                                    "duration": { "value": "240" },
                                    "artists": { "primary": [ { "name": "Pritam" } ] },
                                    "image": [ { "quality": "500x500", "url": "https://c.saavncdn.com/k.jpg" } ],
                                    "downloadUrl": [ { "quality": "320kbps", "url": "https://aac.saavncdn.com/k.mp4" } ]
                                  }
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                        path.startsWith("/v1/playlists/") -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "data": {
                                "playlist_name": { "text": "Audius Nested Set" },
                                "description": { "runs": [ { "text": "Electronic set" } ] },
                                "user": { "name": { "simpleText": "DJ Nova" } },
                                "tracks": [
                                  {
                                    "id": "aud_t1",
                                    "title": { "simpleText": "Pulse" },
                                    "duration": { "value": 195 },
                                    "user": { "id": "u1", "name": "DJ Nova", "handle": "djnova" },
                                    "is_streamable": { "value": true }
                                  }
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val client = OkHttpClient()

            val invidious = InvidiousProvider(client = client, baseUrlsOverride = listOf(baseUrl))
            val invPage = invidious.fetchAlbumOrPlaylist("PL_inv_1")
            assertEquals("Late Night Synthwave", invPage.title)
            assertEquals("Invidious Curator", invPage.artist)
            assertEquals("Nested playlist description", invPage.description)
            assertEquals("$baseUrl/vi/pl1/hq.jpg", invPage.thumbnailUrl)
            assertEquals(1, invPage.tracks.size)
            assertEquals("Neon Drive", invPage.tracks.first().title)

            val piped = PipedProvider(client = client, baseUrlsOverride = listOf(baseUrl))
            val pipedPage = piped.fetchAlbumOrPlaylist("piped_pl_1")
            assertEquals("Piped Chill Collection", pipedPage.title)
            assertEquals("Piped Host", pipedPage.artist)
            assertEquals("Warm ambient tape", pipedPage.description)
            assertEquals("https://cdn.example/piped_cover.jpg", pipedPage.thumbnailUrl)
            assertEquals(1, pipedPage.tracks.size)
            assertEquals("Glass Waves", pipedPage.tracks.first().title)

            val jiosaavn = JioSaavnProvider(client = client, baseUrlsOverride = listOf(baseUrl))
            val saavnPage = jiosaavn.fetchAlbumOrPlaylist("saavn_alb_1")
            assertEquals("JioSaavn Nested Album", saavnPage.title)
            assertEquals("Arijit", saavnPage.artist)
            assertEquals(1, saavnPage.tracks.size)
            assertEquals("Kesariya", saavnPage.tracks.first().title)

            val audius = AudiusProvider(client = client, baseUrlsOverride = listOf(baseUrl))
            val audPage = audius.fetchAlbumOrPlaylist("aud_pl_1")
            assertEquals("Audius Nested Set", audPage.title)
            assertEquals("DJ Nova", audPage.artist)
            assertEquals(1, audPage.tracks.size)
            assertEquals("Pulse", audPage.tracks.first().title)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun localProviderOverrides_priorityReorder_persistenceAndResetOnRefreshWork() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val remoteJson = """
                {
                  "schemaVersion": 2,
                  "configVersion": "2026.11.26-1",
                  "refreshIntervalSeconds": 1800,
                  "providers": [
                    {
                      "id": "jiosaavn",
                      "displayName": "JioSaavn",
                      "adapterType": "jiosaavn",
                      "enabled": true,
                      "priority": 10,
                      "endpoints": [
                        { "url": "$baseUrl/saavn", "priority": 1, "enabled": true, "operations": ["search", "metadata", "playback", "lyrics"] }
                      ]
                    },
                    {
                      "id": "audius",
                      "displayName": "Audius",
                      "adapterType": "audius",
                      "enabled": true,
                      "priority": 20,
                      "endpoints": [
                        { "url": "$baseUrl/audius", "priority": 1, "enabled": true, "operations": ["search", "metadata", "playback"] }
                      ]
                    }
                  ],
                  "lyricsProviders": [
                    {
                      "id": "liriqo",
                      "displayName": "LiriQo",
                      "adapterType": "liriqo",
                      "enabled": true,
                      "priority": 10,
                      "endpoints": [ { "url": "$baseUrl/liriqo", "priority": 1, "enabled": true } ]
                    },
                    {
                      "id": "lrclib",
                      "displayName": "LRCLIB",
                      "adapterType": "lrclib",
                      "enabled": true,
                      "priority": 20,
                      "endpoints": [ { "url": "$baseUrl/lrclib", "priority": 1, "enabled": true } ]
                    }
                  ]
                }
            """.trimIndent()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return MockResponse().setResponseCode(200).setBody(remoteJson)
                }
            }

            val repo = RemoteConfigRepository(
                settingsRepository = settingsRepo,
                baseClient = OkHttpClient(),
                allowTestLoopback = true
            )
            val url = "$baseUrl/providers.json"
            repo.refreshNow(customUrl = url, force = true)
            assertFalse(repo.status.value.hasLocalOverrides)
            assertEquals(listOf("jiosaavn", "audius"), repo.activeConfig.value.providers.map { it.id })

            // Move Audius up above JioSaavn and disable JioSaavn + LiriQo
            repo.moveMusicProviderPriority("audius", -1)
            repo.toggleMusicProviderEnabled("jiosaavn")
            repo.moveLyricsProviderPriority("lrclib", -1)
            repo.toggleLyricsProviderEnabled("liriqo")

            assertTrue(repo.status.value.hasLocalOverrides)
            assertEquals(listOf("audius", "jiosaavn"), repo.activeConfig.value.providers.map { it.id })
            assertEquals(false, repo.activeConfig.value.providerById("jiosaavn")?.enabled)
            assertEquals(1, repo.status.value.enabledProviderCount)

            val resolvedLyrics = repo.activeConfig.value.resolvedLyricsProvidersForDiagnostics()
            assertEquals("lrclib", resolvedLyrics.first().id)
            assertEquals(false, resolvedLyrics.first { it.id == "liriqo" }.enabled)

            // Verify overrides persisted into DataStore and reload in a fresh RemoteConfigRepository
            val savedSettings = settingsRepo.getSettingsOnce()
            assertTrue(savedSettings.musicProviderOverrides.containsKey("jiosaavn"))
            assertTrue(savedSettings.lyricsProviderOverrides.containsKey("liriqo"))

            val reloadedRepo = RemoteConfigRepository(
                settingsRepository = settingsRepo,
                baseClient = OkHttpClient(),
                allowTestLoopback = true
            )
            val waitStart = System.currentTimeMillis()
            while (!reloadedRepo.status.value.hasLocalOverrides &&
                System.currentTimeMillis() - waitStart < 5_000L
            ) {
                Thread.sleep(25)
            }
            assertTrue(reloadedRepo.status.value.hasLocalOverrides)
            assertEquals(false, reloadedRepo.activeConfig.value.providerById("jiosaavn")?.enabled)
            assertEquals("audius", reloadedRepo.activeConfig.value.providers.first().id)

            // Refresh with resetOverrides = true restores remote order & enabled flags
            reloadedRepo.refreshNow(customUrl = url, force = true, resetOverrides = true)
            assertFalse(reloadedRepo.status.value.hasLocalOverrides)
            assertEquals(listOf("jiosaavn", "audius"), reloadedRepo.activeConfig.value.providers.map { it.id })
            assertEquals(true, reloadedRepo.activeConfig.value.providerById("jiosaavn")?.enabled)
            assertEquals(true, reloadedRepo.activeConfig.value.resolvedLyricsProvidersForDiagnostics().first { it.id == "liriqo" }.enabled)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun lyricsRepository_andDiagnostics_respectOverridesAndReportEndpointHealth() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        val requestedHosts = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            requestedHosts += req.url.host
            when {
                url.contains("config.example/providers.json") -> {
                    val body = """
                        {
                          "schemaVersion": 2,
                          "configVersion": "2026.11.26-2",
                          "providers": [
                            {
                              "id": "jiosaavn",
                              "displayName": "JioSaavn",
                              "adapterType": "jiosaavn",
                              "enabled": true,
                              "priority": 10,
                              "endpoints": [
                                { "url": "https://saavn.example", "priority": 1, "enabled": true, "operations": ["search", "metadata", "playback", "lyrics"] }
                              ]
                            }
                          ],
                          "lyricsProviders": [
                            {
                              "id": "liriqo",
                              "displayName": "LiriQo",
                              "adapterType": "liriqo",
                              "enabled": true,
                              "priority": 10,
                              "endpoints": [ { "url": "https://liriqo.example", "priority": 1, "enabled": true } ]
                            },
                            {
                              "id": "musixmatch",
                              "displayName": "Musixmatch",
                              "adapterType": "musixmatch",
                              "enabled": true,
                              "priority": 20,
                              "endpoints": [ { "url": "https://mxm.example", "priority": 1, "enabled": true } ]
                            },
                            {
                              "id": "lrclib",
                              "displayName": "LRCLIB",
                              "adapterType": "lrclib",
                              "enabled": true,
                              "priority": 30,
                              "endpoints": [ { "url": "https://lrclib.example", "priority": 1, "enabled": true } ]
                            }
                          ]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                req.url.host == "liriqo.example" -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body("""{"error":"down"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                req.url.host == "mxm.example" -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"message":{"header":{"status_code":401}}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                req.url.host == "saavn.example" -> {
                    if (req.url.toString().contains("id=1")) {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"data":{"lyrics":"Saavn probe ok"}}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    } else {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("""{"error":"no lyrics"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
                req.url.host == "lrclib.example" -> {
                    val body = """[{"id":1,"trackName":"Horizon","artistName":"Aria","syncedLyrics":"[00:05.00]From LRCLIB","plainLyrics":"From LRCLIB","instrumental":false}]"""
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                else -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
        val mockHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val tracker = ProviderHealthTracker()
        val remoteRepo = RemoteConfigRepository(settingsRepository = settingsRepo, baseClient = mockHttpClient)
        remoteRepo.refreshNow(customUrl = "https://config.example/providers.json", force = true)

        val db = Room.inMemoryDatabaseBuilder(context, AriaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val lyricsRepo = LyricsRepository(
                context = context,
                db = db,
                remoteConfigRepository = remoteRepo,
                client = mockHttpClient,
                healthTracker = tracker
            )
            // Disable LiriQo and Musixmatch locally -> LyricsRepository must skip them and go directly to LRCLIB
            remoteRepo.toggleLyricsProviderEnabled("liriqo")
            remoteRepo.toggleLyricsProviderEnabled("musixmatch")

            val song = OnlineTrack(
                id = "jiosaavn:55",
                sourceId = "55",
                providerType = OnlineProviderType.JIOSAAVN,
                sourceUrl = "https://www.jiosaavn.com/song/55",
                title = "Horizon",
                artist = "Aria",
                album = "Echoes",
                durationMs = 210_000L,
                providerId = "jiosaavn"
            ).toUiTrack()

            requestedHosts.clear()
            val lyricsResult = lyricsRepo.loadLyricsForTrack(song, forceRemoteRefresh = true)
            assertEquals(LyricsSource.LRCLIB, lyricsResult.source)
            assertFalse("Disabled LiriQo should not be called", requestedHosts.contains("liriqo.example"))
            assertFalse("Disabled Musixmatch should not be called", requestedHosts.contains("mxm.example"))
            assertTrue(requestedHosts.contains("lrclib.example"))

            // Re-enable all and test diagnostics + endpoint tests via OnlineProviderManager
            remoteRepo.resetProviderOverrides()
            val manager = OnlineProviderManager(
                remoteConfigRepository = remoteRepo,
                httpClient = mockHttpClient,
                healthTracker = tracker
            )

            val liriqoTest = manager.testLyricsProviderConnectivity("liriqo")
            assertFalse(liriqoTest.success)
            assertTrue(liriqoTest.errorSummary.orEmpty().contains("500"))

            val mxmTest = manager.testLyricsProviderConnectivity("musixmatch")
            assertFalse(mxmTest.success)
            assertTrue(mxmTest.errorSummary.orEmpty().contains("401"))

            val saavnLyricsTest = manager.testLyricsProviderConnectivity("jiosaavn_lyrics")
            assertTrue(saavnLyricsTest.success)
            assertTrue(saavnLyricsTest.latencyMs > 0L)

            val lrclibTest = manager.testLyricsProviderConnectivity("lrclib")
            assertTrue(lrclibTest.success)
            assertTrue(lrclibTest.latencyMs > 0L)

            val musicDiagnostics = manager.getProviderDiagnostics()
            val saavnDiag = musicDiagnostics.first { it.providerId == "jiosaavn" }
            assertTrue(saavnDiag.supportsLyrics)
            assertNotNull(saavnDiag.operationHealth[OperationType.LYRICS])
            val mirroredSaavnEp = saavnDiag.operationHealth[OperationType.LYRICS]!!.first()
            assertTrue(mirroredSaavnEp.isHealthy)
            assertTrue(mirroredSaavnEp.ewmaLatencyMs > 0L)

            val lyricsDiagnostics = manager.getLyricsProviderDiagnostics()
            assertEquals(4, lyricsDiagnostics.size)
            val liriqoDiag = lyricsDiagnostics.first { it.providerId == "liriqo" }
            assertFalse(liriqoDiag.isHealthy)
            assertTrue(liriqoDiag.lastError.orEmpty().contains("500"))

            val mxmDiag = lyricsDiagnostics.first { it.providerId == "musixmatch" }
            assertFalse(mxmDiag.isHealthy)
            assertTrue(mxmDiag.lastError.orEmpty().contains("401"))

            val lrclibDiag = lyricsDiagnostics.first { it.providerId == "lrclib" }
            assertTrue(lrclibDiag.isHealthy)
            assertTrue(lrclibDiag.ewmaLatencyMs > 0L)
        } finally {
            db.close()
        }
    }

    @Test
    fun playlistNavigationTarget_prefersExactIdAndDistinguishesProviders() {
        val saavnPlaylist = OnlineAlbumSummary(
            id = "pl_exact_8812",
            title = "Top Bollywood 2026",
            artist = "JioSaavn",
            url = "https://www.jiosaavn.com/featured/top-bollywood/xyz",
            providerType = OnlineProviderType.JIOSAAVN,
            providerId = "jiosaavn",
            isPlaylist = true
        )
        assertEquals("pl_exact_8812", saavnPlaylist.navigationTarget())

        val pipedPlaylist = OnlineAlbumSummary(
            id = "PLpiped_990",
            title = "Midnight Mix",
            artist = "Piped",
            url = "https://piped.video/playlist?list=PLpiped_990",
            providerType = OnlineProviderType.PIPED,
            providerId = "piped",
            isPlaylist = true
        )
        assertEquals("PLpiped_990", pipedPlaylist.navigationTarget())

        val soundcloudPlaylist = OnlineAlbumSummary(
            id = "sc_123",
            title = "SC Set",
            artist = "DJ",
            url = "https://soundcloud.com/dj/sets/late-night",
            providerType = OnlineProviderType.SOUNDCLOUD,
            providerId = "soundcloud",
            isPlaylist = true
        )
        assertEquals("https://soundcloud.com/dj/sets/late-night", soundcloudPlaylist.navigationTarget())
    }

    @Test
    fun playlistDetails_queriesExactPlaylistIdAndTemplatedRoutesAcrossJioSaavnPipedAndInvidious() = runTest {
        val server = MockWebServer()
        server.start()
        val recordedPaths = mutableListOf<String>()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    recordedPaths += path
                    return when {
                        path.startsWith("/playlists?id=pl_alpha") ||
                            path.startsWith("/api/playlists?id=pl_alpha") -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "status": "SUCCESS",
                              "data": {
                                "id": "pl_alpha",
                                "name": "Alpha Drive Mix",
                                "username": "Curator A",
                                "image": [ { "quality": "500x500", "url": "https://img.example/alpha.jpg" } ],
                                "songs": [
                                  {
                                    "id": "s_alpha_1",
                                    "name": "Alpha Track One",
                                    "duration": "205",
                                    "artists": { "primary": [ { "name": "Artist Alpha" } ] }
                                  }
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                        path.startsWith("/playlists?id=pl_beta") ||
                            path.startsWith("/api/playlists?id=pl_beta") -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "status": "SUCCESS",
                              "data": {
                                "id": "pl_beta",
                                "name": "Beta Chill Vibes",
                                "username": "Curator B",
                                "image": [ { "quality": "500x500", "url": "https://img.example/beta.jpg" } ],
                                "songs": [
                                  {
                                    "id": "s_beta_1",
                                    "name": "Beta Track One",
                                    "duration": "190",
                                    "artists": { "primary": [ { "name": "Artist Beta" } ] }
                                  }
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                        path.startsWith("/playlists?browseId=PL_custom_1") -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "status": "SUCCESS",
                              "data": {
                                "id": "PL_custom_1",
                                "name": "Templated Browse Playlist",
                                "songs": [
                                  { "id": "s_tmpl_1", "name": "Templated Song", "duration": "175" }
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                        path == "/playlists/PL_piped_42" -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "name": "Piped Exact 42",
                              "uploader": "Channel 42",
                              "thumbnailUrl": "https://img.example/p42.jpg",
                              "relatedStreams": [
                                { "url": "/watch?v=vid42", "title": "Piped Song 42", "uploaderName": "Artist 42", "duration": 210 }
                              ]
                            }
                            """.trimIndent()
                        )
                        path == "/api/v1/playlists/PL_inv_77" -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "playlistId": "PL_inv_77",
                              "title": "Invidious Exact 77",
                              "author": "Inv Host 77",
                              "videos": [
                                { "videoId": "inv77", "title": "Inv Song 77", "author": "Artist 77", "lengthSeconds": 185 }
                              ]
                            }
                            """.trimIndent()
                        )
                        path.startsWith("/playlists") -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "status": "SUCCESS",
                              "data": {
                                "id": "default_featured",
                                "name": "WRONG DEFAULT FEATURED PLAYLIST",
                                "songs": [ { "id": "wrong_1", "name": "Wrong Default Song", "duration": "180" } ]
                              }
                            }
                            """.trimIndent()
                        )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val client = OkHttpClient()
            val jiosaavn = JioSaavnProvider(client = client, baseUrlsOverride = listOf(baseUrl))

            val alpha = jiosaavn.fetchAlbumOrPlaylist("pl_alpha", isPlaylist = true)
            val beta = jiosaavn.fetchAlbumOrPlaylist("pl_beta", isPlaylist = true)
            assertEquals("pl_alpha", alpha.id)
            assertEquals("Alpha Drive Mix", alpha.title)
            assertEquals("Alpha Track One", alpha.tracks.single().title)
            assertTrue(alpha.isPlaylist)

            assertEquals("pl_beta", beta.id)
            assertEquals("Beta Chill Vibes", beta.title)
            assertEquals("Beta Track One", beta.tracks.single().title)
            assertTrue(beta.isPlaylist)
            assertTrue(recordedPaths.any { it.contains("id=pl_alpha") })
            assertTrue(recordedPaths.any { it.contains("id=pl_beta") })
            assertFalse(recordedPaths.any { it == "/playlists" })

            // Verify templated playlistDetails route with {browseId} is honored when configured
            val context = ApplicationProvider.getApplicationContext<Application>()
            val settingsRepo = SettingsRepository(context)
            val configInterceptor = Interceptor { chain ->
                val req = chain.request()
                if (req.url.toString().contains("cfg.example/playlists.json")) {
                    val body = """
                        {
                          "schemaVersion": 2,
                          "configVersion": "2026.11.26-pl",
                          "providers": [
                            {
                              "id": "jiosaavn",
                              "displayName": "JioSaavn",
                              "adapterType": "jiosaavn",
                              "enabled": true,
                              "priority": 10,
                              "endpoints": [
                                { "url": "$baseUrl", "priority": 1, "enabled": true, "operations": ["search", "metadata", "playback"] }
                              ],
                              "routes": {
                                "playlistDetails": { "method": "GET", "path": "/playlists", "queryParams": { "browseId": "{browseId}" } }
                              }
                            }
                          ]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    chain.proceed(req)
                }
            }
            val cfgClient = OkHttpClient.Builder().addInterceptor(configInterceptor).build()
            val tracker = ProviderHealthTracker()
            val remoteRepo = RemoteConfigRepository(
                settingsRepository = settingsRepo,
                baseClient = cfgClient,
                allowTestLoopback = true
            )
            remoteRepo.refreshNow(customUrl = "https://cfg.example/playlists.json", force = true)
            val templatedSaavn = JioSaavnProvider(
                client = cfgClient,
                healthTracker = tracker,
                dynamicConfigProvider = { remoteRepo.activeConfig.value.providerById("jiosaavn") },
                executorOverride = DynamicHttpExecutor(
                    baseClient = cfgClient,
                    healthTracker = tracker,
                    allowLoopbackForTests = true
                )
            )
            val templatedPage = templatedSaavn.fetchAlbumOrPlaylist("https://music.example/playlist?browseId=PL_custom_1", isPlaylist = true)
            assertEquals("Templated Browse Playlist", templatedPage.title)
            assertEquals("Templated Song", templatedPage.tracks.single().title)
            assertTrue(recordedPaths.any { it == "/playlists?browseId=PL_custom_1" })

            // Verify Piped & Invidious extract exact playlist ID from full playlist URLs
            val piped = PipedProvider(client = client, baseUrlsOverride = listOf(baseUrl))
            val pipedUrlPage = piped.fetchAlbumOrPlaylist("https://piped.video/playlist?list=PL_piped_42", isPlaylist = true)
            assertEquals("Piped Exact 42", pipedUrlPage.title)
            assertEquals("Piped Song 42", pipedUrlPage.tracks.single().title)

            val invidious = InvidiousProvider(client = client, baseUrlsOverride = listOf(baseUrl))
            val invUrlPage = invidious.fetchAlbumOrPlaylist("https://yewtu.be/playlist?list=PL_inv_77", isPlaylist = true)
            assertEquals("Invidious Exact 77", invUrlPage.title)
            assertEquals("Inv Song 77", invUrlPage.tracks.single().title)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun onlineCoordinator_resetsStateOnCollectionChangeAndDropsStaleOutOfOrderResponses() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        val tracker = ProviderHealthTracker()
        val remoteRepo = RemoteConfigRepository(settingsRepository = settingsRepo, baseClient = OkHttpClient())

        val firstGate = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val slowGate = CompletableDeferred<Unit>()
        val fastGate = CompletableDeferred<Unit>()

        val delayedProvider = object : OnlineMusicProvider {
            override val providerType = OnlineProviderType.JIOSAAVN
            override val supportsPagination: Boolean = false
            override suspend fun search(
                query: String,
                filter: OnlineSearchFilter,
                pageToken: String?
            ) = OnlineSearchPage(query = query, providerUsed = providerType)

            override suspend fun suggestions(query: String): List<String> = emptyList()
            override suspend fun trending(): List<OnlineTrack> = emptyList()
            override suspend fun resolveStream(
                track: OnlineTrack,
                speedTier: NetworkSpeedTier
            ): OnlineStreamResolution = throw UnsupportedOperationException()
            override suspend fun fetchRelatedTracks(track: OnlineTrack, limit: Int): List<OnlineTrack> = emptyList()
            override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage =
                throw UnsupportedOperationException()

            override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage =
                fetchAlbumOrPlaylist(albumIdOrUrl, isPlaylist = true)

            override suspend fun fetchAlbumOrPlaylist(
                albumIdOrUrl: String,
                isPlaylist: Boolean
            ): OnlineAlbumPage {
                when (albumIdOrUrl) {
                    "pl_first" -> firstGate.await()
                    "pl_slow" -> {
                        slowStarted.complete(Unit)
                        slowGate.await()
                    }
                    "pl_fast" -> fastGate.await()
                }
                val title = when (albumIdOrUrl) {
                    "pl_first" -> "First Playlist"
                    "pl_slow" -> "Stale Slow Playlist"
                    "pl_fast" -> "Latest Fast Playlist"
                    else -> "Unknown"
                }
                return OnlineAlbumPage(
                    id = albumIdOrUrl,
                    title = title,
                    artist = "Curator",
                    url = "https://example.org/playlist/$albumIdOrUrl",
                    thumbnailUrl = "https://img.example/$albumIdOrUrl.jpg",
                    tracks = listOf(
                        OnlineTrack(
                            id = "jiosaavn:trk_$albumIdOrUrl",
                            sourceId = "trk_$albumIdOrUrl",
                            providerType = OnlineProviderType.JIOSAAVN,
                            sourceUrl = "https://example.org/song/$albumIdOrUrl",
                            title = "Song for $albumIdOrUrl",
                            artist = "Artist"
                        )
                    ),
                    providerType = OnlineProviderType.JIOSAAVN,
                    providerId = "jiosaavn",
                    isPlaylist = isPlaylist
                )
            }
        }

        val manager = OnlineProviderManager(
            providers = mapOf(OnlineProviderType.JIOSAAVN to delayedProvider),
            healthTracker = tracker,
            remoteConfigRepository = remoteRepo
        )
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = OnlineCoordinator(
                app = context,
                scope = coordScope,
                emitMessage = {},
                providerManagerOverride = manager
            )

            // Load first playlist to completion
            coordinator.openAlbum("pl_first", OnlineProviderType.JIOSAAVN, "jiosaavn", isPlaylist = true)
            firstGate.complete(Unit)
            withTimeout(2_000L) {
                while (coordinator.albumDetailState.value.isLoading) {
                    delay(5L)
                }
            }
            assertEquals("First Playlist", coordinator.albumDetailState.value.page?.title)
            assertEquals("pl_first", coordinator.albumDetailState.value.requestedId)

            // Selecting a new playlist immediately clears previous title, artwork, and tracks
            coordinator.prepareAlbumSelection("pl_slow", OnlineProviderType.JIOSAAVN, "jiosaavn", isPlaylist = true)
            val resetState = coordinator.albumDetailState.value
            assertTrue(resetState.isLoading)
            assertNull(resetState.page)
            assertEquals("pl_slow", resetState.requestedId)
            assertTrue(resetState.isPlaylist)

            // Trigger slow request, wait until it enters fetch, then supersede with fast request
            coordinator.openAlbum("pl_slow", OnlineProviderType.JIOSAAVN, "jiosaavn", isPlaylist = true)
            withTimeout(2_000L) { slowStarted.await() }
            coordinator.openAlbum("pl_fast", OnlineProviderType.JIOSAAVN, "jiosaavn", isPlaylist = true)

            // Verify state immediately cleared for pl_fast before completion
            assertTrue(coordinator.albumDetailState.value.isLoading)
            assertNull(coordinator.albumDetailState.value.page)
            assertEquals("pl_fast", coordinator.albumDetailState.value.requestedId)

            // Complete pl_fast first, then let stale pl_slow resolve later
            fastGate.complete(Unit)
            withTimeout(2_000L) {
                while (coordinator.albumDetailState.value.isLoading) {
                    delay(5L)
                }
            }
            slowGate.complete(Unit)
            delay(30L)

            val finalState = coordinator.albumDetailState.value
            assertFalse(finalState.isLoading)
            assertEquals("pl_fast", finalState.requestedId)
            assertEquals("pl_fast", finalState.page?.id)
            assertEquals("Latest Fast Playlist", finalState.page?.title)
            assertEquals("Song for pl_fast", finalState.page?.tracks?.single()?.title)
        } finally {
            coordScope.cancel()
        }
    }
}
