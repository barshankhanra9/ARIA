package app.nocturne.player.playback

import app.nocturne.player.data.model.AudioEngineTelemetry
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.model.OnlineTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingMs: Long = 0L,
    val totalMs: Long = 0L
)

enum class ActiveAudioEngine {
    OFFLINE,
    ONLINE
}

data class PreloadedLyricsEntry(
    val trackUri: String,
    val lyrics: LyricsData
)

data class OnlineEngineSnapshot(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isResolving: Boolean = false,
    val currentTrack: OnlineTrack? = null,
    val queue: List<OnlineTrack> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val activeStreamLabel: String = "",
    val activeProviderLabel: String = "",
    val relatedTracks: List<OnlineTrack> = emptyList(),
    val errorMessage: String? = null
)

interface OnlinePlaybackBridge {
    fun playOnlineTracks(tracks: List<OnlineTrack>, startIndex: Int = 0, shuffle: Boolean = false)
    fun togglePlayPause()
    fun pause()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()
    fun toggleShuffle()
    fun cycleRepeatMode()
    fun setPlaybackSpeed(speed: Float)
    fun addToQueue(tracks: List<OnlineTrack>)
    fun playNext(tracks: List<OnlineTrack>)
    fun jumpToQueueIndex(index: Int)
    fun moveQueueItem(fromIndex: Int, toIndex: Int)
    fun removeQueueItem(index: Int)
    fun clearQueue()
    fun shuffleCurrentQueue()
    fun retryCurrentTrack()
}

interface OfflinePlaybackBridge {
    fun activateOfflineEngine(pauseOnline: Boolean = false)
    fun setMediaItemsAndPlay(items: List<androidx.media3.common.MediaItem>, startIndex: Int, shuffle: Boolean)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()
    fun toggleShuffle()
    fun cycleRepeatMode()
    fun setPlaybackSpeed(speed: Float)
    fun addToQueue(items: List<androidx.media3.common.MediaItem>)
    fun playNext(items: List<androidx.media3.common.MediaItem>)
    fun jumpToQueueIndex(index: Int)
    fun moveQueueItem(fromIndex: Int, toIndex: Int)
    fun removeQueueItem(index: Int)
    fun clearQueue()
    fun shuffleCurrentQueue(reordered: List<androidx.media3.common.MediaItem>, positionMs: Long, wasPlaying: Boolean)
    fun getOfflineSnapshot(): OfflineEngineSnapshot?
}

data class OfflineEngineSnapshot(
    val isPlaying: Boolean = false,
    val mediaItems: List<androidx.media3.common.MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0,
    val playbackSpeed: Float = 1.0f
)

object PlaybackRuntimeBus {
    private val _telemetry = MutableStateFlow(AudioEngineTelemetry())
    val telemetry: StateFlow<AudioEngineTelemetry> = _telemetry.asStateFlow()

    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    private val _presetBandPreview = MutableStateFlow<List<Int>>(emptyList())
    val presetBandPreview: StateFlow<List<Int>> = _presetBandPreview.asStateFlow()

    private val _activeEngine = MutableStateFlow(ActiveAudioEngine.OFFLINE)
    val activeEngine: StateFlow<ActiveAudioEngine> = _activeEngine.asStateFlow()

    private val _onlineSnapshot = MutableStateFlow(OnlineEngineSnapshot())
    val onlineSnapshot: StateFlow<OnlineEngineSnapshot> = _onlineSnapshot.asStateFlow()

    private val _offlineSnapshot = MutableStateFlow(OfflineEngineSnapshot())
    val offlineSnapshot: StateFlow<OfflineEngineSnapshot> = _offlineSnapshot.asStateFlow()

    private val _preloadedLyrics = MutableStateFlow<PreloadedLyricsEntry?>(null)
    val preloadedLyrics: StateFlow<PreloadedLyricsEntry?> = _preloadedLyrics.asStateFlow()

    @Volatile
    var onlineBridge: OnlinePlaybackBridge? = null

    @Volatile
    var offlineBridge: OfflinePlaybackBridge? = null

    fun updateTelemetry(transform: (AudioEngineTelemetry) -> AudioEngineTelemetry) {
        _telemetry.update(transform)
    }

    fun updateSleepTimer(state: SleepTimerState) {
        _sleepTimer.value = state
    }

    fun updatePresetBandPreview(levels: List<Int>) {
        _presetBandPreview.value = levels
    }

    fun updateActiveEngine(engine: ActiveAudioEngine) {
        _activeEngine.value = engine
    }

    fun updateOnlineSnapshot(transform: (OnlineEngineSnapshot) -> OnlineEngineSnapshot) {
        _onlineSnapshot.update(transform)
    }

    fun updateOfflineSnapshot(snapshot: OfflineEngineSnapshot) {
        _offlineSnapshot.value = snapshot
    }

    fun updatePreloadedLyrics(trackUri: String, lyrics: LyricsData) {
        _preloadedLyrics.value = PreloadedLyricsEntry(trackUri = trackUri, lyrics = lyrics)
    }
}
