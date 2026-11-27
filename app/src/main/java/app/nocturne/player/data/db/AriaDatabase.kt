package app.nocturne.player.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "track_stats")
data class TrackStatsEntity(
    @PrimaryKey val uriString: String,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val isFavorite: Boolean = false,
    val totalListenMs: Long = 0L
)

@Entity(tableName = "track_tag_overrides")
data class TrackTagOverrideEntity(
    @PrimaryKey val uriString: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val composer: String? = null,
    val lyrics: String? = null,
    val customCoverUri: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val coverUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId")]
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0L,
    val playlistId: Long,
    val trackUri: String,
    val position: Int
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis()
)

// ---- ONLINE MUSIC ENTITIES ----

@Entity(tableName = "online_tracks")
data class OnlineTrackEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val providerType: String,
    val providerLabel: String,
    val sourceUrl: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val artistUrl: String? = null,
    val album: String = "Online Stream",
    val albumUrl: String? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
    val viewCount: Long = 0L,
    val publishedText: String = "",
    val isLive: Boolean = false,
    val isFavorite: Boolean = false,
    val favoritedAt: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val totalListenMs: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "online_listening_history",
    indices = [Index("trackId"), Index("playedAt")]
)
data class OnlineHistoryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0L,
    val trackId: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "online_playlists")
data class OnlinePlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "online_playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = OnlinePlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index("trackId")]
)
data class OnlinePlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0L,
    val playlistId: Long,
    val trackId: String,
    val position: Int
)

@Entity(tableName = "online_recent_searches")
data class OnlineRecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "lyrics_cache",
    indices = [Index("normalizedKey"), Index("updatedAt")]
)
data class LyricsCacheEntity(
    @PrimaryKey val cacheKey: String,
    val trackId: String,
    val normalizedKey: String,
    val title: String,
    val artist: String,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val isInstrumental: Boolean = false,
    val source: String = "LRCLIB",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "user_affinity",
    indices = [
        Index("artist"),
        Index("affinityScore"),
        Index("lastPlayedAt"),
        Index("playCount")
    ]
)
data class UserAffinityEntity(
    @PrimaryKey val trackId: String,
    val artist: String,
    val artistId: String? = null,
    val artistUrl: String? = null,
    val title: String = "",
    val album: String = "Online Stream",
    val albumUrl: String? = null,
    val sourceId: String = "",
    val providerType: String = "JIOSAAVN",
    val providerId: String = "jiosaavn",
    val providerLabel: String = "JioSaavn",
    val sourceUrl: String = "",
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val isLiked: Boolean = false,
    val lastPlayedAt: Long = 0L,
    val lastSkippedAt: Long = 0L,
    val affinityScore: Double = 0.0,
    val lastDecayAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "artist_affinity",
    indices = [Index("affinityScore"), Index("lastPlayedAt")]
)
data class ArtistAffinityEntity(
    @PrimaryKey val artistKey: String,
    val artistName: String,
    val artistId: String? = null,
    val artistUrl: String? = null,
    val providerType: String = "JIOSAAVN",
    val providerId: String = "jiosaavn",
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val likedCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val affinityScore: Double = 0.0,
    val lastDecayAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "affinity_state")
data class AffinityStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastDecayAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "downloaded_tracks",
    indices = [Index("status"), Index("downloadedAt")]
)
data class DownloadedTrackEntity(
    @PrimaryKey val trackId: String,
    val sourceId: String,
    val providerType: String,
    val providerId: String = "jiosaavn",
    val providerLabel: String = "JioSaavn",
    val sourceUrl: String = "",
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val artistUrl: String? = null,
    val album: String = "Online Stream",
    val albumUrl: String? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
    val localAudioPath: String = "",
    val localArtworkPath: String? = null,
    val localLyricsPath: String? = null,
    val mimeType: String = "audio/mp4",
    val formatLabel: String = "M4A",
    val bitrateKbps: Int = 0,
    val fileSizeBytes: Long = 0L,
    val status: String = STATUS_DOWNLOADING,
    val progressPercent: Int = 0,
    val errorMessage: String? = null,
    val downloadedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
    }
}

