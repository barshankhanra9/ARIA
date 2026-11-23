package app.nocturne.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.Track
import app.nocturne.player.ui.theme.AriaPaperTheme

@Composable
fun MiniPlayerBar(
    visible: Boolean,
    track: Track?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    preferFolderCover: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    errorMessage: String? = null
) {
    val palette = AriaPaperTheme.palette
    val motion = AriaPaperTheme.motion
    AnimatedVisibility(
        visible = visible && track != null,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(tween(motion.durationMs)),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(tween(motion.durationMs / 2)),
        modifier = modifier
    ) {
        if (track != null) {
            var dragSum by remember { mutableFloatStateOf(0f) }
            val targetProgress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            val animatedProgress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(durationMillis = if (motion.isReduced) 0 else 220),
                label = "mini_progress"
            )

            PaperCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("mini_player_bar")
                    .pointerInput(track.uriString) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragSum = 0f },
                            onHorizontalDrag = { _, delta -> dragSum += delta },
                            onDragEnd = {
                                if (dragSum < -70f) onNext()
                                else if (dragSum > 70f) onPrevious()
                                dragSum = 0f
                            }
                        )
                    },
                containerColor = palette.paperElevated,
                elevation = 6.dp,
                cornerRadius = 10.dp,
                onClick = onExpand,
                contentPadding = PaddingValues(0.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PrintedArtwork(
                            trackUri = track.uriString,
                            customCoverUri = track.customCoverUri,
                            folderPath = track.folderPath,
                            preferFolderCover = preferFolderCover,
                            modifier = Modifier.size(46.dp),
                            mattePadding = 3.dp,
                            targetSizePx = 180
                        )
                        Spacer(Modifier.width(12.dp))
                        AnimatedContent(
                            targetState = track,
                            contentKey = { it.uriString },
                            transitionSpec = {
                                (slideInVertically(tween(motion.durationMs)) { it / 3 } + fadeIn(tween(motion.durationMs))) togetherWith
                                    (slideOutVertically(tween(motion.durationMs / 2)) { -it / 3 } + fadeOut(tween(motion.durationMs / 2)))
                            },
                            label = "mini_headline",
                            modifier = Modifier.weight(1f)
                        ) { animatedTrack ->
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isPlaying) {
                                        AnimatedInkEqualizer(isPlaying = true)
                                    }
                                    if (animatedTrack.isOnline) {
                                        InkStampTag(text = "ONLINE", accented = true)
                                    }
                                    Text(
                                        text = animatedTrack.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = palette.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = when {
                                        !errorMessage.isNullOrBlank() -> errorMessage
                                        isBuffering && animatedTrack.isOnline -> "${animatedTrack.artist} · Buffering stream…"
                                        animatedTrack.isOnline -> "${animatedTrack.artist} · ${animatedTrack.onlineProvider.ifBlank { animatedTrack.formatLabel }}"
                                        else -> "${animatedTrack.artist} · ${animatedTrack.formatLabel}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (!errorMessage.isNullOrBlank()) palette.danger else palette.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = onTogglePlay,
                            modifier = Modifier.testTag("mini_player_play_pause")
                        ) {
                            if (isBuffering && !isPlaying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.2.dp,
                                    color = palette.accent
                                )
                            } else {
                                AnimatedContent(
                                    targetState = isPlaying,
                                    transitionSpec = {
                                        (scaleIn(tween(170)) + fadeIn(tween(140))) togetherWith
                                            (scaleOut(tween(140)) + fadeOut(tween(110)))
                                    },
                                    label = "mini_play_morph"
                                ) { playing ->
                                    Icon(
                                        imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (playing) "Pause" else "Play",
                                        tint = palette.accent,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = onNext,
                            modifier = Modifier.testTag("mini_player_next")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Next track",
                                tint = palette.inkPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Debossed newsprint progress hairline
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .background(palette.paperPressed)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress)
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                                .background(palette.accent)
                        )
                    }
                }
            }
        }
    }
}
