package app.nocturne.player

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAlbumPage
import app.nocturne.player.data.model.OnlineArtistPage
import app.nocturne.player.data.model.OnlineAudioStream
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineSearchPage
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.online.JioSaavnProvider
import app.nocturne.player.data.online.OnlineMusicProvider
import app.nocturne.player.data.online.OnlineProviderManager
import app.nocturne.player.data.online.PipedProvider
import app.nocturne.player.data.recommendation.AffinityRepository
import app.nocturne.player.data.recommendation.RecommendationEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecommendationEngineTest {

    private fun testClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun saavnTrack(
        sourceId: String,
        title: String = "Song $sourceId",
        artist: String = "Artist $sourceId",
        artistId: String? = "art_$sourceId"
    ): OnlineTrack = OnlineTrack(
        id = OnlineTrack.canonicalId("jiosaavn", sourceId),
        sourceId = sourceId,
        providerType = OnlineProviderType.JIOSAAVN,
        providerLabel = "JioSaavn",
        sourceUrl = "https://www.jiosaavn.com/song/$sourceId",
        title = title,
        artist = artist,
        artistId = artistId,
        album = "Album $sourceId",
        durationMs = 210_000L,
        thumbnailUrl = "https://c.saavncdn.com/000/${sourceId}_500x500.jpg",
        providerId = "jiosaavn"
    )

    private fun jioSongJson(
        id: String,
        name: String,
        artist: String,
        artistId: String = "art_$id",
        durationSec: Int = 200
    ): String {
        return """
            {
              "id": "$id",
              "name": "$name",
              "duration": "$durationSec",
              "url": "https://www.jiosaavn.com/song/$id",
              "album": { "id": "alb_$id", "name": "Album $id" },
              "artists": {
                "primary": [
                  { "id": "$artistId", "name": "$artist", "url": "https://www.jiosaavn.com/artist/$artistId" }
                ]
              },
              "image": [
                { "quality": "500x500", "url": "https://c.saavncdn.com/111/${id}_500x500.jpg" }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun roomMigration3To4_preservesV3DataAndCreatesAffinityTables() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "test_aria_v3_v4_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val v3Helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `track_stats` (`uriString` TEXT NOT NULL, `playCount` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `totalListenMs` INTEGER NOT NULL, PRIMARY KEY(`uriString`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `track_tag_overrides` (`uriString` TEXT NOT NULL, `title` TEXT, `artist` TEXT, `album` TEXT, `albumArtist` TEXT, `genre` TEXT, `year` INTEGER, `trackNumber` INTEGER, `discNumber` INTEGER, `composer` TEXT, `customCoverUri` TEXT, `lyrics` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`uriString`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `coverUri` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_entries` (`entryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `trackUri` TEXT NOT NULL, `position` INTEGER NOT NULL, FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entries_playlistId` ON `playlist_entries` (`playlistId`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `recent_searches` (`query` TEXT NOT NULL, `searchedAt` INTEGER NOT NULL, PRIMARY KEY(`query`))")
                        AriaDatabase.MIGRATION_1_2.migrate(db)
                        AriaDatabase.MIGRATION_2_3.migrate(db)

                        val cv = ContentValues().apply {
                            put("id", "JIOSAAVN:legacy1")
                            put("sourceId", "legacy1")
                            put("providerType", "JIOSAAVN")
                            put("providerLabel", "JioSaavn")
                            put("sourceUrl", "https://www.jiosaavn.com/song/legacy1")
                            put("title", "Preserved Track")
                            put("artist", "Preserved Artist")
                            put("album", "Preserved Album")
                            put("durationMs", 180000L)
                            put("viewCount", 0L)
                            put("publishedText", "2024")
                            put("isLive", 0)
                            put("isFavorite", 1)
                            put("favoritedAt", 1000L)
                            put("playCount", 4)
                            put("lastPlayedAt", 2000L)
                            put("totalListenMs", 120000L)
                            put("addedAt", 1000L)
                        }
                        db.insert("online_tracks", SQLiteDatabase.CONFLICT_REPLACE, cv)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v3Helper.writableDatabase.close()
        v3Helper.close()

        val db = Room.databaseBuilder(context, AriaDatabase::class.java, dbName)
            .addMigrations(AriaDatabase.MIGRATION_1_2, AriaDatabase.MIGRATION_2_3, AriaDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        try {
            val preserved = db.onlineTrackDao().getById("JIOSAAVN:legacy1")
            assertNotNull(preserved)
            assertEquals("Preserved Track", preserved!!.title)

            val repo = AffinityRepository(db)
            val t = saavnTrack("migrated_1", title = "V4 Song", artist = "V4 Artist")
            repo.recordTrackCompleted(t)
            val saved = db.userAffinityDao().getByTrackId(t.id)
            assertNotNull(saved)
            assertEquals(3.0, saved!!.affinityScore, 0.001)
        } finally {
            db.close()
            dbFile.delete()
        }
    }

    @Test
    fun affinityRepository_scoresCompletionLikePlaylistSkipAndWeeklyDecay() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AriaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            var nowMs = 1_700_000_000_000L
            val repo = AffinityRepository(db) { nowMs }
            val track = saavnTrack("t1", title = "Starlight", artist = "Arijit Singh")

            // +3.0 on >80% listen
            repo.recordTrackCompleted(track)
            var entity = db.userAffinityDao().getByTrackId(track.id)!!
            assertEquals(3.0, entity.affinityScore, 0.001)
            assertEquals(1, entity.playCount)

            // +5.0 on like (no double-count when called again with true)
            repo.recordTrackLiked(track, isLiked = true)
            repo.recordTrackLiked(track, isLiked = true)
            entity = db.userAffinityDao().getByTrackId(track.id)!!
            assertEquals(8.0, entity.affinityScore, 0.001)
            assertTrue(entity.isLiked)

            // +5.0 on playlist add
            repo.recordTrackAddedToPlaylist(track)
            entity = db.userAffinityDao().getByTrackId(track.id)!!
            assertEquals(13.0, entity.affinityScore, 0.001)

            // -3.0 on early skip (<15s)
            repo.recordTrackSkippedEarly(track)
            entity = db.userAffinityDao().getByTrackId(track.id)!!
            assertEquals(10.0, entity.affinityScore, 0.001)
            assertEquals(1, entity.skipCount)

            // Advance 1 week -> 0.95x decay applied automatically
            nowMs += AffinityRepository.WEEK_MS + 1000L
            val weeksDecayed = repo.applyWeeklyDecayIfDue()
            assertEquals(1, weeksDecayed)
            entity = db.userAffinityDao().getByTrackId(track.id)!!
            assertEquals(9.5, entity.affinityScore, 0.001)

            val artistEntity = db.userAffinityDao().getArtistAffinity("arijit singh")!!
            assertEquals(9.5, artistEntity.affinityScore, 0.001)
        } finally {
            db.close()
        }
    }

    @Test
    fun recommendationEngine_brandNewUserFallsBackToJioSaavnTrending() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AriaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val songs = (1..5).joinToString(",") { idx ->
                    jioSongJson("trend_$idx", "Trending Hit $idx", "Chart Artist $idx")
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setBody("""{ "success": true, "data": { "results": [$songs] } }""")
            }
        }
        server.start()

        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val jio = JioSaavnProvider(client = testClient(), baseUrlsOverride = listOf(baseUrl))
            val manager = OnlineProviderManager(
                providers = mapOf(OnlineProviderType.JIOSAAVN to jio),
                allowLoopbackForTests = true
            )
            val affinityRepo = AffinityRepository(db)
            val engine = RecommendationEngine(affinityRepo, manager)

            val recs = engine.buildHomeRecommendations()
            assertTrue(recs.isBrandNewUser)
            assertTrue(recs.onRepeat.isEmpty())
            assertTrue(recs.jumpBackIn.isEmpty())
            assertEquals(5, recs.trendingFallback.size)
            assertEquals("Trending Hit 1", recs.trendingFallback.first().title)
        } finally {
            server.shutdown()
            db.close()
        }
    }

    @Test
    fun recommendationEngine_generatesDailyMix8020ArtistShelfAndOfflineRoomShelves() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AriaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/suggestions") -> {
                        val songs = (1..12).joinToString(",") { idx ->
                            val id = if (idx == 1) "skipped_bad_song" else "sug_$idx"
                            val title = if (idx == 1) "Skipped Bad Song" else "Suggested Track $idx"
                            jioSongJson(id, title, "Discovery Artist $idx")
                        }
                        MockResponse().setResponseCode(200).setBody("""{ "success": true, "data": [$songs] }""")
                    }
                    path.contains("/artists/") -> {
                        val topSongs = (1..6).joinToString(",") { idx ->
                            jioSongJson("top_art_$idx", "Pritam Classic $idx", "Pritam", artistId = "pritam_1")
                        }
                        MockResponse().setResponseCode(200).setBody(
                            """{ "success": true, "data": { "id": "pritam_1", "name": "Pritam", "topSongs": [$topSongs], "topAlbums": [] } }"""
                        )
                    }
                    else -> {
                        val fallbackSongs = (1..5).joinToString(",") { idx ->
                            jioSongJson("fb_$idx", "Fallback $idx", "Pritam")
                        }
                        MockResponse().setResponseCode(200).setBody("""{ "success": true, "data": { "results": [$fallbackSongs] } }""")
                    }
                }
            }
        }
        server.start()

        try {
            var nowMs = 1_700_000_000_000L
            var currentDateSeed = 2026329L
            val baseUrl = server.url("").toString().trimEnd('/')
            val jio = JioSaavnProvider(client = testClient(), baseUrlsOverride = listOf(baseUrl))
            val manager = OnlineProviderManager(
                providers = mapOf(OnlineProviderType.JIOSAAVN to jio),
                allowLoopbackForTests = true
            )
            val affinityRepo = AffinityRepository(db) { nowMs }
            val engine = RecommendationEngine(affinityRepo, manager) { currentDateSeed }

            // Seed Room history: 3 familiar favorites (Pritam is top artist) + 1 recently skipped track
            val fav1 = saavnTrack("fav_1", title = "Kesariya", artist = "Pritam", artistId = "pritam_1")
            val fav2 = saavnTrack("fav_2", title = "Ilahi", artist = "Pritam", artistId = "pritam_1")
            val fav3 = saavnTrack("fav_3", title = "Raabta", artist = "Pritam", artistId = "pritam_1")
            val skippedTrack = saavnTrack("skipped_bad_song", title = "Skipped Bad Song", artist = "Discovery Artist 1")

            affinityRepo.recordTrackCompleted(fav1)
            affinityRepo.recordTrackLiked(fav1, true)
            nowMs += 10_000L
            affinityRepo.recordTrackCompleted(fav2)
            affinityRepo.recordTrackLiked(fav2, true)
            nowMs += 10_000L
            affinityRepo.recordTrackCompleted(fav3)
            nowMs += 10_000L
            affinityRepo.recordTrackSkippedEarly(skippedTrack)

            val recsDay1 = engine.buildHomeRecommendations(dailyMixTargetSize = 10)
            assertFalse(recsDay1.isBrandNewUser)

            // Instant Room shelves
            assertEquals(3, recsDay1.onRepeat.size)
            assertEquals(3, recsDay1.jumpBackIn.size)
            assertEquals("fav_3", recsDay1.jumpBackIn.first().sourceId) // Most recently played

            // "Because You Listen To [Top Artist]"
            assertEquals("Pritam", recsDay1.becauseYouListenToArtist)
            assertTrue(recsDay1.becauseYouListenToTracks.isNotEmpty())

            // Daily Mix: 10 tracks = 80% new (8) + 20% familiar (2), and skipped track excluded!
            assertEquals(10, recsDay1.dailyMix.size)
            val familiarIds = setOf(fav1.id, fav2.id, fav3.id)
            val familiarInMix = recsDay1.dailyMix.count { it.id in familiarIds }
            val newInMix = recsDay1.dailyMix.count { it.id !in familiarIds }
            assertEquals(2, familiarInMix)
            assertEquals(8, newInMix)
            assertFalse(recsDay1.dailyMix.any { it.sourceId == "skipped_bad_song" })

            // Same date seed produces identical deterministic Daily Mix order
            val recsDay1Repeat = engine.buildHomeRecommendations(dailyMixTargetSize = 10)
            assertEquals(recsDay1.dailyMix.map { it.id }, recsDay1Repeat.dailyMix.map { it.id })

            // New day seed refreshes Daily Mix order
            currentDateSeed = 2026330L
            val recsDay2 = engine.buildHomeRecommendations(dailyMixTargetSize = 10)
            assertNotEquals(recsDay1.dailyMix.map { it.id }, recsDay2.dailyMix.map { it.id })

            // Reactive Flow checks for instant On Repeat / Jump Back In
            assertEquals(3, affinityRepo.onRepeatTracks.first().size)
            assertEquals(3, affinityRepo.jumpBackInTracks.first().size)
        } finally {
            server.shutdown()
            db.close()
        }
    }

    @Test
    fun infiniteRadio_fetches10DiverseTracksExcludingRecent2HoursAndBackToBackSameArtist() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AriaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val jioServer = MockWebServer()
        jioServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Intentionally return consecutive same-artist tracks + a recently played track!
                val items = listOf(
                    jioSongJson("recent_2h", "Already Played Song", "Artist Alpha"),
                    jioSongJson("same_tail_1", "Back To Back With Seed", "Tail Artist"),
                    jioSongJson("rad_1", "Radio One", "Artist Alpha"),
                    jioSongJson("rad_2", "Radio Two", "Artist Alpha"), // consecutive Artist Alpha
                    jioSongJson("rad_3", "Radio Three", "Artist Beta"),
                    jioSongJson("rad_4", "Radio Four", "Artist Beta"), // consecutive Artist Beta
                    jioSongJson("rad_5", "Radio Five", "Artist Gamma"),
                    jioSongJson("rad_6", "Radio Six", "Artist Delta"),
                    jioSongJson("rad_7", "Radio Seven", "Artist Epsilon"),
                    jioSongJson("rad_8", "Radio Eight", "Artist Zeta")
                ).joinToString(",")
                return MockResponse().setResponseCode(200).setBody("""{ "success": true, "data": [$items] }""")
            }
        }
        jioServer.start()

        val pipedServer = MockWebServer()
        pipedServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val itemsJson = (1..6).joinToString(",") { idx ->
                    """
                    {
                      "type": "stream",
                      "url": "/watch?v=piped_$idx",
                      "title": "Piped Radio $idx",
                      "uploaderName": "Piped Artist $idx",
                      "uploaderUrl": "/channel/UC_$idx",
                      "duration": 215
                    }
                    """.trimIndent()
                }
                return if (path.startsWith("/search")) {
                    MockResponse().setResponseCode(200).setBody(
                        """{ "items": [$itemsJson], "nextpage": null }"""
                    )
                } else {
                    MockResponse().setResponseCode(200).setBody(
                        """{ "title": "Seed", "relatedStreams": [$itemsJson] }"""
                    )
                }
            }
        }
        pipedServer.start()

        try {
            var nowMs = 1_700_000_000_000L
            val jioUrl = jioServer.url("").toString().trimEnd('/')
            val pipedUrl = pipedServer.url("").toString().trimEnd('/')

            val jio = JioSaavnProvider(client = testClient(), baseUrlsOverride = listOf(jioUrl))
            val piped = PipedProvider(client = testClient(), baseUrlsOverride = listOf(pipedUrl))
            val manager = OnlineProviderManager(
                providers = mapOf(
                    OnlineProviderType.JIOSAAVN to jio,
                    OnlineProviderType.PIPED to piped
                ),
                allowLoopbackForTests = true
            )
            val affinityRepo = AffinityRepository(db) { nowMs }
            val engine = RecommendationEngine(affinityRepo, manager)

            val seedTrack = saavnTrack("seed_99", title = "Seed Song", artist = "Tail Artist")
            val recentTrack = saavnTrack("recent_2h", title = "Already Played Song", artist = "Artist Alpha")

            // Mark "recent_2h" as played 30 minutes ago (within 2 hour exclusion window)
            affinityRepo.recordTrackEngaged(recentTrack)
            nowMs += 30 * 60 * 1000L

            val radioQueue = engine.generateInfiniteRadioTracks(
                seedTrack = seedTrack,
                currentQueue = listOf(seedTrack),
                count = 10
            )

            assertEquals(10, radioQueue.size)

            // Excluded recent 2h song & seed song
            assertFalse(radioQueue.any { it.sourceId == "recent_2h" })
            assertFalse(radioQueue.any { it.id == seedTrack.id })

            // First radio track must NOT be by Tail Artist (the last song in currentQueue)
            assertNotEquals(
                AffinityRepository.normalizeArtistKey(seedTrack.artist),
                AffinityRepository.normalizeArtistKey(radioQueue.first().artist)
            )

            // Strictly no back-to-back songs by the same primary artist
            for (i in 1 until radioQueue.size) {
                val prevArtist = AffinityRepository.normalizeArtistKey(radioQueue[i - 1].artist)
                val curArtist = AffinityRepository.normalizeArtistKey(radioQueue[i].artist)
                assertNotEquals(
                    "Found back-to-back artist at index $i: $curArtist",
                    prevArtist,
                    curArtist
                )
            }
        } finally {
            jioServer.shutdown()
            pipedServer.shutdown()
            db.close()
        }
    }
}