@Entity(tableName = "downloaded_playlists")
data class DownloadedPlaylistEntity(
    @PrimaryKey val id: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String? = null,
    val localCoverPath: String? = null,
    val providerType: String = "JIOSAAVN",
    val providerId: String = "jiosaavn",
    val trackCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "downloaded_playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = DownloadedPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index("trackId")]
)
data class DownloadedPlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0L,
    val playlistId: String,
    val trackId: String,
    val position: Int
)

@Dao
interface TrackStatsDao {
    @Query("SELECT * FROM track_stats")
    fun observeAllStats(): Flow<List<TrackStatsEntity>>

    @Query("SELECT * FROM track_stats")
    suspend fun getAllStatsOnce(): List<TrackStatsEntity>

    @Query("SELECT * FROM track_stats WHERE uriString = :uriString")
    suspend fun getByUri(uriString: String): TrackStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackStatsEntity)

    @Query("SELECT * FROM track_stats WHERE isFavorite = 1")
    suspend fun getFavoritesOnce(): List<TrackStatsEntity>
}

@Dao
interface TrackTagOverrideDao {
    @Query("SELECT * FROM track_tag_overrides")
    fun observeAllOverrides(): Flow<List<TrackTagOverrideEntity>>

    @Query("SELECT * FROM track_tag_overrides")
    suspend fun getAllOverridesOnce(): List<TrackTagOverrideEntity>

    @Query("SELECT * FROM track_tag_overrides WHERE uriString = :uriString")
    suspend fun getByUri(uriString: String): TrackTagOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackTagOverrideEntity)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    suspend fun getPlaylistsOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId ASC, position ASC")
    fun observeAllEntries(): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getEntriesForPlaylist(playlistId: Long): List<PlaylistEntryEntity>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: Long)

    @Transaction
    suspend fun replacePlaylistEntries(playlistId: Long, trackUris: List<String>) {
        clearEntries(playlistId)
        val entries = trackUris.mapIndexed { index, uri ->
            PlaylistEntryEntity(playlistId = playlistId, trackUri = uri, position = index)
        }
        if (entries.isNotEmpty()) insertEntries(entries)
    }
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT 15")
    fun observeRecentSearches(): Flow<List<RecentSearchEntity>>

    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT 15")
    suspend fun getRecentSearchesOnce(): List<RecentSearchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clearAll()
}

// ---- ONLINE DAOS ----

@Dao
interface OnlineTrackDao {
    @Query("SELECT * FROM online_tracks")
    fun observeAllTracks(): Flow<List<OnlineTrackEntity>>

    @Query("SELECT * FROM online_tracks")
    suspend fun getAllTracksOnce(): List<OnlineTrackEntity>

    @Query("SELECT * FROM online_tracks WHERE isFavorite = 1 ORDER BY favoritedAt DESC")
    fun observeFavorites(): Flow<List<OnlineTrackEntity>>

    @Query("SELECT * FROM online_tracks WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 100")
    fun observeRecentlyPlayed(): Flow<List<OnlineTrackEntity>>

    @Query("SELECT * FROM online_tracks WHERE playCount > 0 ORDER BY playCount DESC, lastPlayedAt DESC LIMIT 100")
    fun observeMostPlayed(): Flow<List<OnlineTrackEntity>>

    @Query("SELECT * FROM online_tracks WHERE id = :id")
    suspend fun getById(id: String): OnlineTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: OnlineTrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(track: OnlineTrackEntity): Long

    @Query("SELECT * FROM online_listening_history ORDER BY playedAt DESC LIMIT 250")
    fun observeHistory(): Flow<List<OnlineHistoryEntity>>

