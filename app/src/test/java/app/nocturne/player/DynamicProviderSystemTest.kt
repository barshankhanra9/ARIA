package app.nocturne.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.data.online.OnlineProviderManager
import app.nocturne.player.data.online.ProviderHealthTracker
import app.nocturne.player.data.online.RemoteConfigRepository
import app.nocturne.player.data.online.RemoteConfigValidator
import app.nocturne.player.data.prefs.SettingsRepository
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DynamicProviderSystemTest {

    @Test
    fun githubV2Config_etag304_cacheAndLegacyUrlMigrationWork() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        settingsRepo.updateSettings {
            it.copy(remoteProviderConfigUrl = UserSettings.LEGACY_REMOTE_CONFIG_URL)
        }
        // Verify getSettingsOnce auto-migrates legacy URL -> new barshankhanra9 GitHub URL
        val resolvedSettings = settingsRepo.getSettingsOnce()
        assertEquals(UserSettings.DEFAULT_REMOTE_CONFIG_URL, resolvedSettings.remoteProviderConfigUrl)

        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val v2Json = """
                {
                  "schemaVersion": 2,
                  "configVersion": "2026.11.23-1",
                  "refreshIntervalSeconds": 1800,
                  "providers": [
                    {
                      "id": "jiosaavn",
                      "displayName": "JioSaavn",
                      "adapterType": "jiosaavn",
                      "enabled": true,
                      "priority": 1,
                      "endpoints": [
                        { "url": "$baseUrl/saavn", "priority": 1, "enabled": true, "operations": ["search", "metadata", "playback"] }
                      ]
                    },
                    {
                      "id": "audius",
                      "displayName": "Audius",
                      "adapterType": "audius",
                      "enabled": true,
                      "priority": 2,
                      "endpoints": [
                        { "url": "$baseUrl/audius", "priority": 1, "enabled": true, "operations": ["search", "metadata", "playback"] }
                      ]
                    }
                  ],
                  "lyricsProviders": [
                    {
                      "id": "lrclib",
                      "displayName": "LRCLIB",
                      "enabled": true,
                      "priority": 1,
                      "endpoints": [
                        { "url": "$baseUrl/lrclib", "priority": 1, "enabled": true }
                      ]
                    }
                  ]
                }
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", "\"etag-v1\"")
                    .addHeader("Last-Modified", "Mon, 23 Nov 2026 00:00:00 GMT")
                    .setBody(v2Json)
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(304)
                    .addHeader("ETag", "\"etag-v1\"")
            )

            val repo = RemoteConfigRepository(
                settingsRepository = settingsRepo,
                baseClient = OkHttpClient(),
                allowTestLoopback = true
            )
            val url = "$baseUrl/providers.json"

            // First fetch populates config, ETag, and status
            val first = repo.refreshNow(customUrl = url, force = true)
            assertEquals(2, first.schemaVersion)
            assertEquals("2026.11.23-1", first.configVersion)
            assertEquals(2, first.providers.size)
            assertEquals(listOf("$baseUrl/saavn"), first.jiosaavn)
            assertEquals(listOf("$baseUrl/audius"), first.audius)
            assertEquals(listOf("$baseUrl/lrclib"), first.lrclib)
            assertEquals("\"etag-v1\"", repo.status.value.etag)
            assertFalse(repo.status.value.wasNotModified304)

            val req1 = server.takeRequest()
            assertEquals("/providers.json", req1.path)

            // Conditional fetch without customUrl uses stored ETag & handles HTTP 304 without re-parsing
            settingsRepo.updateSettings { it.copy(remoteProviderConfigUrl = url) }
            val after304 = repo.refreshNow(customUrl = null, force = true)
            assertEquals("2026.11.23-1", after304.configVersion)
            assertTrue(repo.status.value.wasNotModified304)
            assertTrue(repo.status.value.isFromCache)
            assertNull(repo.lastFetchError.value)

            val req2 = server.takeRequest()
            assertEquals("\"etag-v1\"", req2.getHeader("If-None-Match"))

            // Offline fallback when server shuts down preserves cached config
            server.shutdown()
            val offlineCfg = repo.refreshNow(customUrl = url, force = true)
            assertEquals("2026.11.23-1", offlineCfg.configVersion)
            assertNotNull(repo.lastFetchError.value)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun invalidOrUnsafeConfigsRejectedWithoutCorruptingWorkingConfig_andRollbackWorks() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val validV1 = """
                {
                  "schemaVersion": 2,
                  "configVersion": "cfg-v1",
                  "providers": [
                    {
                      "id": "jiosaavn",
                      "displayName": "JioSaavn",
                      "adapterType": "jiosaavn",
                      "enabled": true,
                      "priority": 1,
                      "endpoints": [{ "url": "$baseUrl/s1", "priority": 1, "enabled": true }]
                    }
                  ]
                }
            """.trimIndent()
            val validV2 = """
                {
                  "schemaVersion": 2,
                  "configVersion": "cfg-v2",
                  "providers": [
                    {
                      "id": "audius",
                      "displayName": "Audius",
                      "adapterType": "audius",
                      "enabled": true,
                      "priority": 1,
                      "endpoints": [{ "url": "$baseUrl/a2", "priority": 1, "enabled": true }]
                    }
                  ]
                }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(validV1))
            server.enqueue(MockResponse().setResponseCode(200).setBody("not valid json {{{"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"schemaVersion": 99, "configVersion": "bad", "providers": []}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"schemaVersion": 2, "configVersion": "empty", "providers": []}"""))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "schemaVersion": 2,
                      "configVersion": "ssrf",
                      "providers": [
                        {
                          "id": "evil",
                          "adapterType": "generic_rest",
                          "enabled": true,
                          "endpoints": [
                            { "url": "http://insecure.example/api" },
                            { "url": "https://169.254.169.254/latest/meta-data" },
                            { "url": "https://10.0.0.5/internal" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                )
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(validV2))

            val repo = RemoteConfigRepository(
                settingsRepository = settingsRepo,
                baseClient = OkHttpClient(),
                allowTestLoopback = true
            )
            val url = "$baseUrl/providers.json"

            // 1. Sync valid v1
            val c1 = repo.refreshNow(customUrl = url, force = true)
            assertEquals("cfg-v1", c1.configVersion)

            // 2. Malformed JSON, unsupported schemaVersion, empty providers, SSRF-only endpoints all keep cfg-v1 intact
            repeat(4) {
                val res = repo.refreshNow(customUrl = url, force = true)
                assertEquals("cfg-v1", res.configVersion)
                assertNotNull(repo.lastFetchError.value)
                assertEquals(listOf("$baseUrl/s1"), res.jiosaavn)
            }

            // 3. Direct validator checks in production mode (allowLoopback = false)
            var badSchemaFailed = false
            try {
                RemoteConfigValidator.parseAndValidate("""{"schemaVersion": 0, "providers": []}""", allowLoopback = false)
            } catch (_: IOException) {
                badSchemaFailed = true
            }
            assertTrue(badSchemaFailed)

            var unsafeUrlFailed = false
            try {
                RemoteConfigValidator.parseAndValidate(
                    """
                    {
                      "schemaVersion": 2,
                      "providers": [{
                        "id": "local",
                        "adapterType": "generic_rest",
                        "endpoints": [{ "url": "https://127.0.0.1/api" }]
                      }]
                    }
                    """.trimIndent(),
                    allowLoopback = false
                )
            } catch (_: IOException) {
                unsafeUrlFailed = true
            }
            assertTrue(unsafeUrlFailed)

            // 4. Sync valid v2, then rollback restores cfg-v1
            val c2 = repo.refreshNow(customUrl = url, force = true)
            assertEquals("cfg-v2", c2.configVersion)
            assertTrue(repo.status.value.canRollback)

            val rolledBack = repo.rollbackToPreviousConfig()
            assertNotNull(rolledBack)
            assertEquals("cfg-v1", rolledBack!!.configVersion)
            assertEquals("cfg-v1", repo.activeConfig.value.configVersion)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun dynamicGenericRestProvider_supportsPostTemplatesNestedPathsPaginationAndDynamicLifecycle() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v2/search") -> {
                        // Verify POST method, custom headers, and substituted body template
                        if (request.method != "POST" || request.getHeader("X-Aria-Client") != "android-dynamic") {
                            return MockResponse().setResponseCode(400).setBody("""{"error":"missing headers/method"}""")
                        }
                        val reqBody = request.body.readUtf8()
                        val isPage2 = reqBody.contains("\"cursor\":\"cur_2\"")
                        if (isPage2) {
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "payload": {
                                    "items": [
                                      {
                                        "track": {
                                          "uid": "dyn_2",
                                          "meta": { "heading": "Paper Waltz", "creator": { "id": "art_9", "display": "Quill Band" } },
                                          "collection": { "title": "Ink Album", "link": "alb_9" },
                                          "lenSec": 195,
                                          "cover": { "hd": "https://cdn.example.com/dyn2.jpg" }
                                        }
                                      }
                                    ],
                                    "paging": { "next": "" }
                                  }
                                }
                                """.trimIndent()
                            )
                        } else {
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "payload": {
                                    "items": [
                                      {
                                        "track": {
                                          "uid": "dyn_1",
                                          "meta": { "heading": "Nocturne Echo", "creator": { "id": "art_9", "display": "Quill Band" } },
                                          "collection": { "title": "Ink Album", "link": "alb_9" },
                                          "lenSec": 210,
                                          "cover": { "hd": "https://cdn.example.com/dyn1.jpg" }
                                        }
                                      }
                                    ],
                                    "paging": { "next": "cur_2" }
                                  }
                                }
                                """.trimIndent()
                            )
                        }
                    }
                    path.startsWith("/api/v2/artist/art_9") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "artist": {
                            "id": "art_9",
                            "name": "Quill Band",
                            "followers": "42K listeners",
                            "tracks": [
                              {
                                "track": {
                                  "uid": "dyn_1",
                                  "meta": { "heading": "Nocturne Echo", "creator": { "id": "art_9", "display": "Quill Band" } },
                                  "lenSec": 210
                                }
                              }
                            ],
                            "albums": [
                              { "id": "alb_9", "name": "Ink Album", "artist": "Quill Band" }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/api/v2/album/alb_9") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "album": {
                            "id": "alb_9",
                            "title": "Ink Album",
                            "artist": "Quill Band",
                            "tracks": [
                              {
                                "track": {
                                  "uid": "dyn_1",
                                  "meta": { "heading": "Nocturne Echo", "creator": { "id": "art_9", "display": "Quill Band" } },
                                  "lenSec": 210
                                }
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/api/v2/stream") -> {
                        val base = server.url("").toString().trimEnd('/')
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "playback": {
                                "sources": [
                                  { "href": "$base/cdn/dyn_1_128.m4a", "kbps": 128, "mime": "audio/mp4", "codec": "aac" },
                                  { "href": "$base/cdn/dyn_1_320.m4a", "kbps": 320, "mime": "audio/mp4", "codec": "aac" }
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
                }
            }
        }
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val initialConfigJson = """
                {
                  "schemaVersion": 2,
                  "configVersion": "dyn-1",
                  "providers": [
                    {
                      "id": "disabled_legacy",
                      "displayName": "Disabled",
                      "adapterType": "generic_rest",
                      "enabled": false,
                      "priority": 1,
                      "endpoints": [{ "url": "$baseUrl", "priority": 1, "enabled": true }]
                    },
                    {
                      "id": "quill_music",
                      "displayName": "Quill Music",
                      "adapterType": "generic_rest",
                      "enabled": true,
                      "priority": 2,
                      "capabilities": ["search", "artist", "album", "stream"],
                      "headers": { "X-Aria-Client": "android-dynamic" },
                      "endpoints": [
                        { "url": "$baseUrl", "priority": 1, "enabled": true, "operations": ["search", "metadata", "playback"] }
                      ],
                      "routes": {
                        "searchAll": {
                          "path": "/api/v2/search",
                          "method": "POST",
                          "bodyTemplate": "{\"q\":\"{query}\",\"cursor\":\"{pageToken}\"}",
                          "resultsPath": "payload.items",
                          "pagination": {
                            "type": "cursor",
                            "nextTokenPath": "payload.paging.next"
                          }
                        },
                        "artistDetails": {
                          "path": "/api/v2/artist/{id}",
                          "method": "GET"
                        },
                        "albumDetails": {
                          "path": "/api/v2/album/{id}",
                          "method": "GET"
                        },
                        "resolveStream": {
                          "path": "/api/v2/stream",
                          "method": "GET",
                          "queryParams": { "trackId": "{id}" }
                        }
                      },
                      "mapping": {
                        "track": {
                          "idPath": "track.uid",
                          "titlePath": "track.meta.heading",
                          "artistPath": "track.meta.creator.display",
                          "artistIdPath": "track.meta.creator.id",
                          "albumPath": "track.collection.title",
                          "albumUrlPath": "track.collection.link",
                          "durationPath": "track.lenSec",
                          "durationUnit": "seconds",
                          "thumbnailPath": "track.cover.hd"
                        },
                        "artist": {
                          "idPath": "artist.id",
                          "namePath": "artist.name",
                          "subscriberPath": "artist.followers",
                          "tracksPath": "artist.tracks",
                          "albumsPath": "artist.albums"
                        },
                        "album": {
                          "idPath": "album.id",
                          "titlePath": "album.title",
                          "artistPath": "album.artist",
                          "tracksPath": "album.tracks"
                        },
                        "streamResolution": {
                          "streamsArrayPath": "playback.sources",
                          "streamItemUrlPath": "href",
                          "streamItemBitratePath": "kbps",
                          "streamItemBitrateUnit": "kbps",
                          "streamItemMimePath": "mime",
                          "streamItemCodecPath": "codec"
                        }
                      }
                    }
                  ]
                }
            """.trimIndent()

            val removedConfigJson = """
                {
                  "schemaVersion": 2,
                  "configVersion": "dyn-2",
                  "providers": [
                    {
                      "id": "quill_music",
                      "displayName": "Quill Music",
                      "adapterType": "generic_rest",
                      "enabled": false,
                      "priority": 2,
                      "endpoints": [{ "url": "$baseUrl", "priority": 1, "enabled": true }]
                    }
                  ]
                }
            """.trimIndent()

            val repo = RemoteConfigRepository(settingsRepo, OkHttpClient(), allowTestLoopback = true)
            val manager = OnlineProviderManager(
                remoteConfigRepository = repo,
                httpClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
                allowLoopbackForTests = true
            )

            // Fetch config through MockWebServer /providers.json (actually dispatcher handles non-api routes? Wait - dispatcher had else -> 404!)
            // Switch to Repo using direct parse or update dispatcher; simpler: let dispatcher stay clean and feed repo via another MockWebServer
            val cfgServer = MockWebServer()
            cfgServer.start()
            try {
                cfgServer.enqueue(MockResponse().setResponseCode(200).setBody(initialConfigJson))
                cfgServer.enqueue(MockResponse().setResponseCode(200).setBody(removedConfigJson))
                val cfgUrl = cfgServer.url("/providers.json").toString()

                repo.refreshNow(customUrl = cfgUrl, force = true)

                // Disabled provider is surfaced as disabled in diagnostics; only quill_music is enabled
                val diags = manager.getProviderDiagnostics()
                assertEquals(listOf("quill_music"), diags.filter { it.enabled }.map { it.providerId })
                assertFalse(diags.first { it.providerId == "disabled_legacy" }.enabled)

                // Page 1 search (POST + custom header + nested JSON path)
                val page1 = manager.search("nocturne")
                assertEquals("quill_music", page1.providerUsedId)
                assertEquals("Quill Music", page1.providerUsedLabel)
                assertEquals(1, page1.tracks.size)
                assertEquals("dyn_1", page1.tracks[0].sourceId)
                assertEquals("Nocturne Echo", page1.tracks[0].title)
                assertEquals("Quill Band", page1.tracks[0].artist)
                assertEquals(210_000L, page1.tracks[0].durationMs)
                assertTrue(page1.hasMore)
                assertEquals("cur_2", page1.nextPageToken)

                // Page 2 cursor pagination
                val page2 = manager.search("nocturne", OnlineSearchFilter.ALL, page1.nextPageToken, preferredProviderId = "quill_music")
                assertEquals(1, page2.tracks.size)
                assertEquals("dyn_2", page2.tracks[0].sourceId)
                assertFalse(page2.hasMore)

                // Artist & Album detail pages
                val artistPage = manager.fetchArtist("art_9", OnlineProviderType.DYNAMIC, hintProviderId = "quill_music")
                assertEquals("Quill Band", artistPage.name)
                assertEquals("Quill Music", artistPage.providerLabel)
                assertEquals(1, artistPage.tracks.size)
                assertEquals(1, artistPage.playlists.size)

                val albumPage = manager.fetchAlbumOrPlaylist("alb_9", OnlineProviderType.DYNAMIC, hintProviderId = "quill_music")
                assertEquals("Ink Album", albumPage.title)
                assertEquals(1, albumPage.tracks.size)

                // Stream resolution through manager (STANDARD tier picks 128k sweet-spot, and verify providerUsedId/url)
                val streamRes = manager.resolveStream(page1.tracks[0], forceRefresh = true)
                assertEquals("quill_music", streamRes.resolvedByProviderId)
                assertEquals(128, streamRes.selectedStream.bitrateKbps)
                assertTrue(streamRes.selectedStream.url.endsWith("/cdn/dyn_1_128.m4a"))

                // Updating GitHub config to disable provider dynamically removes active adapter
                repo.refreshNow(customUrl = cfgUrl, force = true)
                assertTrue(manager.getProviderDiagnostics().none { it.enabled })
            } finally {
                cfgServer.shutdown()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun operationScopedHealthStickyEndpointsFailoverStreamProbeAndSanitizedErrors() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        val nowClock = longArrayOf(100_000L)
        val tracker = ProviderHealthTracker(
            clockMs = { nowClock[0] },
            failureThreshold = 2,
            baseCooldownMs = 30_000L,
            maxCooldownMs = 120_000L
        )

        val badEp = "https://bad.example"
        val goodEp = "https://good.example"

        // Recording endpoint failure on SEARCH excludes badEp for SEARCH, while PLAYBACK keeps badEp first!
        tracker.recordEndpointFailure("p1", OperationType.SEARCH, badEp, "HTTP 500 Bearer super_secret_token_123", endpointFailureThreshold = 1, customCooldownMs = 30_000L)
        assertEquals(
            listOf(goodEp),
            tracker.selectOrderedEndpoints("p1", OperationType.SEARCH, configuredEndpoints = listOf(badEp, goodEp))
        )
        assertEquals(
            listOf(badEp, goodEp),
            tracker.selectOrderedEndpoints("p1", OperationType.PLAYBACK, configuredEndpoints = listOf(badEp, goodEp))
        )

        // Session-sticky last successful endpoint jumps to priority #1 for that operation
        val altEp = "https://alt.example"
        tracker.recordEndpointSuccess("p1", OperationType.PLAYBACK, goodEp, 120L)
        val ordered = tracker.selectOrderedEndpoints(
            providerId = "p1",
            operation = OperationType.PLAYBACK,
            configuredEndpoints = listOf(altEp, goodEp)
        )
        assertEquals(goodEp, ordered.first())

        // Diagnostics error sanitization strips leaked tokens/credentials
        val sanitized = InputAndUrlSecurity.sanitizeErrorForDiagnostics("Failed Authorization: Bearer abcdef123456 with api_key=XYZ999")
        assertNotNull(sanitized)
        assertFalse(sanitized!!.contains("abcdef123456"))
        assertFalse(sanitized.contains("XYZ999"))

        // End-to-end failover across bad HTTP 503 endpoint, invalid HTML endpoint, and broken stream probe -> healthy endpoint
        val apiServer = MockWebServer()
        val cfgServer = MockWebServer()
        apiServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/ep1/") -> MockResponse().setResponseCode(503).setBody("overloaded")
                    path.startsWith("/ep2/") -> MockResponse().setResponseCode(200).setBody("<html>not json</html>")
                    path.startsWith("/ep3/search") -> {
                        val base = apiServer.url("").toString().trimEnd('/')
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "items": [
                                {
                                  "id": "trk_probe",
                                  "title": "Failover Etude",
                                  "artist": "Test Ensemble",
                                  "duration": 180,
                                  "streamHref": "$base/broken_stream.m4a"
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                    path.startsWith("/ep3/stream") -> {
                        val base = apiServer.url("").toString().trimEnd('/')
                        MockResponse().setResponseCode(200).setBody(
                            """{ "stream": { "url": "$base/ep3/ok_stream.m4a" } }"""
                        )
                    }
                    path.startsWith("/ep3/ok_stream.m4a") -> {
                        if (request.method.equals("HEAD", ignoreCase = true)) {
                            MockResponse().setResponseCode(200)
                        } else {
                            MockResponse().setResponseCode(200).setBody("AUDIOBYTES")
                        }
                    }
                    path.startsWith("/broken_stream.m4a") -> MockResponse().setResponseCode(403)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        apiServer.start()
        cfgServer.start()
        try {
            val apiBase = apiServer.url("").toString().trimEnd('/')
            val cfgJson = """
                {
                  "schemaVersion": 2,
                  "configVersion": "failover-1",
                  "providers": [
                    {
                      "id": "primary_dyn",
                      "displayName": "Primary Dyn",
                      "adapterType": "generic_rest",
                      "enabled": true,
                      "priority": 1,
                      "verifyStreamReachable": true,
                      "maxRetries": 0,
                      "endpoints": [
                        { "url": "$apiBase/ep1", "priority": 1, "enabled": true },
                        { "url": "$apiBase/ep2", "priority": 2, "enabled": true },
                        { "url": "$apiBase/ep3", "priority": 3, "enabled": true }
                      ],
                      "routes": {
                        "searchAll": { "path": "/search", "queryParams": { "q": "{query}" }, "resultsPath": "items" },
                        "resolveStream": { "path": "/stream", "queryParams": { "id": "{id}" } }
                      },
                      "mapping": {
                        "track": {
                          "idPath": "id",
                          "titlePath": "title",
                          "artistPath": "artist",
                          "durationPath": "duration",
                          "streamUrlPath": "streamHref"
                        },
                        "streamResolution": {
                          "streamUrlPath": "stream.url"
                        }
                      }
                    }
                  ]
                }
            """.trimIndent()

            cfgServer.enqueue(MockResponse().setResponseCode(200).setBody(cfgJson))
            val repo = RemoteConfigRepository(settingsRepo, OkHttpClient(), allowTestLoopback = true)
            repo.refreshNow(customUrl = cfgServer.url("/providers.json").toString(), force = true)

            val manager = OnlineProviderManager(
                remoteConfigRepository = repo,
                httpClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
                allowLoopbackForTests = true,
                healthTracker = tracker
            )

            // Search fails on ep1 (503) and ep2 (invalid HTML JSON) -> lands on ep3!
            val page = manager.search("etude")
            assertEquals("primary_dyn", page.providerUsedId)
            assertEquals(1, page.tracks.size)
            assertEquals("$apiBase/ep3", tracker.activeEndpointFor("primary_dyn", OperationType.SEARCH))

            // Even though track carries inline streamHref (/broken_stream.m4a returns 403), verifyStreamReachable fails probe & resolves /ep3/stream -> ok_stream.m4a!
            val resolved = manager.resolveStream(page.tracks[0], forceRefresh = true)
            assertEquals("$apiBase/ep3/ok_stream.m4a", resolved.selectedStream.url)

            // Connectivity test succeeds on ep3
            val testResult = manager.testProviderConnectivity("primary_dyn")
            assertTrue("Connectivity failed: ${testResult.errorSummary} / ${testResult.details}", testResult.success)
            assertEquals("$apiBase/ep3", testResult.testedEndpoint)
        } finally {
            apiServer.shutdown()
            cfgServer.shutdown()
        }
    }

    @Test
    fun validatorParsesObjectMapAndCategorySchemasWithoutZeroProviderBug() {
        val objectMapJson = """
            {
              "schemaVersion": 2,
              "configVersion": "2026.11.23-map",
              "providers": {
                "jiosaavn": ["https://saavn.example.org/api"],
                "piped": ["https://pipedapi.example.org"],
                "youtube": true,
                "lrclib": ["https://lrclib.net"]
              }
            }
        """.trimIndent()

        val parsedMap = RemoteConfigValidator.parseAndValidate(objectMapJson, allowLoopback = false)
        assertEquals(3, parsedMap.providers.size)
        assertEquals(3, parsedMap.enabledProviders().size)
        assertEquals(listOf("https://saavn.example.org/api"), parsedMap.jiosaavn)
        assertEquals(listOf("https://pipedapi.example.org"), parsedMap.piped)
        assertTrue(parsedMap.providers.any { it.id == "youtube" && it.adapterType == "newpipe" && it.enabled })
        assertEquals(listOf("https://lrclib.net"), parsedMap.lrclib)

        val categorizedJson = """
            {
              "schemaVersion": 2,
              "version": "2026.11.23-cats",
              "providers": {
                "Audio Streaming": [
                  {
                    "id": "jiosaavn",
                    "name": "JioSaavn",
                    "type": "jiosaavn",
                    "endpoints": ["https://saavn.example.org"]
                  }
                ],
                "Video Mirrors": {
                  "invidious": {
                    "enabled": false,
                    "instances": ["https://invidious.example.org"]
                  },
                  "audius": {
                    "categories": ["Indie Electronic"],
                    "urls": ["https://audius.example.org"]
                  }
                }
              }
            }
        """.trimIndent()

        val parsedCats = RemoteConfigValidator.parseAndValidate(categorizedJson, allowLoopback = false)
        assertEquals("2026.11.23-cats", parsedCats.configVersion)
        assertEquals(3, parsedCats.providers.size)
        assertEquals(2, parsedCats.enabledProviders().size)
        val categories = parsedCats.distinctCategories()
        assertTrue(categories.contains("Audio Streaming"))
        assertTrue(categories.contains("Indie Electronic"))
        assertTrue(categories.contains("Video Mirrors"))
    }

    @Test
    fun testConnectionAndSaveUrlClassifyErrorsPreserveLastWorkingConfigAndPersistAcrossRestart() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val workingUrl = "$baseUrl/barshan/aria-providers/refs/heads/main/providers.json"
            val updatedUrl = "$baseUrl/custom-org/custom-repo/main/custom_providers.json"

            val workingJson = """
                {
                  "schemaVersion": 2,
                  "configVersion": "cfg-working-1",
                  "providers": [
                    {
                      "id": "jiosaavn",
                      "displayName": "JioSaavn",
                      "adapterType": "jiosaavn",
                      "enabled": true,
                      "priority": 1,
                      "categories": ["Audio Streaming"],
                      "endpoints": [{ "url": "$baseUrl/saavn", "priority": 1, "enabled": true }]
                    },
                    {
                      "id": "audius",
                      "displayName": "Audius",
                      "adapterType": "audius",
                      "enabled": false,
                      "priority": 2,
                      "categories": ["Indie"],
                      "endpoints": [{ "url": "$baseUrl/audius", "priority": 1, "enabled": true }]
                    }
                  ],
                  "lyricsProviders": [
                    {
                      "id": "lrclib",
                      "enabled": true,
                      "endpoints": [{ "url": "$baseUrl/lrclib", "priority": 1, "enabled": true }]
                    }
                  ]
                }
            """.trimIndent()

            val updatedJson = """
                {
                  "schemaVersion": 2,
                  "configVersion": "cfg-custom-2",
                  "providers": [
                    {
                      "id": "piped",
                      "displayName": "Piped Mirror",
                      "adapterType": "piped",
                      "enabled": true,
                      "priority": 1,
                      "categories": ["Video & Audio"],
                      "endpoints": [{ "url": "$baseUrl/piped", "priority": 1, "enabled": true }]
                    }
                  ]
                }
            """.trimIndent()

            val repo = RemoteConfigRepository(
                settingsRepository = settingsRepo,
                baseClient = OkHttpClient(),
                allowTestLoopback = true
            )

            // 1. Save valid initial working URL
            server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"etag-w1\"").setBody(workingJson))
            val saveOk = repo.saveAndActivateUrl(workingUrl)
            assertTrue(saveOk is app.nocturne.player.data.online.ConfigOperationResult.Success)
            assertEquals(workingUrl, settingsRepo.getSettingsOnce().remoteProviderConfigUrl)
            assertEquals("cfg-working-1", repo.activeConfig.value.configVersion)
            assertEquals(2, repo.status.value.providerCount)
            assertEquals(1, repo.status.value.enabledProviderCount)
            assertEquals(1, repo.status.value.lyricsEndpointCount)
            assertTrue(repo.status.value.categories.contains("Audio Streaming"))

            // 2. URL Error (github.com blob page / non-HTTPS) -> fails with URL_ERROR & preserves workingUrl
            val githubBlobResult = repo.saveAndActivateUrl("https://github.com/barshankhanra9/aria-providers/blob/main/providers.json")
            assertTrue(githubBlobResult is app.nocturne.player.data.online.ConfigOperationResult.Failure)
            assertEquals(
                app.nocturne.player.data.model.RemoteConfigErrorType.URL_ERROR,
                (githubBlobResult as app.nocturne.player.data.online.ConfigOperationResult.Failure).errorType
            )
            assertEquals(workingUrl, settingsRepo.getSettingsOnce().remoteProviderConfigUrl)
            assertEquals("cfg-working-1", repo.activeConfig.value.configVersion)

            // 3. Network Error (HTTP 404) -> fails with NETWORK_ERROR & preserves workingUrl
            server.enqueue(MockResponse().setResponseCode(404).setBody("404: Not Found"))
            val notFoundUrl = "$baseUrl/missing/providers.json"
            val netFail = repo.saveAndActivateUrl(notFoundUrl)
            assertTrue(netFail is app.nocturne.player.data.online.ConfigOperationResult.Failure)
            assertEquals(
                app.nocturne.player.data.model.RemoteConfigErrorType.NETWORK_ERROR,
                (netFail as app.nocturne.player.data.online.ConfigOperationResult.Failure).errorType
            )
            assertEquals(workingUrl, settingsRepo.getSettingsOnce().remoteProviderConfigUrl)
            assertEquals("cfg-working-1", repo.activeConfig.value.configVersion)

            // 4. JSON Parse Error (HTML / malformed JSON) -> fails with JSON_PARSE_ERROR & preserves workingUrl
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Error</body></html>"))
            val jsonFail = repo.saveAndActivateUrl("$baseUrl/bad-json.json")
            assertTrue(jsonFail is app.nocturne.player.data.online.ConfigOperationResult.Failure)
            assertEquals(
                app.nocturne.player.data.model.RemoteConfigErrorType.JSON_PARSE_ERROR,
                (jsonFail as app.nocturne.player.data.online.ConfigOperationResult.Failure).errorType
            )
            assertEquals(workingUrl, settingsRepo.getSettingsOnce().remoteProviderConfigUrl)

            // 5. Unsupported Schema Error (schemaVersion 99 / empty providers) -> fails with UNSUPPORTED_SCHEMA_ERROR & preserves workingUrl
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"schemaVersion": 99, "providers": []}"""))
            val schemaFail = repo.saveAndActivateUrl("$baseUrl/bad-schema.json")
            assertTrue(schemaFail is app.nocturne.player.data.online.ConfigOperationResult.Failure)
            assertEquals(
                app.nocturne.player.data.model.RemoteConfigErrorType.UNSUPPORTED_SCHEMA_ERROR,
                (schemaFail as app.nocturne.player.data.online.ConfigOperationResult.Failure).errorType
            )
            assertEquals(workingUrl, settingsRepo.getSettingsOnce().remoteProviderConfigUrl)

            // 6. Test Connection with valid updated URL succeeds WITHOUT mutating active config yet
            server.enqueue(MockResponse().setResponseCode(200).setBody(updatedJson))
            val testOk = repo.testConnection(updatedUrl)
            assertTrue(testOk is app.nocturne.player.data.online.ConfigOperationResult.Success)
            assertEquals("cfg-custom-2", (testOk as app.nocturne.player.data.online.ConfigOperationResult.Success).config.configVersion)
            assertEquals(workingUrl, settingsRepo.getSettingsOnce().remoteProviderConfigUrl)
            assertEquals("cfg-working-1", repo.activeConfig.value.configVersion)

            // 7. Save URL activates new URL & persists across a new RemoteConfigRepository instance
            server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"etag-c2\"").setBody(updatedJson))
            val saveCustomOk = repo.saveAndActivateUrl(updatedUrl)
            assertTrue(saveCustomOk is app.nocturne.player.data.online.ConfigOperationResult.Success)
            assertEquals(updatedUrl, settingsRepo.getSettingsOnce().remoteProviderConfigUrl)
            assertEquals("cfg-custom-2", repo.activeConfig.value.configVersion)

            // Verify refreshNow() (customUrl = null) uses the newly persisted URL and NOT old ETag
            server.enqueue(MockResponse().setResponseCode(304).addHeader("ETag", "\"etag-c2\""))
            val restartedRepo = RemoteConfigRepository(
                settingsRepository = settingsRepo,
                baseClient = OkHttpClient(),
                allowTestLoopback = true
            )
            restartedRepo.refreshNow(customUrl = null, force = true)
            assertEquals(updatedUrl, restartedRepo.status.value.sourceUrl)
            assertEquals("cfg-custom-2", restartedRepo.activeConfig.value.configVersion)
            // Drain requests to verify last request targeted updatedUrl with new ETag
            var lastReqPath: String? = null
            var lastIfNoneMatch: String? = null
            while (true) {
                val r = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
                lastReqPath = r.path
                lastIfNoneMatch = r.getHeader("If-None-Match")
            }
            assertEquals("/custom-org/custom-repo/main/custom_providers.json", lastReqPath)
            assertEquals("\"etag-c2\"", lastIfNoneMatch)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun sourceMetadataHelperParsesGithubAndCustomUrlsDynamically() {
        val refsUrlMeta = app.nocturne.player.data.model.RemoteConfigUrlHelper.parseSourceMetadata(
            "https://raw.githubusercontent.com/alice/my-music-providers/refs/heads/develop/custom.json"
        )
        assertEquals("alice/my-music-providers", refsUrlMeta.repositoryDisplay)
        assertEquals("custom.json", refsUrlMeta.fileName)
        assertEquals("develop", refsUrlMeta.branchOrRef)

        val shortBranchMeta = app.nocturne.player.data.model.RemoteConfigUrlHelper.parseSourceMetadata(
            "https://raw.githubusercontent.com/bob/aria-mirrors/main/providers_v2.json"
        )
        assertEquals("bob/aria-mirrors", shortBranchMeta.repositoryDisplay)
        assertEquals("providers_v2.json", shortBranchMeta.fileName)
        assertEquals("main", shortBranchMeta.branchOrRef)

        val customHostMeta = app.nocturne.player.data.model.RemoteConfigUrlHelper.parseSourceMetadata(
            "https://cdn.example.org/configs/audio.json"
        )
        assertEquals("cdn.example.org/configs", customHostMeta.repositoryDisplay)
        assertEquals("audio.json", customHostMeta.fileName)
        assertNull(customHostMeta.branchOrRef)
    }
}
