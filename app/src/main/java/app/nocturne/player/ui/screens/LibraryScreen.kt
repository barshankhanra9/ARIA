package app.nocturne.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.LibrarySection
import app.nocturne.player.data.model.Playlist
import app.nocturne.player.data.model.SortField
import app.nocturne.player.data.model.SortOrder
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.ui.LibraryDerivedState
import app.nocturne.player.ui.PlaybackUiState
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.PaperAlertDialog
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDebossedWell
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.PaperModalSheet
import app.nocturne.player.ui.components.PaperSkeletonList
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.TrackGridCard
import app.nocturne.player.ui.components.TrackListRow
import app.nocturne.player.ui.components.formatDuration
import app.nocturne.player.ui.components.formatFileSize
import app.nocturne.player.ui.theme.AriaPaperTheme
import kotlinx.coroutines.launch

sealed interface LibraryDrillTarget {
    data class AlbumDetail(val albumName: String) : LibraryDrillTarget
    data class ArtistDetail(val artistName: String, val isAlbumArtist: Boolean = false) : LibraryDrillTarget
    data class GenreDetail(val genreName: String) : LibraryDrillTarget
    data class FolderDetail(val folderPath: String) : LibraryDrillTarget
}

@Composable
fun LibraryScreen(
    section: LibrarySection,
    drillTarget: LibraryDrillTarget?,
    library: LibraryDerivedState,
    playback: PlaybackUiState,
    playlists: List<Playlist>,
    settings: UserSettings,
    selectedUris: Set<String>,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onSelectSection: (LibrarySection) -> Unit,
    onSetDrillTarget: (LibraryDrillTarget?) -> Unit,
    onSortTracks: (List<Track>, SortField, SortOrder) -> List<Track>,
    onUpdateSort: (LibrarySection, SortField, SortOrder) -> Unit,
    onToggleGridForSection: (LibrarySection) -> Unit,
    onPlayTracks: (List<Track>, Int, Boolean) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onToggleSelectTrack: (String) -> Unit,
    onSelectAll: (List<Track>) -> Unit,
    onClearSelection: () -> Unit,
    onBatchPlayNext: (List<Track>) -> Unit,
    onBatchAddToQueue: (List<Track>) -> Unit,
    onBatchAddToPlaylist: (List<Track>) -> Unit,
    onBatchFavorite: () -> Unit,
    onOpenContextMenu: (Track) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenQueueSheet: () -> Unit,
    onRescanLibrary: () -> Unit,
    onPickMusicFolder: () -> Unit,
    onPickAudioFile: () -> Unit,
    onToggleExcludeFolder: (String) -> Unit,
    downloadedOfflineTracks: List<Track> = emptyList(),
    onDeleteDownloadedTrack: ((Track) -> Unit)? = null
) {
    val palette = AriaPaperTheme.palette
    val scope = rememberCoroutineScope()
    val isMultiSelect = selectedUris.isNotEmpty()

    if (drillTarget != null) {
        BackHandler { onSetDrillTarget(null) }
    } else if (isMultiSelect) {
        BackHandler { onClearSelection() }
    }

    val sortField = settings.sectionSortFieldOverrides[section.name] ?: settings.defaultSortField
    val sortOrder = settings.sectionSortOrderOverrides[section.name] ?: settings.defaultSortOrder
    val isGrid = settings.sectionGridOverrides[section.name] ?: settings.defaultGridLayout

    var showSortSheet by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    val activeTrackList: List<Track> = remember(section, drillTarget, library, playback.queueTracks, downloadedOfflineTracks, sortField, sortOrder) {
        val base = when (drillTarget) {
            is LibraryDrillTarget.AlbumDetail ->
                library.albums.firstOrNull { it.name == drillTarget.albumName }?.tracks.orEmpty()
            is LibraryDrillTarget.ArtistDetail ->
                (if (drillTarget.isAlbumArtist) library.albumArtists else library.artists)
                    .firstOrNull { it.name == drillTarget.artistName }?.tracks.orEmpty()
            is LibraryDrillTarget.GenreDetail ->
                library.genres.firstOrNull { it.name == drillTarget.genreName }?.tracks.orEmpty()
            is LibraryDrillTarget.FolderDetail ->
                library.folders.firstOrNull { it.path == drillTarget.folderPath }?.tracks.orEmpty()
            null -> when (section) {
                LibrarySection.ALL_SONGS -> library.allTracks
                LibrarySection.RECENTLY_ADDED -> library.recentlyAdded
                LibrarySection.RECENTLY_PLAYED -> library.recentlyPlayed
                LibrarySection.MOST_PLAYED -> library.mostPlayed
                LibrarySection.FAVORITES -> library.favorites
                LibrarySection.DOWNLOADS -> downloadedOfflineTracks
                LibrarySection.QUEUE -> playback.queueTracks
                else -> emptyList()
            }
        }
        if (section == LibrarySection.QUEUE && drillTarget == null) base
        else onSortTracks(base, sortField, sortOrder)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp)
    ) {
        // Header / Multi-Select Toolbar
        if (isMultiSelect) {
            PaperDebossedWell(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                cornerRadius = 14.dp,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Rounded.Close, "Clear selection", tint = palette.inkPrimary)
                    }
                    Text(
                        text = "${selectedUris.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    val selectedTracks = activeTrackList.filter { it.uriString in selectedUris }
                    IconButton(onClick = { onSelectAll(activeTrackList) }) {
                        Icon(Icons.Rounded.DoneAll, "Select all", tint = palette.accent)
                    }
                    IconButton(onClick = { onBatchPlayNext(selectedTracks); onClearSelection() }) {
                        Icon(Icons.Rounded.PlayArrow, "Play next", tint = palette.accent)
                    }
                    IconButton(onClick = { onBatchAddToQueue(selectedTracks); onClearSelection() }) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", tint = palette.accent)
                    }
                    IconButton(onClick = { onBatchAddToPlaylist(selectedTracks) }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", tint = palette.accent)
                    }
                    IconButton(onClick = onBatchFavorite) {
                        Icon(Icons.Rounded.Favorite, "Favorite selected", tint = palette.accent)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (drillTarget != null) {
                    IconButton(onClick = { onSetDrillTarget(null) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.inkPrimary)
                    }
                }
                val titleText = when (drillTarget) {
                    is LibraryDrillTarget.AlbumDetail -> drillTarget.albumName
                    is LibraryDrillTarget.ArtistDetail -> drillTarget.artistName
                    is LibraryDrillTarget.GenreDetail -> drillTarget.genreName
                    is LibraryDrillTarget.FolderDetail -> drillTarget.folderPath.substringAfterLast('/')
                    null -> section.label
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (drillTarget != null) "COLLECTION DETAIL" else "MUSIC LIBRARY",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = { showSortSheet = true }, modifier = Modifier.testTag("library_sort_btn")) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, "Sort & filter", tint = palette.inkPrimary)
                }
                IconButton(onClick = { onToggleGridForSection(section) }, modifier = Modifier.testTag("library_layout_toggle_btn")) {
                    Icon(
                        imageVector = if (isGrid) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                        contentDescription = "Toggle list or grid",
                        tint = palette.inkPrimary
                    )
                }
                IconButton(onClick = onRescanLibrary) {
                    Icon(Icons.Rounded.Refresh, "Rescan library", tint = palette.accent)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Section Selector Chips when not drilled in
        if (drillTarget == null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(LibrarySection.entries) { item ->
                    PaperFilterChip(
                        label = item.label,
                        selected = section == item,
                        onClick = {
                            if (item == LibrarySection.QUEUE) onOpenQueueSheet()
                            onSelectSection(item)
                        },
                        modifier = Modifier.testTag("lib_tab_${item.name}")
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Action Bar for Track-based views or Folder Storage tools
        if (drillTarget != null || section in listOf(
                LibrarySection.ALL_SONGS,
                LibrarySection.RECENTLY_ADDED,
                LibrarySection.RECENTLY_PLAYED,
                LibrarySection.MOST_PLAYED,
                LibrarySection.FAVORITES,
                LibrarySection.DOWNLOADS,
                LibrarySection.QUEUE
            )
        ) {
            if (activeTrackList.isNotEmpty() && !isMultiSelect) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TactilePaperButton(
                        text = "Play (${activeTrackList.size})",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = { onPlayTracks(activeTrackList, 0, false) },
                        modifier = Modifier.weight(1f)
                    )
                    TactilePaperButton(
                        text = "Shuffle",
                        icon = Icons.Rounded.Shuffle,
                        filled = false,
                        onClick = { onPlayTracks(activeTrackList, activeTrackList.indices.random(), true) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onSelectAll(activeTrackList) }) {
                        Icon(Icons.Rounded.Checklist, "Multi-select", tint = palette.inkSecondary)
                    }
                }
            }
        } else if (section == LibrarySection.FOLDERS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TactilePaperButton(
                    text = "Add Folder",
                    icon = Icons.Rounded.CreateNewFolder,
                    onClick = onPickMusicFolder,
                    modifier = Modifier.weight(1f)
                )
                TactilePaperButton(
                    text = "Storage & Health",
                    icon = Icons.Rounded.Storage,
                    filled = false,
                    onClick = { showDiagnosticsDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (library.isScanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                color = palette.accent,
                trackColor = palette.paperPressed
            )
        }

        Spacer(Modifier.height(4.dp))

        // Main Content Body
        Box(modifier = Modifier.weight(1f)) {
            if (library.isScanning && library.allTracks.isEmpty()) {
                PaperSkeletonList(count = 6, modifier = Modifier.padding(horizontal = 18.dp))
            } else if (!hasAudioPermission && library.allTracks.isEmpty() && section != LibrarySection.QUEUE && section != LibrarySection.DOWNLOADS) {
                Box(Modifier.padding(18.dp)) {
                    PaperEmptyState(
                        icon = Icons.Rounded.LibraryMusic,
                        title = "Allow Audio Access",
                        subtitle = "ARIA needs audio permission to automatically scan all music and audio files on your device.",
                        actionLabel = "Scan Device Audio",
                        onAction = onRequestAudioPermission
                    )
                }
            } else if (drillTarget == null && section == LibrarySection.ALBUMS) {
                if (library.albums.isEmpty()) {
                    Box(Modifier.padding(18.dp)) {
                        PaperEmptyState(Icons.Rounded.LibraryMusic, "No Albums Indexed", "Add audio files or folders to browse by album.")
                    }
                } else if (isGrid) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(155.dp),
                        contentPadding = PaddingValues(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(library.albums) { _, album ->
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 3.dp,
                                onClick = { onSetDrillTarget(LibraryDrillTarget.AlbumDetail(album.name)) },
                                contentPadding = PaddingValues(10.dp)
                            ) {
                                PrintedArtwork(
                                    trackUri = album.representativeUri,
                                    folderPath = album.tracks.firstOrNull()?.folderPath.orEmpty(),
                                    preferFolderCover = settings.preferFolderCoverArt,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                    mattePadding = 4.dp
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(album.name, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${album.artist} · ${album.tracks.size} tracks", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(library.albums) { album ->
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 2.dp,
                                onClick = { onSetDrillTarget(LibraryDrillTarget.AlbumDetail(album.name)) },
                                contentPadding = PaddingValues(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PrintedArtwork(
                                        trackUri = album.representativeUri,
                                        folderPath = album.tracks.firstOrNull()?.folderPath.orEmpty(),
                                        preferFolderCover = settings.preferFolderCoverArt,
                                        modifier = Modifier.size(54.dp),
                                        mattePadding = 3.dp,
                                        targetSizePx = 192
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(album.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${album.artist} · ${album.tracks.size} tracks", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (drillTarget == null && (section == LibrarySection.ARTISTS || section == LibrarySection.ALBUM_ARTISTS)) {
                val list = if (section == LibrarySection.ALBUM_ARTISTS) library.albumArtists else library.artists
                if (list.isEmpty()) {
                    Box(Modifier.padding(18.dp)) {
                        PaperEmptyState(Icons.Rounded.LibraryMusic, "No Artists Indexed", "Add audio tracks with artist metadata.")
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(list) { artist ->
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 2.dp,
                                onClick = {
                                    onSetDrillTarget(
                                        LibraryDrillTarget.ArtistDetail(
                                            artist.name,
                                            isAlbumArtist = section == LibrarySection.ALBUM_ARTISTS
                                        )
                                    )
                                },
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PrintedArtwork(
                                        trackUri = artist.representativeUri,
                                        preferFolderCover = settings.preferFolderCoverArt,
                                        modifier = Modifier.size(48.dp),
                                        mattePadding = 2.dp,
                                        targetSizePx = 160
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(artist.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${artist.albumCount} albums · ${artist.tracks.size} tracks", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (drillTarget == null && section == LibrarySection.GENRES) {
                if (library.genres.isEmpty()) {
                    Box(Modifier.padding(18.dp)) {
                        PaperEmptyState(Icons.Rounded.LibraryMusic, "No Genres Yet", "Tracks scanned with genre tags appear here.")
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(library.genres) { genre ->
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 2.dp,
                                onClick = { onSetDrillTarget(LibraryDrillTarget.GenreDetail(genre.name)) },
                                contentPadding = PaddingValues(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(genre.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, modifier = Modifier.weight(1f))
                                    Text("${genre.tracks.size} tracks", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                                }
                            }
                        }
                    }
                }
            } else if (drillTarget == null && section == LibrarySection.PLAYLISTS) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (playlists.isEmpty()) {
                        item {
                            PaperEmptyState(
                                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                title = "No Custom Playlists",
                                subtitle = "Visit the Playlists tab to create or import M3U playlists."
                            )
                        }
                    } else {
                        items(playlists) { pl ->
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 2.dp,
                                onClick = { onOpenPlaylist(pl) },
                                contentPadding = PaddingValues(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(pl.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary)
                                        Text("${pl.trackUris.size} tracks", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                                    }
                                    Icon(Icons.Rounded.PlayArrow, null, tint = palette.accent)
                                }
                            }
                        }
                    }
                }
            } else if (drillTarget == null && section == LibrarySection.FOLDERS) {
                if (library.folders.isEmpty()) {
                    Box(Modifier.padding(18.dp)) {
                        PaperEmptyState(
                            icon = Icons.Rounded.Folder,
                            title = "No Music Folders Found",
                            subtitle = "Add a folder using Android Storage Access Framework or open local audio files.",
                            actionLabel = "Add Music Folder",
                            onAction = onPickMusicFolder
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(library.folders) { folder ->
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 2.dp,
                                onClick = { onSetDrillTarget(LibraryDrillTarget.FolderDetail(folder.path)) },
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Folder, null, tint = palette.accent, modifier = Modifier.size(26.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(folder.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${folder.tracks.size} tracks · ${folder.path}", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (!folder.isSafTree && folder.path.isNotBlank()) {
                                        IconButton(onClick = { onToggleExcludeFolder(folder.path) }) {
                                            Icon(Icons.Rounded.FolderOff, "Exclude folder", tint = palette.inkTertiary, modifier = Modifier.size(19.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Track list or grid (with Alphabetical Fast Scroller when sorted alphabetically)
                if (activeTrackList.isEmpty()) {
                    Box(Modifier.padding(18.dp)) {
                        if (section == LibrarySection.DOWNLOADS) {
                            PaperEmptyState(
                                icon = Icons.Rounded.DownloadDone,
                                title = "No Offline Downloads",
                                subtitle = "Download any song or playlist in Online Music to play it completely offline here."
                            )
                        } else {
                            PaperEmptyState(
                                icon = Icons.Rounded.LibraryMusic,
                                title = "Nothing in ${section.label}",
                                subtitle = "Rescan your device audio library, add a music folder, or open an audio file.",
                                actionLabel = if (section == LibrarySection.ALL_SONGS) "Rescan Device" else "Open Audio File",
                                onAction = if (section == LibrarySection.ALL_SONGS) onRescanLibrary else onPickAudioFile
                            )
                        }
                    }
                } else if (isGrid) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(155.dp),
                        contentPadding = PaddingValues(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(activeTrackList, key = { idx, t -> "${t.uriString}_$idx" }) { idx, track ->
                            TrackGridCard(
                                track = track,
                                isCurrent = track.uriString == playback.currentUri,
                                isSelected = track.uriString in selectedUris,
                                preferFolderCover = settings.preferFolderCoverArt,
                                onClick = {
                                    if (isMultiSelect) onToggleSelectTrack(track.uriString)
                                    else onPlayTracks(activeTrackList, idx, false)
                                },
                                onLongClick = { onToggleSelectTrack(track.uriString) },
                                onMoreClick = { onOpenContextMenu(track) }
                            )
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    val showAlphaRail = activeTrackList.size > 12 &&
                        (sortField == SortField.TITLE || sortField == SortField.ARTIST || sortField == SortField.ALBUM)
                    val alphabet = remember { ('A'..'Z').toList() }

                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(
                                start = 18.dp,
                                end = if (showAlphaRail) 34.dp else 18.dp,
                                top = 6.dp,
                                bottom = 24.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(activeTrackList, key = { idx, t -> "${t.uriString}_$idx" }) { idx, track ->
                                if (section == LibrarySection.DOWNLOADS && onDeleteDownloadedTrack != null && !isMultiSelect) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) {
                                            TrackListRow(
                                                track = track,
                                                isCurrent = track.uriString == playback.currentUri,
                                                isSelected = track.uriString in selectedUris,
                                                isMultiSelectMode = isMultiSelect,
                                                preferFolderCover = settings.preferFolderCoverArt,
                                                onClick = { onPlayTracks(activeTrackList, idx, false) },
                                                onLongClick = { onToggleSelectTrack(track.uriString) },
                                                onToggleFavorite = { onToggleFavorite(track) },
                                                onMoreClick = { onOpenContextMenu(track) }
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteDownloadedTrack(track) },
                                            modifier = Modifier.testTag("remove_offline_dl_${track.onlineTrackId}")
                                        ) {
                                            Icon(Icons.Rounded.Delete, "Remove offline download", tint = palette.inkTertiary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                } else {
                                    TrackListRow(
                                        track = track,
                                        isCurrent = track.uriString == playback.currentUri,
                                        isSelected = track.uriString in selectedUris,
                                        isMultiSelectMode = isMultiSelect,
                                        preferFolderCover = settings.preferFolderCoverArt,
                                        onClick = {
                                            if (isMultiSelect) onToggleSelectTrack(track.uriString)
                                            else onPlayTracks(activeTrackList, idx, false)
                                        },
                                        onLongClick = { onToggleSelectTrack(track.uriString) },
                                        onToggleFavorite = { onToggleFavorite(track) },
                                        onMoreClick = { onOpenContextMenu(track) }
                                    )
                                }
                            }
                        }

                        if (showAlphaRail) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight(0.84f)
                                    .width(26.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.paperCream.copy(alpha = 0.78f))
                                    .pointerInput(activeTrackList, sortField) {
                                        detectVerticalDragGestures { change, _ ->
                                            val frac = (change.position.y / size.height.toFloat()).coerceIn(0f, 0.99f)
                                            val letter = alphabet[(frac * alphabet.size).toInt()]
                                            val targetIdx = activeTrackList.indexOfFirst { t ->
                                                val key = when (sortField) {
                                                    SortField.ARTIST -> t.artist
                                                    SortField.ALBUM -> t.album
                                                    else -> t.title
                                                }
                                                key.firstOrNull()?.uppercaseChar() == letter
                                            }
                                            if (targetIdx >= 0) {
                                                scope.launch { listState.scrollToItem(targetIdx) }
                                            }
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceEvenly
                            ) {
                                alphabet.filterIndexed { i, _ -> i % 2 == 0 }.forEach { ch ->
                                    Text(
                                        text = ch.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accent,
                                        modifier = Modifier.clickable {
                                            val targetIdx = activeTrackList.indexOfFirst { t ->
                                                val key = when (sortField) {
                                                    SortField.ARTIST -> t.artist
                                                    SortField.ALBUM -> t.album
                                                    else -> t.title
                                                }
                                                key.firstOrNull()?.uppercaseChar() == ch
                                            }
                                            if (targetIdx >= 0) {
                                                scope.launch { listState.scrollToItem(targetIdx) }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSortSheet) {
        PaperModalSheet(onDismissRequest = { showSortSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                EditorialSectionHeader(eyebrow = "ORGANIZE", title = "Sort & Order")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SortOrder.entries.forEach { ord ->
                        PaperFilterChip(
                            label = ord.label,
                            selected = sortOrder == ord,
                            onClick = { onUpdateSort(section, sortField, ord) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                PaperDivider()
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortField.entries.chunked(3).forEach { rowFields ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            rowFields.forEach { field ->
                                PaperFilterChip(
                                    label = field.label,
                                    selected = sortField == field,
                                    onClick = { onUpdateSort(section, field, sortOrder); showSortSheet = false },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDiagnosticsDialog) {
        val diag = library.diagnostics
        PaperAlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = "Storage & Archive Health",
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Total Tracks: ${diag.totalTracks}", style = MaterialTheme.typography.bodyMedium)
                    Text("Total Size: ${formatFileSize(diag.totalSizeBytes)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Total Duration: ${formatDuration(diag.totalDurationMs)}", style = MaterialTheme.typography.bodyMedium)
                    if (diag.formatBreakdown.isNotEmpty()) {
                        PaperDivider()
                        Text("FORMATS", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                        Text(
                            diag.formatBreakdown.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    PaperDivider()
                    Text("Potential Duplicates: ${diag.duplicateGroups.size} group(s)", style = MaterialTheme.typography.bodyMedium)
                    diag.duplicateGroups.take(4).forEach { grp ->
                        Text("• ${grp.keyLabel} (${grp.tracks.size} copies)", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                    }
                    Text("Missing Saved Files: ${diag.missingTrackUris.size}", style = MaterialTheme.typography.bodyMedium)
                    if (settings.excludedFolders.isNotEmpty()) {
                        PaperDivider()
                        Text("EXCLUDED FOLDERS (tap to restore)", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                        settings.excludedFolders.forEach { ex ->
                            Text(
                                text = "↺ $ex",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.accent,
                                modifier = Modifier.clickable { onToggleExcludeFolder(ex) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) { Text("Close", color = palette.accent) }
            }
        )
    }
}