    @Insert
    suspend fun insertHistory(entry: OnlineHistoryEntity)

    @Query("DELETE FROM online_listening_history")
    suspend fun clearHistory()

    @Query("UPDATE online_tracks SET lastPlayedAt = 0")
    suspend fun resetLastPlayedTimestamps()
}

@Dao
interface OnlinePlaylistDao {
    @Query("SELECT * FROM online_playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<OnlinePlaylistEntity>>

    @Query("SELECT * FROM online_playlists ORDER BY updatedAt DESC")
    suspend fun getPlaylistsOnce(): List<OnlinePlaylistEntity>

    @Query("SELECT * FROM online_playlist_entries ORDER BY playlistId ASC, position ASC")
    fun observeAllEntries(): Flow<List<OnlinePlaylistEntryEntity>>

    @Query("SELECT * FROM online_playlist_entries WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getEntriesForPlaylist(playlistId: Long): List<OnlinePlaylistEntryEntity>

    @Insert
    suspend fun insertPlaylist(playlist: OnlinePlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: OnlinePlaylistEntity)

    @Query("DELETE FROM online_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert
    suspend fun insertEntries(entries: List<OnlinePlaylistEntryEntity>)

    @Query("DELETE FROM online_playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: Long)

    @Transaction
    suspend fun replaceEntries(playlistId: Long, trackIds: List<String>) {
        clearEntries(playlistId)
        val entries = trackIds.mapIndexed { idx, id ->
            OnlinePlaylistEntryEntity(playlistId = playlistId, trackId = id, position = idx)
        }
        if (entries.isNotEmpty()) insertEntries(entries)
    }
}

@Dao
interface OnlineSearchHistoryDao {
    @Query("SELECT * FROM online_recent_searches ORDER BY searchedAt DESC LIMIT 18")
    fun observeRecentSearches(): Flow<List<OnlineRecentSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: OnlineRecentSearchEntity)

    @Query("DELETE FROM online_recent_searches WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM online_recent_searches")
    suspend fun clearAll()
}

@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM lyrics_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getByCacheKey(cacheKey: String): LyricsCacheEntity?

    @Query("SELECT * FROM lyrics_cache WHERE trackId = :trackId OR normalizedKey = :normalizedKey ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getByTrackIdOrNormalized(trackId: String, normalizedKey: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsCacheEntity)

    @Query("DELETE FROM lyrics_cache WHERE trackId = :trackId OR cacheKey = :cacheKey")
    suspend fun deleteForTrack(trackId: String, cacheKey: String)
}

@Dao
interface UserAffinityDao {
    @Query("SELECT * FROM user_affinity WHERE playCount > 0 ORDER BY playCount DESC, affinityScore DESC, lastPlayedAt DESC LIMIT :limit")
    fun observeOnRepeatTracks(limit: Int = 12): Flow<List<UserAffinityEntity>>

    @Query("SELECT * FROM user_affinity WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeJumpBackInTracks(limit: Int = 12): Flow<List<UserAffinityEntity>>

    @Query("SELECT * FROM user_affinity WHERE affinityScore > 0 ORDER BY affinityScore DESC, playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun observeTopAffinityTracks(limit: Int = 5): Flow<List<UserAffinityEntity>>

    @Query("SELECT * FROM artist_affinity WHERE affinityScore > 0 ORDER BY affinityScore DESC, playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun observeTopArtists(limit: Int = 3): Flow<List<ArtistAffinityEntity>>

    @Query("SELECT * FROM user_affinity WHERE trackId = :trackId LIMIT 1")
    suspend fun getByTrackId(trackId: String): UserAffinityEntity?

    @Query("SELECT * FROM user_affinity ORDER BY affinityScore DESC, playCount DESC, lastPlayedAt DESC LIMIT :limit")
    suspend fun getTopAffinityTracks(limit: Int = 5): List<UserAffinityEntity>

    @Query("SELECT * FROM artist_affinity ORDER BY affinityScore DESC, playCount DESC, lastPlayedAt DESC LIMIT :limit")
    suspend fun getTopArtists(limit: Int = 3): List<ArtistAffinityEntity>

    @Query("SELECT * FROM user_affinity WHERE isLiked = 1 OR playCount > 0 ORDER BY isLiked DESC, affinityScore DESC, playCount DESC LIMIT :limit")
    suspend fun getFamiliarFavorites(limit: Int = 25): List<UserAffinityEntity>

    @Query("SELECT * FROM user_affinity WHERE playCount > 0 ORDER BY playCount DESC, affinityScore DESC, lastPlayedAt DESC LIMIT :limit")
    suspend fun getOnRepeatTracksOnce(limit: Int = 12): List<UserAffinityEntity>

    @Query("SELECT * FROM user_affinity WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun getJumpBackInTracksOnce(limit: Int = 12): List<UserAffinityEntity>

    @Query("SELECT trackId FROM user_affinity WHERE skipCount > 0 AND lastSkippedAt >= :sinceMs AND lastSkippedAt >= lastPlayedAt AND isLiked = 0")
    suspend fun getRecentlySkippedTrackIds(sinceMs: Long): List<String>

    @Query("SELECT trackId FROM user_affinity WHERE lastPlayedAt >= :sinceMs")
    suspend fun getRecentlyPlayedTrackIds(sinceMs: Long): List<String>

    @Query("SELECT trackId FROM online_listening_history WHERE playedAt >= :sinceMs")
    suspend fun getHistoryTrackIdsSince(sinceMs: Long): List<String>

    @Query("SELECT * FROM user_affinity")
    suspend fun getAllTrackAffinitiesOnce(): List<UserAffinityEntity>

    @Query("SELECT * FROM artist_affinity WHERE artistKey = :artistKey LIMIT 1")
    suspend fun getArtistAffinity(artistKey: String): ArtistAffinityEntity?

    @Query("SELECT * FROM artist_affinity")
    suspend fun getAllArtistAffinitiesOnce(): List<ArtistAffinityEntity>

    @Query("SELECT COUNT(*) FROM user_affinity")
    suspend fun countTrackedEntries(): Int

    @Query("SELECT * FROM affinity_state WHERE id = 1 LIMIT 1")
    suspend fun getAffinityState(): AffinityStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackAffinity(entity: UserAffinityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistAffinity(entity: ArtistAffinityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAffinityState(state: AffinityStateEntity)

    @Query("UPDATE user_affinity SET affinityScore = affinityScore * :decayFactor, lastDecayAt = :decayAt")
    suspend fun applyDecayToTracks(decayFactor: Double, decayAt: Long)

    @Query("UPDATE artist_affinity SET affinityScore = affinityScore * :decayFactor, lastDecayAt = :decayAt")
    suspend fun applyDecayToArtists(decayFactor: Double, decayAt: Long)

    @Query("DELETE FROM user_affinity")
    suspend fun clearTrackAffinities()

    @Query("DELETE FROM artist_affinity")
    suspend fun clearArtistAffinities()
}

@Dao
interface OfflineDownloadDao {
    @Query("SELECT * FROM downloaded_tracks WHERE status = 'COMPLETED' ORDER BY downloadedAt DESC")
    fun observeCompletedTracks(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAt DESC")
    fun observeAllTracks(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks WHERE trackId = :trackId LIMIT 1")
    suspend fun getByTrackId(trackId: String): DownloadedTrackEntity?

    @Query("SELECT * FROM downloaded_tracks WHERE trackId = :trackId AND status = 'COMPLETED' LIMIT 1")
    suspend fun getCompletedTrackOnce(trackId: String): DownloadedTrackEntity?

    @Query("SELECT * FROM downloaded_tracks WHERE status = 'COMPLETED' ORDER BY downloadedAt DESC")
    suspend fun getCompletedTracksOnce(): List<DownloadedTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(entity: DownloadedTrackEntity)

    @Query("UPDATE downloaded_tracks SET progressPercent = :progress WHERE trackId = :trackId")
    suspend fun updateProgress(trackId: String, progress: Int)

    @Query("DELETE FROM downloaded_tracks WHERE trackId = :trackId")
    suspend fun deleteTrack(trackId: String)

    @Query("SELECT * FROM downloaded_playlists ORDER BY createdAt DESC")
    fun observeDownloadedPlaylists(): Flow<List<DownloadedPlaylistEntity>>

    @Query("SELECT * FROM downloaded_playlist_entries ORDER BY playlistId ASC, position ASC")
    fun observeAllPlaylistEntries(): Flow<List<DownloadedPlaylistEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: DownloadedPlaylistEntity)

    @Insert
    suspend fun insertPlaylistEntries(entries: List<DownloadedPlaylistEntryEntity>)

    @Query("DELETE FROM downloaded_playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearPlaylistEntries(playlistId: String)

    @Query("DELETE FROM downloaded_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Transaction
    suspend fun replacePlaylist(playlist: DownloadedPlaylistEntity, trackIds: List<String>) {
        upsertPlaylist(playlist)
        clearPlaylistEntries(playlist.id)
        val entries = trackIds.mapIndexed { idx, tid ->
            DownloadedPlaylistEntryEntity(playlistId = playlist.id, trackId = tid, position = idx)
        }
        if (entries.isNotEmpty()) insertPlaylistEntries(entries)
    }
}

@Database(
    entities = [
        TrackStatsEntity::class,
        TrackTagOverrideEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        RecentSearchEntity::class,
        OnlineTrackEntity::class,
        OnlineHistoryEntity::class,
        OnlinePlaylistEntity::class,
        OnlinePlaylistEntryEntity::class,
        OnlineRecentSearchEntity::class,
        LyricsCacheEntity::class,
        UserAffinityEntity::class,
        ArtistAffinityEntity::class,
        AffinityStateEntity::class,
        DownloadedTrackEntity::class,
        DownloadedPlaylistEntity::class,
        DownloadedPlaylistEntryEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AriaDatabase : RoomDatabase() {
    abstract fun trackStatsDao(): TrackStatsDao
    abstract fun trackTagOverrideDao(): TrackTagOverrideDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    abstract fun onlineTrackDao(): OnlineTrackDao
    abstract fun onlinePlaylistDao(): OnlinePlaylistDao
    abstract fun onlineSearchHistoryDao(): OnlineSearchHistoryDao
    abstract fun lyricsCacheDao(): LyricsCacheDao
    abstract fun userAffinityDao(): UserAffinityDao
    abstract fun offlineDownloadDao(): OfflineDownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AriaDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `online_tracks` (
                        `id` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `providerType` TEXT NOT NULL,
                        `providerLabel` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `artistId` TEXT,
                        `artistUrl` TEXT,
                        `album` TEXT NOT NULL,
                        `albumUrl` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `thumbnailUrl` TEXT,
                        `viewCount` INTEGER NOT NULL,
                        `publishedText` TEXT NOT NULL,
                        `isLive` INTEGER NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        `favoritedAt` INTEGER NOT NULL,
                        `playCount` INTEGER NOT NULL,
                        `lastPlayedAt` INTEGER NOT NULL,
                        `totalListenMs` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `online_listening_history` (
                        `entryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `playedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_online_listening_history_trackId` ON `online_listening_history` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_online_listening_history_playedAt` ON `online_listening_history` (`playedAt`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `online_playlists` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `coverUrl` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `online_playlist_entries` (
                        `entryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlistId` INTEGER NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        FOREIGN KEY(`playlistId`) REFERENCES `online_playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_online_playlist_entries_playlistId` ON `online_playlist_entries` (`playlistId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_online_playlist_entries_trackId` ON `online_playlist_entries` (`trackId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `online_recent_searches` (
                        `query` TEXT NOT NULL,
                        `searchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`query`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lyrics_cache` (
                        `cacheKey` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `normalizedKey` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `syncedLyrics` TEXT,
                        `plainLyrics` TEXT,
                        `isInstrumental` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lyrics_cache_normalizedKey` ON `lyrics_cache` (`normalizedKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lyrics_cache_updatedAt` ON `lyrics_cache` (`updatedAt`)")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_affinity` (
                        `trackId` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `artistId` TEXT,
                        `artistUrl` TEXT,
                        `title` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `albumUrl` TEXT,
                        `sourceId` TEXT NOT NULL,
                        `providerType` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `providerLabel` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `thumbnailUrl` TEXT,
                        `playCount` INTEGER NOT NULL,
                        `skipCount` INTEGER NOT NULL,
                        `isLiked` INTEGER NOT NULL,
                        `lastPlayedAt` INTEGER NOT NULL,
                        `lastSkippedAt` INTEGER NOT NULL,
                        `affinityScore` REAL NOT NULL,
                        `lastDecayAt` INTEGER NOT NULL,
                        PRIMARY KEY(`trackId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_affinity_artist` ON `user_affinity` (`artist`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_affinity_affinityScore` ON `user_affinity` (`affinityScore`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_affinity_lastPlayedAt` ON `user_affinity` (`lastPlayedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_affinity_playCount` ON `user_affinity` (`playCount`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `artist_affinity` (
                        `artistKey` TEXT NOT NULL,
                        `artistName` TEXT NOT NULL,
                        `artistId` TEXT,
                        `artistUrl` TEXT,
                        `providerType` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `playCount` INTEGER NOT NULL,
                        `skipCount` INTEGER NOT NULL,
                        `likedCount` INTEGER NOT NULL,
                        `lastPlayedAt` INTEGER NOT NULL,
                        `affinityScore` REAL NOT NULL,
                        `lastDecayAt` INTEGER NOT NULL,
                        PRIMARY KEY(`artistKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_affinity_affinityScore` ON `artist_affinity` (`affinityScore`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_affinity_lastPlayedAt` ON `artist_affinity` (`lastPlayedAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `affinity_state` (
                        `id` INTEGER NOT NULL,
                        `lastDecayAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloaded_tracks` (
                        `trackId` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `providerType` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `providerLabel` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `artistId` TEXT,
                        `artistUrl` TEXT,
                        `album` TEXT NOT NULL,
                        `albumUrl` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `thumbnailUrl` TEXT,
                        `localAudioPath` TEXT NOT NULL,
                        `localArtworkPath` TEXT,
                        `localLyricsPath` TEXT,
                        `mimeType` TEXT NOT NULL,
                        `formatLabel` TEXT NOT NULL,
                        `bitrateKbps` INTEGER NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `progressPercent` INTEGER NOT NULL,
                        `errorMessage` TEXT,
                        `downloadedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`trackId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloaded_tracks_status` ON `downloaded_tracks` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloaded_tracks_downloadedAt` ON `downloaded_tracks` (`downloadedAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloaded_playlists` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `subtitle` TEXT NOT NULL,
                        `coverUrl` TEXT,
                        `localCoverPath` TEXT,
                        `providerType` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `trackCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloaded_playlist_entries` (
                        `entryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlistId` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        FOREIGN KEY(`playlistId`) REFERENCES `downloaded_playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloaded_playlist_entries_playlistId` ON `downloaded_playlist_entries` (`playlistId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloaded_playlist_entries_trackId` ON `downloaded_playlist_entries` (`trackId`)")
            }
        }

        fun getInstance(context: Context): AriaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AriaDatabase::class.java,
                    "aria_paper_library.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
