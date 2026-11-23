package app.nocturne.player.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.HomeSectionConfig
import app.nocturne.player.data.model.HomeSectionId
import app.nocturne.player.data.model.LibrarySection
import app.nocturne.player.data.model.PlaybackMode
import app.nocturne.player.data.model.Playlist
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.ui.LibraryDerivedState
import app.nocturne.player.ui.PlaybackUiState
import app.nocturne.player.ui.components.AnimatedInkEqualizer
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDebossedWell
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.PaperModalSheet
import app.nocturne.player.ui.components.PaperSkeletonList
import app.nocturne.player.ui.components.PlaybackModeSwitcher
import app.nocturne.player.ui.components.PlaylistArtworkCollage
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.formatFileSize
import app.nocturne.player.ui.theme.AriaPaperTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    library: LibraryDerivedState,
    playback: PlaybackUiState,
    playlists: List<Playlist>,
    settings: UserSettings,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onRescanLibrary: () -> Unit,
    onShuffleAll: () -> Unit,
    onOpenLibrarySection: (LibrarySection) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPickMusicFile: () -> Unit,
    onPickMusicFolder: () -> Unit,
    onMoveSection: (HomeSectionId, Int) -> Unit,
    onToggleSectionVisibility: (HomeSectionId) -> Unit,
    onToggleSectionCompact: (HomeSectionId) -> Unit,
    onResetSections: () -> Unit,
    playbackMode: PlaybackMode = PlaybackMode.OFFLINE,
    isOnlinePlaying: Boolean = false,
    onSwitchPlaybackMode: (PlaybackMode) -> Unit = {}
) {
    val palette = AriaPaperTheme.palette
    var showCustomizeSheet by remember { mutableStateOf(false) }

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..21 -> "Good Evening"
            else -> "Late Night Listening"
        }
    }

    val orderedSections = remember(settings.homeSections) {
        val existingIds = settings.homeSections.map { it.id }.toSet()
        val missing = HomeSectionId.entries.filter { it !in existingIds }.map {
            HomeSectionConfig(it, visible = true, order = it.ordinal, compact = false)
        }
        (settings.homeSections + missing).sortedBy { it.order }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_screen_list"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Newspaper Broadsheet Masthead
        item(key = "header") {
            val editionDate = remember {
                SimpleDateFormat("EEE · MMM dd, yyyy", Locale.getDefault()).format(Date()).uppercase()
            }
            Column {
                PaperDivider(doubleRule = true)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "THE ARIA CHRONICLE",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                    Text(
                        text = editionDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary
                    )
                }
                Spacer(Modifier.height(6.dp))
                PaperDivider()
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.displayLarge,
                            color = palette.inkPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (library.allTracks.isEmpty()) {
                                "Offline Archive Edition · Ready for Press"
                            } else {
                                "${library.allTracks.size} Tracks · ${library.albums.size} Albums · ${library.artists.size} Artists"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary
                        )
                    }

                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(44.dp)
                            .background(palette.divider)
                    )
                    Spacer(Modifier.width(12.dp))

                    PaperCard(
                        modifier = Modifier.testTag("home_customize_btn"),
                        containerColor = palette.paperElevated,
                        cornerRadius = 8.dp,
                        elevation = 3.dp,
                        onClick = { showCustomizeSheet = true },
                        contentPadding = PaddingValues(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Customize Home",
                            tint = palette.accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                PlaybackModeSwitcher(
                    selectedMode = playbackMode,
                    isOfflinePlaying = playback.isPlaying,
                    isOnlinePlaying = isOnlinePlaying,
                    onSelectMode = onSwitchPlaybackMode
                )
                Spacer(Modifier.height(12.dp))
                PaperDivider(doubleRule = true)
                Spacer(Modifier.height(14.dp))

                // Quick Action Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TactilePaperButton(
                        text = "Shuffle All",
                        icon = Icons.Rounded.Shuffle,
                        onClick = onShuffleAll,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_shuffle_all_btn")
                    )
                    TactilePaperButton(
                        text = "All Songs",
                        icon = Icons.Rounded.LibraryMusic,
                        filled = false,
                        onClick = { onOpenLibrarySection(LibrarySection.ALL_SONGS) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_all_songs_btn")
                    )
                    PaperCard(
                        modifier = Modifier.testTag("home_search_btn"),
                        containerColor = palette.paperElevated,
                        cornerRadius = 12.dp,
                        elevation = 2.dp,
                        onClick = onOpenSearch,
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Icon(Icons.Rounded.Search, "Search", tint = palette.inkPrimary, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        if (library.isScanning) {
            item(key = "scanning_indicator") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Scanning device audio files…",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.accent,
                        trackColor = palette.paperPressed
                    )
                }
            }
        }

        if (library.isScanning && library.allTracks.isEmpty()) {
            item(key = "scanning_skeleton") {
                PaperSkeletonList(count = 4)
            }
        } else if (library.allTracks.isEmpty()) {
            item(key = "empty_home") {
                if (!hasAudioPermission) {
                    PaperEmptyState(
                        icon = Icons.Rounded.LibraryMusic,
                        title = "Allow Audio Access",
                        subtitle = "Grant audio permission so ARIA can automatically scan and index all songs stored on your device.",
                        actionLabel = "Scan Device Audio",
                        onAction = onRequestAudioPermission
                    )
                } else {
                    PaperEmptyState(
                        icon = Icons.Rounded.LibraryMusic,
                        title = "No Audio Files Found Yet",
                        subtitle = "ARIA automatically scans all device audio folders. Tap Rescan Device or pick an external folder.",
                        actionLabel = "Rescan Device",
                        onAction = onRescanLibrary
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TactilePaperButton(
                        text = "Select Folder",
                        icon = Icons.Rounded.Add,
                        filled = false,
                        onClick = onPickMusicFolder,
                        modifier = Modifier.weight(1f)
                    )
                    TactilePaperButton(
                        text = "Open File",
                        icon = Icons.Rounded.Add,
                        filled = false,
                        onClick = onPickMusicFile,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            orderedSections.filter { it.visible }.forEach { sectionCfg ->
                when (sectionCfg.id) {
                    HomeSectionId.CONTINUE_LISTENING -> {
                        val heroTrack = playback.currentTrack ?: library.recentlyPlayed.firstOrNull() ?: library.allTracks.firstOrNull()
                        if (heroTrack != null) {
                            item(key = "sec_continue") {
                                ContinueListeningCard(
                                    track = heroTrack,
                                    isPlaying = playback.isPlaying,
                                    compact = sectionCfg.compact,
                                    preferFolderCover = settings.preferFolderCoverArt,
                                    onClick = {
                                        if (playback.currentTrack != null) onOpenPlayer()
                                        else onPlayTracks(library.allTracks, library.allTracks.indexOf(heroTrack).coerceAtLeast(0))
                                    }
                                )
                            }
                        }
                    }
                    HomeSectionId.RECENTLY_PLAYED -> {
                        if (library.recentlyPlayed.isNotEmpty()) {
                            item(key = "sec_recent_played") {
                                TrackShelfSection(
                                    eyebrow = "RECENT FOOTPRINTS",
                                    title = "Recently Played",
                                    tracks = library.recentlyPlayed.take(10),
                                    compact = sectionCfg.compact,
                                    preferFolderCover = settings.preferFolderCoverArt,
                                    onSeeAll = { onOpenLibrarySection(LibrarySection.RECENTLY_PLAYED) },
                                    onPlayAt = { idx -> onPlayTracks(library.recentlyPlayed, idx) }
                                )
                            }
                        }
                    }
                    HomeSectionId.RECENTLY_ADDED -> {
                        if (library.recentlyAdded.isNotEmpty()) {
                            item(key = "sec_recent_added") {
                                TrackShelfSection(
                                    eyebrow = "FRESH PRESSINGS",
                                    title = "Recently Added",
                                    tracks = library.recentlyAdded.take(10),
                                    compact = sectionCfg.compact,
                                    preferFolderCover = settings.preferFolderCoverArt,
                                    onSeeAll = { onOpenLibrarySection(LibrarySection.RECENTLY_ADDED) },
                                    onPlayAt = { idx -> onPlayTracks(library.recentlyAdded, idx) }
                                )
                            }
                        }
                    }
                    HomeSectionId.MOST_PLAYED -> {
                        if (library.mostPlayed.isNotEmpty()) {
                            item(key = "sec_most_played") {
                                TrackShelfSection(
                                    eyebrow = "HEAVY ROTATION",
                                    title = "Most Played",
                                    tracks = library.mostPlayed.take(10),
                                    compact = sectionCfg.compact,
                                    preferFolderCover = settings.preferFolderCoverArt,
                                    onSeeAll = { onOpenLibrarySection(LibrarySection.MOST_PLAYED) },
                                    onPlayAt = { idx -> onPlayTracks(library.mostPlayed, idx) }
                                )
                            }
                        }
                    }
                    HomeSectionId.FAVORITES -> {
                        if (library.favorites.isNotEmpty()) {
                            item(key = "sec_favorites") {
                                TrackShelfSection(
                                    eyebrow = "KEPT CLOSE",
                                    title = "Favorite Songs",
                                    tracks = library.favorites.take(10),
                                    compact = sectionCfg.compact,
                                    preferFolderCover = settings.preferFolderCoverArt,
                                    onSeeAll = { onOpenLibrarySection(LibrarySection.FAVORITES) },
                                    onPlayAt = { idx -> onPlayTracks(library.favorites, idx) }
                                )
                            }
                        }
                    }
                    HomeSectionId.ALBUMS -> {
                        if (library.albums.isNotEmpty()) {
                            item(key = "sec_albums") {
                                Column {
                                    EditorialSectionHeader(
                                        eyebrow = "${library.albums.size} RELEASES",
                                        title = "Albums",
                                        trailing = {
                                            TextButton(onClick = { onOpenLibrarySection(LibrarySection.ALBUMS) }) {
                                                Text("See all", color = palette.accent)
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(library.albums.take(12)) { album ->
                                            val width = if (sectionCfg.compact) 114.dp else 142.dp
                                            PaperCard(
                                                modifier = Modifier.width(width),
                                                containerColor = palette.paperElevated,
                                                elevation = 3.dp,
                                                onClick = { onOpenAlbum(album.name) },
                                                contentPadding = PaddingValues(8.dp)
                                            ) {
                                                PrintedArtwork(
                                                    trackUri = album.representativeUri,
                                                    folderPath = album.tracks.firstOrNull()?.folderPath.orEmpty(),
                                                    preferFolderCover = settings.preferFolderCoverArt,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1f),
                                                    mattePadding = 3.dp,
                                                    targetSizePx = 280
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Text(album.name, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(album.artist, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HomeSectionId.ARTISTS -> {
                        if (library.artists.isNotEmpty()) {
                            item(key = "sec_artists") {
                                Column {
                                    EditorialSectionHeader(
                                        eyebrow = "${library.artists.size} PERFORMERS",
                                        title = "Artists",
                                        trailing = {
                                            TextButton(onClick = { onOpenLibrarySection(LibrarySection.ARTISTS) }) {
                                                Text("See all", color = palette.accent)
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(library.artists.take(14)) { artist ->
                                            PaperCard(
                                                modifier = Modifier.width(if (sectionCfg.compact) 130.dp else 160.dp),
                                                containerColor = palette.paperElevated,
                                                elevation = 2.dp,
                                                onClick = { onOpenArtist(artist.name) },
                                                contentPadding = PaddingValues(12.dp)
                                            ) {
                                                Text(artist.name, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Spacer(Modifier.height(2.dp))
                                                Text("${artist.tracks.size} tracks · ${artist.albumCount} albums", style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HomeSectionId.GENRES -> {
                        if (library.genres.isNotEmpty()) {
                            item(key = "sec_genres") {
                                Column {
                                    EditorialSectionHeader(
                                        eyebrow = "CATALOGUE",
                                        title = "Genres",
                                        trailing = {
                                            TextButton(onClick = { onOpenLibrarySection(LibrarySection.GENRES) }) {
                                                Text("See all", color = palette.accent)
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(library.genres.take(15)) { genre ->
                                            PaperFilterChip(
                                                label = "${genre.name} (${genre.tracks.size})",
                                                selected = false,
                                                onClick = { onOpenGenre(genre.name) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HomeSectionId.PLAYLISTS -> {
                        if (playlists.isNotEmpty()) {
                            item(key = "sec_playlists") {
                                val trackMap = remember(library.allTracks) { library.allTracks.associateBy { it.uriString } }
                                Column {
                                    EditorialSectionHeader(
                                        eyebrow = "CURATED FOLIOS",
                                        title = "Playlists",
                                        trailing = {
                                            TextButton(onClick = { onOpenLibrarySection(LibrarySection.PLAYLISTS) }) {
                                                Text("See all", color = palette.accent)
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(playlists) { pl ->
                                            val plTracks = pl.trackUris.mapNotNull { trackMap[it] }
                                            PaperCard(
                                                modifier = Modifier.width(if (sectionCfg.compact) 118.dp else 144.dp),
                                                containerColor = palette.paperElevated,
                                                elevation = 3.dp,
                                                onClick = { onOpenPlaylist(pl) },
                                                contentPadding = PaddingValues(8.dp)
                                            ) {
                                                PlaylistArtworkCollage(
                                                    tracks = plTracks,
                                                    customCoverUri = pl.coverUri,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1f)
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Text(pl.name, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${pl.trackUris.size} tracks", style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HomeSectionId.LISTENING_STATS -> {
                        item(key = "sec_stats") {
                            ListeningStatsJournalCard(library = library, compact = sectionCfg.compact)
                        }
                    }
                }
            }
        }
    }

    if (showCustomizeSheet) {
        PaperModalSheet(onDismissRequest = { showCustomizeSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                EditorialSectionHeader(
                    eyebrow = "HOME LAYOUT",
                    title = "Customize Journal",
                    trailing = {
                        TextButton(onClick = onResetSections) {
                            Icon(Icons.Rounded.RestartAlt, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reset", color = palette.accent)
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
                PaperDivider()
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.height(410.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(orderedSections, key = { it.id.name }) { cfg ->
                        PaperCard(
                            containerColor = palette.paperElevated,
                            elevation = 2.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = cfg.id.defaultTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (cfg.visible) palette.inkPrimary else palette.inkTertiary
                                    )
                                    Text(
                                        text = if (cfg.compact) "Compact strip" else "Expanded shelf",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkSecondary
                                    )
                                }
                                IconButton(onClick = { onToggleSectionCompact(cfg.id) }) {
                                    Icon(
                                        imageVector = if (cfg.compact) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                                        contentDescription = "Toggle compact",
                                        tint = palette.inkSecondary
                                    )
                                }
                                IconButton(onClick = { onToggleSectionVisibility(cfg.id) }) {
                                    Icon(
                                        imageVector = if (cfg.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        contentDescription = "Toggle visibility",
                                        tint = if (cfg.visible) palette.accent else palette.inkTertiary
                                    )
                                }
                                IconButton(onClick = { onMoveSection(cfg.id, -1) }) {
                                    Icon(Icons.Rounded.ArrowUpward, "Move up", tint = palette.inkSecondary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onMoveSection(cfg.id, 1) }) {
                                    Icon(Icons.Rounded.ArrowDownward, "Move down", tint = palette.inkSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueListeningCard(
    track: Track,
    isPlaying: Boolean,
    compact: Boolean,
    preferFolderCover: Boolean,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("continue_listening_card"),
        containerColor = palette.paperCream,
        elevation = 5.dp,
        cornerRadius = 8.dp,
        onClick = onClick,
        contentPadding = PaddingValues(if (compact) 12.dp else 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrintedArtwork(
                trackUri = track.uriString,
                customCoverUri = track.customCoverUri,
                folderPath = track.folderPath,
                preferFolderCover = preferFolderCover,
                modifier = Modifier.size(if (compact) 62.dp else 84.dp),
                mattePadding = 4.dp,
                targetSizePx = 256
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .width(1.dp)
                    .height(if (compact) 54.dp else 72.dp)
                    .background(palette.divider)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isPlaying) {
                        AnimatedInkEqualizer(isPlaying = true)
                    }
                    Text(
                        text = if (isPlaying) "FRONT PAGE · SPINNING" else "LEAD FEATURE",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = track.title,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    color = palette.inkPrimary,
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
                if (!compact) {
                    Spacer(Modifier.height(6.dp))
                    InkStampTag(text = track.formatLabel, accented = isPlaying)
                }
            }
            Spacer(Modifier.width(8.dp))
            PaperCard(
                modifier = Modifier.size(44.dp),
                containerColor = palette.accent,
                cornerRadius = 8.dp,
                elevation = 2.dp
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, "Resume", tint = palette.onAccent)
                }
            }
        }
    }
}

@Composable
private fun TrackShelfSection(
    eyebrow: String,
    title: String,
    tracks: List<Track>,
    compact: Boolean,
    preferFolderCover: Boolean,
    onSeeAll: () -> Unit,
    onPlayAt: (Int) -> Unit
) {
    val palette = AriaPaperTheme.palette
    Column {
        EditorialSectionHeader(
            eyebrow = eyebrow,
            title = title,
            trailing = {
                TextButton(onClick = onSeeAll) { Text("See all", color = palette.accent) }
            }
        )
        Spacer(Modifier.height(10.dp))
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tracks.take(3).forEachIndexed { idx, track ->
                    PaperCard(
                        containerColor = palette.paperElevated,
                        elevation = 2.dp,
                        onClick = { onPlayAt(idx) },
                        contentPadding = PaddingValues(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrintedArtwork(
                                trackUri = track.uriString,
                                customCoverUri = track.customCoverUri,
                                folderPath = track.folderPath,
                                preferFolderCover = preferFolderCover,
                                modifier = Modifier.size(44.dp),
                                mattePadding = 2.dp,
                                targetSizePx = 160
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Rounded.PlayArrow, null, tint = palette.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tracks.size) { idx ->
                    val track = tracks[idx]
                    PaperCard(
                        modifier = Modifier.width(138.dp),
                        containerColor = palette.paperElevated,
                        elevation = 3.dp,
                        onClick = { onPlayAt(idx) },
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        PrintedArtwork(
                            trackUri = track.uriString,
                            customCoverUri = track.customCoverUri,
                            folderPath = track.folderPath,
                            preferFolderCover = preferFolderCover,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            mattePadding = 3.dp,
                            targetSizePx = 280
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(track.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningStatsJournalCard(library: LibraryDerivedState, compact: Boolean) {
    val palette = AriaPaperTheme.palette
    val totalPlays = library.allTracks.sumOf { it.playCount }
    val totalListenMin = (library.allTracks.sumOf { it.totalListenMs } / 60_000L)
    val topArtist = library.artists.maxByOrNull { a -> a.tracks.sumOf { it.playCount } }?.name ?: "—"

    Column {
        EditorialSectionHeader(eyebrow = "LISTENING JOURNAL", title = "Statistics")
        Spacer(Modifier.height(10.dp))
        PaperDebossedWell(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCell("Plays Logged", totalPlays.toString(), Modifier.weight(1f))
                    StatCell("Listen Time", "${totalListenMin}m", Modifier.weight(1f))
                    StatCell("Favorites", library.favorites.size.toString(), Modifier.weight(1f))
                }
                if (!compact) {
                    PaperDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("TOP ARTIST", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            Text(topArtist, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("ARCHIVE SIZE", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            Text(formatFileSize(library.diagnostics.totalSizeBytes), style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = modifier,
        containerColor = palette.paperElevated,
        elevation = 1.dp,
        cornerRadius = 10.dp,
        contentPadding = PaddingValues(10.dp)
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = palette.accent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary, maxLines = 1)
    }
}
