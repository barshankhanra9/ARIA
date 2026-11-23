package app.nocturne.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nocturne.player.data.model.LrcLine
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.ui.PlaybackUiState
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.PaperAlertDialog
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDebossedWell
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.PaperModalSheet
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.formatDuration
import app.nocturne.player.ui.components.paperGrainBackground
import app.nocturne.player.ui.theme.AriaPaperTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun QueueManagementSheet(
    playback: PlaybackUiState,
    preferFolderCover: Boolean,
    onDismiss: () -> Unit,
    onJumpToIndex: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onShuffleQueue: () -> Unit,
    onSaveQueueAsPlaylist: (String) -> Unit
) {
    val palette = AriaPaperTheme.palette
    var showSaveDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("Evening Queue") }

    PaperModalSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            EditorialSectionHeader(
                eyebrow = "${playback.queueTracks.size} TRACKS",
                title = "Playback Queue",
                trailing = {
                    Row {
                        IconButton(onClick = onShuffleQueue) {
                            Icon(Icons.Rounded.Shuffle, "Shuffle queue", tint = palette.accent)
                        }
                        IconButton(onClick = { showSaveDialog = true }) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as playlist", tint = palette.accent)
                        }
                        IconButton(onClick = onClearQueue) {
                            Icon(Icons.Rounded.DeleteSweep, "Clear queue", tint = palette.danger)
                        }
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            PaperDivider()
            Spacer(Modifier.height(10.dp))

            if (playback.queueTracks.isEmpty()) {
                PaperEmptyState(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Queue is Empty",
                    subtitle = "Play any song, album, or playlist to populate the active paper ledger."
                )
            } else {
                var draggingIdx by remember { mutableIntStateOf(-1) }
                var dragOffsetY by remember { mutableFloatStateOf(0f) }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(playback.queueTracks, key = { idx, t -> "${t.uriString}_$idx" }) { index, track ->
                        val isCurrent = index == playback.currentQueueIndex
                        PaperCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("queue_item_$index")
                                .pointerInput(index, playback.queueTracks.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingIdx = index
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            val stepPx = 140f
                                            if (dragOffsetY > stepPx && draggingIdx in 0 until playback.queueTracks.lastIndex) {
                                                onMoveItem(draggingIdx, draggingIdx + 1)
                                                draggingIdx += 1
                                                dragOffsetY = 0f
                                            } else if (dragOffsetY < -stepPx && draggingIdx > 0) {
                                                onMoveItem(draggingIdx, draggingIdx - 1)
                                                draggingIdx -= 1
                                                dragOffsetY = 0f
                                            }
                                        },
                                        onDragEnd = { draggingIdx = -1; dragOffsetY = 0f },
                                        onDragCancel = { draggingIdx = -1; dragOffsetY = 0f }
                                    )
                                },
                            containerColor = if (isCurrent) palette.paperCream else palette.paperElevated,
                            selected = isCurrent || draggingIdx == index,
                            elevation = if (draggingIdx == index) 7.dp else 2.dp,
                            onClick = { onJumpToIndex(index) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    tint = palette.inkTertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                PrintedArtwork(
                                    trackUri = track.uriString,
                                    customCoverUri = track.customCoverUri,
                                    folderPath = track.folderPath,
                                    preferFolderCover = preferFolderCover,
                                    modifier = Modifier.size(42.dp),
                                    mattePadding = 2.dp,
                                    targetSizePx = 144
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isCurrent) palette.accent else palette.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (track.durationMs > 0L) {
                                    Text(
                                        text = formatDuration(track.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkTertiary
                                    )
                                }
                                IconButton(onClick = { onRemoveItem(index) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove from queue",
                                        tint = palette.inkTertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        PaperAlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = "Save Queue as Playlist",
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.divider
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TactilePaperButton(
                    text = "Save",
                    onClick = {
                        onSaveQueueAsPlaylist(playlistName)
                        showSaveDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel", color = palette.inkSecondary) }
            }
        )
    }
}

@Composable
fun LyricsReaderDialog(
    track: Track,
    lyrics: LyricsData,
    positionMs: Long,
    settings: UserSettings,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit,
    onUpdateFontSize: (Float) -> Unit,
    onUpdateSyncOffset: (Long) -> Unit,
    onSaveLyrics: (String) -> Unit,
    onRetry: (() -> Unit)? = null
) {
    BackHandler(onBack = onDismiss)
    val palette = AriaPaperTheme.palette
    var fullScreenMode by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var draftLyrics by remember(lyrics.rawText) { mutableStateOf(lyrics.rawText) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .paperGrainBackground(palette.paperBackground)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("lyrics_dialog_close_btn")
                ) {
                    Icon(Icons.Rounded.Close, "Close lyrics", tint = palette.inkPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val cacheSuffix = if (lyrics.fromCache) " · Cached" else ""
                    Text(
                        text = "${track.artist} · ${lyrics.source.label}$cacheSuffix",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onRetry != null && !editMode) {
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag("lyrics_dialog_retry_btn")
                    ) {
                        Icon(Icons.Rounded.Refresh, "Refresh lyrics", tint = palette.inkSecondary)
                    }
                }
                IconButton(
                    onClick = { editMode = !editMode },
                    modifier = Modifier.testTag("lyrics_dialog_edit_btn")
                ) {
                    Icon(Icons.Rounded.Edit, "Edit lyrics", tint = if (editMode) palette.accent else palette.inkSecondary)
                }
                IconButton(
                    onClick = { fullScreenMode = !fullScreenMode },
                    modifier = Modifier.testTag("lyrics_dialog_fullscreen_btn")
                ) {
                    Icon(
                        imageVector = if (fullScreenMode) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = "Toggle full screen lyrics",
                        tint = palette.inkSecondary
                    )
                }
            }

            AnimatedVisibility(visible = !fullScreenMode && !editMode) {
                PaperDebossedWell(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    cornerRadius = 12.dp,
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Font Size: ${settings.lyricsFontSizeSp.toInt()}sp", style = MaterialTheme.typography.labelMedium, color = palette.inkSecondary)
                            if (lyrics.isSynced) {
                                Text(
                                    "Sync Offset: ${if (settings.lyricsSyncOffsetMs >= 0) "+" else ""}${settings.lyricsSyncOffsetMs / 1000.0}s",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.accent
                                )
                            }
                        }
                        Slider(
                            value = settings.lyricsFontSizeSp,
                            onValueChange = onUpdateFontSize,
                            valueRange = 14f..28f,
                            colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                        )
                        if (lyrics.isSynced) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PaperFilterChip("-0.5s", false, { onUpdateSyncOffset(settings.lyricsSyncOffsetMs - 500L) }, Modifier.weight(1f))
                                PaperFilterChip("-0.1s", false, { onUpdateSyncOffset(settings.lyricsSyncOffsetMs - 100L) }, Modifier.weight(1f))
                                PaperFilterChip("Reset", settings.lyricsSyncOffsetMs == 0L, { onUpdateSyncOffset(0L) }, Modifier.weight(1f))
                                PaperFilterChip("+0.1s", false, { onUpdateSyncOffset(settings.lyricsSyncOffsetMs + 100L) }, Modifier.weight(1f))
                                PaperFilterChip("+0.5s", false, { onUpdateSyncOffset(settings.lyricsSyncOffsetMs + 500L) }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (editMode) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Supports plain text, [mm:ss.xx] line tags, or <mm:ss.xx> enhanced word LRC",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftLyrics,
                        onValueChange = { draftLyrics = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.divider
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TactilePaperButton(
                            text = "Cancel",
                            filled = false,
                            onClick = { editMode = false },
                            modifier = Modifier.weight(1f)
                        )
                        TactilePaperButton(
                            text = "Save Lyrics",
                            onClick = {
                                onSaveLyrics(draftLyrics)
                                editMode = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                PaperLyricsViewport(
                    lyrics = lyrics,
                    positionMs = positionMs,
                    syncOffsetMs = settings.lyricsSyncOffsetMs,
                    fontSizeSp = settings.lyricsFontSizeSp,
                    compact = false,
                    onSeek = onSeek,
                    onRetry = onRetry,
                    onEdit = { editMode = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun PaperLyricsViewport(
    lyrics: LyricsData,
    positionMs: Long,
    syncOffsetMs: Long,
    fontSizeSp: Float,
    compact: Boolean = false,
    onSeek: (Long) -> Unit,
    onRetry: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var manualScrolling by remember { mutableStateOf(false) }

    val adjustedPos = (positionMs + syncOffsetMs).coerceAtLeast(0L)
    val activeIdx = remember(lyrics.syncedLines, adjustedPos) {
        if (lyrics.syncedLines.isEmpty()) -1
        else lyrics.syncedLines.indexOfLast { it.timeMs <= adjustedPos }.coerceAtLeast(0)
    }

    LaunchedEffect(activeIdx, manualScrolling, lyrics.syncedLines) {
        if (!manualScrolling && activeIdx >= 0 && lyrics.isSynced) {
            val contextOffset = if (compact) 1 else 2
            listState.animateScrollToItem((activeIdx - contextOffset).coerceAtLeast(0))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            lyrics.isLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(if (compact) 26.dp else 34.dp),
                        strokeWidth = 2.5.dp,
                        color = palette.accent
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Loading lyrics…",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.inkPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Checking local tags, cache, and LRCLIB",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            lyrics.isInstrumental -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PaperEmptyState(
                        icon = Icons.Rounded.MusicNote,
                        title = "Instrumental Track",
                        subtitle = "Marked as instrumental with no vocal lines.",
                        actionLabel = if (onEdit != null) "Add Custom Notes" else null,
                        onAction = onEdit
                    )
                }
            }

            !lyrics.hasContent -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lyrics,
                            contentDescription = "No lyrics",
                            tint = palette.inkTertiary,
                            modifier = Modifier.size(if (compact) 32.dp else 44.dp)
                        )
                        Text(
                            text = "No lyrics found",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "No local .lrc, embedded tags, or LRCLIB match was found for this track.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onRetry != null) {
                                TactilePaperButton(
                                    text = "Retry",
                                    icon = Icons.Rounded.Refresh,
                                    filled = false,
                                    onClick = onRetry,
                                    modifier = Modifier.testTag("lyrics_retry_btn")
                                )
                            }
                            if (onEdit != null) {
                                TactilePaperButton(
                                    text = "Paste Lyrics",
                                    icon = Icons.Rounded.Edit,
                                    onClick = onEdit,
                                    modifier = Modifier.testTag("lyrics_paste_btn")
                                )
                            }
                        }
                    }
                }
            }

            lyrics.isSynced -> {
                val effectiveFontSize = if (compact) fontSizeSp.coerceIn(14f, 20f) else fontSizeSp
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(if (compact) "inline_synced_lyrics_list" else "synced_lyrics_list"),
                    contentPadding = PaddingValues(vertical = if (compact) 12.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)
                ) {
                    itemsIndexed(lyrics.syncedLines) { idx, line ->
                        val isActive = idx == activeIdx
                        WordSyncedLyricLine(
                            line = line,
                            isActive = isActive,
                            adjustedPosMs = adjustedPos,
                            fontSizeSp = effectiveFontSize,
                            textAlign = if (compact) TextAlign.Center else TextAlign.Start,
                            onClick = {
                                manualScrolling = false
                                onSeek((line.timeMs - syncOffsetMs).coerceAtLeast(0L))
                            }
                        )
                    }
                }

                if (listState.isScrollInProgress) {
                    manualScrolling = true
                }

                if (manualScrolling) {
                    TactilePaperButton(
                        text = "Sync",
                        icon = Icons.Rounded.Sync,
                        onClick = {
                            manualScrolling = false
                            if (activeIdx >= 0) {
                                val contextOffset = if (compact) 1 else 2
                                scope.launch { listState.animateScrollToItem((activeIdx - contextOffset).coerceAtLeast(0)) }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .testTag("lyrics_sync_btn")
                    )
                }
            }

            else -> {
                val effectiveFontSize = if (compact) fontSizeSp.coerceIn(14f, 19f) else fontSizeSp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 10.dp)
                        .testTag("plain_lyrics_scroll")
                ) {
                    Text(
                        text = lyrics.rawText,
                        fontSize = effectiveFontSize.sp,
                        lineHeight = (effectiveFontSize * 1.45f).sp,
                        color = palette.inkPrimary,
                        textAlign = if (compact) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun WordSyncedLyricLine(
    line: LrcLine,
    isActive: Boolean,
    adjustedPosMs: Long,
    fontSizeSp: Float,
    textAlign: TextAlign = TextAlign.Start,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    val annotated: AnnotatedString = remember(
        line,
        isActive,
        if (isActive) adjustedPosMs / 45L else -1L,
        palette.accent,
        palette.inkPrimary,
        palette.inkSecondary
    ) {
        if (!isActive) {
            buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = palette.inkSecondary.copy(alpha = 0.65f),
                        fontWeight = FontWeight.Normal
                    )
                ) {
                    append(line.text)
                }
            }
        } else if (line.words.isEmpty()) {
            buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = palette.accent,
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(line.text)
                }
            }
        } else {
            buildAnnotatedString {
                line.words.forEachIndexed { wIdx, word ->
                    if (wIdx > 0) append(" ")
                    when {
                        adjustedPosMs >= word.endTimeMs -> {
                            withStyle(
                                SpanStyle(
                                    color = palette.accent,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(word.text)
                            }
                        }

                        adjustedPosMs >= word.startTimeMs -> {
                            val spanDuration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L).toFloat()
                            val fraction = ((adjustedPosMs - word.startTimeMs).toFloat() / spanDuration).coerceIn(0f, 1f)
                            val splitChars = (word.text.length * fraction).roundToInt().coerceIn(0, word.text.length)
                            val sungPart = word.text.substring(0, splitChars)
                            val activePart = word.text.substring(splitChars)
                            if (sungPart.isNotEmpty()) {
                                withStyle(
                                    SpanStyle(
                                        color = palette.accent,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append(sungPart)
                                }
                            }
                            if (activePart.isNotEmpty()) {
                                val blended = lerp(
                                    palette.inkPrimary.copy(alpha = 0.72f),
                                    palette.accent,
                                    (fraction * 0.65f).coerceIn(0f, 1f)
                                )
                                withStyle(
                                    SpanStyle(
                                        color = blended,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append(activePart)
                                }
                            }
                        }

                        else -> {
                            withStyle(
                                SpanStyle(
                                    color = palette.inkPrimary.copy(alpha = 0.56f),
                                    fontWeight = FontWeight.SemiBold
                                )
                            ) {
                                append(word.text)
                            }
                        }
                    }
                }
            }
        }
    }

    val containerBg = if (isActive) palette.paperCream.copy(alpha = 0.72f) else androidx.compose.ui.graphics.Color.Transparent
    Text(
        text = annotated,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 1.38f).sp,
        textAlign = textAlign,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

