package app.nocturne.player.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import app.nocturne.player.data.library.ArtworkCache
import app.nocturne.player.data.model.ArtworkShape
import app.nocturne.player.data.model.ArtworkTapAction
import app.nocturne.player.data.model.AudioEngineTelemetry
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.model.SwipeVerticalAction
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.data.security.InputAndUrlSecurity
import app.nocturne.player.playback.SleepTimerState
import app.nocturne.player.ui.PlaybackUiState
import app.nocturne.player.ui.components.AnimatedInkEqualizer
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.PaperAlertDialog
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDebossedWell
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.PaperModalSheet
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.formatDuration
import app.nocturne.player.ui.components.paperGrainBackground
import app.nocturne.player.ui.theme.AriaPaperTheme
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(
    playback: PlaybackUiState,
    settings: UserSettings,
    telemetry: AudioEngineTelemetry,
    sleepTimer: SleepTimerState,
    lyrics: LyricsData,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onToggleRemainingTime: () -> Unit,
    onChangeSpeed: (Float) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenAudioFx: () -> Unit,
    onOpenTagEditor: (Track) -> Unit,
    onOpenAddToPlaylist: (Track) -> Unit,
    onOpenDetails: (Track) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    isBuffering: Boolean = false,
    isResolving: Boolean = false,
    activeStreamLabel: String = "",
    errorMessage: String? = null,
    onRetryStream: (() -> Unit)? = null,
    onOpenRelated: (() -> Unit)? = null,
    onRetryLyrics: (() -> Unit)? = null
) {
    BackHandler(onBack = onDismiss)

    val palette = AriaPaperTheme.palette
    val motion = AriaPaperTheme.motion
    val context = LocalContext.current
    val track = playback.currentTrack

    var extractedAmbientColor by remember { mutableStateOf<Color?>(null) }
    val artworkLookupKey = remember(track?.uriString, track?.customCoverUri, track?.artworkUrl) {
        track?.customCoverUri?.takeIf { it.isNotBlank() }
            ?: track?.artworkUrl?.takeIf { it.isNotBlank() }
            ?: track?.uriString
    }
    LaunchedEffect(artworkLookupKey, settings.preferFolderCoverArt) {
        extractedAmbientColor = null
        val cur = track ?: return@LaunchedEffect
        val remoteUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(artworkLookupKey)
        val bmp = withContext(Dispatchers.IO) {
            if (remoteUrl != null) {
                runCatching {
                    val req = ImageRequest.Builder(context)
                        .data(remoteUrl)
                        .size(256)
                        .allowHardware(false)
                        .build()
                    val result = context.imageLoader.execute(req) as? SuccessResult
                    (result?.drawable as? BitmapDrawable)?.bitmap
                }.getOrNull()
            } else {
                ArtworkCache.loadArtwork(
                    context = context,
                    trackUri = cur.uriString,
                    customCoverUri = cur.customCoverUri,
                    folderPath = cur.folderPath,
                    preferFolderCover = settings.preferFolderCoverArt,
                    targetSizePx = 256
                )?.asAndroidBitmap()
            }
        } ?: return@LaunchedEffect

        val swatchRgb = withContext(Dispatchers.Default) {
            runCatching {
                val p = Palette.from(bmp).generate()
                (if (palette.isDark) p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.dominantSwatch else p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb
            }.getOrNull()
        }
        if (swatchRgb != null) {
            extractedAmbientColor = Color(swatchRgb)
        }
    }

    val targetAmbientColor = extractedAmbientColor ?: palette.accent
    val animatedAmbientColor by animateColorAsState(
        targetValue = targetAmbientColor,
        animationSpec = tween(durationMillis = if (motion.isReduced) 0 else 550),
        label = "now_playing_ambient_color"
    )

    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var draggingSeekFraction by remember(playback.currentUri) { mutableStateOf<Float?>(null) }
    var showInlineLyricsOverlay by remember { mutableStateOf(false) }
    var showAudioInfoBanner by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }

    val animatedDragX by animateFloatAsState(
        targetValue = dragX,
        animationSpec = motion.springSpec,
        label = "art_drag_x"
    )

    val safeDuration = playback.durationMs.coerceAtLeast(0L)
    val actualProgress = if (safeDuration > 0L) {
        (playback.positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val displayedProgress = draggingSeekFraction ?: actualProgress
    val displayedPosMs = if (draggingSeekFraction != null && safeDuration > 0L) {
        (displayedProgress * safeDuration).toLong()
    } else {
        playback.positionMs
    }

    fun executeVerticalAction(action: SwipeVerticalAction) {
        when (action) {
            SwipeVerticalAction.OPEN_QUEUE -> onOpenQueue()
            SwipeVerticalAction.OPEN_LYRICS -> onOpenLyrics()
            SwipeVerticalAction.TOGGLE_FAVORITE -> track?.let(onToggleFavorite)
            SwipeVerticalAction.OPEN_AUDIO_FX -> onOpenAudioFx()
            SwipeVerticalAction.DISMISS_PLAYER -> onDismiss()
            SwipeVerticalAction.NONE -> Unit
        }
    }

    fun executeArtworkTap() {
        when (settings.artworkTapAction) {
            ArtworkTapAction.TOGGLE_LYRICS -> showInlineLyricsOverlay = !showInlineLyricsOverlay
            ArtworkTapAction.SHOW_AUDIO_INFO -> showAudioInfoBanner = !showAudioInfoBanner
            ArtworkTapAction.PLAY_PAUSE -> onTogglePlay()
            ArtworkTapAction.OPEN_TAG_EDITOR -> track?.let(onOpenTagEditor)
            ArtworkTapAction.NONE -> Unit
        }
    }

    val topAmbientAlpha = if (extractedAmbientColor != null) {
        if (palette.isDark) 0.34f else 0.22f
    } else {
        if (palette.isDark) 0.14f else 0.08f
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .paperGrainBackground(palette.paperBackground)
            .background(
                Brush.verticalGradient(
                    0.0f to animatedAmbientColor.copy(alpha = topAmbientAlpha),
                    0.52f to animatedAmbientColor.copy(alpha = topAmbientAlpha * 0.32f),
                    1.0f to Color.Transparent
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Broadsheet Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("player_minimize_btn")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Minimize player",
                        tint = palette.inkPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (playback.isPlaying) {
                            AnimatedInkEqualizer(isPlaying = true)
                        }
                        Text(
                            text = if (track?.isOnline == true) "NOW PLAYING · ONLINE" else "NOW PLAYING",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accent
                        )
                    }
                    Text(
                        text = "FOLIO ${playback.currentQueueIndex + 1} OF ${playback.queueTracks.size.coerceAtLeast(1)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary
                    )
                }

                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Player options",
                            tint = palette.inkPrimary
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        containerColor = palette.paperElevated
                    ) {
                        if (track != null) {
                            DropdownMenuItem(
                                text = { Text("Add to playlist", color = palette.inkPrimary) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = palette.accent) },
                                onClick = { showMoreMenu = false; onOpenAddToPlaylist(track) }
                            )
                            if (track.isOnline && onOpenRelated != null) {
                                DropdownMenuItem(
                                    text = { Text("Related tracks", color = palette.inkPrimary) },
                                    leadingIcon = { Icon(Icons.Rounded.Stream, null, tint = palette.accent) },
                                    onClick = { showMoreMenu = false; onOpenRelated() }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Edit metadata tags", color = palette.inkPrimary) },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = palette.accent) },
                                    onClick = { showMoreMenu = false; onOpenTagEditor(track) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Fullscreen lyrics & editor", color = palette.inkPrimary) },
                                leadingIcon = { Icon(Icons.Outlined.Lyrics, null, tint = palette.accent) },
                                onClick = { showMoreMenu = false; onOpenLyrics() }
                            )
                            DropdownMenuItem(
                                text = { Text(if (track.isOnline) "Stream details" else "Audio & file details", color = palette.inkPrimary) },
                                leadingIcon = { Icon(Icons.Outlined.Info, null, tint = palette.accent) },
                                onClick = { showMoreMenu = false; onOpenDetails(track) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            PaperDivider(doubleRule = true)
            Spacer(Modifier.weight(0.42f))

            val playScale by animateFloatAsState(
                targetValue = if (playback.isPlaying || motion.isReduced) 1f else 0.965f,
                animationSpec = motion.springSpec,
                label = "art_play_scale"
            )
            val vinylTransition = rememberInfiniteTransition(label = "vinyl_spin")
            val vinylAngle by vinylTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
                label = "vinyl_angle"
            )

            // Layered Printed-Photo Artwork Frame with Gestures
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                // Underlying folio sheet for layered paper parallax
                PaperCard(
                    modifier = Modifier
                        .fillMaxSize(0.95f)
                        .graphicsLayer {
                            rotationZ = if (motion.isReduced) 0f else (-2.1f + (animatedDragX / 110f))
                        },
                    containerColor = palette.paperCream,
                    elevation = 4.dp,
                    cornerRadius = 8.dp
                ) {}

                AnimatedContent(
                    targetState = track,
                    contentKey = { it?.uriString },
                    transitionSpec = {
                        (slideInHorizontally(tween(motion.durationMs)) { it / 3 } +
                            scaleIn(tween(motion.durationMs), initialScale = 0.94f) +
                            fadeIn(tween(motion.durationMs))) togetherWith
                            (slideOutHorizontally(tween(motion.durationMs)) { -it / 3 } +
                                scaleOut(tween(motion.durationMs), targetScale = 0.94f) +
                                fadeOut(tween(motion.durationMs / 2)))
                    },
                    label = "now_playing_art",
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(animatedDragX.roundToInt(), 0) }
                        .graphicsLayer {
                            scaleX = playScale
                            scaleY = playScale
                            rotationZ = when {
                                motion.isReduced -> 0f
                                settings.artworkShape == ArtworkShape.VINYL_SLEEVE && playback.isPlaying ->
                                    (animatedDragX / 36f) + vinylAngle
                                else -> (animatedDragX / 36f)
                            }
                        }
                        .pointerInput(settings.swipeHorizontalTrackChange, settings.swipeUpAction, settings.swipeDownAction) {
                            detectDragGestures(
                                onDragStart = { dragX = 0f; dragY = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (settings.swipeHorizontalTrackChange) {
                                        dragX = (dragX + dragAmount.x).coerceIn(-220f, 220f)
                                    }
                                    dragY += dragAmount.y
                                },
                                onDragEnd = {
                                    if (abs(dragX) > abs(dragY) && settings.swipeHorizontalTrackChange) {
                                        if (dragX < -100f) onNext()
                                        else if (dragX > 100f) onPrevious()
                                    } else if (abs(dragY) > 95f) {
                                        if (dragY < 0f) executeVerticalAction(settings.swipeUpAction)
                                        else executeVerticalAction(settings.swipeDownAction)
                                    }
                                    dragX = 0f
                                    dragY = 0f
                                },
                                onDragCancel = { dragX = 0f; dragY = 0f }
                            )
                        }
                        .pointerInput(settings.artworkTapAction, showInlineLyricsOverlay) {
                            if (!showInlineLyricsOverlay) {
                                detectTapGestures(onTap = { executeArtworkTap() })
                            }
                        }
                ) { animatedTrack ->
                    Box(Modifier.fillMaxSize()) {
                        PrintedArtwork(
                            trackUri = animatedTrack?.uriString,
                            customCoverUri = animatedTrack?.customCoverUri,
                            folderPath = animatedTrack?.folderPath.orEmpty(),
                            preferFolderCover = settings.preferFolderCoverArt,
                            modifier = Modifier.fillMaxSize(),
                            mattePadding = 7.dp,
                            targetSizePx = 768
                        )

                        androidx.compose.animation.AnimatedVisibility(
                            visible = animatedTrack?.isOnline == true && (isResolving || isBuffering),
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                        ) {
                            PaperCard(
                                containerColor = palette.paperElevated.copy(alpha = 0.92f),
                                cornerRadius = 10.dp,
                                elevation = 4.dp,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.accent
                                    )
                                    Text(
                                        text = if (isResolving) "Resolving best stream…" else "Buffering stream…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.inkPrimary
                                    )
                                }
                            }
                        }

                        // Toggleable Modern Paper Lyrics Card/View (Word-by-word & Line-synced)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showInlineLyricsOverlay,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            PaperCard(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(7.dp)
                                    .testTag("now_playing_lyrics_panel"),
                                containerColor = palette.paperElevated.copy(alpha = 0.96f),
                                elevation = 6.dp,
                                cornerRadius = 10.dp,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val sourceText = when {
                                            lyrics.isLoading -> "SEARCHING LYRICS…"
                                            lyrics.isSynced -> "${lyrics.source.label.uppercase()} · WORD SYNC"
                                            lyrics.hasContent -> "${lyrics.source.label.uppercase()} · TEXT"
                                            else -> "LYRICS"
                                        }
                                        Text(
                                            text = sourceText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.accent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (onRetryLyrics != null) {
                                            IconButton(
                                                onClick = onRetryLyrics,
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .testTag("inline_lyrics_retry_btn")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Refresh,
                                                    contentDescription = "Refresh lyrics",
                                                    tint = palette.inkSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = onOpenLyrics,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("inline_lyrics_expand_btn")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Fullscreen,
                                                contentDescription = "Expand fullscreen lyrics",
                                                tint = palette.inkSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { showInlineLyricsOverlay = false },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("inline_lyrics_close_btn")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Show cover artwork",
                                                tint = palette.inkSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    PaperDivider()
                                    Spacer(Modifier.height(4.dp))
                                    PaperLyricsViewport(
                                        lyrics = lyrics,
                                        positionMs = playback.positionMs,
                                        syncOffsetMs = settings.lyricsSyncOffsetMs,
                                        fontSizeSp = settings.lyricsFontSizeSp,
                                        compact = true,
                                        onSeek = onSeek,
                                        onRetry = onRetryLyrics,
                                        onEdit = onOpenLyrics,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // Animated Track Headline & Favorite
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(
                    targetState = track,
                    contentKey = { it?.uriString },
                    transitionSpec = {
                        (slideInVertically(tween(motion.durationMs)) { it / 3 } + fadeIn(tween(motion.durationMs))) togetherWith
                            (slideOutVertically(tween(motion.durationMs / 2)) { -it / 3 } + fadeOut(tween(motion.durationMs / 2)))
                    },
                    label = "now_playing_headline",
                    modifier = Modifier.weight(1f)
                ) { animatedTrack ->
                    Column {
                        Text(
                            text = animatedTrack?.title ?: "Nothing Playing",
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = animatedTrack?.artist ?: "Select a track from your library",
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(enabled = animatedTrack != null) {
                                    animatedTrack?.artist?.let(onNavigateToArtist)
                                }
                            )
                            if (animatedTrack != null) {
                                Text("  ·  ", style = MaterialTheme.typography.bodyMedium, color = palette.inkTertiary)
                                Text(
                                    text = animatedTrack.album,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .clickable { onNavigateToAlbum(animatedTrack.album) }
                                )
                            }
                        }
                    }
                }
                if (track != null) {
                    IconButton(
                        onClick = { onToggleFavorite(track) },
                        modifier = Modifier.testTag("now_playing_favorite_btn")
                    ) {
                        Icon(
                            imageVector = if (track.isFavorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Toggle favorite",
                            tint = if (track.isFavorite) palette.accent else palette.inkSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Honest live audio quality & format ink stamp
            val audioBadgeText = buildString {
                if (track?.isOnline == true) {
                    append("ONLINE · ")
                    append(track.onlineProvider.ifBlank { track.formatLabel })
                    if (activeStreamLabel.isNotBlank()) {
                        append(" · $activeStreamLabel")
                    }
                } else {
                    append(track?.formatLabel ?: "AUDIO")
                    if (telemetry.sampleRateHz > 0) {
                        append(" · %.1f kHz".format(telemetry.sampleRateHz / 1000f))
                    } else if ((track?.sampleRateHz ?: 0) > 0) {
                        append(" · %.1f kHz".format((track?.sampleRateHz ?: 0) / 1000f))
                    }
                    if ((track?.bitrateKbps ?: 0) > 0) {
                        append(" · ${track?.bitrateKbps} kbps")
                    }
                    if (telemetry.encodingName.isNotBlank() && showAudioInfoBanner) {
                        append(" · ${telemetry.encodingName}")
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InkStampTag(
                    text = audioBadgeText,
                    accented = showAudioInfoBanner || track?.isOnline == true,
                    modifier = Modifier.clickable { showAudioInfoBanner = !showAudioInfoBanner }
                )
                if (track?.isOnline == true && onOpenRelated != null) {
                    InkStampTag(
                        text = "RELATED",
                        accented = false,
                        modifier = Modifier
                            .testTag("now_playing_related_chip")
                            .clickable(onClick = onOpenRelated)
                    )
                }
                if (!errorMessage.isNullOrBlank() && onRetryStream != null) {
                    InkStampTag(
                        text = "RETRY STREAM",
                        accented = true,
                        modifier = Modifier.clickable(onClick = onRetryStream)
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(12.dp))

            // Seek Slider & Mono Time Readout
            Slider(
                value = displayedProgress,
                onValueChange = { draggingSeekFraction = it },
                onValueChangeFinished = {
                    draggingSeekFraction?.let { frac ->
                        if (safeDuration > 0L) onSeek((frac * safeDuration).toLong())
                    }
                    draggingSeekFraction = null
                },
                enabled = safeDuration > 0L,
                colors = SliderDefaults.colors(
                    thumbColor = palette.accent,
                    activeTrackColor = palette.accent,
                    inactiveTrackColor = palette.paperPressed,
                    disabledThumbColor = palette.inkTertiary,
                    disabledActiveTrackColor = palette.inkTertiary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(displayedPosMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.inkSecondary
                )
                val rightTimeLabel = if (settings.showRemainingDuration && safeDuration > 0L) {
                    "-${formatDuration((safeDuration - displayedPosMs).coerceAtLeast(0L))}"
                } else {
                    formatDuration(safeDuration)
                }
                Text(
                    text = rightTimeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.inkSecondary,
                    modifier = Modifier.clickable(onClick = onToggleRemainingTime)
                )
            }

            Spacer(Modifier.height(14.dp))

            // Primary Tactile Transport Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playback.shuffleEnabled) palette.accent else palette.inkTertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous track",
                        tint = palette.inkPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                val playBtnRadius by animateDpAsState(
                    targetValue = if (playback.isPlaying) 20.dp else 37.dp,
                    animationSpec = tween(durationMillis = if (motion.isReduced) 0 else 240),
                    label = "play_btn_morph_radius"
                )
                PaperCard(
                    modifier = Modifier
                        .size(74.dp)
                        .testTag("now_playing_play_pause"),
                    containerColor = palette.accent,
                    cornerRadius = playBtnRadius,
                    elevation = 7.dp,
                    onClick = onTogglePlay
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isResolving && !playback.isPlaying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                strokeWidth = 3.dp,
                                color = palette.onAccent
                            )
                        } else {
                            AnimatedContent(
                                targetState = playback.isPlaying,
                                transitionSpec = {
                                    (scaleIn(tween(180)) + fadeIn(tween(150))) togetherWith
                                        (scaleOut(tween(150)) + fadeOut(tween(120)))
                                },
                                label = "play_pause_morph"
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = palette.onAccent,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next track",
                        tint = palette.inkPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        imageVector = if (playback.repeatMode == Player.REPEAT_MODE_ONE) {
                            Icons.Rounded.RepeatOne
                        } else {
                            Icons.Rounded.Repeat
                        },
                        contentDescription = "Repeat mode",
                        tint = if (playback.repeatMode == Player.REPEAT_MODE_OFF) palette.inkTertiary else palette.accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.weight(0.55f))

            // Secondary Feature Dock
            PaperDebossedWell(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockControlButton(
                        icon = Icons.Rounded.Speed,
                        label = "%.2gx".format(playback.playbackSpeed),
                        active = playback.playbackSpeed != 1.0f,
                        onClick = { showSpeedSheet = true }
                    )
                    DockControlButton(
                        icon = Icons.Rounded.Bedtime,
                        label = if (sleepTimer.isActive) formatDuration(sleepTimer.remainingMs) else "Timer",
                        active = sleepTimer.isActive,
                        onClick = { showSleepSheet = true }
                    )
                    DockControlButton(
                        icon = Icons.Outlined.Lyrics,
                        label = "Lyrics",
                        active = showInlineLyricsOverlay || lyrics.hasContent,
                        onClick = { showInlineLyricsOverlay = !showInlineLyricsOverlay },
                        modifier = Modifier.testTag("now_playing_lyrics_btn")
                    )
                    DockControlButton(
                        icon = Icons.Rounded.Equalizer,
                        label = "FX",
                        active = settings.eqEnabled,
                        onClick = onOpenAudioFx
                    )
                    DockControlButton(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        label = "Queue",
                        active = false,
                        onClick = onOpenQueue
                    )
                }
            }
        }
    }

    if (showSpeedSheet) {
        PaperModalSheet(onDismissRequest = { showSpeedSheet = false }) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text("Playback Speed", style = MaterialTheme.typography.headlineSmall, color = palette.inkPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "%.2fx".format(playback.playbackSpeed),
                    style = MaterialTheme.typography.headlineLarge,
                    color = palette.accent
                )
                Spacer(Modifier.height(10.dp))
                Slider(
                    value = playback.playbackSpeed,
                    onValueChange = { onChangeSpeed((it * 20f).roundToInt() / 20f) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { preset ->
                        PaperFilterChip(
                            label = "${preset}x",
                            selected = abs(playback.playbackSpeed - preset) < 0.02f,
                            onClick = { onChangeSpeed(preset) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }

    if (showSleepSheet) {
        var customMinutes by remember { mutableIntStateOf(settings.sleepTimerDefaultMinutes) }
        PaperModalSheet(onDismissRequest = { showSleepSheet = false }) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text("Sleep Timer", style = MaterialTheme.typography.headlineSmall, color = palette.inkPrimary)
                Spacer(Modifier.height(6.dp))
                if (sleepTimer.isActive) {
                    Text(
                        "Stopping gently in ${formatDuration(sleepTimer.remainingMs)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.accent
                    )
                    Spacer(Modifier.height(14.dp))
                    TactilePaperButton(
                        text = "Cancel Sleep Timer",
                        onClick = { onCancelSleepTimer(); showSleepSheet = false },
                        filled = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Fade out and pause playback automatically.", style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 45, 60).forEach { min ->
                            PaperFilterChip(
                                label = "${min}m",
                                selected = customMinutes == min,
                                onClick = { customMinutes = min },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = customMinutes.toFloat(),
                        onValueChange = { customMinutes = it.roundToInt().coerceIn(5, 120) },
                        valueRange = 5f..120f,
                        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                    )
                    TactilePaperButton(
                        text = "Start $customMinutes min Timer",
                        onClick = { onStartSleepTimer(customMinutes); showSleepSheet = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun DockControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) palette.accent else palette.inkSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) palette.accent else palette.inkTertiary,
            maxLines = 1
        )
    }
}
