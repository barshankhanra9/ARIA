package app.nocturne.player.data.model

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackMode(val label: String) {
    OFFLINE("Offline Music"),
    ONLINE("Online Music")
}

@Serializable
enum class ThemeMode(val label: String) {
    PAPER_LIGHT("Warm Ivory Paper"),
    PAPER_DARK("Charcoal Paper"),
    AMOLED_BLACK("AMOLED Ink")
}

@Serializable
enum class PaperAccent(val label: String, val lightHex: Long, val darkHex: Long) {
    TERRACOTTA("Terracotta", 0xFFB85332, 0xFFD97757),
    BURNT_OCHRE("Burnt Ochre", 0xFFA66B24, 0xFFD69A4A),
    MUTED_OLIVE("Muted Olive", 0xFF566841, 0xFF839B68),
    OXBLOOD("Oxblood Ink", 0xFF8A3838, 0xFFC26464)
}

@Serializable
enum class ArtworkShape(val label: String) {
    PHOTO_PRINT("Printed Photo"),
    SOFT_SQUARE("Soft Folio"),
    ROUNDED_JOURNAL("Journal Card"),
    VINYL_SLEEVE("Vinyl Cutout")
}

@Serializable
enum class AnimationIntensity(val label: String) {
    FULL("Full Tactile Motion"),
    SUBTLE("Subtle Paper Motion"),
    REDUCED("Minimal / Reduced")
}

@Serializable
enum class LibrarySection(val label: String) {
    ALL_SONGS("All Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    ALBUM_ARTISTS("Album Artists"),
    GENRES("Genres"),
    PLAYLISTS("Playlists"),
    FOLDERS("Folders"),
    RECENTLY_ADDED("Recently Added"),
    RECENTLY_PLAYED("Recently Played"),
    MOST_PLAYED("Most Played"),
    FAVORITES("Favorites"),
    QUEUE("Queue")
}

@Serializable
enum class SortField(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DURATION("Duration"),
    DATE_ADDED("Date Added"),
    DATE_MODIFIED("Date Modified"),
    TRACK_NUMBER("Track Number"),
    PLAY_COUNT("Play Count"),
    YEAR("Year")
}

@Serializable
enum class SortOrder(val label: String) {
    ASC("Ascending"),
    DESC("Descending")
}

@Serializable
enum class SwipeVerticalAction(val label: String) {
    OPEN_QUEUE("Open Queue"),
    OPEN_LYRICS("Open Lyrics"),
    TOGGLE_FAVORITE("Toggle Favorite"),
    OPEN_AUDIO_FX("Open Audio FX"),
    DISMISS_PLAYER("Minimize Player"),
    NONE("Disabled")
}

@Serializable
enum class ArtworkTapAction(val label: String) {
    TOGGLE_LYRICS("Toggle Lyrics Overlay"),
    SHOW_AUDIO_INFO("Show Format & Tag Info"),
    PLAY_PAUSE("Play / Pause"),
    OPEN_TAG_EDITOR("Edit Track Tags"),
    NONE("Disabled")
}

@Serializable
enum class ReplayGainMode(val label: String) {
    OFF("Off"),
    TRACK("Track Gain"),
    ALBUM("Album Gain")
}

@Serializable
enum class HomeSectionId(val defaultTitle: String) {
    CONTINUE_LISTENING("Continue Listening"),
    RECENTLY_PLAYED("Recently Played"),
    RECENTLY_ADDED("Recently Added"),
    MOST_PLAYED("Most Played"),
    FAVORITES("Favorite Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    GENRES("Genres"),
    PLAYLISTS("Playlists"),
    LISTENING_STATS("Listening Journal")
}

@Serializable
data class HomeSectionConfig(
    val id: HomeSectionId,
    val visible: Boolean = true,
    val order: Int = id.ordinal,
    val compact: Boolean = false
)

@Serializable
data class UserSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.PAPER_LIGHT,
    val accent: PaperAccent = PaperAccent.TERRACOTTA,
    val paperTextureIntensity: Float = 0.65f,
    val artworkShape: ArtworkShape = ArtworkShape.PHOTO_PRINT,
    val animationIntensity: AnimationIntensity = AnimationIntensity.FULL,
    val fontScaleMultiplier: Float = 1.0f,
    val defaultGridLayout: Boolean = false,
    val sectionGridOverrides: Map<String, Boolean> = emptyMap(),

