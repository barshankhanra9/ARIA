package app.nocturne.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.ArtworkShape
import app.nocturne.player.data.model.OnlinePlaylist
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.PlaybackMode
import app.nocturne.player.ui.theme.AriaPaperTheme

@Composable
fun PlaybackModeSwitcher(
    selectedMode: PlaybackMode,
    isOfflinePlaying: Boolean,
    isOnlinePlaying: Boolean,
    onSelectMode: (PlaybackMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    val motion = AriaPaperTheme.motion

    PaperDebossedWell(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        contentPadding = PaddingValues(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ModeSwitcherTab(
                label = "Offline Music",
                icon = Icons.Rounded.FolderOpen,
                selected = selectedMode == PlaybackMode.OFFLINE,
                isPlayingInBackground = isOfflinePlaying && selectedMode != PlaybackMode.OFFLINE,
                onClick = { onSelectMode(PlaybackMode.OFFLINE) },
                durationMs = motion.durationMs,
                modifier = Modifier
                    .weight(1f)
                    .testTag("mode_switch_offline")
            )
            ModeSwitcherTab(
                label = "Online Music",
                icon = Icons.Rounded.CloudQueue,
                selected = selectedMode == PlaybackMode.ONLINE,
                isPlayingInBackground = isOnlinePlaying && selectedMode != PlaybackMode.ONLINE,
                onClick = { onSelectMode(PlaybackMode.ONLINE) },
                durationMs = motion.durationMs,
                modifier = Modifier
                    .weight(1f)
                    .testTag("mode_switch_online")
            )
        }
    }
}

@Composable
private fun ModeSwitcherTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    isPlayingInBackground: Boolean,
    onClick: () -> Unit,
    durationMs: Int,
    modifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    val bgColor by animateColorAsState(
        targetValue = if (selected) palette.paperElevated else Color.Transparent,
        animationSpec = tween(durationMs),
        label = "mode_tab_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.inkSecondary,
        animationSpec = tween(durationMs),
        label = "mode_tab_fg"
    )
    val borderColor = if (selected) palette.accent.copy(alpha = 0.55f) else Color.Transparent

    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (selected && !palette.isAmoled) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) palette.inkPrimary else palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isPlayingInBackground) {
                Spacer(Modifier.width(6.dp))
                AnimatedInkEqualizer(isPlaying = true, color = palette.accent)
            }
        }
    }
}

