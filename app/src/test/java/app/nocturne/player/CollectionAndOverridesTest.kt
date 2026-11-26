package app.nocturne.player

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nocturne.player.data.audio.LyricsRepository
import app.nocturne.player.data.audio.MetadataCleaner
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.online.AudiusProvider
import app.nocturne.player.data.online.InvidiousProvider
import app.nocturne.player.data.online.JioSaavnProvider
import app.nocturne.player.data.online.OnlineProviderManager
import app.nocturne.player.data.online.PipedProvider
import app.nocturne.player.data.online.ProviderHealthTracker
import app.nocturne.player.data.online.RemoteConfigRepository
import app.nocturne.player.data.prefs.SettingsRepository
import kotlinx.coroutines.test.runTest
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
}