    // Home customization
    val homeSections: List<HomeSectionConfig> = HomeSectionId.entries.map {
        HomeSectionConfig(id = it, visible = true, order = it.ordinal, compact = false)
    },

    // Playback
    val audioFocusEnabled: Boolean = true,
    val gaplessPlayback: Boolean = true,
    val crossfadeSeconds: Int = 0,
    val resumePlaybackOnStart: Boolean = true,
    val pauseOnHeadsetDisconnect: Boolean = true,
    val resumeOnBluetoothConnect: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerDefaultMinutes: Int = 30,
    val showRemainingDuration: Boolean = false,
    val notificationShowSeekBar: Boolean = true,

    // Gestures
    val swipeHorizontalTrackChange: Boolean = true,
    val swipeUpAction: SwipeVerticalAction = SwipeVerticalAction.OPEN_QUEUE,
    val swipeDownAction: SwipeVerticalAction = SwipeVerticalAction.DISMISS_PLAYER,
    val artworkTapAction: ArtworkTapAction = ArtworkTapAction.TOGGLE_LYRICS,

    // Library
    val defaultSortField: SortField = SortField.TITLE,
    val defaultSortOrder: SortOrder = SortOrder.ASC,
    val sectionSortFieldOverrides: Map<String, SortField> = emptyMap(),
    val sectionSortOrderOverrides: Map<String, SortOrder> = emptyMap(),
    val includedSafFolderUris: List<String> = emptyList(),
    val excludedFolders: List<String> = emptyList(),
    val includeHiddenFolders: Boolean = false,
    val preferFolderCoverArt: Boolean = true,

    // Audio FX
    val eqEnabled: Boolean = false,
    val eqPresetIndex: Int = -1,
    val eqCustomBandLevelsMb: List<Int> = emptyList(),
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val loudnessGainMb: Int = 0,
    val channelBalance: Float = 0f,
    val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
    val replayGainPreampDb: Float = 0f,

    // Lyrics
    val lyricsFontSizeSp: Float = 19f,
    val lyricsSyncOffsetMs: Long = 0L,

    // Playback Mode
    val activePlaybackMode: PlaybackMode = PlaybackMode.OFFLINE,

    // Online Recommendations & Infinite Radio
    val infiniteRadioEnabled: Boolean = true,

