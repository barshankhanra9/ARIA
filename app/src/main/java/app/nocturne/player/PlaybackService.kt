package app.nocturne.player

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.nocturne.player.data.audio.LyricsRepository
import app.nocturne.player.data.audio.TagAndLyricsEngine
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.ReplayGainMode
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.data.online.OnlineDependencies
import app.nocturne.player.data.online.OnlineHttpClient
import app.nocturne.player.data.online.OnlineLibraryRepository
import app.nocturne.player.data.online.OnlineProviderManager
import app.nocturne.player.data.prefs.SettingsRepository
import app.nocturne.player.data.recommendation.AffinityRepository
import app.nocturne.player.data.recommendation.RecommendationEngine
import app.nocturne.player.data.security.InputAndUrlSecurity
import app.nocturne.player.playback.ActiveAudioEngine
import app.nocturne.player.playback.AudioEffectsManager
import app.nocturne.player.playback.BalanceAndReplayGainAudioProcessor
import app.nocturne.player.playback.OfflineEngineSnapshot
import app.nocturne.player.playback.OfflinePlaybackBridge
import app.nocturne.player.playback.OnlinePlaybackBridge
import app.nocturne.player.playback.PlaybackRuntimeBus
import app.nocturne.player.playback.SleepTimerState
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class PlaybackService : MediaLibraryService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var database: AriaDatabase
    private lateinit var providerManager: OnlineProviderManager
    private lateinit var onlineRepo: OnlineLibraryRepository
    private lateinit var affinityRepo: AffinityRepository
    private lateinit var recommendationEngine: RecommendationEngine
    private lateinit var lyricsRepository: LyricsRepository

    // Offline playback engine (existing behavior preserved)
    private lateinit var player: ExoPlayer

    // Independent online playback engine
    private lateinit var onlinePlayer: ExoPlayer

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var activeEngine: ActiveAudioEngine = ActiveAudioEngine.OFFLINE

    private val effectsManager = AudioEffectsManager()
    private lateinit var balanceAndRgProcessor: BalanceAndReplayGainAudioProcessor
    private var currentSettings = UserSettings()
    private var sleepTimerJob: Job? = null
    private var progressSaverJob: Job? = null
    private var onlineProgressJob: Job? = null
    private var crossfadeJob: Job? = null
    private var lyricsPreloadJob: Job? = null
    private var radioFetchJob: Job? = null
    private var receiversRegistered = false

    private data class ResolvedStreamCtx(
        val track: OnlineTrack,
        val providerType: OnlineProviderType,
        val providerId: String
    )

    private val onlineTrackMap = ConcurrentHashMap<String, OnlineTrack>()
    private val onlineRetryCounts = ConcurrentHashMap<String, Int>()
    private val resolvedStreamContext = ConcurrentHashMap<String, ResolvedStreamCtx>()
    private var lastCountedOnlineId: String? = null
    private var onlineAccumulatedMs: Long = 0L

    private var trackedOnlineTrack: OnlineTrack? = null
    private var trackedOnlineListenMs: Long = 0L
    private var trackedOnlineMaxPosMs: Long = 0L
    private var trackedOnlineEngagedAwarded: Boolean = false
    private var trackedOnlineCompletedAwarded: Boolean = false
    private var lastRadioSeedKey: String? = null
    @Volatile private var pendingRadioAutoAdvance: Boolean = false

    private val noisyAndBtReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val active = activePlayer() ?: return
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (currentSettings.pauseOnHeadsetDisconnect && active.isPlaying) {
                        active.pause()
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (currentSettings.resumeOnBluetoothConnect &&
                        active.mediaItemCount > 0 && !active.isPlaying
                    ) {
                        active.play()
                    }
                }
            }
        }
    }

    private fun activePlayer(): ExoPlayer? {
        if (!::player.isInitialized || !::onlinePlayer.isInitialized) return null
        return if (activeEngine == ActiveAudioEngine.ONLINE) onlinePlayer else player
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        database = AriaDatabase.getInstance(this)
        val remoteConfigRepo = OnlineDependencies.getRemoteConfigRepository(this)
        remoteConfigRepo.triggerStartupRefreshIfNeeded()
        providerManager = OnlineDependencies.getProviderManager(this)
        onlineRepo = OnlineDependencies.getRepository(this)
        affinityRepo = OnlineDependencies.getAffinityRepository(this)
        recommendationEngine = OnlineDependencies.getRecommendationEngine(this)
        lyricsRepository = LyricsRepository(
            context = this,
            db = database,
            remoteConfigRepository = remoteConfigRepo,
            healthTracker = OnlineDependencies.getHealthTracker()
        )

        balanceAndRgProcessor = BalanceAndReplayGainAudioProcessor { sampleRate, channels, encoding ->
            PlaybackRuntimeBus.updateTelemetry {
                it.copy(sampleRateHz = sampleRate, channelCount = channels, encodingName = encoding)
            }
        }

        val offlineRenderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(balanceAndRgProcessor))
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // 1. Initialize Offline Player
        player = ExoPlayer.Builder(this, offlineRenderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (activeEngine == ActiveAudioEngine.OFFLINE) {
                    attachEffects(audioSessionId)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyReplayGainForCurrentItem(mediaItem)
                applyCrossfadeFadeInIfActive()
                preloadLyricsForOfflineMediaItem(mediaItem)
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && activeEngine == ActiveAudioEngine.OFFLINE) {
                    attachEffects(player.audioSessionId)
                    applyReplayGainForCurrentItem(player.currentMediaItem)
                }
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    activateEngine(ActiveAudioEngine.OFFLINE, pauseOther = true)
                    PlaybackRuntimeBus.updateTelemetry { it.copy(activeErrorMessage = null) }
                    startProgressAndCrossfadeLoops()
                }
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }

            override fun onPlayerError(error: PlaybackException) {
                PlaybackRuntimeBus.updateTelemetry {
                    it.copy(activeErrorMessage = error.localizedMessage ?: "Unsupported or unreadable audio file")
                }
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        // 2. Initialize Independent Online Player with OkHttp + Failover + ResolvingDataSource
        val okHttpUpstreamFactory = OkHttpDataSource.Factory(OnlineHttpClient.client)
            .setUserAgent(OnlineHttpClient.USER_AGENT)
            .setDefaultRequestProperties(OnlineHttpClient.defaultMediaHeaders())

        val failoverUpstreamFactory = DataSource.Factory {
            FailoverStreamDataSource(okHttpUpstreamFactory.createDataSource())
        }

        val resolvingFactory = ResolvingDataSource.Factory(failoverUpstreamFactory) { dataSpec: DataSpec ->
            resolveOnlineDataSpec(dataSpec)
        }

        val onlineLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                50_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs
                3_000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        onlinePlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setLoadControl(onlineLoadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        onlinePlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (activeEngine == ActiveAudioEngine.ONLINE) {
                    attachEffects(audioSessionId)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                evaluateOutgoingOnlineTrack(reason)
                onlineAccumulatedMs = 0L
                lastCountedOnlineId = null
                val nextTrack = mediaItem?.mediaId?.let { id -> onlineTrackMap[id] }
                resetOnlineTrackingFor(nextTrack)
                nextTrack?.let { tr ->
                    preloadLyricsForOnlineTrack(tr)
                }
                syncOnlineSnapshot()
                saveOnlineQueueStateAsync()
                if (onlinePlayer.mediaItemCount > 0 &&
                    onlinePlayer.currentMediaItemIndex >= onlinePlayer.mediaItemCount - 1
                ) {
                    maybeTriggerInfiniteRadio(autoAdvanceAfterAppend = false)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val trackId = onlinePlayer.currentMediaItem?.mediaId
                    if (trackId != null) onlineRetryCounts.remove(trackId)
                    PlaybackRuntimeBus.updateOnlineSnapshot {
                        it.copy(isResolving = false, errorMessage = null)
                    }
                    if (activeEngine == ActiveAudioEngine.ONLINE) {
                        attachEffects(onlinePlayer.audioSessionId)
                    }
                    if (onlinePlayer.mediaItemCount > 0 &&
                        onlinePlayer.currentMediaItemIndex >= onlinePlayer.mediaItemCount - 1
                    ) {
                        maybeTriggerInfiniteRadio(autoAdvanceAfterAppend = false)
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    val prev = trackedOnlineTrack
                    if (prev != null && !trackedOnlineCompletedAwarded && trackedOnlineListenMs >= 3_000L) {
                        trackedOnlineCompletedAwarded = true
                        val needsPlayIncrement = !trackedOnlineEngagedAwarded
                        trackedOnlineEngagedAwarded = true
                        serviceScope.launch(Dispatchers.IO) {
                            affinityRepo.recordTrackCompleted(prev, incrementPlayCount = needsPlayIncrement)
                        }
                    }
                    maybeTriggerInfiniteRadio(autoAdvanceAfterAppend = true)
                }
                syncOnlineSnapshot()
                saveOnlineQueueStateAsync()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    activateEngine(ActiveAudioEngine.ONLINE, pauseOther = true)
                    PlaybackRuntimeBus.updateOnlineSnapshot {
                        it.copy(isResolving = false, errorMessage = null)
                    }
                    startOnlineProgressLoop()
                }
                syncOnlineSnapshot()
                saveOnlineQueueStateAsync()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                syncOnlineSnapshot()
                saveOnlineQueueStateAsync()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                syncOnlineSnapshot()
                saveOnlineQueueStateAsync()
            }

            override fun onPlayerError(error: PlaybackException) {
                handleOnlinePlayerError(error)
            }
        })

        val sessionIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionIntent)
            .build()

        PlaybackRuntimeBus.onlineBridge = OnlineBridgeImpl()
        PlaybackRuntimeBus.offlineBridge = OfflineBridgeImpl()

        registerAudioReceivers()
        observeSettings()
        restorePersistedQueue()
        restorePersistedOnlineQueue()
    }

    @OptIn(UnstableApi::class)
    private fun resolveOnlineDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != OnlineTrack.ONLINE_SCHEME) {
            return dataSpec
        }

        val trackId = extractOnlineTrackIdFromUri(uri)
        val sourceUrl = uri.getQueryParameter("src").orEmpty()
        val rawHost = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: "youtube"
        val pType = OnlineProviderType.fromProviderId(rawHost)
        val sourceId = uri.pathSegments.firstOrNull().orEmpty()
        val fallbackLabel = if (pType == OnlineProviderType.DYNAMIC) {
            rawHost.replaceFirstChar { it.uppercase() }
        } else {
            pType.displayName
        }

        val track = onlineTrackMap[trackId] ?: OnlineTrack(
            id = trackId,
            sourceId = sourceId,
            providerType = pType,
            providerLabel = fallbackLabel,
            sourceUrl = sourceUrl,
            title = "Online Track",
            artist = fallbackLabel,
            providerId = rawHost
        )

        PlaybackRuntimeBus.updateOnlineSnapshot {
            it.copy(isResolving = true, errorMessage = null)
        }

        return try {
            val resolution = runBlocking {
                providerManager.resolveStream(track, forceRefresh = false)
            }
            val safeStreamUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(resolution.selectedStream.url)
                ?: throw IOException("Blocked unsafe or non-HTTPS stream URL")
            val resolvedPid = resolution.resolvedByProviderId.ifBlank { resolution.resolvedBy.defaultProviderId() }
            val resolvedLabel = resolution.resolvedByLabel.ifBlank { resolution.resolvedBy.displayName }
            onlineTrackMap[track.id] = resolution.track
            resolvedStreamContext[safeStreamUrl] = ResolvedStreamCtx(resolution.track, resolution.resolvedBy, resolvedPid)
            preloadLyricsForOnlineTrack(resolution.track, resolution.selectedStream.formatLabel)
            PlaybackRuntimeBus.updateOnlineSnapshot { snap ->
                snap.copy(
                    isResolving = false,
                    activeStreamLabel = resolution.selectedStream.formatLabel,
                    activeProviderLabel = resolvedLabel,
                    relatedTracks = resolution.relatedTracks,
                    errorMessage = null
                )
            }
            val headers = OnlineHttpClient.defaultMediaHeaders(safeStreamUrl) +
                resolution.selectedStream.headers
            dataSpec.buildUpon()
                .setUri(Uri.parse(safeStreamUrl))
                .setHttpRequestHeaders(headers)
                .build()
        } catch (t: Throwable) {
            PlaybackRuntimeBus.updateOnlineSnapshot {
                it.copy(isResolving = false, errorMessage = t.localizedMessage ?: "Failed to resolve stream")
            }
            throw IOException("Online stream resolution failed: ${t.message}", t)
        }
    }

    @OptIn(UnstableApi::class)
    private inner class FailoverStreamDataSource(
        private val upstream: HttpDataSource
    ) : DataSource {
        private var currentUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            return try {
                currentUri = dataSpec.uri
                upstream.open(dataSpec)
            } catch (err: HttpDataSource.HttpDataSourceException) {
                val isRecoverableHttp = when (err) {
                    is HttpDataSource.InvalidResponseCodeException ->
                        err.responseCode == 403 || err.responseCode == 429 || err.responseCode >= 500
                    else -> true
                }
                val ctx = resolvedStreamContext[dataSpec.uri.toString()]
                if (!isRecoverableHttp || ctx == null) throw err

                val (track, initialFailedType, initialFailedId) = ctx
                val excludedIds = mutableSetOf(initialFailedId)
                providerManager.recordStreamFailure(initialFailedType, initialFailedId)
                providerManager.invalidateCachedStream(track.id)

                var lastErr: HttpDataSource.HttpDataSourceException = err
                repeat(2) {
                    val altResolution = runBlocking {
                        runCatching {
                            providerManager.resolveStream(
                                track = track,
                                forceRefresh = true,
                                excludedProviders = emptySet(),
                                excludedProviderIds = excludedIds
                            )
                        }.getOrNull()
                    } ?: throw lastErr

                    val altPid = altResolution.resolvedByProviderId.ifBlank { altResolution.resolvedBy.defaultProviderId() }
                    val altLabel = altResolution.resolvedByLabel.ifBlank { altResolution.resolvedBy.displayName }
                    excludedIds.add(altPid)
                    val safeAltUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(altResolution.selectedStream.url)
                        ?: run {
                            providerManager.recordStreamFailure(altResolution.resolvedBy, altPid)
                            providerManager.invalidateCachedStream(track.id)
                            throw lastErr
                        }
                    onlineTrackMap[track.id] = altResolution.track
                    resolvedStreamContext[safeAltUrl] =
                        ResolvedStreamCtx(altResolution.track, altResolution.resolvedBy, altPid)
                    PlaybackRuntimeBus.updateOnlineSnapshot { snap ->
                        snap.copy(
                            isResolving = false,
                            activeStreamLabel = altResolution.selectedStream.formatLabel,
                            activeProviderLabel = altLabel,
                            relatedTracks = altResolution.relatedTracks,
                            errorMessage = null
                        )
                    }
                    val headers = OnlineHttpClient.defaultMediaHeaders(safeAltUrl) +
                        altResolution.selectedStream.headers
                    val retrySpec = dataSpec.buildUpon()
                        .setUri(Uri.parse(safeAltUrl))
                        .setHttpRequestHeaders(headers)
                        .build()
                    runCatching { upstream.close() }
                    try {
                        currentUri = retrySpec.uri
                        return upstream.open(retrySpec)
                    } catch (retryErr: HttpDataSource.HttpDataSourceException) {
                        lastErr = retryErr
                        providerManager.recordStreamFailure(altResolution.resolvedBy, altPid)
                        providerManager.invalidateCachedStream(track.id)
                    }
                }
                throw lastErr
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return upstream.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = upstream.uri ?: currentUri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() {
            upstream.close()
        }
    }

    private fun extractOnlineTrackIdFromUri(uri: Uri): String {
        val rawHost = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: "youtube"
        val pType = OnlineProviderType.fromProviderId(rawHost)
        val sourceId = uri.pathSegments.firstOrNull().orEmpty()
        return OnlineTrack.canonicalId(rawHost, pType, sourceId)
    }

    private fun handleOnlinePlayerError(error: PlaybackException) {
        val currentItem = onlinePlayer.currentMediaItem
        val trackId = currentItem?.mediaId
        val track = trackId?.let { onlineTrackMap[it] }
        val attempt = if (trackId != null) (onlineRetryCounts[trackId] ?: 0) else 99

        if (track != null && attempt < 3) {
            onlineRetryCounts[trackId] = attempt + 1
            val resumePos = onlinePlayer.currentPosition.coerceAtLeast(0L)
            val resumeIndex = onlinePlayer.currentMediaItemIndex.coerceAtLeast(0)
            val lastCtx = resolvedStreamContext.values.lastOrNull { it.track.id == track.id }
            val activeProvName = PlaybackRuntimeBus.onlineSnapshot.value.activeProviderLabel
            val failedProvider = lastCtx?.providerType ?: OnlineProviderType.entries.firstOrNull {
                it.displayName.equals(activeProvName, ignoreCase = true)
            } ?: track.providerType
            val failedProviderId = lastCtx?.providerId ?: track.effectiveProviderId()
            providerManager.recordStreamFailure(failedProvider, failedProviderId)
            providerManager.invalidateCachedStream(track.id)
            PlaybackRuntimeBus.updateOnlineSnapshot {
                it.copy(isResolving = true, errorMessage = null)
            }
            serviceScope.launch {
                val excludedIds = if (attempt >= 1) setOf(failedProviderId) else emptySet()
                val reResolved = runCatching {
                    providerManager.resolveStream(
                        track = track,
                        forceRefresh = true,
                        excludedProviders = emptySet(),
                        excludedProviderIds = excludedIds
                    )
                }.getOrNull() ?: runCatching {
                    providerManager.resolveStream(
                        track = track,
                        forceRefresh = true,
                        excludedProviders = emptySet(),
                        excludedProviderIds = emptySet()
                    )
                }.getOrNull()

                if (reResolved != null && ::onlinePlayer.isInitialized) {
                    val rePid = reResolved.resolvedByProviderId.ifBlank { reResolved.resolvedBy.defaultProviderId() }
                    val reLabel = reResolved.resolvedByLabel.ifBlank { reResolved.resolvedBy.displayName }
                    onlineTrackMap[track.id] = reResolved.track
                    resolvedStreamContext[reResolved.selectedStream.url] =
                        ResolvedStreamCtx(reResolved.track, reResolved.resolvedBy, rePid)
                    PlaybackRuntimeBus.updateOnlineSnapshot { snap ->
                        snap.copy(
                            isResolving = false,
                            activeStreamLabel = reResolved.selectedStream.formatLabel,
                            activeProviderLabel = reLabel,
                            relatedTracks = reResolved.relatedTracks,
                            errorMessage = null
                        )
                    }
                    if (resumeIndex in 0 until onlinePlayer.mediaItemCount) {
                        onlinePlayer.seekTo(resumeIndex, resumePos)
                        onlinePlayer.prepare()
                        onlinePlayer.play()
                    }
                } else {
                    advanceOrShowOnlineError(error.localizedMessage ?: "Stream playback error")
                }
            }
        } else {
            advanceOrShowOnlineError(error.localizedMessage ?: "Track unavailable across providers")
        }
    }

    private fun advanceOrShowOnlineError(message: String) {
        PlaybackRuntimeBus.updateOnlineSnapshot {
            it.copy(isResolving = false, isBuffering = false, errorMessage = message)
        }
        if (onlinePlayer.hasNextMediaItem()) {
            onlinePlayer.seekToNextMediaItem()
            onlinePlayer.prepare()
            onlinePlayer.play()
        }
    }

    @OptIn(UnstableApi::class)
    private fun activateEngine(engine: ActiveAudioEngine, pauseOther: Boolean) {
        if (activeEngine != engine) {
            activeEngine = engine
            PlaybackRuntimeBus.updateActiveEngine(engine)
            if (engine == ActiveAudioEngine.ONLINE) {
                if (pauseOther && ::player.isInitialized && player.isPlaying) {
                    player.pause()
                }
                mediaLibrarySession?.setPlayer(onlinePlayer)
                if (onlinePlayer.audioSessionId != 0) {
                    attachEffects(onlinePlayer.audioSessionId)
                }
            } else {
                if (pauseOther && ::onlinePlayer.isInitialized && onlinePlayer.isPlaying) {
                    onlinePlayer.pause()
                }
                mediaLibrarySession?.setPlayer(player)
                if (player.audioSessionId != 0) {
                    attachEffects(player.audioSessionId)
                }
            }
        } else if (pauseOther) {
            if (engine == ActiveAudioEngine.ONLINE && ::player.isInitialized && player.isPlaying) {
                player.pause()
            } else if (engine == ActiveAudioEngine.OFFLINE && ::onlinePlayer.isInitialized && onlinePlayer.isPlaying) {
                onlinePlayer.pause()
            }
        }
    }

    private fun registerAudioReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyAndBtReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisyAndBtReceiver, filter)
        }
        receiversRegistered = true
    }

    @OptIn(UnstableApi::class)
    private fun observeSettings() {
        serviceScope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                currentSettings = settings
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                player.setAudioAttributes(audioAttrs, settings.audioFocusEnabled)
                onlinePlayer.setAudioAttributes(audioAttrs, settings.audioFocusEnabled)
                player.setPlaybackSpeed(settings.playbackSpeed.coerceIn(0.5f, 2.0f))
                balanceAndRgProcessor.channelBalance = settings.channelBalance
                applyReplayGainForCurrentItem(player.currentMediaItem)
                effectsManager.applySettings(settings)
                val sessionId = activePlayer()?.audioSessionId ?: 0
                if (sessionId != 0) {
                    val fxTelemetry = effectsManager.inspectCapabilities(sessionId)
                    PlaybackRuntimeBus.updateTelemetry { prev ->
                        prev.copy(
                            audioSessionId = fxTelemetry.audioSessionId,
                            equalizerSupported = fxTelemetry.equalizerSupported,
                            bassBoostSupported = fxTelemetry.bassBoostSupported,
                            virtualizerSupported = fxTelemetry.virtualizerSupported,
                            loudnessSupported = fxTelemetry.loudnessSupported,
                            bandCount = fxTelemetry.bandCount,
                            bandCenterHz = fxTelemetry.bandCenterHz,
                            minBandLevelMb = fxTelemetry.minBandLevelMb,
                            maxBandLevelMb = fxTelemetry.maxBandLevelMb,
                            presetNames = fxTelemetry.presetNames
                        )
                    }
                }
            }
        }
    }

    private fun attachEffects(sessionId: Int) {
        if (sessionId == 0) return
        val fxTelemetry = effectsManager.attachToSession(sessionId, currentSettings)
        PlaybackRuntimeBus.updateTelemetry { prev ->
            prev.copy(
                audioSessionId = fxTelemetry.audioSessionId,
                equalizerSupported = fxTelemetry.equalizerSupported,
                bassBoostSupported = fxTelemetry.bassBoostSupported,
                virtualizerSupported = fxTelemetry.virtualizerSupported,
                loudnessSupported = fxTelemetry.loudnessSupported,
                bandCount = fxTelemetry.bandCount,
                bandCenterHz = fxTelemetry.bandCenterHz,
                minBandLevelMb = fxTelemetry.minBandLevelMb,
                maxBandLevelMb = fxTelemetry.maxBandLevelMb,
                presetNames = fxTelemetry.presetNames
            )
        }
    }

    private fun applyReplayGainForCurrentItem(mediaItem: MediaItem?) {
        val extras = mediaItem?.mediaMetadata?.extras
        val trackGain = if (extras?.containsKey(EXTRA_RG_TRACK) == true) extras.getFloat(EXTRA_RG_TRACK) else null
        val albumGain = if (extras?.containsKey(EXTRA_RG_ALBUM) == true) extras.getFloat(EXTRA_RG_ALBUM) else null
        val gainDb = when (currentSettings.replayGainMode) {
            ReplayGainMode.OFF -> 0f
            ReplayGainMode.TRACK -> (trackGain ?: 0f) + currentSettings.replayGainPreampDb
            ReplayGainMode.ALBUM -> (albumGain ?: trackGain ?: 0f) + currentSettings.replayGainPreampDb
        }
        balanceAndRgProcessor.replayGainDb = gainDb
        PlaybackRuntimeBus.updateTelemetry { it.copy(replayGainAppliedDb = gainDb) }
    }

    private fun startProgressAndCrossfadeLoops() {
        if (progressSaverJob?.isActive == true) return
        progressSaverJob = serviceScope.launch {
            var tick = 0
            while (player.isPlaying) {
                val crossfadeSec = if (currentSettings.gaplessPlayback) 0 else currentSettings.crossfadeSeconds
                if (crossfadeSec > 0 && player.hasNextMediaItem()) {
                    val dur = player.duration
                    val pos = player.currentPosition
                    if (dur > 0L) {
                        val remainingMs = dur - pos
                        val fadeWindowMs = crossfadeSec * 1000L
                        if (remainingMs in 1L..fadeWindowMs) {
                            player.volume = (remainingMs.toFloat() / fadeWindowMs.toFloat()).coerceIn(0.05f, 1f)
                        } else if (pos > fadeWindowMs) {
                            player.volume = 1f
                        }
                    }
                } else {
                    if (player.volume < 1f && sleepTimerJob?.isActive != true) {
                        player.volume = 1f
                    }
                }
                if (tick % 6 == 0) {
                    savePlaybackStateAsync()
                }
                tick++
                delay(250L)
            }
        }
    }

    private fun resetOnlineTrackingFor(track: OnlineTrack?) {
        trackedOnlineTrack = track
        trackedOnlineListenMs = 0L
        trackedOnlineMaxPosMs = 0L
        trackedOnlineEngagedAwarded = false
        trackedOnlineCompletedAwarded = false
    }

    private fun evaluateOutgoingOnlineTrack(reason: Int) {
        val outgoing = trackedOnlineTrack ?: return
        if (!::affinityRepo.isInitialized) return
        if (trackedOnlineCompletedAwarded) return

        val isAutoFinished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        val isManualSkip = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED

        if (isAutoFinished && trackedOnlineListenMs >= 3_000L) {
            trackedOnlineCompletedAwarded = true
            val needsPlayIncrement = !trackedOnlineEngagedAwarded
            trackedOnlineEngagedAwarded = true
            serviceScope.launch(Dispatchers.IO) {
                affinityRepo.recordTrackCompleted(outgoing, incrementPlayCount = needsPlayIncrement)
            }
        } else if (isManualSkip && trackedOnlineListenMs >= 1_000L && trackedOnlineMaxPosMs < 15_000L) {
            serviceScope.launch(Dispatchers.IO) {
                affinityRepo.recordTrackSkippedEarly(outgoing)
            }
        }
    }

    private fun maybeTriggerInfiniteRadio(autoAdvanceAfterAppend: Boolean) {
        if (!::onlinePlayer.isInitialized || !::recommendationEngine.isInitialized) return
        if (!currentSettings.infiniteRadioEnabled) return
        if (onlinePlayer.repeatMode != Player.REPEAT_MODE_OFF) return
        if (onlinePlayer.mediaItemCount == 0) return
        val currentIdx = onlinePlayer.currentMediaItemIndex
        if (currentIdx < onlinePlayer.mediaItemCount - 1) return

        val queueSnapshot = (0 until onlinePlayer.mediaItemCount).mapNotNull { i ->
            onlineTrackMap[onlinePlayer.getMediaItemAt(i).mediaId]
        }
        val lastTrack = queueSnapshot.lastOrNull() ?: return
        val seedKey = "${lastTrack.id}:${queueSnapshot.size}"

        if (autoAdvanceAfterAppend) {
            pendingRadioAutoAdvance = true
        }
        if (radioFetchJob?.isActive == true) {
            return
        }
        if (lastRadioSeedKey == seedKey && !autoAdvanceAfterAppend) {
            return
        }
        lastRadioSeedKey = seedKey

        PlaybackRuntimeBus.updateOnlineSnapshot { it.copy(isRadioLoading = true) }
        radioFetchJob = serviceScope.launch {
            try {
                val related = runCatching {
                    recommendationEngine.generateInfiniteRadioTracks(
                        seedTrack = lastTrack,
                        currentQueue = queueSnapshot,
                        count = 10
                    )
                }.getOrDefault(emptyList())

                if (related.isNotEmpty() && ::onlinePlayer.isInitialized) {
                    val insertIndex = onlinePlayer.mediaItemCount
                    related.forEach { onlineTrackMap[it.id] = it }
                    val newItems = related.map { buildOnlineMediaItem(it) }
                    onlinePlayer.addMediaItems(newItems)
                    PlaybackRuntimeBus.updateOnlineSnapshot {
                        it.copy(
                            isRadioLoading = false,
                            radioAppendedCount = it.radioAppendedCount + related.size
                        )
                    }
                    if (pendingRadioAutoAdvance && insertIndex in 0 until onlinePlayer.mediaItemCount) {
                        pendingRadioAutoAdvance = false
                        activateEngine(ActiveAudioEngine.ONLINE, pauseOther = true)
                        onlinePlayer.seekTo(insertIndex, 0L)
                        onlinePlayer.prepare()
                        onlinePlayer.play()
                    }
                    syncOnlineSnapshot()
                    saveOnlineQueueStateAsync()
                } else {
                    pendingRadioAutoAdvance = false
                    PlaybackRuntimeBus.updateOnlineSnapshot { it.copy(isRadioLoading = false) }
                }
            } catch (_: Throwable) {
                pendingRadioAutoAdvance = false
                PlaybackRuntimeBus.updateOnlineSnapshot { it.copy(isRadioLoading = false) }
            }
        }
    }

    private fun startOnlineProgressLoop() {
        if (onlineProgressJob?.isActive == true) return
        onlineProgressJob = serviceScope.launch {
            var tick = 0
            while (::onlinePlayer.isInitialized &&
                (onlinePlayer.isPlaying || onlinePlayer.playbackState == Player.STATE_BUFFERING)
            ) {
                syncOnlineSnapshot()
                if (onlinePlayer.isPlaying) {
                    val current = PlaybackRuntimeBus.onlineSnapshot.value.currentTrack
                    if (current != null) {
                        if (trackedOnlineTrack?.id != current.id) {
                            resetOnlineTrackingFor(current)
                        }
                        onlineAccumulatedMs += 250L
                        trackedOnlineListenMs += 250L
                        val posMs = onlinePlayer.currentPosition.coerceAtLeast(0L)
                        if (posMs > trackedOnlineMaxPosMs) {
                            trackedOnlineMaxPosMs = posMs
                        }

                        if (onlineAccumulatedMs >= 5000L && lastCountedOnlineId != current.id) {
                            lastCountedOnlineId = current.id
                            trackedOnlineEngagedAwarded = true
                            serviceScope.launch(Dispatchers.IO) {
                                onlineRepo.recordTrackPlayed(current, 5000L)
                                affinityRepo.recordTrackEngaged(current)
                            }
                        } else if (onlineAccumulatedMs % 5000L == 0L && lastCountedOnlineId == current.id) {
                            serviceScope.launch(Dispatchers.IO) {
                                onlineRepo.addListenTime(current.id, 5000L)
                            }
                        }

                        val durMs = onlinePlayer.duration.takeIf { it > 0L } ?: current.durationMs
                        if (!trackedOnlineCompletedAwarded &&
                            durMs > 0L &&
                            posMs >= (durMs * 0.80).toLong() &&
                            (trackedOnlineListenMs >= (durMs * 0.40).toLong() || trackedOnlineListenMs >= 20_000L)
                        ) {
                            trackedOnlineCompletedAwarded = true
                            val needsPlayIncrement = !trackedOnlineEngagedAwarded
                            trackedOnlineEngagedAwarded = true
                            serviceScope.launch(Dispatchers.IO) {
                                affinityRepo.recordTrackCompleted(current, incrementPlayCount = needsPlayIncrement)
                            }
                        }
                    }
                }
                if (tick % 8 == 0) {
                    saveOnlineQueueStateAsync()
                }
                tick++
                delay(250L)
            }
        }
    }

    private fun applyCrossfadeFadeInIfActive() {
        val crossfadeSec = if (currentSettings.gaplessPlayback) 0 else currentSettings.crossfadeSeconds
        if (crossfadeSec <= 0) {
            player.volume = 1f
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = serviceScope.launch {
            val steps = (crossfadeSec * 10).coerceIn(5, 80)
            for (i in 1..steps) {
                player.volume = (i.toFloat() / steps.toFloat()).coerceIn(0.05f, 1f)
                delay(100L)
            }
            player.volume = 1f
        }
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            if (::player.isInitialized) player.volume = 1f
            if (::onlinePlayer.isInitialized) onlinePlayer.volume = 1f
            PlaybackRuntimeBus.updateSleepTimer(SleepTimerState())
            return
        }
        val totalMs = minutes * 60_000L
        sleepTimerJob = serviceScope.launch {
            var remaining = totalMs
            while (remaining > 0L) {
                PlaybackRuntimeBus.updateSleepTimer(
                    SleepTimerState(isActive = true, remainingMs = remaining, totalMs = totalMs)
                )
                if (remaining <= 3000L) {
                    val vol = (remaining / 3000f).coerceIn(0.05f, 1f)
                    activePlayer()?.volume = vol
                }
                delay(1000L)
                remaining -= 1000L
            }
            activePlayer()?.pause()
            if (::player.isInitialized) player.volume = 1f
            if (::onlinePlayer.isInitialized) onlinePlayer.volume = 1f
            PlaybackRuntimeBus.updateSleepTimer(SleepTimerState())
        }
    }

    private fun restorePersistedQueue() {
        serviceScope.launch {
            val settings = settingsRepository.getSettingsOnce()
            currentSettings = settings
            val saved = settingsRepository.loadQueueState()
            if (saved.queueUris.isEmpty()) return@launch
            if (player.mediaItemCount > 0) return@launch

            val items = saved.queueUris.mapNotNull { uriStr ->
                val parsed = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@mapNotNull null
                if (!InputAndUrlSecurity.isSafePlayableLocalUri(parsed)) return@mapNotNull null
                runCatching { buildMediaItem(parsed) }.getOrNull()
            }
            if (items.isNotEmpty()) {
                val safeIndex = saved.currentIndex.coerceIn(items.indices)
                player.setMediaItems(items, safeIndex, saved.positionMs.coerceAtLeast(0L))
                player.shuffleModeEnabled = saved.shuffleEnabled
                player.repeatMode = saved.repeatMode
                player.prepare()
                if (settings.resumePlaybackOnStart) {
                    player.pause()
                }
                syncOfflineSnapshot()
            }
        }
    }

    private fun restorePersistedOnlineQueue() {
        serviceScope.launch {
            val saved = settingsRepository.loadOnlineQueueState()
            if (saved.tracks.isEmpty()) return@launch
            if (onlinePlayer.mediaItemCount > 0) return@launch

            saved.tracks.forEach { onlineTrackMap[it.id] = it }
            val items = saved.tracks.map { buildOnlineMediaItem(it) }
            val safeIdx = saved.currentIndex.coerceIn(items.indices)
            onlinePlayer.setMediaItems(items, safeIdx, saved.positionMs.coerceAtLeast(0L))
            onlinePlayer.shuffleModeEnabled = saved.shuffleEnabled
            onlinePlayer.repeatMode = saved.repeatMode
            // Don't prepare immediately so we don't hit network until user plays Online
            syncOnlineSnapshot()
        }
    }

    private fun savePlaybackStateAsync() {
        if (!::player.isInitialized) return
        val uris = (0 until player.mediaItemCount).mapNotNull { i ->
            player.getMediaItemAt(i).localConfiguration?.uri?.toString()
        }
        val idx = player.currentMediaItemIndex.coerceAtLeast(0)
        val pos = player.currentPosition.coerceAtLeast(0L)
        val shuffle = player.shuffleModeEnabled
        val repeat = player.repeatMode
        serviceScope.launch(Dispatchers.IO) {
            settingsRepository.saveQueueState(uris, idx, pos, shuffle, repeat)
        }
    }

    private fun saveOnlineQueueStateAsync() {
        if (!::onlinePlayer.isInitialized) return
        val tracks = (0 until onlinePlayer.mediaItemCount).mapNotNull { i ->
            val item = onlinePlayer.getMediaItemAt(i)
            onlineTrackMap[item.mediaId]
        }
        val idx = onlinePlayer.currentMediaItemIndex.coerceAtLeast(0)
        val pos = onlinePlayer.currentPosition.coerceAtLeast(0L)
        val shuffle = onlinePlayer.shuffleModeEnabled
        val repeat = onlinePlayer.repeatMode
        serviceScope.launch(Dispatchers.IO) {
            settingsRepository.saveOnlineQueueState(tracks, idx, pos, shuffle, repeat)
        }
    }

    private fun syncOnlineSnapshot() {
        if (!::onlinePlayer.isInitialized) return
        val queue = (0 until onlinePlayer.mediaItemCount).mapNotNull { i ->
            val item = onlinePlayer.getMediaItemAt(i)
            onlineTrackMap[item.mediaId]
        }
        val idx = onlinePlayer.currentMediaItemIndex.coerceAtLeast(0)
        val current = queue.getOrNull(idx)
        val dur = onlinePlayer.duration.takeIf { it > 0L } ?: (current?.durationMs ?: 0L)
        val buffering = onlinePlayer.playbackState == Player.STATE_BUFFERING
        PlaybackRuntimeBus.updateOnlineSnapshot { prev ->
            prev.copy(
                isReady = true,
                isPlaying = onlinePlayer.isPlaying,
                isBuffering = buffering,
                currentTrack = current,
                queue = queue,
                currentIndex = idx,
                positionMs = onlinePlayer.currentPosition.coerceAtLeast(0L),
                durationMs = dur,
                bufferedPositionMs = onlinePlayer.bufferedPosition.coerceAtLeast(0L),
                shuffleEnabled = onlinePlayer.shuffleModeEnabled,
                repeatMode = onlinePlayer.repeatMode,
                playbackSpeed = onlinePlayer.playbackParameters.speed,
                activeProviderLabel = prev.activeProviderLabel.ifBlank { current?.providerLabel.orEmpty() }
            )
        }
    }

    private fun buildOfflineSnapshot(): OfflineEngineSnapshot? {
        if (!::player.isInitialized) return null
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        return OfflineEngineSnapshot(
            isPlaying = player.isPlaying,
            mediaItems = items,
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            playbackSpeed = player.playbackParameters.speed
        )
    }

    private fun syncOfflineSnapshot() {
        buildOfflineSnapshot()?.let { PlaybackRuntimeBus.updateOfflineSnapshot(it) }
    }

    private fun buildMediaItem(uri: Uri): MediaItem {
        val ext = TagAndLyricsEngine.readExtendedTags(
            context = this,
            uri = uri,
            mimeType = contentResolver.getType(uri).orEmpty(),
            displayName = uri.lastPathSegment.orEmpty()
        )
        val title = ext.title ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() } ?: "Local Audio"
        val artist = ext.artist ?: "Unknown Artist"
        val extras = Bundle().apply {
            ext.replayGainTrackDb?.let { putFloat(EXTRA_RG_TRACK, it) }
            ext.replayGainAlbumDb?.let { putFloat(EXTRA_RG_ALBUM, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(ext.album ?: "Unknown Album")
            .setDisplayTitle(title)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun buildOnlineMediaItem(track: OnlineTrack): MediaItem {
        onlineTrackMap[track.id] = track
        val virtualUri = OnlineTrack.buildOnlineVirtualUri(
            track.effectiveProviderId(),
            track.sourceId,
            track.sourceUrl
        )
        val extras = Bundle().apply {
            putString(EXTRA_ONLINE_TRACK_JSON, json.encodeToString(track))
        }
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifBlank { track.providerLabel })
            .setDisplayTitle(track.title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(extras)
        track.thumbnailUrl?.takeIf { InputAndUrlSecurity.isSafeArtworkUri(it) }?.let { art ->
            metadataBuilder.setArtworkUri(Uri.parse(art))
        }
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(virtualUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val active = activePlayer()
        if (active != null) {
            when (intent?.action) {
                ACTION_TOGGLE_PLAY -> if (active.isPlaying) active.pause() else if (active.mediaItemCount > 0) active.play()
                ACTION_NEXT -> if (active.mediaItemCount > 0) active.seekToNextMediaItem()
                ACTION_PREVIOUS -> if (active.mediaItemCount > 0) active.seekToPreviousMediaItem()
            }
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        savePlaybackStateAsync()
        saveOnlineQueueStateAsync()
        PlaybackRuntimeBus.onlineBridge = null
        PlaybackRuntimeBus.offlineBridge = null
        sleepTimerJob?.cancel()
        progressSaverJob?.cancel()
        onlineProgressJob?.cancel()
        crossfadeJob?.cancel()
        lyricsPreloadJob?.cancel()
        radioFetchJob?.cancel()
        if (receiversRegistered) {
            runCatching { unregisterReceiver(noisyAndBtReceiver) }
            receiversRegistered = false
        }
        effectsManager.release()
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        if (::player.isInitialized) player.release()
        if (::onlinePlayer.isInitialized) onlinePlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun preloadLyricsForOnlineTrack(track: OnlineTrack, formatTag: String? = null) {
        if (!::lyricsRepository.isInitialized) return
        val uiTrack = track.toUiTrack(formatTag)
        lyricsPreloadJob?.cancel()
        lyricsPreloadJob = serviceScope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                lyricsRepository.loadLyricsForTrack(
                    track = uiTrack,
                    includedSafFolders = currentSettings.includedSafFolderUris,
                    forceRemoteRefresh = false
                )
            }.getOrNull()
            if (loaded != null && loaded.hasContent) {
                PlaybackRuntimeBus.updatePreloadedLyrics(uiTrack.uriString, loaded)
            }
        }
    }

    private fun preloadLyricsForOfflineMediaItem(mediaItem: MediaItem?) {
        if (mediaItem == null || !::lyricsRepository.isInitialized) return
        val uriStr = mediaItem.localConfiguration?.uri?.toString() ?: return
        val meta = mediaItem.mediaMetadata
        val title = meta.title?.toString().orEmpty()
        val artist = meta.artist?.toString().orEmpty()
        val album = meta.albumTitle?.toString().orEmpty()
        if (title.isBlank()) return
        val lightweightTrack = Track(
            id = uriStr.hashCode().toLong(),
            uriString = uriStr,
            filePath = "",
            displayName = title,
            title = title,
            artist = artist,
            album = album,
            albumArtist = artist,
            genre = "",
            year = 0,
            trackNumber = 0,
            discNumber = 0,
            composer = "",
            durationMs = 0L,
            sizeBytes = 0L,
            dateAddedSec = 0L,
            dateModifiedSec = 0L,
            mimeType = "audio/*",
            formatLabel = "",
            bitrateKbps = 0,
            sampleRateHz = 0,
            channelCount = 0,
            folderPath = "",
            folderName = ""
        )
        lyricsPreloadJob?.cancel()
        lyricsPreloadJob = serviceScope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                lyricsRepository.loadLyricsForTrack(
                    track = lightweightTrack,
                    includedSafFolders = currentSettings.includedSafFolderUris,
                    forceRemoteRefresh = false
                )
            }.getOrNull()
            if (loaded != null && loaded.hasContent) {
                PlaybackRuntimeBus.updatePreloadedLyrics(uriStr, loaded)
            }
        }
    }

    private inner class OnlineBridgeImpl : OnlinePlaybackBridge {
        override fun playOnlineTracks(tracks: List<OnlineTrack>, startIndex: Int, shuffle: Boolean) {
            if (tracks.isEmpty()) return
            serviceScope.launch {
                lastRadioSeedKey = null
                pendingRadioAutoAdvance = false
                radioFetchJob?.cancel()
                tracks.forEach { onlineTrackMap[it.id] = it }
                val safeIdx = startIndex.coerceIn(tracks.indices)
                preloadLyricsForOnlineTrack(tracks[safeIdx])
                val items = tracks.map { buildOnlineMediaItem(it) }
                activateEngine(ActiveAudioEngine.ONLINE, pauseOther = true)
                onlinePlayer.shuffleModeEnabled = shuffle
                onlinePlayer.setMediaItems(items, safeIdx, 0L)
                onlinePlayer.prepare()
                onlinePlayer.play()
                syncOnlineSnapshot()
                startOnlineProgressLoop()
                saveOnlineQueueStateAsync()
            }
        }

        override fun togglePlayPause() {
            serviceScope.launch {
                if (onlinePlayer.isPlaying) {
                    onlinePlayer.pause()
                } else if (onlinePlayer.mediaItemCount > 0) {
                    activateEngine(ActiveAudioEngine.ONLINE, pauseOther = true)
                    if (onlinePlayer.playbackState == Player.STATE_IDLE) {
                        onlinePlayer.prepare()
                    }
                    onlinePlayer.play()
                    startOnlineProgressLoop()
                }
                syncOnlineSnapshot()
            }
        }

        override fun pause() {
            serviceScope.launch {
                if (onlinePlayer.isPlaying) onlinePlayer.pause()
                syncOnlineSnapshot()
            }
        }

        override fun seekTo(positionMs: Long) {
            serviceScope.launch {
                onlinePlayer.seekTo(positionMs.coerceAtLeast(0L))
                syncOnlineSnapshot()
            }
        }

        override fun skipNext() {
            serviceScope.launch {
                activateEngine(ActiveAudioEngine.ONLINE, pauseOther = true)
                if (onlinePlayer.hasNextMediaItem()) {
                    onlinePlayer.seekToNextMediaItem()
                    if (onlinePlayer.playbackState == Player.STATE_IDLE) onlinePlayer.prepare()
                    onlinePlayer.play()
                } else if (onlinePlayer.mediaItemCount > 0) {
                    if (currentSettings.infiniteRadioEnabled && onlinePlayer.repeatMode == Player.REPEAT_MODE_OFF) {
                        evaluateOutgoingOnlineTrack(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                        maybeTriggerInfiniteRadio(autoAdvanceAfterAppend = true)
                    } else {
                        onlinePlayer.seekToDefaultPosition(0)
                        if (onlinePlayer.playbackState == Player.STATE_IDLE) onlinePlayer.prepare()
                        onlinePlayer.play()
                    }
                }
                syncOnlineSnapshot()
            }
        }

        override fun skipPrevious() {
            serviceScope.launch {
                activateEngine(ActiveAudioEngine.ONLINE, pauseOther = true)
                if (onlinePlayer.currentPosition > 3000L) {
                    onlinePlayer.seekTo(0L)
                } else if (onlinePlayer.hasPreviousMediaItem()) {
                    onlinePlayer.seekToPreviousMediaItem()
                } else if (onlinePlayer.mediaItemCount > 0) {
                    onlinePlayer.seekToDefaultPosition(onlinePlayer.mediaItemCount - 1)
                }
                if (onlinePlayer.playbackState == Player.STATE_IDLE) onlinePlayer.prepare()
                onlinePlayer.play()
                syncOnlineSnapshot()
            }
        }

        override fun toggleShuffle() {
            serviceScope.launch {
                onlinePlayer.shuffleModeEnabled = !onlinePlayer.shuffleModeEnabled
                syncOnlineSnapshot()
            }
        }

        override fun cycleRepeatMode() {
            serviceScope.launch {
                onlinePlayer.repeatMode = when (onlinePlayer.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                syncOnlineSnapshot()
            }
        }

        override fun setPlaybackSpeed(speed: Float) {
            serviceScope.launch {
                onlinePlayer.setPlaybackSpeed(speed.coerceIn(0.5f, 2.0f))
                syncOnlineSnapshot()
            }
        }

        override fun addToQueue(tracks: List<OnlineTrack>) {
            if (tracks.isEmpty()) return
            serviceScope.launch {
                if (onlinePlayer.mediaItemCount == 0) {
                    playOnlineTracks(tracks, 0, false)
                    return@launch
                }
                tracks.forEach { onlineTrackMap[it.id] = it }
                onlinePlayer.addMediaItems(tracks.map { buildOnlineMediaItem(it) })
                syncOnlineSnapshot()
                saveOnlineQueueStateAsync()
            }
        }

        override fun playNext(tracks: List<OnlineTrack>) {
            if (tracks.isEmpty()) return
            serviceScope.launch {
                if (onlinePlayer.mediaItemCount == 0) {
                    playOnlineTracks(tracks, 0, false)
                    return@launch
                }
                tracks.forEach { onlineTrackMap[it.id] = it }
                val insertIdx = (onlinePlayer.currentMediaItemIndex + 1).coerceAtMost(onlinePlayer.mediaItemCount)
                onlinePlayer.addMediaItems(insertIdx, tracks.map { buildOnlineMediaItem(it) })
                syncOnlineSnapshot()
                saveOnlineQueueStateAsync()
            }
        }

        override fun jumpToQueueIndex(index: Int) {
            serviceScope.launch {
                if (index in 0 until onlinePlayer.mediaItemCount) {
                    activateEngine(ActiveAudioEngine.ONLINE, pauseOther = true)
                    onlinePlayer.seekTo(index, 0L)
                    onlinePlayer.prepare()
                    onlinePlayer.play()
                    startOnlineProgressLoop()
                    syncOnlineSnapshot()
                }
            }
        }

        override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
            serviceScope.launch {
                if (fromIndex in 0 until onlinePlayer.mediaItemCount &&
                    toIndex in 0 until onlinePlayer.mediaItemCount &&
                    fromIndex != toIndex
                ) {
                    onlinePlayer.moveMediaItem(fromIndex, toIndex)
                    syncOnlineSnapshot()
                    saveOnlineQueueStateAsync()
                }
            }
        }

        override fun removeQueueItem(index: Int) {
            serviceScope.launch {
                if (index in 0 until onlinePlayer.mediaItemCount) {
                    onlinePlayer.removeMediaItem(index)
                    syncOnlineSnapshot()
                    saveOnlineQueueStateAsync()
                }
            }
        }

        override fun clearQueue() {
            serviceScope.launch {
                onlinePlayer.clearMediaItems()
                syncOnlineSnapshot()
                saveOnlineQueueStateAsync()
            }
        }

        override fun shuffleCurrentQueue() {
            serviceScope.launch {
                if (onlinePlayer.mediaItemCount <= 1) return@launch
                val currentIdx = onlinePlayer.currentMediaItemIndex
                val snap = PlaybackRuntimeBus.onlineSnapshot.value
                val currentTrack = snap.queue.getOrNull(currentIdx)
                val remaining = snap.queue.filterIndexed { i, _ -> i != currentIdx }.shuffled()
                val reordered = if (currentTrack != null) listOf(currentTrack) + remaining else remaining
                val pos = onlinePlayer.currentPosition
                val wasPlaying = onlinePlayer.isPlaying
                onlinePlayer.setMediaItems(reordered.map { buildOnlineMediaItem(it) }, 0, pos)
                if (wasPlaying) {
                    onlinePlayer.prepare()
                    onlinePlayer.play()
                }
                syncOnlineSnapshot()
                saveOnlineQueueStateAsync()
            }
        }

        override fun retryCurrentTrack() {
            serviceScope.launch {
                val track = PlaybackRuntimeBus.onlineSnapshot.value.currentTrack ?: return@launch
                onlineRetryCounts.remove(track.id)
                providerManager.invalidateCachedStream(track.id)
                activateEngine(ActiveAudioEngine.ONLINE, pauseOther = true)
                val idx = onlinePlayer.currentMediaItemIndex.coerceAtLeast(0)
                val pos = onlinePlayer.currentPosition.coerceAtLeast(0L)
                onlinePlayer.seekTo(idx, pos)
                onlinePlayer.prepare()
                onlinePlayer.play()
                startOnlineProgressLoop()
            }
        }
    }

    private inner class OfflineBridgeImpl : OfflinePlaybackBridge {
        override fun activateOfflineEngine(pauseOnline: Boolean) {
            serviceScope.launch {
                activateEngine(ActiveAudioEngine.OFFLINE, pauseOther = pauseOnline)
            }
        }

        override fun setMediaItemsAndPlay(items: List<MediaItem>, startIndex: Int, shuffle: Boolean) {
            if (items.isEmpty()) return
            serviceScope.launch {
                activateEngine(ActiveAudioEngine.OFFLINE, pauseOther = true)
                val safeIdx = startIndex.coerceIn(items.indices)
                player.shuffleModeEnabled = shuffle
                player.setMediaItems(items, safeIdx, 0L)
                player.prepare()
                player.play()
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }
        }

        override fun togglePlayPause() {
            serviceScope.launch {
                if (player.isPlaying) {
                    player.pause()
                } else if (player.mediaItemCount > 0) {
                    activateEngine(ActiveAudioEngine.OFFLINE, pauseOther = true)
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
                syncOfflineSnapshot()
            }
        }

        override fun seekTo(positionMs: Long) {
            serviceScope.launch {
                player.seekTo(positionMs.coerceAtLeast(0L))
                syncOfflineSnapshot()
            }
        }

        override fun skipNext() {
            serviceScope.launch {
                activateEngine(ActiveAudioEngine.OFFLINE, pauseOther = true)
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                else if (player.mediaItemCount > 0) player.seekToDefaultPosition(0)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                syncOfflineSnapshot()
            }
        }

        override fun skipPrevious() {
            serviceScope.launch {
                activateEngine(ActiveAudioEngine.OFFLINE, pauseOther = true)
                if (player.currentPosition > 3000L) player.seekTo(0L)
                else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                else if (player.mediaItemCount > 0) player.seekToDefaultPosition(player.mediaItemCount - 1)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                syncOfflineSnapshot()
            }
        }

        override fun toggleShuffle() {
            serviceScope.launch {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }
        }

        override fun cycleRepeatMode() {
            serviceScope.launch {
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }
        }

        override fun setPlaybackSpeed(speed: Float) {
            serviceScope.launch {
                player.setPlaybackSpeed(speed.coerceIn(0.5f, 2.0f))
                syncOfflineSnapshot()
            }
        }

        override fun addToQueue(items: List<MediaItem>) {
            if (items.isEmpty()) return
            serviceScope.launch {
                if (player.mediaItemCount == 0) {
                    setMediaItemsAndPlay(items, 0, false)
                    return@launch
                }
                player.addMediaItems(items)
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }
        }

        override fun playNext(items: List<MediaItem>) {
            if (items.isEmpty()) return
            serviceScope.launch {
                if (player.mediaItemCount == 0) {
                    setMediaItemsAndPlay(items, 0, false)
                    return@launch
                }
                val insertIdx = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
                player.addMediaItems(insertIdx, items)
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }
        }

        override fun jumpToQueueIndex(index: Int) {
            serviceScope.launch {
                if (index in 0 until player.mediaItemCount) {
                    activateEngine(ActiveAudioEngine.OFFLINE, pauseOther = true)
                    player.seekTo(index, 0L)
                    player.prepare()
                    player.play()
                    syncOfflineSnapshot()
                }
            }
        }

        override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
            serviceScope.launch {
                if (fromIndex in 0 until player.mediaItemCount &&
                    toIndex in 0 until player.mediaItemCount &&
                    fromIndex != toIndex
                ) {
                    player.moveMediaItem(fromIndex, toIndex)
                    syncOfflineSnapshot()
                    savePlaybackStateAsync()
                }
            }
        }

        override fun removeQueueItem(index: Int) {
            serviceScope.launch {
                if (index in 0 until player.mediaItemCount) {
                    player.removeMediaItem(index)
                    syncOfflineSnapshot()
                    savePlaybackStateAsync()
                }
            }
        }

        override fun clearQueue() {
            serviceScope.launch {
                player.clearMediaItems()
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }
        }

        override fun shuffleCurrentQueue(reordered: List<MediaItem>, positionMs: Long, wasPlaying: Boolean) {
            serviceScope.launch {
                if (reordered.isEmpty()) return@launch
                player.setMediaItems(reordered, 0, positionMs)
                player.prepare()
                if (wasPlaying) player.play()
                syncOfflineSnapshot()
                savePlaybackStateAsync()
            }
        }

        override fun getOfflineSnapshot(): OfflineEngineSnapshot? = buildOfflineSnapshot()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(CMD_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(CMD_PREVIEW_EQ_PRESET, Bundle.EMPTY))
                .add(SessionCommand(CMD_ACTIVATE_OFFLINE_ENGINE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SLEEP_TIMER -> {
                    val minutes = args.getInt(ARG_MINUTES, 30).coerceIn(0, 480)
                    startSleepTimer(minutes)
                }
                CMD_CANCEL_SLEEP_TIMER -> {
                    startSleepTimer(0)
                }
                CMD_PREVIEW_EQ_PRESET -> {
                    val presetIdx = args.getInt(ARG_PRESET_INDEX, -1)
                    if (presetIdx in 0..64) {
                        val levels = effectsManager.getPresetBandLevels(presetIdx)
                        PlaybackRuntimeBus.updatePresetBandPreview(levels)
                    }
                }
                CMD_ACTIVATE_OFFLINE_ENGINE -> {
                    activateEngine(ActiveAudioEngine.OFFLINE, pauseOther = false)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                val uri = item.requestMetadata.mediaUri ?: runCatching { Uri.parse(item.mediaId) }.getOrNull()
                val localUri = item.localConfiguration?.uri
                when {
                    localUri != null && (localUri.scheme == OnlineTrack.ONLINE_SCHEME || InputAndUrlSecurity.isSafePlayableLocalUri(localUri)) -> item
                    uri != null && uri.scheme == OnlineTrack.ONLINE_SCHEME -> {
                        val track = onlineTrackMap[item.mediaId]
                        if (track != null) buildOnlineMediaItem(track) else null
                    }
                    uri != null && InputAndUrlSecurity.isSafePlayableLocalUri(uri) -> buildMediaItem(uri)
                    else -> null
                }
            }
            return Futures.immediateFuture(resolved)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("ARIA Library")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = when (parentId) {
                ROOT_ID -> listOf(
                    browsableItem(NODE_QUEUE, "Offline Queue"),
                    browsableItem(NODE_ONLINE_QUEUE, "Online Queue"),
                    browsableItem(NODE_ONLINE_FAVORITES, "Online Favorites"),
                    browsableItem(NODE_ONLINE_RECENT, "Online Recently Played")
                )
                NODE_QUEUE -> (0 until player.mediaItemCount).map { idx ->
                    val current = player.getMediaItemAt(idx)
                    current.buildUpon()
                        .setMediaMetadata(
                            current.mediaMetadata.buildUpon()
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                }
                NODE_ONLINE_QUEUE -> (0 until onlinePlayer.mediaItemCount).map { idx ->
                    val current = onlinePlayer.getMediaItemAt(idx)
                    current.buildUpon()
                        .setMediaMetadata(
                            current.mediaMetadata.buildUpon()
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                }
                NODE_ONLINE_FAVORITES -> runBlocking(Dispatchers.IO) {
                    onlineRepo.favorites.first().take(50).map { buildOnlineMediaItem(it) }
                }
                NODE_ONLINE_RECENT -> runBlocking(Dispatchers.IO) {
                    onlineRepo.recentlyPlayed.first().take(50).map { buildOnlineMediaItem(it) }
                }
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        private fun browsableItem(id: String, title: String): MediaItem {
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY = "app.nocturne.player.TOGGLE_PLAY"
        const val ACTION_PREVIOUS = "app.nocturne.player.PREVIOUS"
        const val ACTION_NEXT = "app.nocturne.player.NEXT"

        const val CMD_SLEEP_TIMER = "app.nocturne.player.CMD_SLEEP_TIMER"
        const val CMD_CANCEL_SLEEP_TIMER = "app.nocturne.player.CMD_CANCEL_SLEEP_TIMER"
        const val CMD_PREVIEW_EQ_PRESET = "app.nocturne.player.CMD_PREVIEW_EQ_PRESET"
        const val CMD_ACTIVATE_OFFLINE_ENGINE = "app.nocturne.player.CMD_ACTIVATE_OFFLINE_ENGINE"
        const val ARG_MINUTES = "minutes"
        const val ARG_PRESET_INDEX = "preset_index"

        const val EXTRA_RG_TRACK = "rg_track_db"
        const val EXTRA_RG_ALBUM = "rg_album_db"
        private const val EXTRA_ONLINE_TRACK_JSON = "online_track_json"

        private const val ROOT_ID = "aria_root"
        private const val NODE_QUEUE = "aria_queue"
        private const val NODE_ONLINE_QUEUE = "aria_online_queue"
        private const val NODE_ONLINE_FAVORITES = "aria_online_favorites"
        private const val NODE_ONLINE_RECENT = "aria_online_recent"
    }
}
