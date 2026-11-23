package app.nocturne.player.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.Playlist
import app.nocturne.player.data.model.Track
import app.nocturne.player.ui.LibraryDerivedState
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.theme.AriaPaperTheme

private enum class SearchScope(val label: String) {
    ALL("All"),
    SONGS("Songs"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    GENRES("Genres"),
    PLAYLISTS("Playlists"),
    FOLDERS("Folders")
}

@Composable
fun SearchScreen(
    library: LibraryDerivedState,
    playlists: List<Playlist>,
    recentSearches: List<String>,
    preferFolderCover: Boolean,
    onRecordSearch: (String) -> Unit,
    onDeleteRecentSearch: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenFolder: (String) -> Unit
) {
    val palette = AriaPaperTheme.palette
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(SearchScope.ALL) }
    val trimmed = query.trim()

    val matchedTracks = remember(trimmed, library.allTracks) {
        if (trimmed.isBlank()) emptyList()
        else library.allTracks.filter {
            it.title.contains(trimmed, true) ||
                it.artist.contains(trimmed, true) ||
                it.album.contains(trimmed, true)
        }
    }
    val matchedArtists = remember(trimmed, library.artists) {
        if (trimmed.isBlank()) emptyList()
        else library.artists.filter { it.name.contains(trimmed, true) }
    }
    val matchedAlbums = remember(trimmed, library.albums) {
        if (trimmed.isBlank()) emptyList()
        else library.albums.filter { it.name.contains(trimmed, true) || it.artist.contains(trimmed, true) }
    }
    val matchedGenres = remember(trimmed, library.genres) {
        if (trimmed.isBlank()) emptyList()
        else library.genres.filter { it.name.contains(trimmed, true) }
    }
    val matchedPlaylists = remember(trimmed, playlists) {
        if (trimmed.isBlank()) emptyList()
        else playlists.filter { it.name.contains(trimmed, true) || it.description.contains(trimmed, true) }
    }
    val matchedFolders = remember(trimmed, library.folders) {
        if (trimmed.isBlank()) emptyList()
        else library.folders.filter { it.name.contains(trimmed, true) || it.path.contains(trimmed, true) }
    }