    // Remote Provider Config
    val remoteProviderConfigUrl: String = DEFAULT_REMOTE_CONFIG_URL
) {
    companion object {
        const val LEGACY_REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/nocturne-player/aria-providers/main/providers.json"
        const val DEFAULT_REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/barshankhanra9/aria-providers/refs/heads/main/providers.json"
    }
}

@Serializable
data class RemoteProvidersConfig(
    val schemaVersion: Int = 2,
    val configVersion: String = "0",
    val refreshIntervalSeconds: Int = 1800,
    val providers: List<DynamicProviderConfig> = emptyList(),
    val lyricsProviders: List<DynamicLyricsProviderConfig> = emptyList(),
    val lyricsCleaning: LyricsCleaningConfig = LyricsCleaningConfig(),
    val providerDefaults: ProviderDefaultsConfig = ProviderDefaultsConfig(),
    val etag: String? = null,
    val lastModified: String? = null,
    val updatedAtMs: Long = 0L
) {
    val jiosaavn: List<String>
        get() = endpointsForProvider("jiosaavn")

    val piped: List<String>
        get() = endpointsForProvider("piped")

    val invidious: List<String>
        get() = endpointsForProvider("invidious")

    val audius: List<String>
        get() = endpointsForProvider("audius")

    val lrclib: List<String>
        get() = lyricsProviders
            .filter { it.enabled }
            .sortedBy { it.priority }
            .flatMap { it.resolvedEndpoints() }
            .distinct()

    fun lyricsProviderById(id: String): DynamicLyricsProviderConfig? =
        lyricsProviders.firstOrNull {
            it.id.equals(id, ignoreCase = true) || it.adapterType.equals(id, ignoreCase = true)
        }

    fun lrclibConfig(): DynamicLyricsProviderConfig? =
        lyricsProviders
            .filter { it.enabled && (it.id.equals("lrclib", ignoreCase = true) || it.adapterType.equals("lrclib", ignoreCase = true)) }
            .sortedBy { it.priority }
            .firstOrNull()

    fun lrclibEndpointsOnly(): List<String> =
        lyricsProviders
            .filter { it.enabled && (it.id.equals("lrclib", ignoreCase = true) || it.adapterType.equals("lrclib", ignoreCase = true)) }
            .sortedBy { it.priority }
            .flatMap { it.resolvedEndpoints() }
            .distinct()

    fun liriqoConfig(): DynamicLyricsProviderConfig? =
        lyricsProviderById("liriqo")?.takeIf { it.enabled }

    fun musixmatchConfig(): DynamicLyricsProviderConfig? =
        lyricsProviderById("musixmatch")?.takeIf { it.enabled }

    fun jiosaavnLyricsConfig(): DynamicLyricsProviderConfig? {
        val explicitLyrics = lyricsProviders.firstOrNull {
            it.id.equals("jiosaavn_lyrics", ignoreCase = true) ||
                it.id.equals("jiosaavnlyrics", ignoreCase = true) ||
                it.adapterType.equals("jiosaavn_lyrics", ignoreCase = true) ||
                it.id.equals("jiosaavn", ignoreCase = true)
        }
        val musicJioSaavn = providers.firstOrNull {
            it.id.equals("jiosaavn", ignoreCase = true) ||
                it.adapterType.equals("jiosaavn", ignoreCase = true)
        }
        if (explicitLyrics != null) {
            if (!explicitLyrics.enabled || !explicitLyrics.supportsCapability("lyrics")) return null
            if (musicJioSaavn != null && !musicJioSaavn.enabled) {
                return null
            }
            return if (explicitLyrics.resolvedEndpoints().isEmpty() && musicJioSaavn != null) {
                explicitLyrics.copy(
                    endpoints = musicJioSaavn.endpoints,
                    instances = musicJioSaavn.baseUrls(),
                    headers = musicJioSaavn.headers + explicitLyrics.headers,
                    params = musicJioSaavn.params + explicitLyrics.params
                )
            } else {
                explicitLyrics
            }
        }
        if (musicJioSaavn != null && musicJioSaavn.enabled && musicJioSaavn.supportsCapability("lyrics")) {
            return DynamicLyricsProviderConfig(
                id = "jiosaavn_lyrics",
                displayName = "${musicJioSaavn.displayName} Lyrics",
                category = "lyrics",
                adapterType = "jiosaavn_lyrics",
                enabled = true,
                priority = musicJioSaavn.priority,
                capabilities = musicJioSaavn.capabilities,
                endpoints = musicJioSaavn.endpoints,
                instances = musicJioSaavn.baseUrls(),
                timeoutMs = musicJioSaavn.timeoutMs,
                maxRetries = musicJioSaavn.maxRetries,
                cooldownMs = musicJioSaavn.cooldownMs,
                endpointFailover = musicJioSaavn.endpointFailover,
                headers = musicJioSaavn.headers,
                params = musicJioSaavn.params
            )
        }
        return null
    }

    fun providerById(id: String): DynamicProviderConfig? =
        providers.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun enabledProviders(): List<DynamicProviderConfig> =
        providers.filter { it.enabled }.sortedBy { it.priority }

    fun endpointsForProvider(id: String): List<String> =
        providerById(id)
            ?.takeIf { it.enabled }
            ?.endpoints
            ?.filter { it.enabled }
            ?.sortedBy { it.priority }
            ?.map { it.url }
            ?.distinct()
            .orEmpty()

    fun distinctCategories(): List<String> {
        val providerCats = providers.flatMap { it.effectiveCategories() }
        val all = if (lyricsProviders.any { it.enabled && (it.resolvedEndpoints().isNotEmpty() || it.id.equals("musixmatch", ignoreCase = true)) }) {
            providerCats + "Lyrics"
        } else {
            providerCats
        }
        return all.distinct()
    }

    companion object {
        val DEFAULT = RemoteProvidersConfig()
    }
}

data class Track(
    val id: Long,
    val uriString: String,
    val filePath: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val composer: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val mimeType: String,
    val formatLabel: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val channelCount: Int,
    val folderPath: String,
    val folderName: String,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val isFavorite: Boolean = false,
    val totalListenMs: Long = 0L,
    val replayGainTrackDb: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val customCoverUri: String? = null,
    val embeddedLyrics: String? = null,
    val isOnline: Boolean = false,
    val onlineTrackId: String = "",
    val onlineSourceId: String = "",
    val onlineProvider: String = "",
    val artworkUrl: String? = null,
    val artistUrl: String? = null,
    val albumUrl: String? = null
) {
    val uri: Uri get() = Uri.parse(uriString)
}

data class Album(
    val name: String,
    val artist: String,
    val year: Int,
    val tracks: List<Track>,
    val representativeUri: String
)

data class Artist(
    val name: String,
    val tracks: List<Track>,
    val albumCount: Int,
    val representativeUri: String
)

data class Genre(
    val name: String,
    val tracks: List<Track>
)

data class MusicFolder(
    val path: String,
    val name: String,
    val isSafTree: Boolean,
    val tracks: List<Track>
)

enum class SmartPlaylistType(val title: String, val subtitle: String) {
    RECENTLY_PLAYED("Recently Played", "Your latest listening footprints"),
    MOST_PLAYED("Most Played", "Heavy rotation from your archive"),
    FAVORITES("Favorites", "Hearted tracks kept close"),
    RECENTLY_ADDED("Recently Added", "Fresh arrivals in your library")
}

data class Playlist(
    val id: Long,
    val name: String,
    val description: String,
    val coverUri: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val trackUris: List<String>
)

data class LrcWord(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

data class LrcLine(
    val timeMs: Long,
    val text: String,
    val endTimeMs: Long = timeMs + 3500L,
    val words: List<LrcWord> = emptyList(),
    val isExplicitWordSync: Boolean = false
)

enum class LyricsSource(val label: String) {
    EMBEDDED("Embedded Tag"),
    SIDECAR_LRC("Local .lrc File"),
    LIRIQO("LiriQo Synced"),
    MUSIXMATCH("Musixmatch Synced"),
    JIOSAAVN("JioSaavn Lyrics"),
    LRCLIB("LRCLIB Synced"),
    MANUAL("User Note"),
    NONE("No Lyrics")
}

data class LyricsData(
    val syncedLines: List<LrcLine> = emptyList(),
    val rawText: String = "",
    val source: LyricsSource = LyricsSource.NONE,
    val isLoading: Boolean = false,
    val isInstrumental: Boolean = false,
    val fromCache: Boolean = false
) {
    val isSynced: Boolean get() = syncedLines.isNotEmpty()
    val hasContent: Boolean get() = syncedLines.isNotEmpty() || rawText.isNotBlank() || isInstrumental
}

data class AudioEngineTelemetry(
    val audioSessionId: Int = 0,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val encodingName: String = "",
    val replayGainAppliedDb: Float = 0f,
    val equalizerSupported: Boolean = false,
    val bassBoostSupported: Boolean = false,
    val virtualizerSupported: Boolean = false,
    val loudnessSupported: Boolean = false,
    val bandCount: Int = 0,
    val bandCenterHz: List<Int> = emptyList(),
    val minBandLevelMb: Int = -1500,
    val maxBandLevelMb: Int = 1500,
    val presetNames: List<String> = emptyList(),
    val activeErrorMessage: String? = null
)

data class DuplicateGroup(
    val keyLabel: String,
    val tracks: List<Track>
)

data class StorageDiagnostics(
    val totalTracks: Int = 0,
    val totalSizeBytes: Long = 0L,
    val totalDurationMs: Long = 0L,
    val formatBreakdown: Map<String, Int> = emptyMap(),
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val missingTrackUris: List<String> = emptyList()
)

@Serializable
data class AriaBackupBundle(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: UserSettings,
    val favoriteUris: List<String>,
    val playlists: List<BackupPlaylist>,
    val recentSearches: List<String>
)

@Serializable
data class BackupPlaylist(
    val name: String,
    val description: String,
    val coverUri: String?,
    val trackUris: List<String>
)
