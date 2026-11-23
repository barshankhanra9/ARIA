package app.nocturne.player.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.ui.OnlineAlbumDetailState
import app.nocturne.player.ui.OnlineArtistDetailState
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.OnlineTrackListRow
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PaperModalSheet
import app.nocturne.player.ui.components.PaperSkeletonList
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.paperGrainBackground
import app.nocturne.player.ui.theme.AriaPaperTheme

@Composable
fun OnlineArtistDetailScreen(
    state: OnlineArtistDetailState,
    currentPlayingTrackId: String?,
    onBack: () -> Unit,
    onRetry: (() -> Unit)?,
    onPlayTracks: (List<OnlineTrack>, Int, Boolean) -> Unit,
    onToggleFavorite: (OnlineTrack) -> Unit,
    onOpenContextMenu: (OnlineTrack) -> Unit,
    onOpenAlbum: (String, OnlineProviderType) -> Unit
) {
    BackHandler(onBack = onBack)
    val palette = AriaPaperTheme.palette

    Box(
        modifier = Modifier
            .fillMaxSize()
            .paperGrainBackground(palette.paperBackground)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.inkPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.page?.providerType?.displayName?.uppercase() ?: "ONLINE ARTIST",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                    Text(
                        text = state.page?.name ?: "Artist Profile",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PaperDivider()
            Spacer(Modifier.height(12.dp))

            when {
                state.isLoading -> PaperSkeletonList(count = 6)
                state.page == null -> PaperEmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = "Artist Unavailable",
                    subtitle = state.errorMessage ?: "Could not load artist information right now.",
                    actionLabel = if (onRetry != null) "Retry" else null,
                    onAction = onRetry
                )
                else -> {
                    val page = state.page
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 28.dp)
                    ) {
                        item(key = "artist_hero") {
                            PaperCard(
                                containerColor = palette.paperCream,
                                elevation = 4.dp,
                                contentPadding = PaddingValues(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PrintedArtwork(
                                        trackUri = page.avatarUrl,
                                        customCoverUri = page.avatarUrl,
                                        modifier = Modifier.size(72.dp),
                                        mattePadding = 3.dp,
                                        targetSizePx = 240
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(page.name, style = MaterialTheme.typography.headlineSmall, color = palette.inkPrimary)
                                        if (page.subscriberCountText.isNotBlank()) {
                                            Text(page.subscriberCountText, style = MaterialTheme.typography.bodySmall, color = palette.accent)
                                        }
                                        if (page.description.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(page.description, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                if (page.tracks.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        TactilePaperButton(
                                            text = "Play All",
                                            onClick = { onPlayTracks(page.tracks, 0, false) },
                                            modifier = Modifier.weight(1f),
                                            icon = Icons.Rounded.PlayArrow
                                        )
                                        TactilePaperButton(
                                            text = "Shuffle",
                                            onClick = { onPlayTracks(page.tracks, page.tracks.indices.random(), true) },
                                            modifier = Modifier.weight(1f),
                                            icon = Icons.Rounded.Shuffle,
                                            filled = false
                                        )
                                    }
                                }
                            }
                        }

                        if (page.playlists.isNotEmpty()) {
                            item(key = "artist_albums") {
                                Column {
                                    Text("RELEASES & PLAYLISTS", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                                    Spacer(Modifier.height(8.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(page.playlists, key = { it.id }) { al ->
                                            PaperCard(
                                                modifier = Modifier.width(136.dp),
                                                containerColor = palette.paperElevated,
                                                elevation = 2.dp,
                                                onClick = { onOpenAlbum(al.url.ifBlank { al.id }, al.providerType) },
                                                contentPadding = PaddingValues(8.dp)
                                            ) {
                                                PrintedArtwork(al.thumbnailUrl, al.thumbnailUrl, modifier = Modifier.fillMaxWidth().aspectRatio(1f), mattePadding = 2.dp, targetSizePx = 220)
                                                Spacer(Modifier.height(6.dp))
                                                Text(al.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (page.tracks.isNotEmpty()) {
                            item(key = "artist_tracks_hdr") {
                                Text("TRACKS (${page.tracks.size})", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            }
                            itemsIndexed(page.tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, track ->
                                OnlineTrackListRow(
                                    track = track,
                                    isCurrent = track.id == currentPlayingTrackId,
                                    onClick = { onPlayTracks(page.tracks, idx, false) },
                                    onToggleFavorite = { onToggleFavorite(track) },
                                    onMoreClick = { onOpenContextMenu(track) }
                                )
                            }
                        } else {
                            item(key = "artist_empty") {
                                PaperEmptyState(Icons.Rounded.Person, "No Tracks Listed", "Try searching for this artist in Online Search.")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineAlbumDetailScreen(
    state: OnlineAlbumDetailState,
    currentPlayingTrackId: String?,
    onBack: () -> Unit,
    onRetry: (() -> Unit)?,
    onPlayTracks: (List<OnlineTrack>, Int, Boolean) -> Unit,
    onToggleFavorite: (OnlineTrack) -> Unit,
    onOpenContextMenu: (OnlineTrack) -> Unit
) {
    BackHandler(onBack = onBack)
    val palette = AriaPaperTheme.palette

    Box(
        modifier = Modifier
            .fillMaxSize()
            .paperGrainBackground(palette.paperBackground)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.inkPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.page?.providerType?.displayName?.uppercase() ?: "ONLINE ALBUM",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                    Text(
                        text = state.page?.title ?: "Collection",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PaperDivider()
            Spacer(Modifier.height(12.dp))

            when {
                state.isLoading -> PaperSkeletonList(count = 6)
                state.page == null -> PaperEmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = "Collection Unavailable",
                    subtitle = state.errorMessage ?: "Could not load album or playlist details.",
                    actionLabel = if (onRetry != null) "Retry" else null,
                    onAction = onRetry
                )
                else -> {
                    val page = state.page
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 28.dp)
                    ) {
                        item(key = "album_hero") {
                            PaperCard(
                                containerColor = palette.paperCream,
                                elevation = 4.dp,
                                contentPadding = PaddingValues(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PrintedArtwork(
                                        trackUri = page.thumbnailUrl,
                                        customCoverUri = page.thumbnailUrl,
                                        modifier = Modifier.size(84.dp),
                                        mattePadding = 4.dp,
                                        targetSizePx = 280
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(page.title, style = MaterialTheme.typography.headlineSmall, color = palette.inkPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(4.dp))
                                        Text(page.artist, style = MaterialTheme.typography.bodyMedium, color = palette.accent)
                                        Spacer(Modifier.height(6.dp))
                                        InkStampTag(text = "${page.tracks.size} TRACKS", accented = true)
                                    }
                                }
                                if (page.tracks.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        TactilePaperButton(
                                            text = "Play All",
                                            onClick = { onPlayTracks(page.tracks, 0, false) },
                                            modifier = Modifier.weight(1f),
                                            icon = Icons.Rounded.PlayArrow
                                        )
                                        TactilePaperButton(
                                            text = "Shuffle",
                                            onClick = { onPlayTracks(page.tracks, page.tracks.indices.random(), true) },
                                            modifier = Modifier.weight(1f),
                                            icon = Icons.Rounded.Shuffle,
                                            filled = false
                                        )
                                    }
                                }
                            }
                        }

                        if (page.tracks.isEmpty()) {
                            item(key = "album_empty") {
                                PaperEmptyState(Icons.Rounded.Album, "No Tracks Found", "This online collection does not contain playable audio tracks.")
                            }
                        } else {
                            itemsIndexed(page.tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, track ->
                                OnlineTrackListRow(
                                    track = track,
                                    isCurrent = track.id == currentPlayingTrackId,
                                    onClick = { onPlayTracks(page.tracks, idx, false) },
                                    onToggleFavorite = { onToggleFavorite(track) },
                                    onMoreClick = { onOpenContextMenu(track) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RelatedTracksSheet(
    relatedTracks: List<OnlineTrack>,
    currentPlayingTrackId: String?,
    onDismiss: () -> Unit,
    onPlayTracks: (List<OnlineTrack>, Int) -> Unit,
    onToggleFavorite: (OnlineTrack) -> Unit,
    onOpenContextMenu: (OnlineTrack) -> Unit
) {
    PaperModalSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            EditorialSectionHeader(
                eyebrow = "SIMILAR STREAMS",
                title = "Related Recommendations"
            )
            Spacer(Modifier.height(10.dp))
            if (relatedTracks.isEmpty()) {
                PaperEmptyState(
                    icon = Icons.Rounded.Stream,
                    title = "No Related Tracks Yet",
                    subtitle = "Play an online song to discover recommended and similar tracks from the active provider."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(relatedTracks, key = { idx, t -> "${t.id}_rel_$idx" }) { idx, track ->
                        OnlineTrackListRow(
                            track = track,
                            isCurrent = track.id == currentPlayingTrackId,
                            onClick = {
                                onDismiss()
                                onPlayTracks(relatedTracks, idx)
                            },
                            onToggleFavorite = { onToggleFavorite(track) },
                            onMoreClick = { onOpenContextMenu(track) }
                        )
                    }
                }
            }
        }
    }
}
