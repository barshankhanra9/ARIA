package app.nocturne.player.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.nocturne.player.PlaybackService
import app.nocturne.player.data.audio.EditableTagPayload
import app.nocturne.player.data.audio.LyricsRepository
import app.nocturne.player.data.audio.TagAndLyricsEngine
import app.nocturne.player.data.audio.TagWriteResult
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.db.PlaylistEntity
import app.nocturne.player.data.db.RecentSearchEntity
import app.nocturne.player.data.db.TrackStatsEntity
import app.nocturne.player.data.db.TrackTagOverrideEntity
import app.nocturne.player.data.library.ArtworkCache
import app.nocturne.player.data.library.MediaLibraryScanner
import app.nocturne.player.data.model.Album
import app.nocturne.player.data.model.AriaBackupBundle
import app.nocturne.player.data.model.Artist
import app.nocturne.player.data.model.AudioEngineTelemetry
import app.nocturne.player.data.model.BackupPlaylist
import app.nocturne.player.data.model.Genre
import app.nocturne.player.data.model.HomeSectionConfig
import app.nocturne.player.data.model.HomeSectionId
import app.nocturne.player.data.model.LibrarySection
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.model.LyricsSource
import app.nocturne.player.data.model.MusicFolder
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.PlaybackMode
import app.nocturne.player.data.model.Playlist
import app.nocturne.player.data.model.RemoteConfigConnectionState
import app.nocturne.player.data.model.RemoteConfigStatus
import app.nocturne.player.data.model.RemoteConfigUrlHelper
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.model.SmartPlaylistType
import app.nocturne.player.data.model.SortField
import app.nocturne.player.data.model.SortOrder
import app.nocturne.player.data.model.StorageDiagnostics
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.data.online.ConfigOperationResult
import app.nocturne.player.data.online.OnlineDependencies
import app.nocturne.player.data.playlist.PlaylistPortability
import app.nocturne.player.data.prefs.SettingsRepository
import app.nocturne.player.data.security.InputAndUrlSecurity
import app.nocturne.player.playback.ActiveAudioEngine
import app.nocturne.player.playback.OfflineEngineSnapshot
import app.nocturne.player.playback.OnlineEngineSnapshot
import app.nocturne.player.playback.PlaybackRuntimeBus
import app.nocturne.player.playback.SleepTimerState
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTrack: Track? = null,
    val currentUri: String = "",
    val queueTracks: List<Track> = emptyList(),
    val currentQueueIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val playbackSpeed: Float = 1.0f
)

data class LibraryDerivedState(
    val isScanning: Boolean = false,
    val allTracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albumArtists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val folders: List<MusicFolder> = emptyList(),
    val recentlyAdded: List<Track> = emptyList(),
    val recentlyPlayed: List<Track> = emptyList(),
    val mostPlayed: List<Track> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val diagnostics: StorageDiagnostics = StorageDiagnostics()
)

