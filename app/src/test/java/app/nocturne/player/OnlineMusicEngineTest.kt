package app.nocturne.player

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.nocturne.player.data.audio.LyricsManager
import app.nocturne.player.data.audio.LyricsRepository
import app.nocturne.player.data.audio.MetadataCleaner
import app.nocturne.player.data.audio.lyrics.JioSaavnLyricsAdapter
import app.nocturne.player.data.audio.lyrics.LiriQoLyricsAdapter
import app.nocturne.player.data.audio.lyrics.LrcLibLyricsAdapter
import app.nocturne.player.data.audio.lyrics.LyricsQuery
import app.nocturne.player.data.audio.lyrics.MusixmatchLyricsAdapter
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.model.DynamicLyricsProviderConfig
import app.nocturne.player.data.model.LyricsCleaningConfig
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAlbumPage
import app.nocturne.player.data.model.OnlineArtistPage
import app.nocturne.player.data.model.OnlineArtistSummary
import app.nocturne.player.data.model.OnlineAudioStream
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineSearchPage
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.online.AudiusProvider
import app.nocturne.player.data.online.InvidiousProvider
import app.nocturne.player.data.online.JioSaavnProvider
import app.nocturne.player.data.online.OnlineLibraryRepository
import app.nocturne.player.data.online.OnlineMusicProvider
import app.nocturne.player.data.online.OnlineProviderManager
import app.nocturne.player.data.online.PipedProvider
import app.nocturne.player.data.online.ProviderHealthTracker
import app.nocturne.player.data.online.RemoteConfigRepository
import app.nocturne.player.data.online.RemoteConfigValidator
import app.nocturne.player.data.online.StreamQualitySelector
import app.nocturne.player.data.online.StreamResolutionCache
import app.nocturne.player.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnlineMusicEngineTest {

    @Test
    fun providerHealthTracker_exponentialCooldownAndRecoveryWorks() {
        var fakeNow = 10_000L
        val tracker = ProviderHealthTracker(
            clockMs = { fakeNow },
            failureThreshold = 2,
            baseCooldownMs = 30_000L,
            maxCooldownMs = 120_000L
        )
        val candidates = listOf(
            OnlineProviderType.YOUTUBE,
            OnlineProviderType.PIPED,
            OnlineProviderType.INVIDIOUS
        )

        // First failure does not open circuit yet
        tracker.recordFailure(OnlineProviderType.YOUTUBE)
        assertTrue(tracker.isAvailable(OnlineProviderType.YOUTUBE))

        // Second consecutive failure opens cooldown for 30s
        tracker.recordFailure(OnlineProviderType.YOUTUBE)
        assertFalse(tracker.isAvailable(OnlineProviderType.YOUTUBE))
        assertEquals(
            listOf(OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS),
            tracker.selectOrderedCandidates(candidates)
        )

        // Third consecutive failure doubles cooldown to 60s
        fakeNow += 1_000L
        tracker.recordFailure(OnlineProviderType.YOUTUBE)
        val snapDuringCooldown = tracker.snapshotAll(listOf(OnlineProviderType.YOUTUBE)).first()
        assertFalse(snapDuringCooldown.isAvailable)
        assertEquals(60_000L, snapDuringCooldown.cooldownRemainingMs)

        // Advance past 60s -> YOUTUBE automatically becomes eligible again
        fakeNow += 60_001L
        assertTrue(tracker.isAvailable(OnlineProviderType.YOUTUBE))
        assertEquals(OnlineProviderType.YOUTUBE, tracker.selectOrderedCandidates(candidates).first())

        // Recording success clears failures & updates EWMA latency
        tracker.recordSuccess(OnlineProviderType.YOUTUBE, latencyMs = 220L)
        val recovered = tracker.snapshotAll(listOf(OnlineProviderType.YOUTUBE)).first()
        assertTrue(recovered.isAvailable)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(220L, recovered.ewmaLatencyMs)
    }

    @Test
    fun streamQualitySelector_adaptsToNetworkTierAndCachesExpiry() {
        val low = OnlineAudioStream("https://cdn.example/low.webm", 64, "audio/webm", "opus", "WEBM 64k", 0L)
        val med = OnlineAudioStream("https://cdn.example/med.m4a", 128, "audio/mp4", "mp4a.40.2", "M4A 128k", 0L)
        val high = OnlineAudioStream("https://cdn.example/high.webm", 256, "audio/webm", "opus", "WEBM 256k", 0L)
        val invalid = OnlineAudioStream("ftp://bad/stream", 320, "audio/mp4", "aac", "BAD 320k", 0L)

        val streams = listOf(low, med, high, invalid)

        // Fast unmetered prefers highest quality Opus 256k
        assertEquals(high, StreamQualitySelector.selectBestAudioStream(streams, NetworkSpeedTier.FAST_UNMETERED))
        // Standard prefers sweet-spot ~128k (doesn't waste on >192k unless needed)
        assertEquals(med, StreamQualitySelector.selectBestAudioStream(streams, NetworkSpeedTier.STANDARD))
        // Slow/metered prefers lightweight 64k Opus
        assertEquals(low, StreamQualitySelector.selectBestAudioStream(streams, NetworkSpeedTier.SLOW_OR_METERED))

        // URL expiry parsing reserves a 5-minute refresh buffer
        val now = 1_700_000_000_000L
        val expireSec = (now / 1000L) + 3600L
        val parsed = StreamQualitySelector.parseExpiryFromUrl(
            "https://rr1.googlevideo.com/videoplayback?expire=$expireSec&itag=251",
            nowMs = now
        )
        assertEquals(expireSec * 1000L - 5 * 60_000L, parsed)

        // Cache obeys expiry and manual invalidation
        var cacheClock = now
        val cache = StreamResolutionCache(clockMs = { cacheClock })
        val track = sampleTrack("v1")
        val res = OnlineStreamResolution(
            track = track,
            streams = listOf(high),
            selectedStream = high.copy(expiresAtMs = now + 60_000L),
            resolvedBy = OnlineProviderType.YOUTUBE
        )
        cache.put(track.id, res)
        assertNotNull(cache.getIfValid(track.id))
        cacheClock += 60_001L
        assertNull(cache.getIfValid(track.id))

        cache.put(track.id, res.copy(selectedStream = high.copy(expiresAtMs = cacheClock + 60_000L)))
        assertNotNull(cache.getIfValid(track.id))
        cache.invalidate(track.id)
        assertNull(cache.getIfValid(track.id))
    }

    @Test
    fun onlineProviderManager_failsOverAndDeduplicatesAndRefreshesExpiredUrls() = runTest {
        val resolveCounter = AtomicInteger(0)
        val failingYoutube = object : FakeProvider(OnlineProviderType.YOUTUBE) {
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                throw IOException("YouTube extractor temporary 403")
            }
            override suspend fun resolveStream(track: OnlineTrack, speedTier: NetworkSpeedTier): OnlineStreamResolution {
                throw IOException("YouTube cipher mismatch")
            }
        }
        val workingPiped = object : FakeProvider(OnlineProviderType.PIPED) {
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                val t1 = sampleTrack("abc123", title = "Nocturne in E-Flat", artist = "Chopin", durationMs = 242_000L)
                val dupSameTitle = sampleTrack("xyz999", title = "Nocturne in E-Flat!", artist = "Chopin", durationMs = 241_000L)
                val t2 = sampleTrack("def456", title = "Clair de Lune", artist = "Debussy", durationMs = 305_000L)
                return OnlineSearchPage(
                    query = query,
                    tracks = listOf(t1, dupSameTitle, t2),
                    providerUsed = OnlineProviderType.PIPED
                )
            }
            override suspend fun resolveStream(track: OnlineTrack, speedTier: NetworkSpeedTier): OnlineStreamResolution {
                val n = resolveCounter.incrementAndGet()
                return OnlineStreamResolution(
                    track = track,
                    streams = emptyList(),
                    selectedStream = OnlineAudioStream(
                        url = "https://piped.stream/audio?token=tok$n",
                        bitrateKbps = 128,
                        mimeType = "audio/webm",
                        codec = "opus",
                        formatLabel = "WEBM 128k",
                        expiresAtMs = System.currentTimeMillis() + 3_600_000L
                    ),
                    resolvedBy = OnlineProviderType.PIPED
                )
            }
        }

        val manager = OnlineProviderManager(
            providers = mapOf(
                OnlineProviderType.YOUTUBE to failingYoutube,
                OnlineProviderType.PIPED to workingPiped
            ),
            healthTracker = ProviderHealthTracker(failureThreshold = 1)
        )

        // Primary fails -> automatically switches to PIPED & deduplicates near-identical tracks
        val searchPage = manager.search("nocturne")
        assertEquals(OnlineProviderType.PIPED, searchPage.providerUsed)
        assertEquals(2, searchPage.tracks.size)
        assertEquals("Nocturne in E-Flat", searchPage.tracks[0].title)
        assertEquals("Clair de Lune", searchPage.tracks[1].title)

        // Stream resolution hits PIPED directly since YOUTUBE is cooled down, caches result, then forceRefresh re-resolves
        val firstRes = manager.resolveStream(searchPage.tracks[0], forceRefresh = false)
        assertTrue(firstRes.selectedStream.url.endsWith("tok1"))
        val cachedRes = manager.resolveStream(searchPage.tracks[0], forceRefresh = false)
        assertTrue(cachedRes.selectedStream.url.endsWith("tok1"))
        assertEquals(1, resolveCounter.get())

        val refreshedRes = manager.resolveStream(searchPage.tracks[0], forceRefresh = true)
        assertTrue(refreshedRes.selectedStream.url.endsWith("tok2"))
        assertEquals(2, resolveCounter.get())
    }

    @Test
    fun pipedAndInvidiousProviders_failOverBetweenInstancesWithMockWebServer() = runTest {
        val badServer = MockWebServer()
        val goodServer = MockWebServer()
        badServer.start()
        goodServer.start()
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()

            // --- PIPED TEST ---
            badServer.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            goodServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "items": [
                        {
                          "type": "stream",
                          "url": "/watch?v=kJQP7kiw5Fk",
                          "title": "Piped Mock Song",
                          "uploaderName": "Mock Artist",
                          "uploaderUrl": "/channel/UC123",
                          "duration": 215,
                          "thumbnail": "https://img.example/1.jpg",
                          "views": 42000
                        }
                      ],
                      "nextpage": "{\"p\":2}"
                    }
                    """.trimIndent()
                )
            )
            goodServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "title": "Piped Mock Song",
                      "uploader": "Mock Artist",
                      "uploaderUrl": "/channel/UC123",
                      "duration": 215,
                      "thumbnailUrl": "https://img.example/1.jpg",
                      "audioStreams": [
                        {"url": "https://cdn.example/a128.webm", "bitrate": 128000, "mimeType": "audio/webm", "codec": "opus", "format": "WEBMA"},
                        {"url": "https://cdn.example/a256.webm", "bitrate": 256000, "mimeType": "audio/webm", "codec": "opus", "format": "WEBMA"}
                      ],
                      "relatedStreams": []
                    }
                    """.trimIndent()
                )
            )

            val piped = PipedProvider(
                client = client,
                baseUrlsOverride = listOf(badServer.url("").toString().trimEnd('/'), goodServer.url("").toString().trimEnd('/'))
            )
            val pipedSearch = piped.search("mock", OnlineSearchFilter.SONGS, null)
            assertEquals(1, pipedSearch.tracks.size)
            assertEquals("kJQP7kiw5Fk", pipedSearch.tracks[0].sourceId)
            assertTrue(pipedSearch.hasMore)
            assertEquals(goodServer.url("").toString().trimEnd('/'), piped.lastWorkingInstance)

            val pipedStream = piped.resolveStream(pipedSearch.tracks[0], NetworkSpeedTier.FAST_UNMETERED)
            assertEquals("https://cdn.example/a256.webm", pipedStream.selectedStream.url)

            // --- INVIDIOUS TEST ---
            badServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))
            goodServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {
                        "type": "video",
                        "videoId": "inv987",
                        "title": "Invidious Jazz Session",
                        "author": "Night Trio",
                        "authorId": "UC_TRIO",
                        "lengthSeconds": 190,
                        "videoThumbnails": [{"quality": "medium", "url": "/vi/inv987/mqdefault.jpg"}]
                      }
                    ]
                    """.trimIndent()
                )
            )
            goodServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "title": "Invidious Jazz Session",
                      "author": "Night Trio",
                      "authorId": "UC_TRIO",
                      "lengthSeconds": 190,
                      "adaptiveFormats": [
                        {"type": "audio/mp4; codecs=\"mp4a.40.2\"", "url": "/latest_version?id=inv987&itag=140", "bitrate": "128000", "container": "m4a", "encoding": "aac"}
                      ],
                      "recommendedVideos": []
                    }
                    """.trimIndent()
                )
            )

            val invidious = InvidiousProvider(
                client = client,
                baseUrlsOverride = listOf(badServer.url("").toString().trimEnd('/'), goodServer.url("").toString().trimEnd('/'))
            )
            val invSearch = invidious.search("jazz", OnlineSearchFilter.ALL, null)
            assertEquals(1, invSearch.tracks.size)
            assertEquals("inv987", invSearch.tracks[0].sourceId)
            val invStream = invidious.resolveStream(invSearch.tracks[0], NetworkSpeedTier.STANDARD)
            assertTrue(invStream.selectedStream.url.startsWith(goodServer.url("").toString().trimEnd('/')))
        } finally {
            badServer.shutdown()
            goodServer.shutdown()
        }
    }

    @Test
    fun roomMigration1To2AndOnlineLibraryRepository_persistFavoritesPlaylistsAndHistory() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "test_aria_migration_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Step 1: Create v1 schema manually with an existing offline row and offline playlist
        val v1OpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `track_stats` (`uriString` TEXT NOT NULL, `playCount` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `totalListenMs` INTEGER NOT NULL, PRIMARY KEY(`uriString`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `track_tag_overrides` (`uriString` TEXT NOT NULL, `title` TEXT, `artist` TEXT, `album` TEXT, `albumArtist` TEXT, `genre` TEXT, `year` INTEGER, `trackNumber` INTEGER, `discNumber` INTEGER, `composer` TEXT, `customCoverUri` TEXT, `lyrics` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`uriString`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `coverUri` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_entries` (`entryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `trackUri` TEXT NOT NULL, `position` INTEGER NOT NULL, FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entries_playlistId` ON `playlist_entries` (`playlistId`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `recent_searches` (`query` TEXT NOT NULL, `searchedAt` INTEGER NOT NULL, PRIMARY KEY(`query`))")

                        val cv = ContentValues().apply {
                            put("uriString", "content://media/external/audio/media/42")
                            put("playCount", 7)
                            put("lastPlayedAt", 12345L)
                            put("isFavorite", 1)
                            put("totalListenMs", 90000L)
                        }
                        db.insert("track_stats", SQLiteDatabase.CONFLICT_REPLACE, cv)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v1OpenHelper.writableDatabase.close()
        v1OpenHelper.close()

        // Step 2: Open with Room using MIGRATION_1_2 + MIGRATION_2_3 + MIGRATION_3_4 + MIGRATION_4_5 and verify offline data survives
        val db = Room.databaseBuilder(context, AriaDatabase::class.java, dbName)
            .addMigrations(
                AriaDatabase.MIGRATION_1_2,
                AriaDatabase.MIGRATION_2_3,
                AriaDatabase.MIGRATION_3_4,
                AriaDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()

        try {
            val offlineStats = db.trackStatsDao().getByUri("content://media/external/audio/media/42")
            assertNotNull(offlineStats)
            assertEquals(7, offlineStats!!.playCount)
            assertTrue(offlineStats.isFavorite)

            // Step 3: Exercise OnlineLibraryRepository CRUD
            val repo = OnlineLibraryRepository(db)
            val t1 = sampleTrack("trk1", title = "Midnight Etude")
            val t2 = sampleTrack("trk2", title = "Dawn Prelude")

            assertTrue(repo.toggleFavorite(t1))
            assertEquals(1, repo.favorites.first().size)

            repo.recordTrackPlayed(t1)
            repo.recordTrackPlayed(t2)
            assertEquals(2, repo.history.first().size)
            assertEquals(2, repo.recentlyPlayed.first().size)
            assertEquals(2, repo.mostPlayed.first().size)

            val plId = repo.createPlaylist("Late Night Mix", "Warm streams", listOf(t1, t2))
            val created = repo.playlists.first().first { it.id == plId }
            assertEquals(listOf("trk1", "trk2"), created.tracks.map { it.sourceId })

            repo.reorderPlaylistTracks(plId, 0, 1)
            val reordered = repo.playlists.first().first { it.id == plId }
            assertEquals(listOf("trk2", "trk1"), reordered.tracks.map { it.sourceId })

            repo.renamePlaylist(plId, "Study Mix", "Updated")
            assertEquals("Study Mix", repo.playlists.first().first { it.id == plId }.name)

            repo.removeTrackFromPlaylist(plId, 0)
            assertEquals(1, repo.playlists.first().first { it.id == plId }.tracks.size)

            // Clearing listening history preserves favorites and custom playlists!
            repo.clearListeningHistory()
            assertTrue(repo.history.first().isEmpty())
            assertEquals(1, repo.favorites.first().size)
            assertEquals(1, repo.playlists.first().size)

            repo.recordSearch("chopin nocturne")
            assertEquals(listOf("chopin nocturne"), repo.recentSearches.first())
            repo.deleteRecentSearch("chopin nocturne")
            assertTrue(repo.recentSearches.first().isEmpty())

            repo.deletePlaylist(plId)
            assertTrue(repo.playlists.first().isEmpty())
        } finally {
            db.close()
            dbFile.delete()
        }
    }

    @Test
    fun remoteConfigRepository_fetchesPersistsAndFallsBackOnError() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo = SettingsRepository(context)
        val server = MockWebServer()
        server.start()
        try {
            val remoteJson = """
                {
                  "jiosaavn": ["https://saavn.custom.example/api"],
                  "piped": ["https://piped.custom.example"],
                  "invidious": ["https://inv.custom.example"],
                  "lrclib": ["https://lrclib.custom.example"]
                }
            """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(200).setBody(remoteJson))
            server.enqueue(MockResponse().setResponseCode(500).setBody("server down"))

            val repo = RemoteConfigRepository(
                settingsRepository = settingsRepo,
                baseClient = OkHttpClient(),
                allowTestLoopback = true
            )
            val url = server.url("/providers.json").toString()

            val updated = repo.refreshNow(customUrl = url)
            assertEquals(listOf("https://saavn.custom.example/api"), updated.jiosaavn)
            assertEquals(listOf("https://piped.custom.example"), updated.piped)
            assertEquals(listOf("https://inv.custom.example"), updated.invidious)
            assertEquals(listOf("https://lrclib.custom.example"), updated.lrclib)

            // Verify DataStore cached config persists and serves as fallback on next 500 failure
            val cached = settingsRepo.getCachedRemoteProvidersConfig()
            assertNotNull(cached)
            assertEquals(listOf("https://saavn.custom.example/api"), cached!!.jiosaavn)

            val repo2 = RemoteConfigRepository(
                settingsRepository = settingsRepo,
                baseClient = OkHttpClient(),
                allowTestLoopback = true
            )
            val fallbackConfig = repo2.refreshNow(customUrl = url)
            assertEquals(listOf("https://saavn.custom.example/api"), fallbackConfig.jiosaavn)
            assertNotNull(repo2.lastFetchError.value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun jioSaavnAndAudiusProviders_searchAndResolveAudioStreams() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search/songs") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "data": {
                            "results": [
                              {
                                "id": "saavn_1",
                                "name": "Midnight &amp; Echoes",
                                "duration": "210",
                                "year": "2024",
                                "album": {"name": "Night Paper"},
                                "artists": {"primary": [{"id": "art1", "name": "Aria Duo"}]},
                                "image": [{"quality": "500x500", "url": "https://img.example/cover.jpg"}],
                                "downloadUrl": [
                                  {"quality": "96kbps", "url": "https://aac.example/track_96.mp4"},
                                  {"quality": "160kbps", "url": "https://aac.example/track_160.mp4"},
                                  {"quality": "320kbps", "url": "https://aac.example/track_320.mp4"}
                                ]
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.contains("/suggestions") -> MockResponse().setResponseCode(200).setBody("""{"data":[]}""")
                    path.startsWith("/v1/tracks/search") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "data": [
                            {
                              "id": "aud_777",
                              "title": "Audius Horizon",
                              "duration": 185,
                              "genre": "Electronic",
                              "is_streamable": true,
                              "permalink": "/artist/audius-horizon",
                              "user": {"id": "u1", "name": "Horizon Maker", "handle": "horizon"},
                              "artwork": {"480x480": "https://audius.img/480.jpg"}
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/v1/tracks/aud_777") -> MockResponse().setResponseCode(200).setBody(
                        """{"data":{"id":"aud_777","title":"Audius Horizon","duration":185,"is_streamable":true}}"""
                    )
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"unexpected path $path"}""")
                }
            }
        }
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()

            val jioSaavn = JioSaavnProvider(client = client, baseUrlsOverride = listOf(baseUrl))
            val jioPage = jioSaavn.search("Midnight", OnlineSearchFilter.JIOSAAVN, null)
            assertEquals(1, jioPage.tracks.size)
            assertEquals("Midnight & Echoes", jioPage.tracks[0].title)
            assertEquals("Aria Duo", jioPage.tracks[0].artist)

            val fastStream = jioSaavn.resolveStream(jioPage.tracks[0], NetworkSpeedTier.FAST_UNMETERED)
            assertEquals(320, fastStream.selectedStream.bitrateKbps)
            assertTrue(fastStream.selectedStream.url.endsWith("track_320.mp4"))

            val meteredStream = jioSaavn.resolveStream(jioPage.tracks[0], NetworkSpeedTier.SLOW_OR_METERED)
            assertEquals(96, meteredStream.selectedStream.bitrateKbps)

            val audius = AudiusProvider(client = client, baseUrlsOverride = listOf(baseUrl))
            val audiusPage = audius.search("Horizon", OnlineSearchFilter.AUDIUS, null)
            assertEquals(1, audiusPage.tracks.size)
            assertEquals("Audius Horizon", audiusPage.tracks[0].title)
            val audiusRes = audius.resolveStream(audiusPage.tracks[0], NetworkSpeedTier.STANDARD)
            assertEquals("$baseUrl/v1/tracks/aud_777/stream?app_name=ARIA", audiusRes.selectedStream.url)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun onlineProviderManager_failsOverCrossProviderByTitleAndArtistOn403() = runTest {
        val youtube403 = object : FakeProvider(OnlineProviderType.YOUTUBE) {
            override suspend fun resolveStream(track: OnlineTrack, speedTier: NetworkSpeedTier): OnlineStreamResolution {
                throw IOException("HTTP 403 Forbidden from googlevideo")
            }
        }
        val jioSaavnMirror = object : FakeProvider(OnlineProviderType.JIOSAAVN) {
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                val mirror = OnlineTrack(
                    id = OnlineTrack.canonicalId(OnlineProviderType.JIOSAAVN, "mirror_1"),
                    sourceId = "mirror_1",
                    providerType = OnlineProviderType.JIOSAAVN,
                    providerLabel = "JioSaavn",
                    sourceUrl = "https://www.jiosaavn.com/song/mirror_1",
                    title = "Clair de Lune",
                    artist = "Debussy",
                    durationMs = 240_000L
                )
                return OnlineSearchPage(query = query, tracks = listOf(mirror), providerUsed = OnlineProviderType.JIOSAAVN)
            }

            override suspend fun resolveStream(track: OnlineTrack, speedTier: NetworkSpeedTier): OnlineStreamResolution {
                return OnlineStreamResolution(
                    track = track,
                    streams = emptyList(),
                    selectedStream = OnlineAudioStream(
                        url = "https://aac.saavn.example/clair_320.mp4",
                        bitrateKbps = 320,
                        mimeType = "audio/mp4",
                        codec = "mp4a.40.2",
                        formatLabel = "MP4 320k",
                        expiresAtMs = System.currentTimeMillis() + 3_600_000L
                    ),
                    resolvedBy = OnlineProviderType.JIOSAAVN
                )
            }
        }

        val manager = OnlineProviderManager(
            providers = mapOf(
                OnlineProviderType.YOUTUBE to youtube403,
                OnlineProviderType.JIOSAAVN to jioSaavnMirror
            ),
            healthTracker = ProviderHealthTracker(failureThreshold = 1)
        )

        val ytTrack = sampleTrack("yt_blocked", title = "Clair de Lune", artist = "Debussy", durationMs = 241_000L)
        val res = manager.resolveStream(ytTrack, forceRefresh = true, excludedProviders = setOf(OnlineProviderType.YOUTUBE))
        assertEquals(OnlineProviderType.JIOSAAVN, res.resolvedBy)
        assertEquals("https://aac.saavn.example/clair_320.mp4", res.selectedStream.url)
    }

    @Test
    fun lyricsRepository_fetchesLrclibAndPersistsInRoomCache() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AriaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            // Direct /api/search?q=<cleaned_title>+<cleaned_artist> query
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {
                        "trackName": "Nocturne",
                        "artistName": "Chopin",
                        "duration": 240,
                        "instrumental": false,
                        "syncedLyrics": "[00:05.00]Soft evening breeze\n[00:10.00]Under silver moons",
                        "plainLyrics": "Soft evening breeze\nUnder silver moons"
                      }
                    ]
                    """.trimIndent()
                )
            )

            val repo = LyricsRepository(
                context = context,
                db = db,
                remoteConfigRepository = RemoteConfigRepository(
                    settingsRepository = SettingsRepository(context),
                    baseClient = OkHttpClient(),
                    allowTestLoopback = true
                ),
                client = OkHttpClient(),
                baseUrlsOverride = listOf(baseUrl)
            )
            val track = Track(
                id = 99L,
                uriString = "aria_online://youtube/noc_99",
                filePath = "",
                displayName = "Nocturne",
                title = "Nocturne",
                artist = "Chopin",
                album = "Online",
                albumArtist = "Chopin",
                genre = "Classical",
                year = 2024,
                trackNumber = 1,
                discNumber = 1,
                composer = "",
                durationMs = 240_000L,
                sizeBytes = 0L,
                dateAddedSec = 0L,
                dateModifiedSec = 0L,
                mimeType = "audio/mp4",
                formatLabel = "STREAM",
                bitrateKbps = 320,
                sampleRateHz = 44100,
                channelCount = 2,
                folderPath = "Online",
                folderName = "Online",
                isOnline = true,
                onlineTrackId = "youtube:noc_99",
                onlineSourceId = "noc_99",
                onlineProvider = "YouTube"
            )

            val live = repo.loadLyricsForTrack(track)
            val req = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(req)
            assertTrue(req!!.path.orEmpty().startsWith("/api/search?q="))
            assertEquals(LyricsSource.LRCLIB, live.source)
            assertFalse(live.fromCache)
            assertEquals(2, live.syncedLines.size)
            assertEquals("Soft evening breeze", live.syncedLines[0].text)
            assertTrue(live.syncedLines[0].words.isNotEmpty())

            // Shut down server -> Room cache should serve offline identically
            server.shutdown()
            val cached = repo.loadLyricsForTrack(track)
            assertEquals(LyricsSource.LRCLIB, cached.source)
            assertTrue(cached.fromCache)
            assertEquals(2, cached.syncedLines.size)
        } finally {
            runCatching { server.shutdown() }
            db.close()
        }
    }

    @Test
    fun singleTrackFilter_excludesLongCompilationsUnlessExplicitlySearched() {
        val validSingle = sampleTrack("s1", title = "Midnight Echoes", artist = "Aria", durationMs = 215_000L)
        val remixSingle = sampleTrack("s2", title = "Midnight Echoes (Club Remix)", artist = "Aria", durationMs = 240_000L)
        val longUpload = sampleTrack("l1", title = "Late Night Acoustic Session", artist = "Chords", durationMs = 950_000L)
        val topHitsUpload = sampleTrack("c1", title = "Top Hits 2025 Bollywood", artist = "Various", durationMs = 310_000L)
        val jukeboxUpload = sampleTrack("c2", title = "90s Audio Jukebox", artist = "Radio", durationMs = 450_000L)
        val hourNonstop = sampleTrack("c3", title = "1 Hour Nonstop Chill", artist = "Beats", durationMs = 3_600_000L)
        val chillMix = sampleTrack("c4", title = "Sunday Chill Mix", artist = "DJ", durationMs = 420_000L)

        val raw = listOf(validSingle, remixSingle, longUpload, topHitsUpload, jukeboxUpload, hourNonstop, chillMix)

        // Default feed / normal search excludes >10 min videos and compilation keywords while keeping Remix singles
        val defaultFiltered = OnlineProviderManager.filterSingleSongs(raw, query = "midnight echoes")
        assertEquals(listOf("s1", "s2"), defaultFiltered.map { it.sourceId })

        // Explicit mix / playlist search allows user-requested compilations through
        val explicitMixSearch = OnlineProviderManager.filterSingleSongs(raw, query = "sunday chill mix")
        assertEquals(raw.size, explicitMixSearch.size)

        val explicitPlaylistSearch = OnlineProviderManager.filterSingleSongs(raw, query = "bollywood study playlist")
        assertEquals(raw.size, explicitPlaylistSearch.size)
    }

    @Test
    fun trendingAndDiscoveryFeed_prioritizeStudioProvidersAndFilterYoutubeCompilations() = runTest {
        val youtubeCompilation = object : FakeProvider(OnlineProviderType.YOUTUBE) {
            override suspend fun trending(): List<OnlineTrack> = listOf(
                sampleTrack("yt_mix", title = "Nonstop Top Hits 2025 Jukebox", durationMs = 3_600_000L),
                sampleTrack("yt_single", title = "Acoustic Sunrise", durationMs = 210_000L)
            )
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                return OnlineSearchPage(
                    query = query,
                    tracks = listOf(
                        sampleTrack("yt_long", title = "Full Album Workout", durationMs = 2_400_000L),
                        sampleTrack("yt_good", title = "Cardio Pulse", durationMs = 198_000L)
                    ),
                    providerUsed = OnlineProviderType.YOUTUBE
                )
            }
        }
        val jioSaavnStudio = object : FakeProvider(OnlineProviderType.JIOSAAVN) {
            override suspend fun trending(): List<OnlineTrack> = listOf(
                sampleTrack("js_1", title = "Kesariya Single", artist = "Arijit Singh", durationMs = 268_000L)
                    .copy(id = OnlineTrack.canonicalId(OnlineProviderType.JIOSAAVN, "js_1"), providerType = OnlineProviderType.JIOSAAVN, providerLabel = "JioSaavn"),
                sampleTrack("js_bad", title = "2 Hour Nonstop Mix", artist = "DJ", durationMs = 7_200_000L)
                    .copy(id = OnlineTrack.canonicalId(OnlineProviderType.JIOSAAVN, "js_bad"), providerType = OnlineProviderType.JIOSAAVN, providerLabel = "JioSaavn")
            )
        }
        val audiusStudio = object : FakeProvider(OnlineProviderType.AUDIUS) {
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                val tracks = listOf("a1", "a2", "a3").map { id ->
                    sampleTrack(id, title = "Lofi Horizon $id", artist = "Beat Maker", durationMs = 175_000L)
                        .copy(id = OnlineTrack.canonicalId(OnlineProviderType.AUDIUS, id), providerType = OnlineProviderType.AUDIUS, providerLabel = "Audius")
                }
                return OnlineSearchPage(query = query, tracks = tracks, providerUsed = OnlineProviderType.AUDIUS)
            }
        }

        val manager = OnlineProviderManager(
            providers = mapOf(
                OnlineProviderType.YOUTUBE to youtubeCompilation,
                OnlineProviderType.JIOSAAVN to jioSaavnStudio,
                OnlineProviderType.AUDIUS to audiusStudio
            ),
            healthTracker = ProviderHealthTracker()
        )

        // Trending picks JioSaavn first over YouTube and strips the 2-hour mix
        val (trendingTracks, trendingProvider) = manager.trending()
        assertEquals(OnlineProviderType.JIOSAAVN, trendingProvider)
        assertEquals(listOf("js_1"), trendingTracks.map { it.sourceId })

        // Category discovery with preferAudiusFirst returns Audius single tracks cleanly
        val (lofiTracks, lofiProvider) = manager.fetchDiscoveryFeed("lofi beats", preferAudiusFirst = true)
        assertEquals(OnlineProviderType.AUDIUS, lofiProvider)
        assertEquals(3, lofiTracks.size)

        // Fallback to YouTube still excludes "Full Album Workout" (>10min + compilation keyword)
        val ytOnlyManager = OnlineProviderManager(
            providers = mapOf(OnlineProviderType.YOUTUBE to youtubeCompilation),
            healthTracker = ProviderHealthTracker()
        )
        val (ytDiscovery, ytProvider) = ytOnlyManager.fetchDiscoveryFeed("workout energy songs")
        assertEquals(OnlineProviderType.YOUTUBE, ytProvider)
        assertEquals(listOf("yt_good"), ytDiscovery.map { it.sourceId })
    }

    @Test
    fun fetchPopularArtists_returnsStudioArtistsWithPortraitsPrioritized() = runTest {
        val jioSaavnArtists = object : FakeProvider(OnlineProviderType.JIOSAAVN) {
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                val noThumb = OnlineArtistSummary(
                    id = "art_no_img",
                    name = "Indie Voices",
                    url = "https://www.jiosaavn.com/artist/indie/art_no_img",
                    thumbnailUrl = null,
                    subscriberText = "Singer",
                    providerType = OnlineProviderType.JIOSAAVN
                )
                val withThumb = OnlineArtistSummary(
                    id = "art_with_img",
                    name = "Arijit Singh",
                    url = "https://www.jiosaavn.com/artist/arijit/art_with_img",
                    thumbnailUrl = "https://img.example/arijit.jpg",
                    subscriberText = "Singer",
                    providerType = OnlineProviderType.JIOSAAVN
                )
                return OnlineSearchPage(
                    query = query,
                    artists = listOf(noThumb, withThumb),
                    providerUsed = OnlineProviderType.JIOSAAVN
                )
            }
        }
        val manager = OnlineProviderManager(
            providers = mapOf(OnlineProviderType.JIOSAAVN to jioSaavnArtists),
            healthTracker = ProviderHealthTracker()
        )
        val (artists, provider) = manager.fetchPopularArtists()
        assertEquals(OnlineProviderType.JIOSAAVN, provider)
        assertEquals(2, artists.size)
        assertEquals("art_with_img", artists[0].id)
        assertEquals("Arijit Singh", artists[0].name)
    }

    @Test
    fun jioSaavnProvider_resolves320kbpsByDefaultAndUpgrades500x500Artwork() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/api").toString().trimEnd('/')
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "data": {
                        "results": [
                          {
                            "id": "saavn_320",
                            "name": "Starlight",
                            "duration": "205",
                            "album": { "name": "Midnight Album", "url": "https://www.jiosaavn.com/album/mid/a1" },
                            "artists": { "primary": [{ "id": "art1", "name": "Muse", "url": "https://www.jiosaavn.com/artist/muse/art1" }] },
                            "image": [
                              { "quality": "150x150", "url": "https://c.saavncdn.com/123/art-150x150.jpg" }
                            ],
                            "encrypted_media_url": "enc_abc_xyz",
                            "downloadUrl": [
                              { "quality": "96kbps", "url": "https://aac.saavncdn.com/123/track_96.mp4" },
                              { "quality": "320kbps", "url": "https://aac.saavncdn.com/123/track_320.mp4" }
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "data": [
                        {
                          "id": "saavn_320",
                          "name": "Starlight",
                          "duration": "205",
                          "encrypted_media_url": "enc_abc_xyz",
                          "downloadUrl": [
                            { "quality": "96kbps", "url": "https://aac.saavncdn.com/123/track_96.mp4" },
                            { "quality": "320kbps", "url": "https://aac.saavncdn.com/123/track_320.mp4" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                )
            )

            val provider = JioSaavnProvider(
                client = OkHttpClient(),
                baseUrlsOverride = listOf(baseUrl)
            )
            val page = provider.search("Starlight", OnlineSearchFilter.SONGS, null)
            assertEquals(1, page.tracks.size)
            val track = page.tracks[0]
            assertEquals("https://c.saavncdn.com/123/art-500x500.jpg", track.thumbnailUrl)
            assertEquals("enc_abc_xyz", track.saavnEncryptedUrl)
            assertEquals("https://c.saavncdn.com/123/art-500x500.jpg", track.toUnifiedTrack().artworkUrl)

            val stream = provider.resolveStream(track, NetworkSpeedTier.STANDARD)
            assertEquals(320, stream.selectedStream.bitrateKbps)
            assertEquals("320kbps AAC", stream.selectedStream.formatLabel)
            assertTrue(stream.selectedStream.url.endsWith("_320.mp4"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pipedProvider_excludesVideoOnlyAndPrefersHighestBitrateWebmOpus() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "title": "Demo Video",
                      "uploader": "Channel",
                      "duration": 180,
                      "thumbnailUrl": "https://img.example/thumb.jpg",
                      "audioStreams": [
                        { "url": "https://cdn.example/video_only.webm", "bitrate": 320000, "mimeType": "audio/webm", "codec": "opus", "videoOnly": true },
                        { "url": "https://cdn.example/aac_256.m4a", "bitrate": 256000, "mimeType": "audio/mp4", "codec": "mp4a.40.2", "videoOnly": false },
                        { "url": "https://cdn.example/opus_160.webm", "bitrate": 160000, "mimeType": "audio/webm", "codec": "opus", "videoOnly": false },
                        { "url": "https://cdn.example/opus_96.webm", "bitrate": 96000, "mimeType": "audio/webm", "codec": "opus", "videoOnly": false }
                      ],
                      "relatedStreams": []
                    }
                    """.trimIndent()
                )
            )

            val provider = PipedProvider(
                client = OkHttpClient(),
                baseUrlsOverride = listOf(baseUrl)
            )
            val track = OnlineTrack(
                id = OnlineTrack.canonicalId(OnlineProviderType.PIPED, "dQw4w9WgXcQ"),
                sourceId = "dQw4w9WgXcQ",
                providerType = OnlineProviderType.PIPED,
                sourceUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                title = "Demo Video",
                artist = "Channel"
            )
            val resolved = provider.resolveStream(track, NetworkSpeedTier.STANDARD)
            assertEquals("https://cdn.example/opus_160.webm", resolved.selectedStream.url)
            assertEquals(160, resolved.selectedStream.bitrateKbps)
            assertEquals("Opus 160k", resolved.selectedStream.formatLabel)
            assertFalse(resolved.streams.any { it.url.contains("video_only") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun searchDecoupling_queriesOnlyJioSaavnForSongsAndPipedInvidiousForVideosRemixes() = runTest {
        var pipedCalledForSongs = false
        val jioSaavn = object : FakeProvider(OnlineProviderType.JIOSAAVN) {
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                return OnlineSearchPage(
                    query = query,
                    tracks = listOf(
                        sampleTrack("js_clean", title = "Kesariya", artist = "Arijit Singh", durationMs = 268_000L)
                            .copy(id = OnlineTrack.canonicalId(OnlineProviderType.JIOSAAVN, "js_clean"), providerType = OnlineProviderType.JIOSAAVN)
                    ),
                    providerUsed = OnlineProviderType.JIOSAAVN
                )
            }
        }
        val piped = object : FakeProvider(OnlineProviderType.PIPED) {
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                if (filter == OnlineSearchFilter.SONGS) pipedCalledForSongs = true
                return OnlineSearchPage(
                    query = query,
                    tracks = listOf(
                        sampleTrack("piped_remix", title = "Kesariya (Live Unplugged 25m)", durationMs = 1_500_000L)
                            .copy(id = OnlineTrack.canonicalId(OnlineProviderType.PIPED, "piped_remix"), providerType = OnlineProviderType.PIPED)
                    ),
                    providerUsed = OnlineProviderType.PIPED
                )
            }
        }

        val manager = OnlineProviderManager(
            providers = mapOf(
                OnlineProviderType.JIOSAAVN to jioSaavn,
                OnlineProviderType.PIPED to piped
            ),
            healthTracker = ProviderHealthTracker()
        )

        val songsPage = manager.search("Kesariya", OnlineSearchFilter.SONGS, null)
        assertFalse(pipedCalledForSongs)
        assertEquals(OnlineProviderType.JIOSAAVN, songsPage.providerUsed)
        assertEquals(listOf("js_clean"), songsPage.tracks.map { it.sourceId })

        val videosPage = manager.search("Kesariya unplugged", OnlineSearchFilter.VIDEOS_REMIXES, null)
        assertEquals(OnlineProviderType.PIPED, videosPage.providerUsed)
        assertEquals(listOf("piped_remix"), videosPage.tracks.map { it.sourceId })
    }

    @Test
    fun playbackResolver_fallsBackToPipedThenInvidiousWithin3SecondsAndPreservesCleanMetadata() = runTest {
        val failingSaavn = object : FakeProvider(OnlineProviderType.JIOSAAVN) {
            override suspend fun resolveStream(track: OnlineTrack, speedTier: NetworkSpeedTier): OnlineStreamResolution {
                throw IOException("Saavn CDN unavailable")
            }
        }
        var pipedSearchQuery = ""
        val pipedNoExactDuration = object : FakeProvider(OnlineProviderType.PIPED) {
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                pipedSearchQuery = query
                return OnlineSearchPage(
                    query = query,
                    tracks = listOf(
                        // 15s off -> outside +/-3s tolerance
                        sampleTrack("piped_wrong_len", title = "Blinding Lights", artist = "The Weeknd", durationMs = 215_000L)
                            .copy(providerType = OnlineProviderType.PIPED)
                    ),
                    providerUsed = OnlineProviderType.PIPED
                )
            }
        }
        val invidiousExact = object : FakeProvider(OnlineProviderType.INVIDIOUS) {
            override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
                return OnlineSearchPage(
                    query = query,
                    tracks = listOf(
                        sampleTrack("inv_exact", title = "Blinding Lights (Official Audio)", artist = "WeekndVEVO", durationMs = 201_500L)
                            .copy(providerType = OnlineProviderType.INVIDIOUS, thumbnailUrl = "https://i.ytimg.com/vi/inv_exact/hqdefault.jpg")
                    ),
                    providerUsed = OnlineProviderType.INVIDIOUS
                )
            }

            override suspend fun resolveStream(track: OnlineTrack, speedTier: NetworkSpeedTier): OnlineStreamResolution {
                return OnlineStreamResolution(
                    track = track,
                    streams = emptyList(),
                    selectedStream = OnlineAudioStream(
                        url = "https://invidious.example/audio/opus_160.webm",
                        bitrateKbps = 160,
                        mimeType = "audio/webm",
                        codec = "opus",
                        formatLabel = "Opus 160k",
                        expiresAtMs = System.currentTimeMillis() + 3_600_000L
                    ),
                    resolvedBy = OnlineProviderType.INVIDIOUS
                )
            }
        }

        val manager = OnlineProviderManager(
            providers = mapOf(
                OnlineProviderType.JIOSAAVN to failingSaavn,
                OnlineProviderType.PIPED to pipedNoExactDuration,
                OnlineProviderType.INVIDIOUS to invidiousExact
            ),
            healthTracker = ProviderHealthTracker(failureThreshold = 1)
        )

        val cleanSaavnTrack = OnlineTrack(
            id = OnlineTrack.canonicalId(OnlineProviderType.JIOSAAVN, "saavn_bl"),
            sourceId = "saavn_bl",
            providerType = OnlineProviderType.JIOSAAVN,
            providerLabel = "JioSaavn",
            sourceUrl = "https://www.jiosaavn.com/song/bl/saavn_bl",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200_000L,
            thumbnailUrl = "https://c.saavncdn.com/bl-500x500.jpg"
        )

        val resolution = manager.resolveStream(cleanSaavnTrack, forceRefresh = true)
        assertTrue(pipedSearchQuery.contains("Blinding Lights"))
        assertTrue(pipedSearchQuery.contains("official audio", ignoreCase = true))
        assertEquals(OnlineProviderType.INVIDIOUS, resolution.resolvedBy)
        assertEquals("https://invidious.example/audio/opus_160.webm", resolution.selectedStream.url)
        // Original clean JioSaavn title & 500x500 artwork are preserved on screen
        assertEquals("Blinding Lights", resolution.track.title)
        assertEquals("The Weeknd", resolution.track.artist)
        assertEquals("https://c.saavncdn.com/bl-500x500.jpg", resolution.track.thumbnailUrl)
    }

    @Test
    fun metadataCleaner_appliesConfigRegexesAndDelimiters() {
        val cleaned = MetadataCleaner.clean(
            rawTitle = "The Weeknd - Blinding Lights (Official Video) [4K] ft. Rosalía | Lyrical",
            rawArtist = "The Weeknd - Topic",
            config = LyricsCleaningConfig()
        )
        assertEquals("Blinding Lights", cleaned.title)
        assertEquals("The Weeknd", cleaned.artist)
    }

    @Test
    fun lyricsManager_executesFourTierFallbackWithMusixmatchTokenRefreshAndCachesInRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AriaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val liriQoServer = MockWebServer()
        val mxmServer = MockWebServer()
        liriQoServer.start()
        mxmServer.start()

        try {
            // Tier 1 LiriQo returns 404 -> falls back to Tier 2 Musixmatch
            liriQoServer.enqueue(MockResponse().setResponseCode(404))

            // Tier 2 Musixmatch: token.get -> macro returns 401 -> refreshes token -> macro returns synced LRC
            mxmServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"message":{"header":{"status_code":200},"body":{"user_token":"expired_tok"}}}"""
                )
            )
            mxmServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"message":{"header":{"status_code":401},"body":{}}}"""
                )
            )
            mxmServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"message":{"header":{"status_code":200},"body":{"user_token":"fresh_tok_123"}}}"""
                )
            )
            mxmServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "message": {
                        "header": { "status_code": 200 },
                        "body": {
                          "macro_calls": {
                            "track.subtitles.get": {
                              "message": {
                                "body": {
                                  "subtitle_list": [
                                    { "subtitle": { "subtitle_body": "[00:04.00]City lights glow\n[00:08.50]Night drives slow" } }
                                  ]
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent()
                )
            )

            val liriQo = LiriQoLyricsAdapter(
                configProvider = { DynamicLyricsProviderConfig(id = "liriqo", enabled = true) },
                client = OkHttpClient(),
                baseUrlsOverride = listOf(liriQoServer.url("").toString().trimEnd('/'))
            )
            val musixmatch = MusixmatchLyricsAdapter(
                configProvider = {
                    DynamicLyricsProviderConfig(
                        id = "musixmatch",
                        enabled = true,
                        userAgent = "CustomAgent/1.0",
                        cookie = "mxm_sess=abc",
                        appId = "web-desktop-app-v1.0"
                    )
                },
                client = OkHttpClient(),
                baseUrlsOverride = listOf(mxmServer.url("").toString().trimEnd('/'))
            )

            val manager = LyricsManager(
                context = context,
                db = db,
                remoteConfigRepository = RemoteConfigRepository(
                    settingsRepository = SettingsRepository(context),
                    baseClient = OkHttpClient(),
                    allowTestLoopback = true
                ),
                providersOverride = listOf(liriQo, musixmatch)
            )

            val track = Track(
                id = 505L,
                uriString = "aria-online://piped/abc505",
                filePath = "",
                displayName = "City Lights (Official Video) [HD]",
                title = "City Lights (Official Video) [HD]",
                artist = "NightRunner - Topic",
                album = "Online",
                albumArtist = "NightRunner",
                genre = "Synth",
                year = 2025,
                trackNumber = 1,
                discNumber = 1,
                composer = "",
                durationMs = 190_000L,
                sizeBytes = 0L,
                dateAddedSec = 0L,
                dateModifiedSec = 0L,
                mimeType = "audio/webm",
                formatLabel = "Opus 160k",
                bitrateKbps = 160,
                sampleRateHz = 48000,
                channelCount = 2,
                folderPath = "",
                folderName = "Piped",
                isOnline = true,
                onlineTrackId = "YOUTUBE:abc505",
                onlineSourceId = "abc505",
                onlineProvider = "Piped"
            )

            val fetched = manager.loadLyricsForTrack(track)
            assertEquals(LyricsSource.MUSIXMATCH, fetched.source)
            assertFalse(fetched.fromCache)
            assertEquals(2, fetched.syncedLines.size)
            assertEquals("City lights glow", fetched.syncedLines[0].text)

            // Verify cleaned query reached LiriQo & Musixmatch sent custom User-Agent + Cookie
            val liriQoReq = liriQoServer.takeRequest(1, TimeUnit.SECONDS)
            assertTrue(liriQoReq?.path.orEmpty().contains("City%20Lights"))
            assertFalse(liriQoReq?.path.orEmpty().contains("Official"))
            val mxmTokenReq = mxmServer.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("CustomAgent/1.0", mxmTokenReq?.getHeader("User-Agent"))
            assertEquals("mxm_sess=abc", mxmTokenReq?.getHeader("Cookie"))

            // Room cache hit survives network shutdown
            liriQoServer.shutdown()
            mxmServer.shutdown()
            val cached = manager.loadLyricsForTrack(track)
            assertEquals(LyricsSource.MUSIXMATCH, cached.source)
            assertTrue(cached.fromCache)
            assertEquals(2, cached.syncedLines.size)
        } finally {
            runCatching { liriQoServer.shutdown() }
            runCatching { mxmServer.shutdown() }
            db.close()
        }
    }

    @Test
    fun remoteConfigValidator_parsesLyricsProvidersAndCleaningRules() {
        val rawJson = """
            {
              "version": 3,
              "lyrics_cleaning": {
                "strip_brackets_regex": "\\([^)]*\\)|\\[[^\\]]*\\]",
                "strip_keywords_regex": "\\b(official|video|lyrics)\\b",
                "title_delimiters": [" - ", " | "]
              },
              "lyrics": {
                "liriqo": {
                  "enabled": true,
                  "priority": 1,
                  "timeout_ms": 6500,
                  "instances": ["https://lyrics.liriqo.example"]
                },
                "musixmatch": {
                  "enabled": true,
                  "priority": 2,
                  "user_agent": "Mozilla/5.0 Test",
                  "cookie": "x-mxm-user=1",
                  "app_id": "web-desktop-app-v1.0"
                },
                "jiosaavn_lyrics": {
                  "enabled": true,
                  "priority": 3
                },
                "lrclib": {
                  "enabled": true,
                  "priority": 4,
                  "instances": ["https://lrclib.net"]
                }
              },
              "providers": {
                "jiosaavn": {
                  "enabled": true,
                  "type": "jiosaavn",
                  "display_name": "JioSaavn",
                  "priority": 1,
                  "endpoints": {
                    "search": ["https://saavn.dev/api"],
                    "playback": ["https://saavn.dev/api"]
                  }
                }
              }
            }
        """.trimIndent()

        val cfg = RemoteConfigValidator.parseAndValidate(rawJson, allowLoopback = true)

        assertEquals(listOf(" - ", " | "), cfg.lyricsCleaning.titleDelimiters)
        assertNotNull(cfg.liriqoConfig())
        assertEquals(listOf("https://lyrics.liriqo.example"), cfg.liriqoConfig()!!.resolvedEndpoints())
        assertEquals(6500L, cfg.liriqoConfig()!!.timeoutMs)

        val mxm = cfg.musixmatchConfig()
        assertNotNull(mxm)
        assertEquals("Mozilla/5.0 Test", mxm!!.userAgent)
        assertEquals("x-mxm-user=1", mxm.cookie)
        assertEquals("web-desktop-app-v1.0", mxm.appId)

        assertNotNull(cfg.jiosaavnLyricsConfig())
        assertEquals(listOf("https://lrclib.net"), cfg.lrclibEndpointsOnly())
    }

    @Test
    fun remoteConfigValidator_parsesSchemaV2ProvidersJsonAndCapabilityGating() {
        val rawV2Json = """
            {
              "schema_version": 2,
              "config_version": 42,
              "metadata_cleaning": {
                "strip_brackets_regex": "\\([^)]*\\)|\\[[^\\]]*\\]",
                "strip_keywords_regex": "\\b(official|video|4k)\\b",
                "title_delimiters": [" - ", " | "]
              },
              "provider_defaults": {
                "endpoint_failover": true,
                "max_retries_per_endpoint": 2,
                "request_timeout_ms": 4500,
                "health_check_cooldown_ms": 90000
              },
              "providers": [
                {
                  "id": "jiosaavn",
                  "name": "JioSaavn",
                  "category": "music",
                  "enabled": true,
                  "adapter": "jiosaavn",
                  "base_urls": ["https://saavn.dev/api", "https://saavn-backup.example/api"],
                  "capabilities": ["search", "playback", "lyrics"],
                  "priority": 1,
                  "headers": { "X-App": "Nocturne" },
                  "params": { "lang": "hindi,english" }
                },
                {
                  "id": "piped_primary",
                  "name": "Piped",
                  "category": "music",
                  "enabled": true,
                  "adapter": "piped",
                  "base_urls": ["https://pipedapi.kavin.rocks"],
                  "capabilities": ["search", "playback"],
                  "priority": 2
                },
                {
                  "id": "musixmatch",
                  "name": "Musixmatch",
                  "category": "lyrics",
                  "enabled": true,
                  "adapter": "musixmatch",
                  "base_urls": ["https://apic-desktop.musixmatch.com"],
                  "capabilities": ["lyrics", "synced_lyrics"],
                  "priority": 20,
                  "headers": { "User-Agent": "MXM/2.0", "Cookie": "sid=xyz" },
                  "params": { "app_id": "web-desktop-app-v1.0" }
                },
                {
                  "id": "liriqo",
                  "name": "LiriQo",
                  "category": "lyrics",
                  "enabled": true,
                  "adapter": "liriqo",
                  "base_urls": ["https://liriqo.example", "https://liriqo2.example"],
                  "capabilities": ["lyrics", "synced_lyrics"],
                  "priority": 10,
                  "params": { "source": "apple,musixmatch" }
                }
              ]
            }
        """.trimIndent()

        val cfg = RemoteConfigValidator.parseAndValidate(rawV2Json, allowLoopback = true)
        assertEquals(2, cfg.schemaVersion)
        assertEquals("42", cfg.configVersion)
        assertEquals(listOf(" - ", " | "), cfg.lyricsCleaning.titleDelimiters)
        assertTrue(cfg.providerDefaults.endpointFailover)
        assertEquals(2, cfg.providerDefaults.maxRetriesPerEndpoint)
        assertEquals(4500L, cfg.providerDefaults.requestTimeoutMs)
        assertEquals(90_000L, cfg.providerDefaults.healthCheckCooldownMs)

        // Verify music providers inherit defaults, base_urls, headers, params, capabilities
        val saavn = cfg.providers.first { it.id == "jiosaavn" }
        assertEquals("music", saavn.category)
        assertEquals("jiosaavn", saavn.adapterType)
        assertEquals(listOf("https://saavn.dev/api", "https://saavn-backup.example/api"), saavn.baseUrls())
        assertEquals(listOf("search", "playback", "lyrics"), saavn.capabilities)
        assertEquals(4500L, saavn.timeoutMs)
        assertEquals(2, saavn.maxRetries)
        assertEquals(90_000L, saavn.cooldownMs)
        assertEquals("Nocturne", saavn.headers["X-App"])
        assertEquals("hindi,english", saavn.params["lang"])

        // Verify lyrics providers are sorted ascending by priority (10 before 20)
        assertEquals(listOf("liriqo", "musixmatch"), cfg.lyricsProviders.map { it.id })
        val liriqo = cfg.liriqoConfig()!!
        assertEquals(listOf("https://liriqo.example", "https://liriqo2.example"), liriqo.baseUrls())
        assertEquals("apple,musixmatch", liriqo.params["source"])
        assertEquals(4500L, liriqo.timeoutMs)
        assertEquals(90_000L, liriqo.cooldownMs)

        val mxm = cfg.musixmatchConfig()!!
        assertEquals("MXM/2.0", mxm.userAgent)
        assertEquals("sid=xyz", mxm.cookie)
        assertEquals("web-desktop-app-v1.0", mxm.appId)

        // Verify JioSaavn lyrics config is synthesized when "lyrics" is in capabilities
        val saavnLyrics = cfg.jiosaavnLyricsConfig()
        assertNotNull(saavnLyrics)
        assertTrue(saavnLyrics!!.supportsCapability("lyrics"))
        assertEquals(listOf("https://saavn.dev/api", "https://saavn-backup.example/api"), saavnLyrics.baseUrls())

        // Remove "lyrics" from jiosaavn capabilities -> jiosaavnLyricsConfig() must return null
        val noLyricsJson = rawV2Json.replace(
            """"capabilities": ["search", "playback", "lyrics"]""",
            """"capabilities": ["search", "playback"]"""
        )
        val noLyricsCfg = RemoteConfigValidator.parseAndValidate(noLyricsJson, allowLoopback = true)
        assertNull(noLyricsCfg.jiosaavnLyricsConfig())
    }

    @Test
    fun jioSaavnLyricsAdapter_respectsLyricsCapability() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"data":{"lyrics":"Golden hour sky<br>Fading slowly"}}""")
            )
            val baseUrl = server.url("/").toString().trimEnd('/')
            var allowCapability = false
            val adapter = JioSaavnLyricsAdapter(
                configProvider = {
                    DynamicLyricsProviderConfig(
                        id = "jiosaavn_lyrics",
                        adapterType = "jiosaavn",
                        enabled = true,
                        capabilities = if (allowCapability) listOf("search", "playback", "lyrics") else listOf("search", "playback"),
                        instances = listOf(baseUrl)
                    )
                },
                jioSaavnEndpointsProvider = { listOf(baseUrl) },
                client = OkHttpClient(),
                baseUrlsOverride = listOf(baseUrl)
            )
            val query = LyricsQuery(
                cleanedTitle = "Golden Hour",
                cleanedArtist = "Artist",
                durationMs = 200_000L,
                isOnline = true,
                onlineProvider = "JioSaavn",
                sourceId = "saavn77"
            )

            // When "lyrics" capability is absent, adapter must skip HTTP calls entirely
            assertNull(adapter.fetchLyrics(query))
            assertEquals(0, server.requestCount)

            // When "lyrics" capability is present, adapter queries endpoint and parses lyrics
            allowCapability = true
            val resolved = adapter.fetchLyrics(query)
            assertNotNull(resolved)
            assertEquals("Golden hour sky\nFading slowly", resolved!!.plainLyrics)
            assertEquals(1, server.requestCount)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun lyricsAdapter_failsOverAcrossBaseUrlsAndSkipsCoolingDownEndpoint() = runTest {
        val failingServer = MockWebServer()
        val healthyServer = MockWebServer()
        failingServer.start()
        healthyServer.start()
        try {
            var fakeNow = 100_000L
            val tracker = ProviderHealthTracker(clockMs = { fakeNow })

            // Endpoint 1 returns HTTP 503; Endpoint 2 returns synced LRC
            failingServer.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            healthyServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"lrc":"[00:01.00]Line one"}""")
            )
            // Second query response on healthyServer
            healthyServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"lrc":"[00:01.00]Line two"}""")
            )

            val failUrl = failingServer.url("/").toString().trimEnd('/')
            val okUrl = healthyServer.url("/").toString().trimEnd('/')

            val adapter = LiriQoLyricsAdapter(
                configProvider = {
                    DynamicLyricsProviderConfig(
                        id = "liriqo",
                        adapterType = "liriqo",
                        enabled = true,
                        instances = listOf(failUrl, okUrl),
                        timeoutMs = 3_000L,
                        maxRetries = 0,
                        cooldownMs = 45_000L,
                        endpointFailover = true,
                        params = mapOf("mode" to "synced")
                    )
                },
                client = OkHttpClient(),
                baseUrlsOverride = listOf(failUrl, okUrl),
                healthTracker = tracker
            )

            val query = LyricsQuery(
                cleanedTitle = "Midnight",
                cleanedArtist = "Echo",
                durationMs = 180_000L,
                isOnline = false,
                onlineProvider = "",
                sourceId = ""
            )

            val first = adapter.fetchLyrics(query)
            assertNotNull(first)
            assertEquals("Line one", first!!.parsedLines.first().text)
            assertEquals(1, failingServer.requestCount)
            assertEquals(1, healthyServer.requestCount)
            val okReq = healthyServer.takeRequest(1, TimeUnit.SECONDS)
            assertTrue(okReq?.path.orEmpty().contains("mode=synced"))

            // Within 45s cooldown, failingServer must be skipped completely
            fakeNow += 10_000L
            val second = adapter.fetchLyrics(query)
            assertNotNull(second)
            assertEquals("Line two", second!!.parsedLines.first().text)
            assertEquals(1, failingServer.requestCount)
            assertEquals(2, healthyServer.requestCount)

            // Once cooldown expires, failingServer becomes eligible again
            fakeNow += 40_000L
            failingServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"lrc":"[00:02.00]Recovered"}""")
            )
            val third = adapter.fetchLyrics(query)
            assertNotNull(third)
            assertEquals("Recovered", third!!.parsedLines.first().text)
            assertEquals(2, failingServer.requestCount)
        } finally {
            runCatching { failingServer.shutdown() }
            runCatching { healthyServer.shutdown() }
        }
    }

    @Test
    fun invidiousAndPlaybackResolver_failOverPastHtmlResponsesAndPlaybackCapabilityAlias() = runTest {
        val htmlServer = MockWebServer()
        val errorJsonServer = MockWebServer()
        val healthyServer = MockWebServer()
        htmlServer.start()
        errorJsonServer.start()
        healthyServer.start()
        try {
            // 1. Capability alias check: "playback" in schema_version:2 must satisfy supportsCapability("stream")
            val v2Config = app.nocturne.player.data.model.DynamicProviderConfig(
                id = "piped_primary",
                adapterType = "piped",
                enabled = true,
                capabilities = listOf("search", "playback")
            )
            assertTrue(v2Config.supportsCapability("stream"))
            assertTrue(v2Config.supportsCapability("playback"))

            // 2. Invidious resolveStream fails fast on HTML (without retrying) and error JSON -> lands on healthyServer
            htmlServer.enqueue(MockResponse().setResponseCode(200).setBody("<!DOCTYPE html><html><body>Website</body></html>"))
            errorJsonServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"error":"Sign in to confirm","adaptiveFormats":[]}"""))
            val healthyBase = healthyServer.url("/").toString().trimEnd('/')
            healthyServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "title": "Night Drive",
                          "author": "Synth Artist",
                          "lengthSeconds": 180,
                          "adaptiveFormats": [
                            {
                              "url": "$healthyBase/audio.webm",
                              "type": "audio/webm; codecs=\"opus\"",
                              "bitrate": "160000"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
            )

            val htmlBase = htmlServer.url("/").toString().trimEnd('/')
            val errJsonBase = errorJsonServer.url("/").toString().trimEnd('/')
            val tracker = ProviderHealthTracker()
            val provider = InvidiousProvider(
                client = OkHttpClient(),
                baseUrlsOverride = listOf(htmlBase, errJsonBase, healthyBase),
                healthTracker = tracker,
                dynamicConfigProvider = {
                    app.nocturne.player.data.model.DynamicProviderConfig(
                        id = "invidious_custom",
                        adapterType = "invidious",
                        enabled = true,
                        maxRetries = 2,
                        capabilities = listOf("search", "playback")
                    )
                }
            )

            val resolution = provider.resolveStream(sampleTrack("dQw4w9WgXcQ"), NetworkSpeedTier.STANDARD)
            assertEquals("$healthyBase/audio.webm", resolution.selectedStream.url)
            assertEquals("invidious_custom", resolution.resolvedByProviderId)
            // HTML response must NOT be retried even when maxRetries = 2
            assertEquals(1, htmlServer.requestCount)
            assertEquals(1, errorJsonServer.requestCount)
            assertEquals(1, healthyServer.requestCount)
        } finally {
            runCatching { htmlServer.shutdown() }
            runCatching { errorJsonServer.shutdown() }
            runCatching { healthyServer.shutdown() }
        }
    }

    private fun sampleTrack(
        sourceId: String,
        title: String = "Track $sourceId",
        artist: String = "Artist",
        durationMs: Long = 180_000L
    ) = OnlineTrack(
        id = OnlineTrack.canonicalId(OnlineProviderType.YOUTUBE, sourceId),
        sourceId = sourceId,
        providerType = OnlineProviderType.YOUTUBE,
        providerLabel = "YouTube",
        sourceUrl = "https://www.youtube.com/watch?v=$sourceId",
        title = title,
        artist = artist,
        durationMs = durationMs,
        thumbnailUrl = "https://i.ytimg.com/vi/$sourceId/hqdefault.jpg"
    )

    private open class FakeProvider(
        override val providerType: OnlineProviderType
    ) : OnlineMusicProvider {
        override val supportsPagination: Boolean = true
        override suspend fun search(query: String, filter: OnlineSearchFilter, pageToken: String?): OnlineSearchPage {
            return OnlineSearchPage(query = query, providerUsed = providerType)
        }
        override suspend fun suggestions(query: String): List<String> = emptyList()
        override suspend fun trending(): List<OnlineTrack> = emptyList()
        override suspend fun resolveStream(track: OnlineTrack, speedTier: NetworkSpeedTier): OnlineStreamResolution {
            throw UnsupportedOperationException()
        }
        override suspend fun fetchArtist(artistIdOrUrl: String): OnlineArtistPage {
            throw UnsupportedOperationException()
        }
        override suspend fun fetchAlbumOrPlaylist(albumIdOrUrl: String): OnlineAlbumPage {
            throw UnsupportedOperationException()
        }
    }
}