    val suggestions = remember(library) {
        (library.artists.take(4).map { it.name } + library.genres.take(4).map { it.name }).distinct()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        EditorialSectionHeader(
            eyebrow = "ARCHIVE INDEX",
            title = "Search"
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, artists, albums, genres, folders...") },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = palette.accent) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Clear, "Clear search", tint = palette.inkSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onRecordSearch(query) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = palette.paperPressed,
                unfocusedContainerColor = palette.paperPressed,
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.divider,
                focusedTextColor = palette.inkPrimary,
                unfocusedTextColor = palette.inkPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("global_search_input")
        )

        Spacer(Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchScope.entries) { item ->
                PaperFilterChip(
                    label = item.label,
                    selected = scope == item,
                    onClick = { scope = item }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (trimmed.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (recentSearches.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("RECENT SEARCHES", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                            TextButton(onClick = onClearSearchHistory) {
                                Text("Clear all", color = palette.inkSecondary)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            recentSearches.forEach { term ->
                                PaperCard(
                                    containerColor = palette.paperElevated,
                                    elevation = 1.dp,
                                    onClick = { query = term },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.History, null, tint = palette.inkTertiary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(term, style = MaterialTheme.typography.bodyMedium, color = palette.inkPrimary, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { onDeleteRecentSearch(term) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Rounded.Clear, "Remove", tint = palette.inkTertiary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (suggestions.isNotEmpty()) {
                    item {
                        Text("SUGGESTIONS FROM YOUR ARCHIVE", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(suggestions) { s ->
                                PaperFilterChip(
                                    label = s,
                                    selected = false,
                                    onClick = {
                                        query = s
                                        onRecordSearch(s)
                                    }
                                )
                            }
                        }
                    }
                }

                if (recentSearches.isEmpty() && suggestions.isEmpty()) {
                    item {
                        PaperEmptyState(
                            icon = Icons.Rounded.Search,
                            title = "Search Your Collection",
                            subtitle = "Type any song title, artist, album, genre, playlist, or folder name."
                        )
                    }
                }
            }
        } else {
            val totalHits = matchedTracks.size + matchedArtists.size + matchedAlbums.size +
                matchedGenres.size + matchedPlaylists.size + matchedFolders.size

            if (totalHits == 0) {
                PaperEmptyState(
                    icon = Icons.Rounded.Search,
                    title = "No Matches Found",
                    subtitle = "No local audio items matched \"$trimmed\"."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if ((scope == SearchScope.ALL || scope == SearchScope.SONGS) && matchedTracks.isNotEmpty()) {
                        item {
                            Text("SONGS (${matchedTracks.size})", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                        }
                        items(matchedTracks.take(25)) { track ->
                            PaperCard(
                                containerColor = palette.paperElevated,
                                elevation = 2.dp,
                                onClick = {
                                    onRecordSearch(trimmed)
                                    onPlayTrack(matchedTracks, matchedTracks.indexOf(track))
                                },
                                contentPadding = PaddingValues(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PrintedArtwork(
                                        trackUri = track.uriString,
                                        customCoverUri = track.customCoverUri,
                                        folderPath = track.folderPath,
                                        preferFolderCover = preferFolderCover,
                                        modifier = Modifier.size(46.dp),
                                        mattePadding = 2.dp,
                                        targetSizePx = 160
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = highlightMatch(track.title, trimmed, palette.accent),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = palette.inkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = highlightMatch("${track.artist} · ${track.album}", trimmed, palette.accent),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = palette.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if ((scope == SearchScope.ALL || scope == SearchScope.ALBUMS) && matchedAlbums.isNotEmpty()) {
                        item { Text("ALBUMS (${matchedAlbums.size})", style = MaterialTheme.typography.labelMedium, color = palette.accent) }
                        items(matchedAlbums.take(12)) { album ->
                            SearchSimpleCard(
                                icon = Icons.Rounded.Album,
                                title = album.name,
                                subtitle = "${album.artist} · ${album.tracks.size} tracks",
                                query = trimmed,
                                accent = palette.accent
                            ) { onRecordSearch(trimmed); onOpenAlbum(album.name) }
                        }
                    }

                    if ((scope == SearchScope.ALL || scope == SearchScope.ARTISTS) && matchedArtists.isNotEmpty()) {
                        item { Text("ARTISTS (${matchedArtists.size})", style = MaterialTheme.typography.labelMedium, color = palette.accent) }
                        items(matchedArtists.take(12)) { artist ->
                            SearchSimpleCard(
                                icon = Icons.Rounded.Person,
                                title = artist.name,
                                subtitle = "${artist.tracks.size} tracks",
                                query = trimmed,
                                accent = palette.accent
                            ) { onRecordSearch(trimmed); onOpenArtist(artist.name) }
                        }
                    }

                    if ((scope == SearchScope.ALL || scope == SearchScope.GENRES) && matchedGenres.isNotEmpty()) {
                        item { Text("GENRES (${matchedGenres.size})", style = MaterialTheme.typography.labelMedium, color = palette.accent) }
                        items(matchedGenres.take(10)) { genre ->
                            SearchSimpleCard(
                                icon = Icons.Rounded.Category,
                                title = genre.name,
                                subtitle = "${genre.tracks.size} tracks",
                                query = trimmed,
                                accent = palette.accent
                            ) { onRecordSearch(trimmed); onOpenGenre(genre.name) }
                        }
                    }

                    if ((scope == SearchScope.ALL || scope == SearchScope.PLAYLISTS) && matchedPlaylists.isNotEmpty()) {
                        item { Text("PLAYLISTS (${matchedPlaylists.size})", style = MaterialTheme.typography.labelMedium, color = palette.accent) }
                        items(matchedPlaylists) { pl ->
                            SearchSimpleCard(
                                icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                                title = pl.name,
                                subtitle = "${pl.trackUris.size} tracks",
                                query = trimmed,
                                accent = palette.accent
                            ) { onRecordSearch(trimmed); onOpenPlaylist(pl) }
                        }
                    }

                    if ((scope == SearchScope.ALL || scope == SearchScope.FOLDERS) && matchedFolders.isNotEmpty()) {
                        item { Text("FOLDERS (${matchedFolders.size})", style = MaterialTheme.typography.labelMedium, color = palette.accent) }
                        items(matchedFolders) { folder ->
                            SearchSimpleCard(
                                icon = Icons.Rounded.Folder,
                                title = folder.name,
                                subtitle = "${folder.tracks.size} tracks · ${folder.path}",
                                query = trimmed,
                                accent = palette.accent
                            ) { onRecordSearch(trimmed); onOpenFolder(folder.path) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSimpleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    query: String,
    accent: Color,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        containerColor = palette.paperElevated,
        elevation = 2.dp,
        onClick = onClick,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(highlightMatch(title, query, accent), style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun highlightMatch(text: String, query: String, highlightColor: Color) = buildAnnotatedString {
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    var start = 0
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    while (start < text.length) {
        val idx = lowerText.indexOf(lowerQuery, start)
        if (idx < 0) {
            append(text.substring(start))
            break
        }
        append(text.substring(start, idx))
        withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
            append(text.substring(idx, idx + query.length))
        }
        start = idx + query.length
    }
}