data class DisplayedPlaybackState(
    val engine: ActiveAudioEngine = ActiveAudioEngine.OFFLINE,
    val playbackUi: PlaybackUiState = PlaybackUiState(),
    val isBuffering: Boolean = false,
    val isResolving: Boolean = false,
    val activeStreamLabel: String = "",
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val db = AriaDatabase.getInstance(app)
    private val settingsRepo = SettingsRepository(app)
    private val scanner = MediaLibraryScanner(app)
    private val remoteConfigRepo = OnlineDependencies.getRemoteConfigRepository(app)
    private val lyricsRepository = LyricsRepository(
        context = app,
        db = db,
        remoteConfigRepository = remoteConfigRepo,
        healthTracker = OnlineDependencies.getHealthTracker()
    )

    val settings: StateFlow<UserSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val remoteConfig: StateFlow<RemoteProvidersConfig> = remoteConfigRepo.activeConfig
    val remoteConfigStatus: StateFlow<RemoteConfigStatus> = remoteConfigRepo.status
    val isRefreshingRemoteConfig: StateFlow<Boolean> = remoteConfigRepo.isRefreshing
    val lastRemoteConfigError: StateFlow<String?> = remoteConfigRepo.lastFetchError

    private val _remoteConfigConnectionState =
        MutableStateFlow<RemoteConfigConnectionState>(RemoteConfigConnectionState.Idle)
    val remoteConfigConnectionState: StateFlow<RemoteConfigConnectionState> =
        _remoteConfigConnectionState.asStateFlow()

    private val _optimisticPlaybackMode = MutableStateFlow<PlaybackMode?>(null)

    val playbackMode: StateFlow<PlaybackMode> = combine(
        settings,
        _optimisticPlaybackMode
    ) { s, overrideMode -> overrideMode ?: s.activePlaybackMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackMode.OFFLINE)

    val activeEngine: StateFlow<ActiveAudioEngine> = PlaybackRuntimeBus.activeEngine

    val telemetry: StateFlow<AudioEngineTelemetry> = PlaybackRuntimeBus.telemetry
    val sleepTimer: StateFlow<SleepTimerState> = PlaybackRuntimeBus.sleepTimer
    val presetBandPreview: StateFlow<List<Int>> = PlaybackRuntimeBus.presetBandPreview

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _rawTracks = MutableStateFlow<List<Track>>(emptyList())
    private val _isScanning = MutableStateFlow(false)
    private val _diagnostics = MutableStateFlow(StorageDiagnostics())

    private val _selectedTrackUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedTrackUris: StateFlow<Set<String>> = _selectedTrackUris.asStateFlow()
    val isMultiSelectActive: StateFlow<Boolean> = _selectedTrackUris
        .combine(_rawTracks) { sel, _ -> sel.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _currentLyrics = MutableStateFlow(LyricsData())
    val currentLyrics: StateFlow<LyricsData> = _currentLyrics.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    val online = OnlineCoordinator(app, viewModelScope, ::emitMessage)

    val displayedPlayback: StateFlow<DisplayedPlaybackState> = combine(
        _playback,
        online.snapshot,
        playbackMode,
        activeEngine
    ) { offlineUi, onlineSnap, uiMode, runningEngine ->
        val showOnline = when {
            onlineSnap.isPlaying || onlineSnap.isResolving || onlineSnap.isBuffering -> true
            offlineUi.isPlaying -> false
            uiMode == PlaybackMode.ONLINE -> true
            uiMode == PlaybackMode.OFFLINE -> false
            else -> runningEngine == ActiveAudioEngine.ONLINE
        }
        if (showOnline) {
            val uiTracks = onlineSnap.queue.map {
                it.toUiTrack(
                    formatTag = if (it.id == onlineSnap.currentTrack?.id && onlineSnap.activeStreamLabel.isNotBlank()) {
                        onlineSnap.activeStreamLabel
                    } else null
                )
            }
            val currentUiTrack = onlineSnap.currentTrack?.toUiTrack(
                formatTag = onlineSnap.activeStreamLabel.ifBlank { null }
            )
            DisplayedPlaybackState(
                engine = ActiveAudioEngine.ONLINE,
                playbackUi = PlaybackUiState(
                    isConnected = onlineSnap.isReady,
                    isPlaying = onlineSnap.isPlaying,
                    currentTrack = currentUiTrack,
                    currentUri = currentUiTrack?.uriString.orEmpty(),
                    queueTracks = uiTracks,
                    currentQueueIndex = onlineSnap.currentIndex.coerceIn(0, (uiTracks.size - 1).coerceAtLeast(0)),
                    positionMs = onlineSnap.positionMs,
                    durationMs = onlineSnap.durationMs,
                    shuffleEnabled = onlineSnap.shuffleEnabled,
                    repeatMode = onlineSnap.repeatMode,
                    playbackSpeed = onlineSnap.playbackSpeed
                ),
                isBuffering = onlineSnap.isBuffering,
                isResolving = onlineSnap.isResolving,
                activeStreamLabel = onlineSnap.activeStreamLabel,
                errorMessage = onlineSnap.errorMessage
            )
        } else {
            DisplayedPlaybackState(
                engine = ActiveAudioEngine.OFFLINE,
                playbackUi = offlineUi
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DisplayedPlaybackState())

    private val statsFlow = db.trackStatsDao().observeAllStats()
    private val overridesFlow = db.trackTagOverrideDao().observeAllOverrides()

    val library: StateFlow<LibraryDerivedState> = combine(
        _rawTracks,
        statsFlow,
        overridesFlow,
        _isScanning,
        _diagnostics
    ) { tracks, statsList, overridesList, scanning, diagnostics ->
        val statsMap = statsList.associateBy { it.uriString }
        val overrideMap = overridesList.associateBy { it.uriString }
        val mergedTracks = tracks.map { t ->
            val st = statsMap[t.uriString]
            val ov = overrideMap[t.uriString]
            t.copy(
                title = ov?.title?.takeIf { it.isNotBlank() } ?: t.title,
                artist = ov?.artist?.takeIf { it.isNotBlank() } ?: t.artist,
                album = ov?.album?.takeIf { it.isNotBlank() } ?: t.album,
                albumArtist = ov?.albumArtist?.takeIf { it.isNotBlank() } ?: t.albumArtist,
                genre = ov?.genre?.takeIf { it.isNotBlank() } ?: t.genre,
                year = ov?.year ?: t.year,
                trackNumber = ov?.trackNumber ?: t.trackNumber,
                discNumber = ov?.discNumber ?: t.discNumber,
                composer = ov?.composer ?: t.composer,
                customCoverUri = ov?.customCoverUri ?: t.customCoverUri,
                embeddedLyrics = ov?.lyrics ?: t.embeddedLyrics,
                playCount = st?.playCount ?: t.playCount,
                lastPlayedAt = st?.lastPlayedAt ?: t.lastPlayedAt,
                isFavorite = st?.isFavorite ?: t.isFavorite,
                totalListenMs = st?.totalListenMs ?: t.totalListenMs
            )
        }
        buildDerivedLibrary(mergedTracks, scanning, diagnostics)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryDerivedState(isScanning = true))

    val playlists: StateFlow<List<Playlist>> = combine(
        db.playlistDao().observePlaylists(),
        db.playlistDao().observeAllEntries()
    ) { entities, entries ->
        val grouped = entries.groupBy { it.playlistId }
        entities.map { entity ->
            val uris = grouped[entity.id].orEmpty().sortedBy { it.position }.map { it.trackUri }
            Playlist(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                coverUri = entity.coverUri,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                trackUris = uris
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentSearches: StateFlow<List<String>> = db.searchHistoryDao().observeRecentSearches()
        .combine(_rawTracks) { list, _ -> list.map { it.query } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var progressJob: Job? = null
    private var lyricsJob: Job? = null
    private var lastCountedTrackUri: String? = null
    private var currentTrackAccumulatedMs: Long = 0L
    private var pendingRescan: Boolean = false
    private var autoRescanJob: Job? = null

    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scheduleAutoRescan()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleAutoRescan()
        }
    }

    init {
        registerMediaStoreObserver()
        remoteConfigRepo.triggerStartupRefreshIfNeeded()
        viewModelScope.launch {
            migrateLegacyFavorites()
            connectMediaController()
            if (settingsRepo.getSettingsOnce().activePlaybackMode == PlaybackMode.ONLINE) {
                online.loadTrendingIfNeeded()
            }
        }
        viewModelScope.launch {
            PlaybackRuntimeBus.offlineSnapshot.collect { snap ->
                if (snap.mediaItems.isNotEmpty() || _playback.value.queueTracks.isNotEmpty()) {
                    syncOfflinePlaybackFromSnapshot(snap)
                }
            }
        }
        viewModelScope.launch {
            PlaybackRuntimeBus.preloadedLyrics.collect { entry ->
                val curTrack = displayedPlayback.value.playbackUi.currentTrack
                if (entry != null && curTrack != null && entry.trackUri == curTrack.uriString && entry.lyrics.hasContent) {
                    _currentLyrics.value = entry.lyrics
                }
            }
        }
        viewModelScope.launch {
            displayedPlayback
                .map { it.playbackUi.currentTrack }
                .distinctUntilChanged { old, new ->
                    old?.uriString == new?.uriString &&
                        old?.title == new?.title &&
                        old?.artist == new?.artist &&
                        old?.embeddedLyrics == new?.embeddedLyrics
                }
                .collectLatest { track ->
                    if (track != null) {
                        loadLyricsFor(track)
                    } else {
                        lyricsJob?.cancel()
                        _currentLyrics.value = LyricsData()
                    }
                }
        }
    }

    fun switchPlaybackMode(mode: PlaybackMode) {
        _optimisticPlaybackMode.value = mode
        updateSettings { it.copy(activePlaybackMode = mode) }
        if (mode == PlaybackMode.ONLINE) {
            online.loadTrendingIfNeeded()
        }
    }

    private fun registerMediaStoreObserver() {
        runCatching {
            app.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaStoreObserver
            )
        }
    }

    private fun scheduleAutoRescan() {
        autoRescanJob?.cancel()
        autoRescanJob = viewModelScope.launch {
            delay(1200L)
            rescanLibrary()
        }
    }

    private suspend fun migrateLegacyFavorites() {
        val legacyFavs = settingsRepo.migrateLegacyFavoritesIfNeeded()
        legacyFavs.forEach { uri ->
            val existing = db.trackStatsDao().getByUri(uri)
            db.trackStatsDao().upsert(
                (existing ?: TrackStatsEntity(uriString = uri)).copy(isFavorite = true)
            )
        }
    }

    private fun connectMediaController() {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching {
                val ctrl = future.get()
                mediaController = ctrl
                ctrl.addListener(controllerListener)
                syncPlaybackStateFromController()
                startProgressLoop()
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private val controllerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncPlaybackStateFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentTrackAccumulatedMs = 0L
            lastCountedTrackUri = null
            syncPlaybackStateFromController()
            _playback.value.currentTrack?.let { loadLyricsFor(it) }
        }
    }

    private fun syncPlaybackStateFromController() {
        val bridgeSnap = PlaybackRuntimeBus.offlineBridge?.getOfflineSnapshot()
        if (bridgeSnap != null) {
            syncOfflinePlaybackFromSnapshot(bridgeSnap)
            return
        }
        if (PlaybackRuntimeBus.activeEngine.value == ActiveAudioEngine.ONLINE) {
            _playback.update { it.copy(isPlaying = false) }
            return
        }
        val ctrl = mediaController ?: return
        val items = (0 until ctrl.mediaItemCount).map { ctrl.getMediaItemAt(it) }
        syncOfflinePlaybackFromSnapshot(
            OfflineEngineSnapshot(
                isPlaying = ctrl.isPlaying,
                mediaItems = items,
                currentIndex = ctrl.currentMediaItemIndex.coerceAtLeast(0),
                positionMs = ctrl.currentPosition.coerceAtLeast(0L),
                durationMs = ctrl.duration.coerceAtLeast(0L),
                shuffleEnabled = ctrl.shuffleModeEnabled,
                repeatMode = ctrl.repeatMode,
                playbackSpeed = ctrl.playbackParameters.speed
            )
        )
    }

    private fun syncOfflinePlaybackFromSnapshot(snap: OfflineEngineSnapshot) {
        val allByUri = library.value.allTracks.associateBy { it.uriString }
        val queueTracks = snap.mediaItems.map { item ->
            val uri = item.localConfiguration?.uri?.toString() ?: item.mediaId
            allByUri[uri] ?: Track(
                id = uri.hashCode().toLong(),
                uriString = uri,
                filePath = "",
                displayName = item.mediaMetadata.title?.toString() ?: "Audio",
                title = item.mediaMetadata.title?.toString() ?: "Audio",
                artist = item.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                album = item.mediaMetadata.albumTitle?.toString() ?: "Unknown Album",
                albumArtist = item.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                genre = "Unclassified",
                year = 0,
                trackNumber = 0,
                discNumber = 0,
                composer = "",
                durationMs = snap.durationMs.coerceAtLeast(0L),
                sizeBytes = 0L,
                dateAddedSec = 0L,
                dateModifiedSec = 0L,
                mimeType = "",
                formatLabel = "AUDIO",
                bitrateKbps = 0,
                sampleRateHz = 0,
                channelCount = 0,
                folderPath = "",
                folderName = "Local"
            )
        }
        val currentIdx = snap.currentIndex.coerceAtLeast(0)
        val currentItem = snap.mediaItems.getOrNull(currentIdx)
        val currentUri = currentItem?.localConfiguration?.uri?.toString()
            ?: currentItem?.mediaId.orEmpty()
        val currentTrack = queueTracks.getOrNull(currentIdx) ?: allByUri[currentUri]

        _playback.value = PlaybackUiState(
            isConnected = mediaController != null || PlaybackRuntimeBus.offlineBridge != null,
            isPlaying = snap.isPlaying,
            currentTrack = currentTrack,
            currentUri = currentUri,
            queueTracks = queueTracks,
            currentQueueIndex = currentIdx,
            positionMs = snap.positionMs.coerceAtLeast(0L),
            durationMs = snap.durationMs.takeIf { it > 0L } ?: (currentTrack?.durationMs ?: 0L),
            shuffleEnabled = snap.shuffleEnabled,
            repeatMode = snap.repeatMode,
            playbackSpeed = snap.playbackSpeed
        )
    }

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            while (true) {
                val bridgeSnap = PlaybackRuntimeBus.offlineBridge?.getOfflineSnapshot()
                val ctrl = mediaController
                val isOnlineActive = PlaybackRuntimeBus.activeEngine.value == ActiveAudioEngine.ONLINE
                if (bridgeSnap != null) {
                    val playing = bridgeSnap.isPlaying
                    val pos = bridgeSnap.positionMs.coerceAtLeast(0L)
                    val dur = bridgeSnap.durationMs.takeIf { it > 0L }
                        ?: (_playback.value.currentTrack?.durationMs ?: 0L)
                    _playback.update {
                        it.copy(
                            isPlaying = playing,
                            positionMs = pos,
                            durationMs = dur
                        )
                    }
                    if (playing) {
                        val uri = _playback.value.currentUri
                        if (uri.isNotBlank()) {
                            currentTrackAccumulatedMs += 250L
                            if (currentTrackAccumulatedMs >= 8000L && lastCountedTrackUri != uri) {
                                lastCountedTrackUri = uri
                                recordTrackPlayed(uri, 8000L)
                            } else if (currentTrackAccumulatedMs % 5000L == 0L && lastCountedTrackUri == uri) {
                                addListenTime(uri, 5000L)
                            }
                        }
                    }
                } else if (ctrl != null && !isOnlineActive) {
                    val playing = ctrl.isPlaying
                    val pos = ctrl.currentPosition.coerceAtLeast(0L)
                    val dur = ctrl.duration.takeIf { it > 0L }
                        ?: (_playback.value.currentTrack?.durationMs ?: 0L)
                    _playback.update {
                        it.copy(
                            isPlaying = playing,
                            positionMs = pos,
                            durationMs = dur
                        )
                    }
                    if (playing) {
                        val uri = _playback.value.currentUri
                        if (uri.isNotBlank()) {
                            currentTrackAccumulatedMs += 250L
                            if (currentTrackAccumulatedMs >= 8000L && lastCountedTrackUri != uri) {
                                lastCountedTrackUri = uri
                                recordTrackPlayed(uri, 8000L)
                            } else if (currentTrackAccumulatedMs % 5000L == 0L && lastCountedTrackUri == uri) {
                                addListenTime(uri, 5000L)
                            }
                        }
                    }
                }
                delay(250L)
            }
        }
    }

    private fun recordTrackPlayed(uri: String, initialMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.trackStatsDao().getByUri(uri) ?: TrackStatsEntity(uriString = uri)
            db.trackStatsDao().upsert(
                existing.copy(
                    playCount = existing.playCount + 1,
                    lastPlayedAt = System.currentTimeMillis(),
                    totalListenMs = existing.totalListenMs + initialMs
                )
            )
        }
    }

    private fun addListenTime(uri: String, deltaMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.trackStatsDao().getByUri(uri) ?: TrackStatsEntity(uriString = uri)
            db.trackStatsDao().upsert(
                existing.copy(
                    lastPlayedAt = System.currentTimeMillis(),
                    totalListenMs = existing.totalListenMs + deltaMs
                )
            )
        }
    }

    fun rescanLibrary() {
        if (_isScanning.value) {
            pendingRescan = true
            return
        }
        viewModelScope.launch {
            do {
                pendingRescan = false
                _isScanning.value = true
                val curSettings = settingsRepo.getSettingsOnce()
                val statsMap = db.trackStatsDao().getAllStatsOnce().associateBy { it.uriString }
                val overrideMap = db.trackTagOverrideDao().getAllOverridesOnce().associateBy { it.uriString }
                val trackedUris = buildSet {
                    statsMap.values.filter { it.isFavorite }.forEach { add(it.uriString) }
                    playlists.value.forEach { pl -> addAll(pl.trackUris) }
                    _playback.value.queueTracks.forEach { add(it.uriString) }
                }
                val (tracks, diagnostics) = scanner.scanAllAudio(
                    settings = curSettings,
                    statsMap = statsMap,
                    overrideMap = overrideMap,
                    trackedUrisForMissingCheck = trackedUris
                )
                _rawTracks.value = tracks
                _diagnostics.value = diagnostics
                _isScanning.value = false
                syncPlaybackStateFromController()
            } while (pendingRescan)
        }
    }

    fun addSinglePickedAudioUri(uri: Uri) {
        if (!InputAndUrlSecurity.isSafePlayableLocalUri(uri)) {
            emitMessage("Unsupported or unsafe audio URI")
            return
        }
        viewModelScope.launch {
            runCatching {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val ext = withContext(Dispatchers.IO) {
                TagAndLyricsEngine.readExtendedTags(
                    app,
                    uri,
                    app.contentResolver.getType(uri).orEmpty(),
                    uri.lastPathSegment.orEmpty()
                )
            }
            val track = Track(
                id = uri.toString().hashCode().toLong(),
                uriString = uri.toString(),
                filePath = "",
                displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Local Audio",
                title = ext.title ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Local Audio",
                artist = ext.artist ?: "Unknown Artist",
                album = ext.album ?: "Unknown Album",
                albumArtist = ext.albumArtist ?: ext.artist ?: "Unknown Artist",
                genre = ext.genre ?: "Unclassified",
                year = ext.year,
                trackNumber = ext.trackNumber,
                discNumber = ext.discNumber,
                composer = ext.composer.orEmpty(),
                durationMs = ext.durationMs,
                sizeBytes = 0L,
                dateAddedSec = System.currentTimeMillis() / 1000L,
                dateModifiedSec = System.currentTimeMillis() / 1000L,
                mimeType = app.contentResolver.getType(uri).orEmpty(),
                formatLabel = ext.formatLabel,
                bitrateKbps = ext.bitrateKbps,
                sampleRateHz = ext.sampleRateHz,
                channelCount = ext.channelCount,
                folderPath = "Picked Files",
                folderName = "Picked Files",
                replayGainTrackDb = ext.replayGainTrackDb,
                replayGainAlbumDb = ext.replayGainAlbumDb,
                embeddedLyrics = ext.embeddedLyrics
            )
            val current = _rawTracks.value.toMutableList()
            if (current.none { it.uriString == track.uriString }) {
                current.add(0, track)
                _rawTracks.value = current
            }
            playTracks(listOf(track), 0)
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val safeIdx = startIndex.coerceIn(tracks.indices)
        val items = tracks.map { trackToMediaItem(it) }
        val bridge = PlaybackRuntimeBus.offlineBridge
        if (bridge != null) {
            bridge.setMediaItemsAndPlay(items, safeIdx, shuffle)
            loadLyricsFor(tracks[safeIdx])
            return
        }
        val ctrl = mediaController ?: return
        ctrl.shuffleModeEnabled = shuffle
        ctrl.setMediaItems(items, safeIdx, 0L)
        ctrl.prepare()
        ctrl.play()
        syncPlaybackStateFromController()
        loadLyricsFor(tracks[safeIdx])
    }

    fun shuffleAll() {
        val tracks = library.value.allTracks
        if (tracks.isEmpty()) {
            emitMessage("No local tracks in your library yet")
            return
        }
        val randomIdx = tracks.indices.random()
        playTracks(tracks, randomIdx, shuffle = true)
    }

    fun togglePlayPause() {
        val bridge = PlaybackRuntimeBus.offlineBridge
        if (bridge != null) {
            val snap = bridge.getOfflineSnapshot()
            if (snap == null || snap.mediaItems.isEmpty()) {
                if (library.value.allTracks.isNotEmpty()) playTracks(library.value.allTracks, 0)
            } else {
                bridge.togglePlayPause()
            }
            return
        }
        val ctrl = mediaController ?: return
        if (ctrl.isPlaying) ctrl.pause()
        else if (ctrl.mediaItemCount > 0) ctrl.play()
        else if (library.value.allTracks.isNotEmpty()) playTracks(library.value.allTracks, 0)
    }

    fun seekTo(positionMs: Long) {
        PlaybackRuntimeBus.offlineBridge?.let {
            it.seekTo(positionMs)
            return
        }
        mediaController?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun skipNext() {
        PlaybackRuntimeBus.offlineBridge?.let {
            it.skipNext()
            return
        }
        val ctrl = mediaController ?: return
        if (ctrl.hasNextMediaItem()) ctrl.seekToNextMediaItem()
        else if (ctrl.mediaItemCount > 0) ctrl.seekToDefaultPosition(0)
    }

    fun skipPrevious() {
        PlaybackRuntimeBus.offlineBridge?.let {
            it.skipPrevious()
            return
        }
        val ctrl = mediaController ?: return
        if (ctrl.currentPosition > 3000L) ctrl.seekTo(0L)
        else if (ctrl.hasPreviousMediaItem()) ctrl.seekToPreviousMediaItem()
        else if (ctrl.mediaItemCount > 0) ctrl.seekToDefaultPosition(ctrl.mediaItemCount - 1)
    }

    fun toggleShuffle() {
        PlaybackRuntimeBus.offlineBridge?.let {
            it.toggleShuffle()
            return
        }
        val ctrl = mediaController ?: return
        ctrl.shuffleModeEnabled = !ctrl.shuffleModeEnabled
        syncPlaybackStateFromController()
    }

    fun cycleRepeatMode() {
        PlaybackRuntimeBus.offlineBridge?.let {
            it.cycleRepeatMode()
            return
        }
        val ctrl = mediaController ?: return
        ctrl.repeatMode = when (ctrl.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        syncPlaybackStateFromController()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        PlaybackRuntimeBus.offlineBridge?.setPlaybackSpeed(clamped) ?: mediaController?.setPlaybackSpeed(clamped)
        updateSettings { it.copy(playbackSpeed = clamped) }
    }

    fun startSleepTimer(minutes: Int) {
        val ctrl = mediaController ?: return
        val args = Bundle().apply { putInt(PlaybackService.ARG_MINUTES, minutes) }
        ctrl.sendCustomCommand(SessionCommand(PlaybackService.CMD_SLEEP_TIMER, Bundle.EMPTY), args)
        emitMessage("Sleep timer set for $minutes min")
    }

    fun cancelSleepTimer() {
        val ctrl = mediaController ?: return
        ctrl.sendCustomCommand(SessionCommand(PlaybackService.CMD_CANCEL_SLEEP_TIMER, Bundle.EMPTY), Bundle.EMPTY)
        emitMessage("Sleep timer cancelled")
    }

    fun previewEqPreset(presetIndex: Int) {
        val ctrl = mediaController ?: return
        val args = Bundle().apply { putInt(PlaybackService.ARG_PRESET_INDEX, presetIndex) }
        ctrl.sendCustomCommand(SessionCommand(PlaybackService.CMD_PREVIEW_EQ_PRESET, Bundle.EMPTY), args)
    }

    // Queue management
    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val bridge = PlaybackRuntimeBus.offlineBridge
        if (bridge != null) {
            bridge.playNext(tracks.map { trackToMediaItem(it) })
            emitMessage("Added ${tracks.size} to play next")
            return
        }
        val ctrl = mediaController ?: return
        if (ctrl.mediaItemCount == 0) {
            playTracks(tracks, 0)
            return
        }
        val insertIndex = (ctrl.currentMediaItemIndex + 1).coerceAtMost(ctrl.mediaItemCount)
        ctrl.addMediaItems(insertIndex, tracks.map { trackToMediaItem(it) })
        syncPlaybackStateFromController()
        emitMessage("Added ${tracks.size} to play next")
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val bridge = PlaybackRuntimeBus.offlineBridge
        if (bridge != null) {
            bridge.addToQueue(tracks.map { trackToMediaItem(it) })
            emitMessage("Added ${tracks.size} to queue")
            return
        }
        val ctrl = mediaController ?: return
        if (ctrl.mediaItemCount == 0) {
            playTracks(tracks, 0)
            return
        }
        ctrl.addMediaItems(tracks.map { trackToMediaItem(it) })
        syncPlaybackStateFromController()
        emitMessage("Added ${tracks.size} to queue")
    }

    fun jumpToQueueIndex(index: Int) {
        PlaybackRuntimeBus.offlineBridge?.let {
            it.jumpToQueueIndex(index)
            return
        }
        val ctrl = mediaController ?: return
        if (index in 0 until ctrl.mediaItemCount) {
            ctrl.seekTo(index, 0L)
            ctrl.play()
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        PlaybackRuntimeBus.offlineBridge?.let {
            it.moveQueueItem(fromIndex, toIndex)
            return
        }
        val ctrl = mediaController ?: return
        if (fromIndex in 0 until ctrl.mediaItemCount && toIndex in 0 until ctrl.mediaItemCount && fromIndex != toIndex) {
            ctrl.moveMediaItem(fromIndex, toIndex)
            syncPlaybackStateFromController()
        }
    }

    fun removeQueueItem(index: Int) {
        PlaybackRuntimeBus.offlineBridge?.let {
            it.removeQueueItem(index)
            return
        }
        val ctrl = mediaController ?: return
        if (index in 0 until ctrl.mediaItemCount) {
            ctrl.removeMediaItem(index)
            syncPlaybackStateFromController()
        }
    }

    fun clearQueue() {
        PlaybackRuntimeBus.offlineBridge?.let {
            it.clearQueue()
            emitMessage("Queue cleared")
            return
        }
        val ctrl = mediaController ?: return
        ctrl.clearMediaItems()
        syncPlaybackStateFromController()
        emitMessage("Queue cleared")
    }

    fun shuffleCurrentQueue() {
        val queue = _playback.value.queueTracks
        if (queue.size <= 1) return
        val currentIdx = _playback.value.currentQueueIndex
        val currentTrack = queue.getOrNull(currentIdx)
        val remaining = queue.filterIndexed { idx, _ -> idx != currentIdx }.shuffled()
        val reordered = if (currentTrack != null) listOf(currentTrack) + remaining else remaining
        val bridge = PlaybackRuntimeBus.offlineBridge
        if (bridge != null) {
            val snap = bridge.getOfflineSnapshot()
            bridge.shuffleCurrentQueue(
                reordered = reordered.map { trackToMediaItem(it) },
                positionMs = snap?.positionMs ?: _playback.value.positionMs,
                wasPlaying = snap?.isPlaying ?: _playback.value.isPlaying
            )
            emitMessage("Queue shuffled")
            return
        }
        val ctrl = mediaController ?: return
        if (ctrl.mediaItemCount <= 1) return
        val pos = ctrl.currentPosition
        val wasPlaying = ctrl.isPlaying
        ctrl.setMediaItems(reordered.map { trackToMediaItem(it) }, 0, pos)
        ctrl.prepare()
        if (wasPlaying) ctrl.play()
        syncPlaybackStateFromController()
        emitMessage("Queue shuffled")
    }

    fun saveQueueAsPlaylist(name: String) {
        val uris = _playback.value.queueTracks.map { it.uriString }
        if (uris.isEmpty()) return
        createPlaylist(name.ifBlank { "Saved Queue" }, "Saved from active playback queue", uris)
    }

    // Unified displayed player controls (operate on whichever audio engine is displayed)
    fun toggleDisplayedPlayPause() {
        if (displayedPlayback.value.engine == ActiveAudioEngine.ONLINE) {
            online.togglePlayPause()
        } else {
            togglePlayPause()
        }
    }

    fun seekDisplayedTo(positionMs: Long) {
        if (displayedPlayback.value.engine == ActiveAudioEngine.ONLINE) {
            online.seekTo(positionMs)
        } else {
            seekTo(positionMs)
        }
    }

    fun skipDisplayedNext() {
        if (displayedPlayback.value.engine == ActiveAudioEngine.ONLINE) {
            online.skipNext()
        } else {
            skipNext()
        }
    }

    fun skipDisplayedPrevious() {
        if (displayedPlayback.value.engine == ActiveAudioEngine.ONLINE) {
            online.skipPrevious()
        } else {
            skipPrevious()
        }
    }

    fun toggleDisplayedShuffle() {
        if (displayedPlayback.value.engine == ActiveAudioEngine.ONLINE) {
            online.toggleShuffle()
        } else {
            toggleShuffle()
        }
    }

    fun cycleDisplayedRepeatMode() {
        if (displayedPlayback.value.engine == ActiveAudioEngine.ONLINE) {
            online.cycleRepeatMode()
        } else {
            cycleRepeatMode()
        }
    }

    fun setDisplayedPlaybackSpeed(speed: Float) {
        if (displayedPlayback.value.engine == ActiveAudioEngine.ONLINE) {
            online.setPlaybackSpeed(speed)
        } else {
            setPlaybackSpeed(speed)
        }
    }

    fun toggleFavoriteForUiTrack(track: Track) {
        if (track.isOnline) {
            val onlineTrack = online.resolveOnlineTrackById(track.onlineTrackId)
                ?: OnlineTrack(
                    id = track.onlineTrackId,
                    sourceId = track.onlineSourceId,
                    providerType = OnlineProviderType.fromProviderId(track.onlineProvider),
                    providerLabel = track.onlineProvider,
                    sourceUrl = "",
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    thumbnailUrl = track.artworkUrl ?: track.customCoverUri,
                    providerId = track.onlineProvider.lowercase().ifBlank { "youtube" }
                )
            online.toggleFavorite(onlineTrack)
        } else {
            toggleFavorite(track)
        }
    }

    // Favorites & Multi-Selection
    fun toggleFavorite(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.trackStatsDao().getByUri(track.uriString)
                ?: TrackStatsEntity(uriString = track.uriString)
            db.trackStatsDao().upsert(existing.copy(isFavorite = !existing.isFavorite))
        }
    }

    fun toggleTrackSelection(uri: String) {
        _selectedTrackUris.update { set ->
            if (uri in set) set - uri else set + uri
        }
    }

    fun selectAllTracks(tracks: List<Track>) {
        _selectedTrackUris.value = tracks.mapTo(LinkedHashSet()) { it.uriString }
    }

    fun clearSelection() {
        _selectedTrackUris.value = emptySet()
    }

    fun favoriteSelected() {
        val uris = _selectedTrackUris.value
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                val existing = db.trackStatsDao().getByUri(uri) ?: TrackStatsEntity(uriString = uri)
                db.trackStatsDao().upsert(existing.copy(isFavorite = true))
            }
        }
        emitMessage("Marked ${uris.size} tracks as favorite")
        clearSelection()
    }

    // Playlist Management
    fun createPlaylist(name: String, description: String = "", trackUris: List<String> = emptyList()) {
        val cleanName = InputAndUrlSecurity.sanitizeSingleLineText(name, 80)
        if (cleanName.isBlank()) return
        val cleanDesc = InputAndUrlSecurity.sanitizeMultilineText(description)
        val safeUris = trackUris.filter { uriStr ->
            val parsed = runCatching { Uri.parse(uriStr) }.getOrNull()
            InputAndUrlSecurity.isSafePlayableLocalUri(parsed)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = db.playlistDao().insertPlaylist(
                PlaylistEntity(name = cleanName, description = cleanDesc, createdAt = now, updatedAt = now)
            )
            if (safeUris.isNotEmpty()) {
                db.playlistDao().replacePlaylistEntries(id, safeUris)
            }
        }
        emitMessage("Created playlist \"$cleanName\"")
    }

    fun renamePlaylist(playlistId: Long, newName: String, newDescription: String) {
        val cleanName = InputAndUrlSecurity.sanitizeSingleLineText(newName, 80)
        if (cleanName.isBlank()) return
        val cleanDesc = InputAndUrlSecurity.sanitizeMultilineText(newDescription)
        viewModelScope.launch(Dispatchers.IO) {
            val cur = db.playlistDao().getPlaylistsOnce().firstOrNull { it.id == playlistId } ?: return@launch
            db.playlistDao().updatePlaylist(
                cur.copy(name = cleanName, description = cleanDesc, updatedAt = System.currentTimeMillis())
            )
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.playlistDao().deletePlaylist(playlistId)
        }
        emitMessage("Playlist deleted")
    }

    fun updatePlaylistCover(playlistId: Long, coverUri: String?) {
        val safeCover = InputAndUrlSecurity.sanitizePlaylistCoverUri(coverUri)
        viewModelScope.launch(Dispatchers.IO) {
            val cur = db.playlistDao().getPlaylistsOnce().firstOrNull { it.id == playlistId } ?: return@launch
            db.playlistDao().updatePlaylist(cur.copy(coverUri = safeCover, updatedAt = System.currentTimeMillis()))
        }
    }

    fun addTracksToPlaylist(playlistId: Long, newTrackUris: List<String>) {
        if (newTrackUris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val curEntries = db.playlistDao().getEntriesForPlaylist(playlistId).map { it.trackUri }
            val updated = curEntries + newTrackUris
            db.playlistDao().replacePlaylistEntries(playlistId, updated)
            db.playlistDao().getPlaylistsOnce().firstOrNull { it.id == playlistId }?.let {
                db.playlistDao().updatePlaylist(it.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        emitMessage("Added ${newTrackUris.size} track(s) to playlist")
    }

    fun removeTrackFromPlaylist(playlistId: Long, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val curEntries = db.playlistDao().getEntriesForPlaylist(playlistId).map { it.trackUri }.toMutableList()
            if (index in curEntries.indices) {
                curEntries.removeAt(index)
                db.playlistDao().replacePlaylistEntries(playlistId, curEntries)
            }
        }
    }

    fun reorderPlaylistTracks(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val curEntries = db.playlistDao().getEntriesForPlaylist(playlistId).map { it.trackUri }.toMutableList()
            if (fromIndex in curEntries.indices && toIndex in curEntries.indices && fromIndex != toIndex) {
                val moved = curEntries.removeAt(fromIndex)
                curEntries.add(toIndex, moved)
                db.playlistDao().replacePlaylistEntries(playlistId, curEntries)
            }
        }
    }

    fun resolveSmartPlaylist(type: SmartPlaylistType): List<Track> {
        val state = library.value
        return when (type) {
            SmartPlaylistType.RECENTLY_PLAYED -> state.recentlyPlayed
            SmartPlaylistType.MOST_PLAYED -> state.mostPlayed
            SmartPlaylistType.FAVORITES -> state.favorites
            SmartPlaylistType.RECENTLY_ADDED -> state.recentlyAdded
        }
    }

    fun exportPlaylistM3u8(playlist: Playlist, destUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val trackMap = library.value.allTracks.associateBy { it.uriString }
            val tracks = playlist.trackUris.mapNotNull { trackMap[it] }
            val m3u = PlaylistPortability.exportM3u8(playlist.name, tracks)
            val ok = PlaylistPortability.writeTextToUri(app, destUri, m3u)
            emitMessage(if (ok) "Exported ${playlist.name}.m3u8" else "Export failed")
        }
    }

    fun importPlaylistM3u8(sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val raw = PlaylistPortability.readTextFromUri(app, sourceUri)
            if (raw.isNullOrBlank()) {
                emitMessage("Could not read playlist file")
                return@launch
            }
            val parsedName = raw.lineSequence()
                .firstOrNull { it.startsWith("#PLAYLIST:") }
                ?.removePrefix("#PLAYLIST:")
                ?.let { InputAndUrlSecurity.sanitizeSingleLineText(it, 80) }
                ?.takeIf { it.isNotBlank() }
                ?: "Imported Playlist"
            val uris = PlaylistPortability.parseM3u8(raw, library.value.allTracks)
            if (uris.isEmpty()) {
                emitMessage("No matching local tracks found in playlist")
                return@launch
            }
            createPlaylist(parsedName, "Imported from M3U", uris)
        }
    }

    // Lyrics
    fun loadLyricsFor(track: Track, forceRemoteRefresh: Boolean = false) {
        lyricsJob?.cancel()
        if (!forceRemoteRefresh) {
            val preloaded = PlaybackRuntimeBus.preloadedLyrics.value
            if (preloaded != null && preloaded.trackUri == track.uriString && preloaded.lyrics.hasContent) {
                _currentLyrics.value = preloaded.lyrics
                return
            }
        }
        _currentLyrics.value = LyricsData(isLoading = true)
        lyricsJob = viewModelScope.launch {
            val curSettings = settingsRepo.getSettingsOnce()
            val result = lyricsRepository.loadLyricsForTrack(
                track = track,
                includedSafFolders = curSettings.includedSafFolderUris,
                forceRemoteRefresh = forceRemoteRefresh
            )
            _currentLyrics.value = result
        }
    }

    fun forceRefreshLyricsFor(track: Track) {
        loadLyricsFor(track, forceRemoteRefresh = true)
    }

    fun retryLoadLyrics() {
        displayedPlayback.value.playbackUi.currentTrack?.let { tr ->
            loadLyricsFor(tr, forceRemoteRefresh = true)
        }
    }

    fun saveLyricsForTrack(track: Track, lyricsText: String) {
        val cleanLyrics = InputAndUrlSecurity.sanitizeMultilineText(lyricsText, maxLength = 32_000)
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.trackTagOverrideDao().getByUri(track.uriString)
                ?: TrackTagOverrideEntity(uriString = track.uriString)
            db.trackTagOverrideDao().upsert(existing.copy(lyrics = cleanLyrics, updatedAt = System.currentTimeMillis()))
            lyricsRepository.clearCachedLyricsForTrack(track)
            loadLyricsFor(track)
            emitMessage("Lyrics saved")
        }
    }

    fun testRemoteConfigUrl(candidateUrl: String) {
        val trimmed = candidateUrl.trim()
        viewModelScope.launch {
            _remoteConfigConnectionState.value = RemoteConfigConnectionState.Testing(trimmed)
            when (val res = remoteConfigRepo.testConnection(trimmed)) {
                is ConfigOperationResult.Success -> {
                    val cfg = res.config
                    _remoteConfigConnectionState.value = RemoteConfigConnectionState.Success(
                        url = res.url,
                        actionLabel = "Connection verified",
                        configVersion = cfg.configVersion,
                        schemaVersion = cfg.schemaVersion,
                        totalProviders = cfg.providers.size,
                        enabledProviders = cfg.enabledProviders().size,
                        lyricsEndpointCount = cfg.lrclib.size,
                        categories = cfg.distinctCategories(),
                        latencyMs = res.latencyMs,
                        sourceMetadata = RemoteConfigUrlHelper.parseSourceMetadata(res.url)
                    )
                    emitMessage("Verified ${cfg.enabledProviders().size}/${cfg.providers.size} providers (v${cfg.configVersion}) in ${res.latencyMs}ms")
                }
                is ConfigOperationResult.Failure -> {
                    _remoteConfigConnectionState.value = RemoteConfigConnectionState.Error(
                        url = res.url,
                        errorType = res.errorType,
                        message = res.message
                    )
                    emitMessage("${res.errorType.label}: ${res.message}")
                }
            }
        }
    }

    fun saveRemoteConfigUrl(candidateUrl: String) {
        val trimmed = candidateUrl.trim()
        viewModelScope.launch {
            _remoteConfigConnectionState.value = RemoteConfigConnectionState.Saving(trimmed)
            when (val res = remoteConfigRepo.saveAndActivateUrl(trimmed)) {
                is ConfigOperationResult.Success -> {
                    online.refreshProviderHealth()
                    val cfg = res.config
                    _remoteConfigConnectionState.value = RemoteConfigConnectionState.Success(
                        url = res.url,
                        actionLabel = "URL saved & synced",
                        configVersion = cfg.configVersion,
                        schemaVersion = cfg.schemaVersion,
                        totalProviders = cfg.providers.size,
                        enabledProviders = cfg.enabledProviders().size,
                        lyricsEndpointCount = cfg.lrclib.size,
                        categories = cfg.distinctCategories(),
                        latencyMs = res.latencyMs,
                        sourceMetadata = RemoteConfigUrlHelper.parseSourceMetadata(res.url)
                    )
                    emitMessage("Saved URL · ${cfg.enabledProviders().size}/${cfg.providers.size} providers enabled (v${cfg.configVersion})")
                }
                is ConfigOperationResult.Failure -> {
                    _remoteConfigConnectionState.value = RemoteConfigConnectionState.Error(
                        url = res.url,
                        errorType = res.errorType,
                        message = res.message
                    )
                    emitMessage("${res.errorType.label}: ${res.message}")
                }
            }
        }
    }

    fun clearRemoteConfigConnectionState() {
        _remoteConfigConnectionState.value = RemoteConfigConnectionState.Idle
    }

    fun refreshRemoteConfig(customUrl: String? = null) {
        val trimmed = customUrl?.trim()?.takeIf { it.isNotBlank() }
        if (trimmed != null) {
            saveRemoteConfigUrl(trimmed)
            return
        }
        viewModelScope.launch {
            _remoteConfigConnectionState.value = RemoteConfigConnectionState.Idle
            val updated = remoteConfigRepo.refreshNow(customUrl = null, force = true)
            online.refreshProviderHealth()
            val status = remoteConfigRepo.status.value
            val err = remoteConfigRepo.lastFetchError.value
            when {
                !err.isNullOrBlank() -> emitMessage("${status.lastErrorType.label.ifBlank { "Sync Error" }}: $err")
                status.wasNotModified304 -> emitMessage("Provider config up to date (${updated.enabledProviders().size}/${updated.providers.size} enabled)")
                else -> emitMessage("Synced ${updated.enabledProviders().size}/${updated.providers.size} active providers (v${updated.configVersion})")
            }
        }
    }

    fun rollbackRemoteConfig() {
        viewModelScope.launch {
            _remoteConfigConnectionState.value = RemoteConfigConnectionState.Idle
            val restored = remoteConfigRepo.rollbackToPreviousConfig()
            online.refreshProviderHealth()
            if (restored != null) {
                emitMessage("Rolled back to config v${restored.configVersion}")
            } else {
                emitMessage("No previous working configuration to roll back to")
            }
        }
    }

    // Tag Editor
    suspend fun saveTrackTags(
        track: Track,
        payload: EditableTagPayload,
        customCoverUriString: String?
    ): TagWriteResult = withContext(Dispatchers.IO) {
        val safeCoverUri = if (customCoverUriString != null) {
            InputAndUrlSecurity.sanitizePlaylistCoverUri(customCoverUriString)
        } else {
            InputAndUrlSecurity.sanitizePlaylistCoverUri(track.customCoverUri)
        }
        db.trackTagOverrideDao().upsert(
            TrackTagOverrideEntity(
                uriString = track.uriString,
                title = InputAndUrlSecurity.sanitizeSingleLineText(payload.title, 160),
                artist = InputAndUrlSecurity.sanitizeSingleLineText(payload.artist, 160),
                album = InputAndUrlSecurity.sanitizeSingleLineText(payload.album, 160),
                albumArtist = InputAndUrlSecurity.sanitizeSingleLineText(payload.albumArtist, 160),
                genre = InputAndUrlSecurity.sanitizeSingleLineText(payload.genre, 80),
                year = payload.year,
                trackNumber = payload.trackNumber,
                discNumber = payload.discNumber,
                composer = InputAndUrlSecurity.sanitizeSingleLineText(payload.composer, 160),
                lyrics = InputAndUrlSecurity.sanitizeMultilineText(payload.lyrics, 32_000).takeIf { it.isNotBlank() },
                customCoverUri = safeCoverUri,
                updatedAt = System.currentTimeMillis()
            )
        )
        ArtworkCache.invalidate(track.uriString)
        if (!safeCoverUri.isNullOrBlank()) ArtworkCache.invalidate(safeCoverUri)

        val writeResult = TagAndLyricsEngine.writeMp3Id3TagsSafely(
            context = app,
            uri = track.uri,
            filePath = track.filePath,
            payload = payload
        )
        syncPlaybackStateFromController()
        writeResult
    }

    // Folders & Storage
    fun addSafFolder(treeUri: Uri) {
        viewModelScope.launch {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure {
                runCatching {
                    app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val uriStr = treeUri.toString()
            settingsRepo.updateSettings { s ->
                if (uriStr in s.includedSafFolderUris) s
                else s.copy(includedSafFolderUris = s.includedSafFolderUris + uriStr)
            }
            rescanLibrary()
            emitMessage("Added music folder")
        }
    }

    fun removeSafFolder(treeUri: String) {
        viewModelScope.launch {
            settingsRepo.updateSettings { s ->
                s.copy(includedSafFolderUris = s.includedSafFolderUris - treeUri)
            }
            rescanLibrary()
        }
    }

    fun toggleExcludeFolder(folderPath: String) {
        viewModelScope.launch {
            settingsRepo.updateSettings { s ->
                val updated = if (folderPath in s.excludedFolders) {
                    s.excludedFolders - folderPath
                } else {
                    s.excludedFolders + folderPath
                }
                s.copy(excludedFolders = updated)
            }
            rescanLibrary()
        }
    }

    // Search history
    fun recordSearch(query: String) {
        val clean = InputAndUrlSecurity.sanitizeSearchQuery(query)
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            db.searchHistoryDao().upsert(RecentSearchEntity(query = clean, searchedAt = System.currentTimeMillis()))
        }
    }

    fun deleteRecentSearch(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.searchHistoryDao().delete(query)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            db.searchHistoryDao().clearAll()
        }
    }

    // Settings & Home Customization
    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch {
            settingsRepo.updateSettings(transform)
        }
    }

    fun moveHomeSection(sectionId: HomeSectionId, direction: Int) {
        updateSettings { s ->
            val ordered = s.homeSections.sortedBy { it.order }.toMutableList()
            val idx = ordered.indexOfFirst { it.id == sectionId }
            val target = idx + direction
            if (idx >= 0 && target in ordered.indices) {
                val item = ordered.removeAt(idx)
                ordered.add(target, item)
                s.copy(homeSections = ordered.mapIndexed { i, cfg -> cfg.copy(order = i) })
            } else s
        }
    }

    fun toggleHomeSectionVisibility(sectionId: HomeSectionId) {
        updateSettings { s ->
            s.copy(homeSections = s.homeSections.map {
                if (it.id == sectionId) it.copy(visible = !it.visible) else it
            })
        }
    }

    fun toggleHomeSectionCompact(sectionId: HomeSectionId) {
        updateSettings { s ->
            s.copy(homeSections = s.homeSections.map {
                if (it.id == sectionId) it.copy(compact = !it.compact) else it
            })
        }
    }

    fun resetHomeSections() {
        updateSettings { s ->
            s.copy(
                homeSections = HomeSectionId.entries.map {
                    HomeSectionConfig(id = it, visible = true, order = it.ordinal, compact = false)
                }
            )
        }
    }

    fun exportFullBackup(destUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = settingsRepo.getSettingsOnce()
            val favs = db.trackStatsDao().getFavoritesOnce().map { it.uriString }
            val pls = playlists.value.map {
                BackupPlaylist(it.name, it.description, it.coverUri, it.trackUris)
            }
            val searches = db.searchHistoryDao().getRecentSearchesOnce().map { it.query }
            val encoded = PlaylistPortability.encodeBackup(
                AriaBackupBundle(
                    settings = curSettings,
                    favoriteUris = favs,
                    playlists = pls,
                    recentSearches = searches
                )
            )
            val ok = PlaylistPortability.writeTextToUri(app, destUri, encoded)
            emitMessage(if (ok) "Backup exported" else "Backup export failed")
        }
    }

    fun importFullBackup(sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val raw = PlaylistPortability.readTextFromUri(app, sourceUri)
            val bundle = raw?.let { PlaylistPortability.decodeBackup(it) }
            if (bundle == null) {
                emitMessage("Invalid backup file")
                return@launch
            }
            val safeSettings = InputAndUrlSecurity.sanitizeUserSettings(bundle.settings)
            settingsRepo.updateSettings { safeSettings }
            bundle.favoriteUris
                .asSequence()
                .map { it.trim() }
                .filter { uriStr ->
                    val parsed = runCatching { Uri.parse(uriStr) }.getOrNull()
                    InputAndUrlSecurity.isSafePlayableLocalUri(parsed) || uriStr.startsWith("${OnlineTrack.ONLINE_SCHEME}://")
                }
                .distinct()
                .take(5_000)
                .forEach { uri ->
                    val ex = db.trackStatsDao().getByUri(uri) ?: TrackStatsEntity(uriString = uri)
                    db.trackStatsDao().upsert(ex.copy(isFavorite = true))
                }
            bundle.playlists.take(500).forEach { bpl ->
                val safeName = InputAndUrlSecurity.sanitizeSingleLineText(bpl.name, 80)
                if (safeName.isNotBlank()) {
                    val safeDesc = InputAndUrlSecurity.sanitizeMultilineText(bpl.description)
                    val safeCover = InputAndUrlSecurity.sanitizePlaylistCoverUri(bpl.coverUri)
                    val safeTrackUris = bpl.trackUris
                        .asSequence()
                        .map { it.trim() }
                        .filter { uriStr ->
                            val parsed = runCatching { Uri.parse(uriStr) }.getOrNull()
                            InputAndUrlSecurity.isSafePlayableLocalUri(parsed)
                        }
                        .take(5_000)
                        .toList()
                    val id = db.playlistDao().insertPlaylist(
                        PlaylistEntity(name = safeName, description = safeDesc, coverUri = safeCover)
                    )
                    if (safeTrackUris.isNotEmpty()) {
                        db.playlistDao().replacePlaylistEntries(id, safeTrackUris)
                    }
                }
            }
            bundle.recentSearches
                .asSequence()
                .map { InputAndUrlSecurity.sanitizeSearchQuery(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(50)
                .forEach { q ->
                    db.searchHistoryDao().upsert(RecentSearchEntity(query = q))
                }
            rescanLibrary()
            emitMessage("Backup restored")
        }
    }

    fun sortTracks(tracks: List<Track>, field: SortField, order: SortOrder): List<Track> {
        val base = when (field) {
            SortField.TITLE -> tracks.sortedBy { it.title.lowercase() }
            SortField.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
            SortField.ALBUM -> tracks.sortedBy { it.album.lowercase() }
            SortField.DURATION -> tracks.sortedBy { it.durationMs }
            SortField.DATE_ADDED -> tracks.sortedBy { it.dateAddedSec }
            SortField.DATE_MODIFIED -> tracks.sortedBy { it.dateModifiedSec }
            SortField.TRACK_NUMBER -> tracks.sortedWith(compareBy<Track> { it.discNumber }.thenBy { it.trackNumber }.thenBy { it.title.lowercase() })
            SortField.PLAY_COUNT -> tracks.sortedBy { it.playCount }
            SortField.YEAR -> tracks.sortedBy { it.year }
        }
        return if (order == SortOrder.DESC) base.reversed() else base
    }

    private fun trackToMediaItem(track: Track): MediaItem {
        val extras = Bundle().apply {
            track.replayGainTrackDb?.let { putFloat(PlaybackService.EXTRA_RG_TRACK, it) }
            track.replayGainAlbumDb?.let { putFloat(PlaybackService.EXTRA_RG_ALBUM, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setDisplayTitle(track.title)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.uriString)
            .setUri(track.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun buildDerivedLibrary(
        tracks: List<Track>,
        scanning: Boolean,
        diagnostics: StorageDiagnostics
    ): LibraryDerivedState {
        val albums = tracks.groupBy { it.album }
            .map { (name, list) ->
                val sorted = list.sortedWith(compareBy<Track> { it.discNumber }.thenBy { it.trackNumber })
                Album(
                    name = name,
                    artist = sorted.firstOrNull()?.albumArtist ?: "Unknown Artist",
                    year = sorted.maxOfOrNull { it.year } ?: 0,
                    tracks = sorted,
                    representativeUri = sorted.first().uriString
                )
            }
            .sortedBy { it.name.lowercase() }

        val artists = tracks.groupBy { it.artist }
            .map { (name, list) ->
                Artist(
                    name = name,
                    tracks = list,
                    albumCount = list.map { it.album }.distinct().size,
                    representativeUri = list.first().uriString
                )
            }
            .sortedBy { it.name.lowercase() }

        val albumArtists = tracks.groupBy { it.albumArtist }
            .map { (name, list) ->
                Artist(
                    name = name,
                    tracks = list,
                    albumCount = list.map { it.album }.distinct().size,
                    representativeUri = list.first().uriString
                )
            }
            .sortedBy { it.name.lowercase() }

        val genres = tracks.groupBy { it.genre }
            .map { (name, list) -> Genre(name = name, tracks = list) }
            .sortedBy { it.name.lowercase() }

        val folders = tracks.groupBy { it.folderPath }
            .map { (path, list) ->
                MusicFolder(
                    path = path,
                    name = list.first().folderName,
                    isSafTree = path.startsWith("saf://"),
                    tracks = list
                )
            }
            .sortedBy { it.name.lowercase() }

        return LibraryDerivedState(
            isScanning = scanning,
            allTracks = tracks,
            albums = albums,
            artists = artists,
            albumArtists = albumArtists,
            genres = genres,
            folders = folders,
            recentlyAdded = tracks.sortedByDescending { it.dateAddedSec },
            recentlyPlayed = tracks.filter { it.lastPlayedAt > 0L }.sortedByDescending { it.lastPlayedAt },
            mostPlayed = tracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount },
            favorites = tracks.filter { it.isFavorite },
            diagnostics = diagnostics
        )
    }

    private fun emitMessage(msg: String) {
        _uiMessages.tryEmit(msg)
    }

    override fun onCleared() {
        autoRescanJob?.cancel()
        progressJob?.cancel()
        lyricsJob?.cancel()
        runCatching { app.contentResolver.unregisterContentObserver(mediaStoreObserver) }
        runCatching { mediaController?.removeListener(controllerListener) }
        runCatching { controllerFuture?.let { MediaController.releaseFuture(it) } }
        mediaController = null
        controllerFuture = null
        super.onCleared()
    }
}
