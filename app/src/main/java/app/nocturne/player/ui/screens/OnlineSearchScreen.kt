package app.nocturne.player.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.OnlineAlbumSummary
import app.nocturne.player.data.model.OnlineArtistSummary
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.ui.OnlineSearchUiState
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.OnlineTrackListRow
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.PaperSkeletonList
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.theme.AriaPaperTheme

@Composable
fun OnlineSearchScreen(
    state: OnlineSearchUiState,
    recentSearches: List<String>,
    currentPlayingTrackId: String?,
    onQueryChange: (String) -> Unit,
    onSelectFilter: (OnlineSearchFilter) -> Unit,
    onExecuteSearch: (String) -> Unit,
    onLoadNextPage: () -> Unit,
    onClearSearch: () -> Unit,
    onDeleteRecentSearch: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
    onPlayTracks: (List<OnlineTrack>, Int) -> Unit,
    onToggleFavorite: (OnlineTrack) -> Unit,
    onOpenContextMenu: (OnlineTrack) -> Unit,
    onOpenArtist: (String, OnlineProviderType, String?) -> Unit,
    onOpenAlbum: (String, OnlineProviderType, String?) -> Unit
) {
    val palette = AriaPaperTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val shouldLoadMore by remember(state.tracks.size, state.hasMore, state.isLoadingMore, state.isLoading) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.hasMore && !state.isLoading && !state.isLoadingMore && total > 0 && lastVisible >= total - 4
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadNextPage()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        val activeProviderName = state.providerUsedLabel?.takeIf { it.isNotBlank() } ?: state.providerUsed?.displayName
        EditorialSectionHeader(
            eyebrow = activeProviderName?.let { "ONLINE DISCOVERY · ${it.uppercase()}" } ?: "ONLINE DISCOVERY",
            title = "Search Streams"
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search songs, artists, albums, collections…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = palette.accent) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = onClearSearch) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear query", tint = palette.inkSecondary)
                        }
                    }
                    IconButton(
                        onClick = {
                            keyboard?.hide()
                            onExecuteSearch(state.query)
                        }
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = "Run search", tint = palette.accent)
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    onExecuteSearch(state.query)
                }
            ),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.divider,
                focusedContainerColor = palette.paperElevated,
                unfocusedContainerColor = palette.paperElevated
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("online_search_input")
        )

        Spacer(Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(OnlineSearchFilter.entries) { filter ->
                PaperFilterChip(
                    label = filter.label,
                    selected = state.filter == filter,
                    onClick = {
                        keyboard?.hide()
                        onSelectFilter(filter)
                    },
                    modifier = Modifier.testTag("online_filter_${filter.name}")
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Suggestions while typing before pressing search (or updating query)
        if (state.suggestions.isNotEmpty() && state.query.isNotBlank() && !state.isLoading) {
            PaperCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = palette.paperCream,
                elevation = 2.dp,
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SUGGESTIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    state.suggestions.take(6).forEach { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    keyboard?.hide()
                                    onExecuteSearch(suggestion)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Search, null, tint = palette.inkTertiary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Rounded.NorthWest, null, tint = palette.inkTertiary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        when {
            !state.hasSearched && state.query.isBlank() -> {
                if (recentSearches.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PaperEmptyState(
                            icon = Icons.Rounded.Search,
                            title = "Discover Online Music",
                            subtitle = "Search songs, artists, and collections across configured providers with automatic failover."
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("RECENT ONLINE SEARCHES", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                        TextButton(onClick = onClearSearchHistory) {
                            Text("Clear", color = palette.inkSecondary)
                        }
                    }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(recentSearches, key = { it }) { q ->
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 1.dp,
                                onClick = {
                                    keyboard?.hide()
                                    onExecuteSearch(q)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.History, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(q, style = MaterialTheme.typography.bodyMedium, color = palette.inkPrimary, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { onDeleteRecentSearch(q) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.Close, "Remove search", tint = palette.inkTertiary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            state.isLoading -> {
                PaperSkeletonList(count = 6)
            }

            state.errorMessage != null && state.tracks.isEmpty() && state.artists.isEmpty() && state.albums.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PaperEmptyState(
                        icon = Icons.Rounded.CloudOff,
                        title = "Online Search Unavailable",
                        subtitle = state.errorMessage,
                        actionLabel = "Retry Search",
                        onAction = { onExecuteSearch(state.query) }
                    )
                }
            }

            state.tracks.isEmpty() && state.artists.isEmpty() && state.albums.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PaperEmptyState(
                        icon = Icons.Rounded.Search,
                        title = "No Matches Found",
                        subtitle = "Try another song, artist, or switch to a different source filter above."
                    )
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    if (state.artists.isNotEmpty()) {
                        item(key = "online_res_artists") {
                            Column {
                                Text("ARTISTS & CHANNELS", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(state.artists, key = { it.id }) { artist ->
                                        OnlineArtistCard(
                                            artist = artist,
                                            onClick = {
                                                val target = if (
                                                    artist.providerType == OnlineProviderType.JIOSAAVN ||
                                                    artist.providerType == OnlineProviderType.AUDIUS ||
                                                    artist.providerId.lowercase() in setOf("jiosaavn", "audius")
                                                ) {
                                                    artist.id.ifBlank { artist.url }
                                                } else {
                                                    artist.url.ifBlank { artist.id }
                                                }
                                                onOpenArtist(target, artist.providerType, artist.providerId)
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                PaperDivider()
                            }
                        }
                    }

                    if (state.albums.isNotEmpty()) {
                        item(key = "online_res_albums") {
                            Column {
                                Text("ALBUMS & COLLECTIONS", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(state.albums, key = { it.id }) { album ->
                                        OnlineAlbumCard(
                                            album = album,
                                            onClick = { onOpenAlbum(album.url.ifBlank { album.id }, album.providerType, album.providerId) }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                PaperDivider()
                            }
                        }
                    }

                    if (state.tracks.isNotEmpty()) {
                        item(key = "online_res_tracks_header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "TRACKS (${state.tracks.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accent
                                )
                                val activeBadge = (state.providerUsedLabel?.takeIf { it.isNotBlank() } ?: state.providerUsed?.badge)?.uppercase()
                                activeBadge?.let {
                                    InkStampTag(text = it, accented = false)
                                }
                            }
                        }
                    }

                    itemsIndexed(state.tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, track ->
                        OnlineTrackListRow(
                            track = track,
                            isCurrent = track.id == currentPlayingTrackId,
                            onClick = { onPlayTracks(state.tracks, idx) },
                            onToggleFavorite = { onToggleFavorite(track) },
                            onMoreClick = { onOpenContextMenu(track) }
                        )
                    }

                    if (state.isLoadingMore) {
                        item(key = "online_loading_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    strokeWidth = 2.5.dp,
                                    color = palette.accent
                                )
                            }
                        }
                    } else if (state.hasMore) {
                        item(key = "online_load_more_btn") {
                            TactilePaperButton(
                                text = "Load More Results",
                                filled = false,
                                onClick = onLoadNextPage,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineArtistCard(
    artist: OnlineArtistSummary,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = Modifier.width(136.dp),
        containerColor = palette.paperElevated,
        elevation = 2.dp,
        onClick = onClick,
        contentPadding = PaddingValues(10.dp)
    ) {
        PrintedArtwork(
            trackUri = artist.thumbnailUrl,
            customCoverUri = artist.thumbnailUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            mattePadding = 3.dp,
            targetSizePx = 240
        )
        Spacer(Modifier.height(8.dp))
        Text(artist.name, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (artist.subscriberText.isNotBlank()) {
            Text(artist.subscriberText, style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OnlineAlbumCard(
    album: OnlineAlbumSummary,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = Modifier.width(142.dp),
        containerColor = palette.paperElevated,
        elevation = 2.dp,
        onClick = onClick,
        contentPadding = PaddingValues(8.dp)
    ) {
        PrintedArtwork(
            trackUri = album.thumbnailUrl,
            customCoverUri = album.thumbnailUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            mattePadding = 3.dp,
            targetSizePx = 240
        )
        Spacer(Modifier.height(8.dp))
        Text(album.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
