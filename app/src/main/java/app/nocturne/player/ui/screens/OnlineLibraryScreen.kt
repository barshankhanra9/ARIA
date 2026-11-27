package app.nocturne.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.db.DownloadedTrackEntity
import app.nocturne.player.data.downloads.DownloadedPlaylistWithTracks
import app.nocturne.player.data.downloads.OfflineDownloadManager
import app.nocturne.player.data.model.OnlineHistoryEntry
import app.nocturne.player.data.model.OnlineLibrarySection
import app.nocturne.player.data.model.OnlinePlaylist
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.playback.OnlineEngineSnapshot
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.OnlineTrackListRow
import app.nocturne.player.ui.components.PaperAlertDialog
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDebossedWell
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.formatFileSize
import app.nocturne.player.ui.theme.AriaPaperTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OnlineLibraryScreen(
    section: OnlineLibrarySection,
    favorites: List<OnlineTrack>,
    history: List<OnlineHistoryEntry>,
    recentlyPlayed: List<OnlineTrack>,
    mostPlayed: List<OnlineTrack>,
    allTracks: List<OnlineTrack>,
    playlists: List<OnlinePlaylist>,
    onlineSnapshot: OnlineEngineSnapshot,
    onSelectSection: (OnlineLibrarySection) -> Unit,
    onPlayTracks: (List<OnlineTrack>, Int, Boolean) -> Unit,
    onToggleFavorite: (OnlineTrack) -> Unit,
    onOpenContextMenu: (OnlineTrack) -> Unit,
    onClearHistory: () -> Unit,
    onJumpQueueIndex: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onOpenQueueSheet: () -> Unit,
    onOpenArtist: (String, OnlineProviderType, String?) -> Unit,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    downloadedTracks: List<DownloadedTrackEntity> = emptyList(),
    downloadStates: Map<String, DownloadedTrackEntity> = emptyMap(),
    downloadedPlaylists: List<DownloadedPlaylistWithTracks> = emptyList(),
    totalDownloadBytes: Long = 0L,
    onDownloadTrack: ((OnlineTrack) -> Unit)? = null,
    onDeleteDownload: (String) -> Unit = {},
    onDeleteDownloadedPlaylist: (String) -> Unit = {},
    onPlayDownloadedTracks: (List<DownloadedTrackEntity>, Int, Boolean) -> Unit = { _, _, _ -> },
    onPlayDownloadedPlaylist: (DownloadedPlaylistWithTracks, Int, Boolean) -> Unit = { _, _, _ -> }
) {
    val palette = AriaPaperTheme.palette
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        EditorialSectionHeader(
            eyebrow = "ONLINE ARCHIVE",
            title = section.label,
            trailing = {
                when (section) {
                    OnlineLibrarySection.HISTORY -> if (history.isNotEmpty()) {
                        IconButton(onClick = { showClearHistoryDialog = true }) {
                            Icon(Icons.Rounded.DeleteSweep, "Clear listening history", tint = palette.danger)
                        }
                    }
                    OnlineLibrarySection.QUEUE -> if (onlineSnapshot.queue.isNotEmpty()) {
                        IconButton(onClick = onOpenQueueSheet) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Manage online queue", tint = palette.accent)
                        }
                    }
                    else -> Unit
                }
            }
        )
        Spacer(Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(OnlineLibrarySection.entries) { sec ->
                PaperFilterChip(
                    label = sec.label,
                    selected = section == sec,
                    onClick = { onSelectSection(sec) },
                    modifier = Modifier.testTag("online_lib_sec_${sec.name}")
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when (section) {
            OnlineLibrarySection.DOWNLOADS -> {
                PaperDebossedWell(
                    cornerRadius = 12.dp,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("OFFLINE STORAGE", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            Text(
                                text = "${downloadedTracks.size} saved song(s) · ${downloadedPlaylists.size} collection(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkPrimary
                            )
                        }
                        InkStampTag(text = formatFileSize(totalDownloadBytes), accented = true)
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (downloadedTracks.isEmpty() && downloadedPlaylists.isEmpty()) {
                    PaperEmptyState(
                        icon = Icons.Rounded.DownloadDone,
                        title = "No Offline Downloads Yet",
                        subtitle = "Tap the download icon on any online song or playlist to save audio, album cover, and synced lyrics for offline playback."
                    )
                } else {
                    if (downloadedTracks.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TactilePaperButton(
                                text = "Play Offline (${downloadedTracks.size})",
                                icon = Icons.Rounded.PlayArrow,
                                onClick = { onPlayDownloadedTracks(downloadedTracks, 0, false) },
                                modifier = Modifier.weight(1f)
                            )
                            TactilePaperButton(
                                text = "Shuffle",
                                icon = Icons.Rounded.Shuffle,
                                filled = false,
                                onClick = { onPlayDownloadedTracks(downloadedTracks, downloadedTracks.indices.random(), true) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (downloadedPlaylists.isNotEmpty()) {
                            item(key = "dl_collections_hdr") {
                                Text("SAVED COLLECTIONS", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            }
                            items(downloadedPlaylists, key = { "dl_pl_${it.id}" }) { pl ->
                                PaperCard(
                                    containerColor = palette.paperCream,
                                    elevation = 2.dp,
                                    onClick = { onPlayDownloadedPlaylist(pl, 0, false) },
                                    contentPadding = PaddingValues(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        PrintedArtwork(
                                            trackUri = pl.localCoverUri,
                                            customCoverUri = pl.localCoverUri,
                                            modifier = Modifier.size(50.dp),
                                            mattePadding = 2.dp,
                                            targetSizePx = 160
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(pl.title, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${pl.tracks.size} offline track(s) · ${pl.subtitle.ifBlank { "Saved collection" }}", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = { onDeleteDownloadedPlaylist(pl.id) }) {
                                            Icon(Icons.Rounded.Delete, "Remove offline collection", tint = palette.inkTertiary, modifier = Modifier.size(19.dp))
                                        }
                                    }
                                }
                            }
                            item(key = "dl_tracks_divider") {
                                Spacer(Modifier.height(4.dp))
                                PaperDivider()
                                Spacer(Modifier.height(4.dp))
                                Text("SAVED SONGS (${downloadedTracks.size})", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            }
                        }
                        itemsIndexed(downloadedTracks, key = { idx, e -> "dl_${e.trackId}_$idx" }) { idx, entity ->
                            val onlineTrack = remember(entity) { OfflineDownloadManager.entityToOnlineTrack(entity) }
                            val isCurrent = onlineTrack.id == onlineSnapshot.currentTrack?.id
                            PaperCard(
                                containerColor = if (isCurrent) palette.paperCream else palette.paperElevated,
                                elevation = if (isCurrent) 4.dp else 2.dp,
                                selected = isCurrent,
                                onClick = { onPlayDownloadedTracks(downloadedTracks, idx, false) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PrintedArtwork(
                                        trackUri = onlineTrack.thumbnailUrl,
                                        customCoverUri = onlineTrack.thumbnailUrl,
                                        modifier = Modifier.size(52.dp),
                                        mattePadding = 2.dp,
                                        targetSizePx = 192
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = entity.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (isCurrent) palette.accent else palette.inkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${entity.artist} · ${formatFileSize(entity.fileSizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = palette.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            InkStampTag(text = "OFFLINE · ${entity.formatLabel}", accented = isCurrent)
                                            if (!entity.localLyricsPath.isNullOrBlank()) {
                                                InkStampTag(text = "LYRICS", accented = false)
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = { onDeleteDownload(entity.trackId) },
                                        modifier = Modifier.testTag("delete_download_${entity.trackId}")
                                    ) {
                                        Icon(Icons.Rounded.Delete, "Delete offline download", tint = palette.inkTertiary, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            OnlineLibrarySection.FAVORITES -> OnlineTrackCollectionBody(
                tracks = favorites,
                currentTrackId = onlineSnapshot.currentTrack?.id,
                emptyIcon = Icons.Rounded.Favorite,
                emptyTitle = "No Online Favorites Yet",
                emptySubtitle = "Heart any online track to save its stream identity and artwork locally.",
                onPlayTracks = onPlayTracks,
                onToggleFavorite = onToggleFavorite,
                onOpenContextMenu = onOpenContextMenu,
                downloadStates = downloadStates,
                onDownloadTrack = onDownloadTrack
            )

            OnlineLibrarySection.RECENTLY_PLAYED -> OnlineTrackCollectionBody(
                tracks = recentlyPlayed,
                currentTrackId = onlineSnapshot.currentTrack?.id,
                emptyIcon = Icons.Rounded.History,
                emptyTitle = "No Recently Played Streams",
                emptySubtitle = "Tracks you stream online will appear here for one-tap replay.",
                onPlayTracks = onPlayTracks,
                onToggleFavorite = onToggleFavorite,
                onOpenContextMenu = onOpenContextMenu,
                downloadStates = downloadStates,
                onDownloadTrack = onDownloadTrack
            )

            OnlineLibrarySection.MOST_PLAYED -> OnlineTrackCollectionBody(
                tracks = mostPlayed,
                currentTrackId = onlineSnapshot.currentTrack?.id,
                emptyIcon = Icons.Rounded.PlayArrow,
                emptyTitle = "No Most Played Streams Yet",
                emptySubtitle = "Stream tracks across your configured providers to build your rotation.",
                onPlayTracks = onPlayTracks,
                onToggleFavorite = onToggleFavorite,
                onOpenContextMenu = onOpenContextMenu,
                downloadStates = downloadStates,
                onDownloadTrack = onDownloadTrack
            )

            OnlineLibrarySection.HISTORY -> {
                if (history.isEmpty()) {
                    PaperEmptyState(
                        icon = Icons.Rounded.History,
                        title = "Listening History is Empty",
                        subtitle = "Your chronological online streaming log appears here and can be cleared anytime."
                    )
                } else {
                    val tracksFromHistory = remember(history) { history.map { it.track } }
                    val timeFmt = remember { SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TactilePaperButton(
                            text = "Play History",
                            icon = Icons.Rounded.PlayArrow,
                            onClick = { onPlayTracks(tracksFromHistory, 0, false) },
                            modifier = Modifier.weight(1f)
                        )
                        TactilePaperButton(
                            text = "Clear Log",
                            icon = Icons.Rounded.DeleteSweep,
                            filled = false,
                            onClick = { showClearHistoryDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        itemsIndexed(history, key = { _, e -> e.entryId }) { idx, entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = timeFmt.format(Date(entry.playedAt)).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.inkTertiary,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                                OnlineTrackListRow(
                                    track = entry.track,
                                    isCurrent = entry.track.id == onlineSnapshot.currentTrack?.id,
                                    onClick = { onPlayTracks(tracksFromHistory, idx, false) },
                                    onToggleFavorite = { onToggleFavorite(entry.track) },
                                    onMoreClick = { onOpenContextMenu(entry.track) },
                                    downloadState = downloadStates[entry.track.id],
                                    onDownloadClick = onDownloadTrack?.let { cb -> { cb(entry.track) } }
                                )
                            }
                        }
                    }
                }
            }

            OnlineLibrarySection.QUEUE -> {
                if (onlineSnapshot.queue.isEmpty()) {
                    PaperEmptyState(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        title = "Online Queue is Empty",
                        subtitle = "Search or play any online track to populate your streaming queue."
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TactilePaperButton(
                            text = "Reorder / Save",
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            onClick = onOpenQueueSheet,
                            modifier = Modifier.weight(1f)
                        )
                        TactilePaperButton(
                            text = "Clear",
                            icon = Icons.Rounded.DeleteSweep,
                            filled = false,
                            onClick = onClearQueue,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        itemsIndexed(onlineSnapshot.queue, key = { idx, t -> "${t.id}_q_$idx" }) { idx, track ->
                            val isCurrent = idx == onlineSnapshot.currentIndex
                            PaperCard(
                                containerColor = if (isCurrent) palette.paperCream else palette.paperElevated,
                                selected = isCurrent,
                                elevation = if (isCurrent) 4.dp else 2.dp,
                                onClick = { onJumpQueueIndex(idx) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PrintedArtwork(
                                        trackUri = track.thumbnailUrl,
                                        customCoverUri = track.thumbnailUrl,
                                        modifier = Modifier.size(44.dp),
                                        mattePadding = 2.dp,
                                        targetSizePx = 160
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
                                            text = "${track.artist} · ${track.providerLabel}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = palette.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { onRemoveQueueItem(idx) }) {
                                        Icon(Icons.Rounded.Close, "Remove from queue", tint = palette.inkTertiary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            OnlineLibrarySection.ARTISTS -> {
                val grouped = remember(allTracks) {
                    allTracks.groupBy { it.artist }
                        .entries
                        .sortedBy { it.key.lowercase() }
                        .map { it.key to it.value }
                }
                if (grouped.isEmpty()) {
                    PaperEmptyState(
                        icon = Icons.Rounded.Person,
                        title = "No Online Artists Logged Yet",
                        subtitle = "Stream or favorite tracks to collect artists in your online library."
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(grouped, key = { it.first }) { (artistName, artistTracks) ->
                            val rep = artistTracks.first()
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 2.dp,
                                onClick = {
                                    val target = rep.artistUrl?.takeIf { it.isNotBlank() } ?: rep.artistId ?: artistName
                                    if (rep.artistUrl.isNullOrBlank() && rep.artistId.isNullOrBlank()) {
                                        onPlayTracks(artistTracks, 0, false)
                                    } else {
                                        onOpenArtist(target, rep.providerType, rep.effectiveProviderId())
                                    }
                                },
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PrintedArtwork(
                                        trackUri = rep.thumbnailUrl,
                                        customCoverUri = rep.thumbnailUrl,
                                        modifier = Modifier.size(48.dp),
                                        mattePadding = 2.dp,
                                        targetSizePx = 160
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(artistName, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${artistTracks.size} saved track(s)", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                                    }
                                    InkStampTag(text = rep.effectiveBadge())
                                }
                            }
                        }
                    }
                }
            }

            OnlineLibrarySection.PLAYLISTS -> {
                if (playlists.isEmpty()) {
                    PaperEmptyState(
                        icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                        title = "No Online Playlists Yet",
                        subtitle = "Create custom online playlists from the Playlists tab or any track menu."
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(playlists, key = { it.id }) { pl ->
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 2.dp,
                                onClick = { onOpenPlaylist(pl) },
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PrintedArtwork(
                                        trackUri = pl.coverUrl,
                                        customCoverUri = pl.coverUrl,
                                        modifier = Modifier.size(48.dp),
                                        mattePadding = 2.dp,
                                        targetSizePx = 160
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(pl.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${pl.tracks.size} online tracks", style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                                    }
                                    InkStampTag(text = "ONLINE")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearHistoryDialog) {
        PaperAlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = "Clear Listening History?",
            text = { Text("This clears your online play log and recently played list while preserving your online favorites and playlists.") },
            confirmButton = {
                TactilePaperButton(
                    text = "Clear History",
                    onClick = {
                        onClearHistory()
                        showClearHistoryDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text("Cancel", color = palette.inkSecondary) }
            }
        )
    }
}

@Composable
private fun OnlineTrackCollectionBody(
    tracks: List<OnlineTrack>,
    currentTrackId: String?,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyTitle: String,
    emptySubtitle: String,
    onPlayTracks: (List<OnlineTrack>, Int, Boolean) -> Unit,
    onToggleFavorite: (OnlineTrack) -> Unit,
    onOpenContextMenu: (OnlineTrack) -> Unit,
    downloadStates: Map<String, DownloadedTrackEntity> = emptyMap(),
    onDownloadTrack: ((OnlineTrack) -> Unit)? = null
) {
    if (tracks.isEmpty()) {
        PaperEmptyState(icon = emptyIcon, title = emptyTitle, subtitle = emptySubtitle)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TactilePaperButton(
                text = "Play (${tracks.size})",
                icon = Icons.Rounded.PlayArrow,
                onClick = { onPlayTracks(tracks, 0, false) },
                modifier = Modifier.weight(1f)
            )
            TactilePaperButton(
                text = "Shuffle",
                icon = Icons.Rounded.Shuffle,
                filled = false,
                onClick = { onPlayTracks(tracks, tracks.indices.random(), true) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, track ->
                OnlineTrackListRow(
                    track = track,
                    isCurrent = track.id == currentTrackId,
                    onClick = { onPlayTracks(tracks, idx, false) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onMoreClick = { onOpenContextMenu(track) },
                    downloadState = downloadStates[track.id],
                    onDownloadClick = onDownloadTrack?.let { cb -> { cb(track) } }
                )
            }
        }
    }
}