@Composable
fun OnlineTrackListRow(
    track: OnlineTrack,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("online_track_row_${track.id}"),
        containerColor = if (isCurrent) palette.paperCream else palette.paperElevated,
        elevation = if (isCurrent) 5.dp else 2.dp,
        cornerRadius = 13.dp,
        selected = isCurrent,
        onClick = onClick,
        onLongClick = onMoreClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                PrintedArtwork(
                    trackUri = track.thumbnailUrl,
                    customCoverUri = track.thumbnailUrl,
                    artworkShape = ArtworkShape.SOFT_SQUARE,
                    modifier = Modifier.size(56.dp),
                    mattePadding = 2.dp,
                    targetSizePx = 500
                )
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.inkPrimary.copy(alpha = 0.52f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedInkEqualizer(isPlaying = true, color = palette.onAccent)
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrent) palette.accent else palette.inkPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(track.artist)
                        if (track.album.isNotBlank() && track.album != "Online Stream" && track.album != track.providerLabel) {
                            append(" · ${track.album}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InkStampTag(text = track.effectiveBadge(), accented = isCurrent)
                    if (track.durationMs > 0L) {
                        Text(
                            text = formatDuration(track.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary
                        )
                    } else if (track.isLive) {
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent
                        )
                    }
                    if (track.playCount > 0) {
                        Text(
                            text = "${track.playCount}×",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary
                        )
                    }
                }
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Toggle online favorite",
                    tint = if (track.isFavorite) palette.accent else palette.inkTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Online track options",
                    tint = palette.inkSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun OnlineTrackContextMenuSheet(
    track: OnlineTrack,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    onViewDetails: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperModalSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrintedArtwork(
                    trackUri = track.thumbnailUrl,
                    customCoverUri = track.thumbnailUrl,
                    modifier = Modifier.size(56.dp),
                    mattePadding = 3.dp
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        InkStampTag(text = "ONLINE", accented = true)
                        InkStampTag(text = track.effectiveBadge(), accented = false)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.inkPrimary,
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
            }
            Spacer(Modifier.height(14.dp))
            PaperDivider()
            Spacer(Modifier.height(8.dp))

            OnlineSheetActionItem(Icons.Rounded.PlayArrow, "Play now") { onDismiss(); onPlayNow() }
            OnlineSheetActionItem(Icons.Rounded.PlaylistPlay, "Play next") { onDismiss(); onPlayNext() }
            OnlineSheetActionItem(Icons.AutoMirrored.Rounded.QueueMusic, "Add to online queue") { onDismiss(); onAddToQueue() }
            OnlineSheetActionItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to online playlist") { onDismiss(); onAddToPlaylist() }
            OnlineSheetActionItem(
                if (track.isFavorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                if (track.isFavorite) "Remove from online favorites" else "Save to online favorites"
            ) { onDismiss(); onToggleFavorite() }
            if (onOpenArtist != null || onOpenAlbum != null) {
                HorizontalDivider(color = palette.divider.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 4.dp))
            }
            if (onOpenArtist != null) {
                OnlineSheetActionItem(Icons.Rounded.Person, "Open artist page") { onDismiss(); onOpenArtist() }
            }
            if (onOpenAlbum != null) {
                OnlineSheetActionItem(Icons.Rounded.Album, "Open album / playlist") { onDismiss(); onOpenAlbum() }
            }
            OnlineSheetActionItem(Icons.Outlined.Info, "Stream & provider details") { onDismiss(); onViewDetails() }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OnlineSheetActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = palette.inkPrimary)
    }
}

@Composable
fun AddToOnlinePlaylistDialog(
    playlists: List<OnlinePlaylist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreateAndAdd: (String) -> Unit
) {
    val palette = AriaPaperTheme.palette
    var newName by remember { mutableStateOf("") }

    PaperAlertDialog(
        onDismissRequest = onDismiss,
        title = "Add to Online Playlist",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New online playlist title") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.divider
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (newName.isNotBlank()) {
                    TactilePaperButton(
                        text = "Create & Add",
                        onClick = {
                            onCreateAndAdd(newName.trim())
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (playlists.isNotEmpty()) {
                    PaperDivider()
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(playlists, key = { it.id }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        onSelectPlaylist(pl.id)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = palette.accent)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pl.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary)
                                    Text("${pl.tracks.size} tracks", style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = palette.accent) }
        }
    )
}

@Composable
fun OnlineTrackDetailsDialog(
    track: OnlineTrack,
    activeStreamLabel: String = "",
    onDismiss: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperAlertDialog(
        onDismissRequest = onDismiss,
        title = "Online Stream Details",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OnlineDetailRow("Title", track.title)
                OnlineDetailRow("Artist", track.artist)
                if (track.album.isNotBlank()) OnlineDetailRow("Album / Source", track.album)
                OnlineDetailRow("Provider", track.providerLabel)
                if (activeStreamLabel.isNotBlank()) OnlineDetailRow("Active Stream", activeStreamLabel)
                if (track.durationMs > 0L) OnlineDetailRow("Duration", formatDuration(track.durationMs))
                OnlineDetailRow("Play Count", "${track.playCount} plays")
                OnlineDetailRow("Track ID", track.id)
                if (track.sourceUrl.isNotBlank()) OnlineDetailRow("Source URL", track.sourceUrl)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = palette.accent) }
        }
    )
}

@Composable
private fun OnlineDetailRow(label: String, value: String) {
    val palette = AriaPaperTheme.palette
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = palette.accent)
        Text(value, style = MaterialTheme.typography.bodySmall, color = palette.inkPrimary)
    }
}
