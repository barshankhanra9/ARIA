package app.nocturne.player.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Image
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.Playlist
import app.nocturne.player.data.model.SmartPlaylistType
import app.nocturne.player.data.model.Track
import app.nocturne.player.ui.LibraryDerivedState
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.PaperAlertDialog
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PlaylistArtworkCollage
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.theme.AriaPaperTheme

sealed interface PlaylistViewTarget {
    data class Smart(val type: SmartPlaylistType) : PlaylistViewTarget
    data class Custom(val playlistId: Long) : PlaylistViewTarget
}

@Composable
fun PlaylistsScreen(
    library: LibraryDerivedState,
    playlists: List<Playlist>,
    selectedTarget: PlaylistViewTarget?,
    preferFolderCover: Boolean,
    onSelectTarget: (PlaylistViewTarget?) -> Unit,
    onResolveSmart: (SmartPlaylistType) -> List<Track>,
    onPlayTracks: (List<Track>, Int, Boolean) -> Unit,
    onCreatePlaylist: (String, String) -> Unit,
    onRenamePlaylist: (Long, String, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onUpdatePlaylistCover: (Long, String?) -> Unit,
    onRemoveTrackFromPlaylist: (Long, Int) -> Unit,
    onReorderPlaylistTracks: (Long, Int, Int) -> Unit,
    onExportPlaylistM3u8: (Playlist, Uri) -> Unit,
    onImportPlaylistM3u8: (Uri) -> Unit
) {
    val context = LocalContext.current
    val palette = AriaPaperTheme.palette
    val trackMap = remember(library.allTracks) { library.allTracks.associateBy { it.uriString } }

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<Playlist?>(null) }
    var pendingExportPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var pendingCoverPlaylistId by remember { mutableStateOf<Long?>(null) }

    val importM3uLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportPlaylistM3u8(uri) }

    val exportM3uLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val pl = pendingExportPlaylist
        if (uri != null && pl != null) {
            onExportPlaylistM3u8(pl, uri)
        }
        pendingExportPlaylist = null
    }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val pid = pendingCoverPlaylistId
        if (uri != null && pid != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onUpdatePlaylistCover(pid, uri.toString())
        }
        pendingCoverPlaylistId = null
    }

    if (selectedTarget != null) {
        BackHandler { onSelectTarget(null) }
        when (selectedTarget) {
            is PlaylistViewTarget.Smart -> {
                val tracks = onResolveSmart(selectedTarget.type)
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onSelectTarget(null) }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.inkPrimary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("SMART PLAYLIST", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            Text(selectedTarget.type.title, style = MaterialTheme.typography.headlineMedium, color = palette.inkPrimary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (tracks.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TactilePaperButton(text = "Play All", icon = Icons.Rounded.PlayArrow, onClick = { onPlayTracks(tracks, 0, false) }, modifier = Modifier.weight(1f))
                            TactilePaperButton(text = "Shuffle", icon = Icons.Rounded.Shuffle, onClick = { onPlayTracks(tracks, tracks.indices.random(), true) }, modifier = Modifier.weight(1f), filled = false)
                        }
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                            itemsIndexed(tracks) { idx, track ->
                                PaperCard(
                                    containerColor = palette.paperElevated,
                                    elevation = 2.dp,
                                    onClick = { onPlayTracks(tracks, idx, false) },
                                    contentPadding = PaddingValues(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        PrintedArtwork(track.uriString, track.customCoverUri, track.folderPath, Modifier.size(46.dp), mattePadding = 2.dp, targetSizePx = 160)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(track.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        PaperEmptyState(Icons.AutoMirrored.Rounded.PlaylistAdd, "No Tracks Yet", selectedTarget.type.subtitle)
                    }
                }
            }

            is PlaylistViewTarget.Custom -> {
                val playlist = playlists.firstOrNull { it.id == selectedTarget.playlistId }
                if (playlist == null) {
                    onSelectTarget(null)
                } else {
                    val tracks = playlist.trackUris.mapNotNull { trackMap[it] }
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onSelectTarget(null) }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.inkPrimary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("CUSTOM PLAYLIST", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                                Text(playlist.name, style = MaterialTheme.typography.headlineMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = {
                                pendingCoverPlaylistId = playlist.id
                                coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) {
                                Icon(Icons.Rounded.Image, "Change artwork", tint = palette.inkSecondary)
                            }
                            IconButton(onClick = { renameTarget = playlist }) {
                                Icon(Icons.Rounded.Edit, "Rename playlist", tint = palette.inkSecondary)
                            }
                            IconButton(onClick = {
                                pendingExportPlaylist = playlist
                                exportM3uLauncher.launch("${playlist.name}.m3u8")
                            }) {
                                Icon(Icons.Rounded.FileUpload, "Export M3U8", tint = palette.accent)
                            }
                            IconButton(onClick = { deleteConfirmTarget = playlist }) {
                                Icon(Icons.Rounded.Delete, "Delete playlist", tint = palette.danger)
                            }
                        }

                        if (playlist.description.isNotBlank()) {
                            Text(playlist.description, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                        }

                        Spacer(Modifier.height(10.dp))
                        if (tracks.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TactilePaperButton(text = "Play (${tracks.size})", icon = Icons.Rounded.PlayArrow, onClick = { onPlayTracks(tracks, 0, false) }, modifier = Modifier.weight(1f))
                                TactilePaperButton(text = "Shuffle", icon = Icons.Rounded.Shuffle, onClick = { onPlayTracks(tracks, tracks.indices.random(), true) }, modifier = Modifier.weight(1f), filled = false)
                            }
                            Spacer(Modifier.height(12.dp))

                            var draggingIdx by remember { mutableIntStateOf(-1) }
                            var dragOffsetY by remember { mutableFloatStateOf(0f) }

                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                                itemsIndexed(tracks, key = { idx, t -> "${t.uriString}_$idx" }) { idx, track ->
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
                                            Icon(Icons.Rounded.DragHandle, "Reorder", tint = palette.inkTertiary, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            PrintedArtwork(track.uriString, track.customCoverUri, track.folderPath, Modifier.size(44.dp), mattePadding = 2.dp, targetSizePx = 160)
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(track.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            IconButton(onClick = { onRemoveTrackFromPlaylist(playlist.id, idx) }) {
                                                Icon(Icons.Rounded.Close, "Remove", tint = palette.inkTertiary, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            PaperEmptyState(
                                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                title = "Empty Playlist",
                                subtitle = "Use any track's options menu or multi-select in Library to add songs here."
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
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                EditorialSectionHeader(
                    eyebrow = "CURATED FOLIOS",
                    title = "Playlists",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(onClick = { importM3uLauncher.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "*/*")) }) {
                                Icon(Icons.Rounded.FileDownload, "Import M3U", tint = palette.inkSecondary)
                            }
                            TactilePaperButton(
                                text = "New",
                                icon = Icons.Rounded.Add,
                                onClick = { showCreateDialog = true },
                                modifier = Modifier.testTag("create_playlist_btn")
                            )
                        }
                    }
                )
            }

            item {
                Text("SMART COLLECTIONS", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(SmartPlaylistType.entries) { type ->
                        val tracks = onResolveSmart(type)
                        PaperCard(
                            modifier = Modifier.width(156.dp),
                            containerColor = palette.paperCream,
                            elevation = 3.dp,
                            onClick = { onSelectTarget(PlaylistViewTarget.Smart(type)) },
                            contentPadding = PaddingValues(10.dp)
                        ) {
                            PlaylistArtworkCollage(
                                tracks = tracks,
                                customCoverUri = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(type.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1)
                            Text("${tracks.size} tracks", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                        }
                    }
                }
            }

            item {
                PaperDivider()
                Spacer(Modifier.height(10.dp))
                Text("YOUR PLAYLISTS (${playlists.size})", style = MaterialTheme.typography.labelMedium, color = palette.accent)
            }

            if (playlists.isEmpty()) {
                item {
                    PaperEmptyState(
                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        title = "Create Your First Folio",
                        subtitle = "Assemble custom mixes, customize cover artwork, or import .m3u / .m3u8 playlists.",
                        actionLabel = "Create Playlist",
                        onAction = { showCreateDialog = true }
                    )
                }
            } else {
                items(playlists, key = { it.id }) { pl ->
                    val tracks = pl.trackUris.mapNotNull { trackMap[it] }
                    PaperCard(
                        containerColor = palette.paperElevated,
                        elevation = 3.dp,
                        onClick = { onSelectTarget(PlaylistViewTarget.Custom(pl.id)) },
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlaylistArtworkCollage(
                                tracks = tracks,
                                customCoverUri = pl.coverUri,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (pl.description.isNotBlank()) {
                                    Text(pl.description, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${tracks.size} tracks", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            }
                            if (tracks.isNotEmpty()) {
                                IconButton(onClick = { onPlayTracks(tracks, 0, false) }) {
                                    Icon(Icons.Rounded.PlayArrow, "Play playlist", tint = palette.accent)
                                }
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
            title = "New Playlist",
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
                        label = { Text("Journal note (optional)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.accent, unfocusedBorderColor = palette.divider),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TactilePaperButton("Create", onClick = {
                    if (name.isNotBlank()) {
                        onCreatePlaylist(name, desc)
                        showCreateDialog = false
                    }
                })
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
            title = "Rename Playlist",
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Playlist title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Journal note") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TactilePaperButton("Save", onClick = {
                    onRenamePlaylist(target.id, name, desc)
                    renameTarget = null
                })
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = palette.inkSecondary) }
            }
        )
    }

    deleteConfirmTarget?.let { target ->
        PaperAlertDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            title = "Delete \"${target.name}\"?",
            text = { Text("Your local audio files will not be affected.") },
            confirmButton = {
                TactilePaperButton("Delete", onClick = {
                    onDeletePlaylist(target.id)
                    deleteConfirmTarget = null
                    onSelectTarget(null)
                })
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmTarget = null }) { Text("Cancel", color = palette.inkSecondary) }
            }
        )
    }
}
