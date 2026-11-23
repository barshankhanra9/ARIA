package app.nocturne.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.Playlist
import app.nocturne.player.data.model.Track
import app.nocturne.player.ui.theme.AriaPaperTheme

@Composable
fun TrackListRow(
    track: Track,
    isCurrent: Boolean,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    preferFolderCover: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("track_row_${track.id}"),
        containerColor = if (isCurrent) palette.paperCream else palette.paperElevated,
        elevation = if (isCurrent) 5.dp else 2.dp,
        cornerRadius = 13.dp,
        selected = isSelected || isCurrent,
        onClick = onClick,
        onLongClick = onLongClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isMultiSelectMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) palette.accent else palette.inkTertiary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
            }

            Box(contentAlignment = Alignment.Center) {
                PrintedArtwork(
                    trackUri = track.uriString,
                    customCoverUri = track.customCoverUri,
                    folderPath = track.folderPath,
                    preferFolderCover = preferFolderCover,
                    modifier = Modifier.size(52.dp),
                    mattePadding = 3.dp,
                    targetSizePx = 192
                )
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(4.dp))
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
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${track.artist} · ${track.album}",
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
                    InkStampTag(text = track.formatLabel, accented = isCurrent)
                    if (track.durationMs > 0L) {
                        Text(
                            text = formatDuration(track.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary
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

            if (!isMultiSelectMode) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Toggle favorite",
                        tint = if (track.isFavorite) palette.accent else palette.inkTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onMoreClick) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Track options",
                        tint = palette.inkSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TrackGridCard(
    track: Track,
    isCurrent: Boolean,
    isSelected: Boolean,
    preferFolderCover: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = if (isCurrent) palette.paperCream else palette.paperElevated,
        elevation = if (isCurrent) 5.dp else 3.dp,
        cornerRadius = 14.dp,
        selected = isSelected || isCurrent,
        onClick = onClick,
        onLongClick = onLongClick,
        contentPadding = PaddingValues(10.dp)
    ) {
        Box {
            PrintedArtwork(
                trackUri = track.uriString,
                customCoverUri = track.customCoverUri,
                folderPath = track.folderPath,
                preferFolderCover = preferFolderCover,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                mattePadding = 4.dp,
                targetSizePx = 320
            )
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.paperElevated.copy(alpha = 0.85f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Track options",
                    tint = palette.inkPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isCurrent) palette.accent else palette.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(track.formatLabel, style = MaterialTheme.typography.labelSmall, color = palette.accent)
            if (track.durationMs > 0L) {
                Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelSmall, color = palette.inkTertiary)
            }
        }
    }
}

@Composable
fun TrackContextMenuSheet(
    track: Track,
    preferFolderCover: Boolean,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onGoToAlbum: () -> Unit,
    onGoToArtist: () -> Unit,
    onEditTags: () -> Unit,
    onViewLyrics: () -> Unit,
    onViewDetails: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperModalSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrintedArtwork(
                    trackUri = track.uriString,
                    customCoverUri = track.customCoverUri,
                    folderPath = track.folderPath,
                    preferFolderCover = preferFolderCover,
                    modifier = Modifier.size(56.dp),
                    mattePadding = 3.dp
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track.artist} · ${track.album}",
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

            SheetActionItem(Icons.Rounded.PlayArrow, "Play now") { onDismiss(); onPlayNow() }
            SheetActionItem(Icons.Rounded.PlaylistPlay, "Play next") { onDismiss(); onPlayNext() }
            SheetActionItem(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { onDismiss(); onAddToQueue() }
            SheetActionItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { onDismiss(); onAddToPlaylist() }
            SheetActionItem(
                if (track.isFavorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                if (track.isFavorite) "Remove from favorites" else "Save to favorites"
            ) { onDismiss(); onToggleFavorite() }
            HorizontalDivider(color = palette.divider.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 4.dp))
            SheetActionItem(Icons.Rounded.Album, "Go to album") { onDismiss(); onGoToAlbum() }
            SheetActionItem(Icons.Rounded.Person, "Go to artist") { onDismiss(); onGoToArtist() }
            SheetActionItem(Icons.Outlined.Lyrics, "Lyrics") { onDismiss(); onViewLyrics() }
            SheetActionItem(Icons.Rounded.Edit, "Edit metadata tags") { onDismiss(); onEditTags() }
            SheetActionItem(Icons.Outlined.Info, "File & audio details") { onDismiss(); onViewDetails() }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SheetActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreateAndAdd: (String) -> Unit
) {
    val palette = AriaPaperTheme.palette
    var newName by remember { mutableStateOf("") }

    PaperAlertDialog(
        onDismissRequest = onDismiss,
        title = "Add to Playlist",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New playlist title") },
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
                        items(playlists) { pl ->
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
                                    Text("${pl.trackUris.size} tracks", style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary)
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
fun TrackDetailsDialog(
    track: Track,
    onDismiss: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperAlertDialog(
        onDismissRequest = onDismiss,
        title = "Audio & File Details",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Title", track.title)
                DetailRow("Artist", track.artist)
                DetailRow("Album", track.album)
                if (track.albumArtist.isNotBlank()) DetailRow("Album Artist", track.albumArtist)
                DetailRow("Genre", track.genre)
                if (track.year > 0) DetailRow("Year", track.year.toString())
                if (track.trackNumber > 0) DetailRow("Track #", track.trackNumber.toString())
                if (track.composer.isNotBlank()) DetailRow("Composer", track.composer)
                PaperDivider()
                DetailRow("Format", track.formatLabel)
                if (track.durationMs > 0L) DetailRow("Duration", formatDuration(track.durationMs))
                if (track.bitrateKbps > 0) DetailRow("Bitrate", "${track.bitrateKbps} kbps")
                if (track.sampleRateHz > 0) DetailRow("Sample Rate", "${track.sampleRateHz} Hz")
                if (track.sizeBytes > 0L) DetailRow("File Size", formatFileSize(track.sizeBytes))
                DetailRow("Play Count", "${track.playCount} plays")
                if (track.filePath.isNotBlank()) DetailRow("Location", track.filePath)
                DetailRow("URI", track.uriString)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = palette.accent) }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    val palette = AriaPaperTheme.palette
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = palette.accent)
        Text(value, style = MaterialTheme.typography.bodySmall, color = palette.inkPrimary)
    }
}
