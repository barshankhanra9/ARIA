package app.nocturne.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.OnlinePlaylist
import app.nocturne.player.data.model.OnlineSmartPlaylistType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.PaperAlertDialog
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.theme.AriaPaperTheme

sealed interface OnlinePlaylistViewTarget {
    data class Smart(val type: OnlineSmartPlaylistType) : OnlinePlaylistViewTarget
    data class Custom(val playlistId: Long) : OnlinePlaylistViewTarget
}

@Composable
fun OnlinePlaylistsScreen(
    playlists: List<OnlinePlaylist>,
    selectedTarget: OnlinePlaylistViewTarget?,
    onSelectTarget: (OnlinePlaylistViewTarget?) -> Unit,
    onResolveSmart: (OnlineSmartPlaylistType) -> List<OnlineTrack>,
    onPlayTracks: (List<OnlineTrack>, Int, Boolean) -> Unit,
    onCreatePlaylist: (String, String) -> Unit,
    onRenamePlaylist: (Long, String, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRemoveTrackFromPlaylist: (Long, Int) -> Unit,
    onReorderPlaylistTracks: (Long, Int, Int) -> Unit
) {
    val palette = AriaPaperTheme.palette
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<OnlinePlaylist?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<OnlinePlaylist?>(null) }

    if (selectedTarget != null) {
        BackHandler { onSelectTarget(null) }
        when (selectedTarget) {
            is OnlinePlaylistViewTarget.Smart -> {
                val tracks = onResolveSmart(selectedTarget.type)
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onSelectTarget(null) }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.inkPrimary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("ONLINE SMART COLLECTION", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            Text(selectedTarget.type.title, style = MaterialTheme.typography.headlineMedium, color = palette.inkPrimary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (tracks.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TactilePaperButton("Play All", icon = Icons.Rounded.PlayArrow, onClick = { onPlayTracks(tracks, 0, false) }, modifier = Modifier.weight(1f))
                            TactilePaperButton("Shuffle", icon = Icons.Rounded.Shuffle, onClick = { onPlayTracks(tracks, tracks.indices.random(), true) }, modifier = Modifier.weight(1f), filled = false)
                        }
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                            itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, track ->
                                PaperCard(
                                    containerColor = palette.paperElevated,
                                    elevation = 2.dp,
                                    onClick = { onPlayTracks(tracks, idx, false) },
                                    contentPadding = PaddingValues(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        PrintedArtwork(track.thumbnailUrl, track.thumbnailUrl, modifier = Modifier.size(46.dp), mattePadding = 2.dp, targetSizePx = 160)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(track.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${track.artist} · ${track.providerLabel}", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        PaperEmptyState(Icons.AutoMirrored.Rounded.PlaylistAdd, "No Online Tracks Yet", selectedTarget.type.subtitle)
                    }
                }
            }

            is OnlinePlaylistViewTarget.Custom -> {
                val playlist = playlists.firstOrNull { it.id == selectedTarget.playlistId }
                if (playlist == null) {
                    onSelectTarget(null)
                } else {
                    val tracks = playlist.tracks
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onSelectTarget(null) }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.inkPrimary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("ONLINE PLAYLIST", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                                Text(playlist.name, style = MaterialTheme.typography.headlineMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { renameTarget = playlist }) {
                                Icon(Icons.Rounded.Edit, "Rename online playlist", tint = palette.inkSecondary)
                            }
                            IconButton(onClick = { deleteConfirmTarget = playlist }) {
                                Icon(Icons.Rounded.Delete, "Delete online playlist", tint = palette.danger)
                            }
                        }
                        if (playlist.description.isNotBlank()) {
                            Text(playlist.description, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        if (tracks.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TactilePaperButton("Play (${tracks.size})", icon = Icons.Rounded.PlayArrow, onClick = { onPlayTracks(tracks, 0, false) }, modifier = Modifier.weight(1f))
                                TactilePaperButton("Shuffle", icon = Icons.Rounded.Shuffle, onClick = { onPlayTracks(tracks, tracks.indices.random(), true) }, modifier = Modifier.weight(1f), filled = false)
                            }
                            Spacer(Modifier.height(12.dp))

                            var draggingIdx by remember { mutableIntStateOf(-1) }
                            var dragOffsetY by remember { mutableFloatStateOf(0f) }

                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                                itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, track ->
                                    PaperCard(
                                        modifier = Modifier.pointerInput(idx, tracks.size) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { draggingIdx = idx; dragOffsetY = 0f },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffsetY += dragAmount.y
                                                    if (dragOffsetY > 130f && draggingIdx < tracks.lastIndex) {
                                                        onReorderPlaylistTracks(playlist.id, draggingIdx, draggingIdx + 1)
                                                        draggingIdx += 1
                                                        dragOffsetY = 0f
                                                    } else if (dragOffsetY < -130f && draggingIdx > 0) {
                                                        onReorderPlaylistTracks(playlist.id, draggingIdx, draggingIdx - 1)
                                                        draggingIdx -= 1
                                                        dragOffsetY = 0f
                                                    }
                                                },
                                                onDragEnd = { draggingIdx = -1; dragOffsetY = 0f },
                                                onDragCancel = { draggingIdx = -1; dragOffsetY = 0f }
                                            )
                                        },
                                        containerColor = palette.paperElevated,
                                        selected = draggingIdx == idx,
                                        elevation = if (draggingIdx == idx) 6.dp else 2.dp,
                                        onClick = { onPlayTracks(tracks, idx, false) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.DragHandle, "Reorder track", tint = palette.inkTertiary, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            PrintedArtwork(track.thumbnailUrl, track.thumbnailUrl, modifier = Modifier.size(44.dp), mattePadding = 2.dp, targetSizePx = 160)
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(track.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${track.artist} · ${track.providerLabel}", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            IconButton(onClick = { onRemoveTrackFromPlaylist(playlist.id, idx) }) {
                                                Icon(Icons.Rounded.Close, "Remove from online playlist", tint = palette.inkTertiary, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            PaperEmptyState(
                                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                title = "Empty Online Playlist",
                                subtitle = "Use the overflow menu on any online track to add streams to this folio."
                            )
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("online_playlists_screen_list"),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "online_pl_header") {
                EditorialSectionHeader(
                    eyebrow = "ONLINE FOLIOS",
                    title = "Playlists",
                    trailing = {
                        TactilePaperButton(
                            text = "New",
                            icon = Icons.Rounded.Add,
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.testTag("create_online_playlist_btn")
                        )
                    }
                )
            }

            item(key = "online_smart_collections") {
                Column {
                    Text("SMART COLLECTIONS", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(OnlineSmartPlaylistType.entries) { smart ->
                            val count = onResolveSmart(smart).size
                            PaperCard(
                                modifier = Modifier.width(172.dp),
                                containerColor = palette.paperCream,
                                elevation = 3.dp,
                                onClick = { onSelectTarget(OnlinePlaylistViewTarget.Smart(smart)) },
                                contentPadding = PaddingValues(14.dp)
                            ) {
                                Text(smart.title, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(smart.subtitle, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))
                                InkStampTag(text = "$count TRACKS", accented = true)
                            }
                        }
                    }
                }
            }

            item(key = "online_custom_header") {
                PaperDivider()
                Spacer(Modifier.height(8.dp))
                Text("CUSTOM ONLINE PLAYLISTS (${playlists.size})", style = MaterialTheme.typography.labelSmall, color = palette.accent)
            }

            if (playlists.isEmpty()) {
                item(key = "empty_online_playlists") {
                    PaperEmptyState(
                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        title = "No Online Playlists Yet",
                        subtitle = "Create playlists to organize your favorite online streams across configured providers.",
                        actionLabel = "Create Playlist",
                        onAction = { showCreateDialog = true }
                    )
                }
            } else {
                items(playlists, key = { it.id }) { pl ->
                    PaperCard(
                        containerColor = palette.paperElevated,
                        elevation = 3.dp,
                        onClick = { onSelectTarget(OnlinePlaylistViewTarget.Custom(pl.id)) },
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrintedArtwork(
                                trackUri = pl.coverUrl,
                                customCoverUri = pl.coverUrl,
                                modifier = Modifier
                                    .size(58.dp)
                                    .aspectRatio(1f),
                                mattePadding = 3.dp,
                                targetSizePx = 192
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (pl.description.isNotBlank()) {
                                    Text(pl.description, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.height(4.dp))
                                InkStampTag(text = "${pl.tracks.size} STREAMS", accented = false)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        PaperAlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = "New Online Playlist",
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Playlist title") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.accent, unfocusedBorderColor = palette.divider),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Description (optional)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.accent, unfocusedBorderColor = palette.divider),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TactilePaperButton(
                    text = "Create",
                    enabled = name.isNotBlank(),
                    onClick = {
                        onCreatePlaylist(name.trim(), desc.trim())
                        showCreateDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel", color = palette.inkSecondary) }
            }
        )
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        var desc by remember(target.id) { mutableStateOf(target.description) }
        PaperAlertDialog(
            onDismissRequest = { renameTarget = null },
            title = "Rename Online Playlist",
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Playlist title") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.accent, unfocusedBorderColor = palette.divider),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Description") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.accent, unfocusedBorderColor = palette.divider),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TactilePaperButton(
                    text = "Save",
                    enabled = name.isNotBlank(),
                    onClick = {
                        onRenamePlaylist(target.id, name.trim(), desc.trim())
                        renameTarget = null
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = palette.inkSecondary) }
            }
        )
    }

    deleteConfirmTarget?.let { target ->
        PaperAlertDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            title = "Delete Online Playlist?",
            text = { Text("Remove \"${target.name}\"? Your favorites and listening history remain intact.") },
            confirmButton = {
                TactilePaperButton(
                    text = "Delete",
                    onClick = {
                        onDeletePlaylist(target.id)
                        deleteConfirmTarget = null
                        onSelectTarget(null)
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmTarget = null }) { Text("Cancel", color = palette.inkSecondary) }
            }
        )
    }
}
