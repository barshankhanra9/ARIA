package app.nocturne.player.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.ArtworkShape
import app.nocturne.player.data.model.OnlineAlbumSummary
import app.nocturne.player.data.model.OnlineArtistSummary
import app.nocturne.player.data.model.OnlineDiscoveryCategory
import app.nocturne.player.data.model.OnlineLibrarySection
import app.nocturne.player.data.model.OnlinePlaylist
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.PlaybackMode
import app.nocturne.player.playback.OnlineEngineSnapshot
import app.nocturne.player.ui.OnlineRecommendationsUiState
import app.nocturne.player.ui.OnlineTrendingUiState
import app.nocturne.player.ui.components.AnimatedInkEqualizer
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDebossedWell
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperEmptyState
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.PaperSkeletonList
import app.nocturne.player.ui.components.PlaybackModeSwitcher
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.formatDuration
import app.nocturne.player.ui.theme.AriaPaperTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun OnlineHomeScreen(
    onlineSnapshot: OnlineEngineSnapshot,
    trendingState: OnlineTrendingUiState,
    recommendationsState: OnlineRecommendationsUiState = OnlineRecommendationsUiState(),
    onRepeatTracks: List<OnlineTrack> = emptyList(),
    jumpBackInTracks: List<OnlineTrack> = emptyList(),
    recentlyPlayed: List<OnlineTrack>,
    mostPlayed: List<OnlineTrack>,
    favorites: List<OnlineTrack>,
    playlists: List<OnlinePlaylist>,
    isOfflinePlaying: Boolean,
    onSwitchPlaybackMode: (PlaybackMode) -> Unit,
    onRefreshTrending: () -> Unit,
    onOpenSearch: () -> Unit,
    onSelectCategory: (OnlineDiscoveryCategory) -> Unit,
    onOpenArtist: (String, OnlineProviderType, String?) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    onPlayTracks: (List<OnlineTrack>, Int, Boolean) -> Unit,
    onOpenLibrarySection: (OnlineLibrarySection) -> Unit,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenCuratedPlaylist: (OnlineAlbumSummary) -> Unit = {}
) {
    val palette = AriaPaperTheme.palette
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..21 -> "Good Evening"
            else -> "Late Night Listening"
        }
    }

    val effectiveTrendingTracks = if (trendingState.tracks.isNotEmpty()) {
        trendingState.tracks
    } else {
        recommendationsState.trendingFallback
    }

    val heroTrack = if (trendingState.selectedCategory != OnlineDiscoveryCategory.TRENDING) {
        effectiveTrendingTracks.firstOrNull()
            ?: onlineSnapshot.currentTrack
            ?: jumpBackInTracks.firstOrNull()
            ?: recentlyPlayed.firstOrNull()
    } else {
        onlineSnapshot.currentTrack
            ?: recommendationsState.dailyMix.firstOrNull()
            ?: effectiveTrendingTracks.firstOrNull()
            ?: jumpBackInTracks.firstOrNull()
            ?: recentlyPlayed.firstOrNull()
    }
    val activeFeedLabel = heroTrack?.providerLabel?.takeIf { it.isNotBlank() }
        ?: effectiveTrendingTracks.firstOrNull()?.providerLabel?.takeIf { it.isNotBlank() }
        ?: trendingState.providerUsedLabel
        ?: trendingState.providerUsed?.displayName

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("online_home_screen_list"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "online_header") {
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
                        text = "THE ARIA CHRONICLE · STREAMING EDITION",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
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
                        val subtitleText = buildString {
                            append("Zero-Config Online Radio")
                            activeFeedLabel?.let { label ->
                                append(" · Active Feed: $label")
                            }
                        }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("online_masthead_active_feed")
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    PaperCard(
                        modifier = Modifier.testTag("online_home_refresh_btn"),
                        containerColor = palette.paperElevated,
                        cornerRadius = 8.dp,
                        elevation = 3.dp,
                        onClick = onRefreshTrending,
                        contentPadding = PaddingValues(10.dp)
                    ) {
                        if (trendingState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.2.dp,
                                color = palette.accent
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh online feed",
                                tint = palette.accent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                PlaybackModeSwitcher(
                    selectedMode = PlaybackMode.ONLINE,
                    isOfflinePlaying = isOfflinePlaying,
                    isOnlinePlaying = onlineSnapshot.isPlaying,
                    onSelectMode = onSwitchPlaybackMode
                )
                Spacer(Modifier.height(12.dp))
                PaperDivider(doubleRule = true)
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TactilePaperButton(
                        text = "Search Online",
                        icon = Icons.Rounded.Search,
                        onClick = onOpenSearch,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("online_home_search_btn")
                    )
                    TactilePaperButton(
                        text = "Shuffle Feed",
                        icon = Icons.Rounded.Shuffle,
                        filled = false,
                        enabled = trendingState.tracks.isNotEmpty() || recentlyPlayed.isNotEmpty(),
                        onClick = {
                            val pool = trendingState.tracks.ifEmpty { recentlyPlayed }
                            if (pool.isNotEmpty()) {
                                onPlayTracks(pool, pool.indices.random(), true)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("online_home_shuffle_btn")
                    )
                    PaperCard(
                        modifier = Modifier.testTag("online_home_queue_btn"),
                        containerColor = palette.paperElevated,
                        cornerRadius = 12.dp,
                        elevation = 2.dp,
                        onClick = onOpenQueue,
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Online queue",
                            tint = palette.inkPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Lead Feature Card (dynamically reflects active discovery category or currently playing track)
        if (heroTrack != null) {
            item(key = "online_continue_card") {
                val isHeroCurrent = onlineSnapshot.currentTrack?.id == heroTrack.id
                OnlineContinueCard(
                    track = heroTrack,
                    categoryLabel = trendingState.selectedCategory.label,
                    isCategoryOverride = trendingState.selectedCategory != OnlineDiscoveryCategory.TRENDING,
                    isPlaying = isHeroCurrent && onlineSnapshot.isPlaying,
                    isResolving = isHeroCurrent && (onlineSnapshot.isResolving || onlineSnapshot.isBuffering),
                    onClick = {
                        if (isHeroCurrent && onlineSnapshot.currentTrack != null) {
                            onOpenPlayer()
                        } else {
                            val list = trendingState.tracks.ifEmpty { recentlyPlayed }
                            val idx = list.indexOfFirst { it.id == heroTrack.id }.coerceAtLeast(0)
                            onPlayTracks(list, idx, false)
                        }
                    }
                )
            }
        }

        // Spotify-Style Discovery & Genre Pill Chips (Paper Outlined Aesthetic)
        item(key = "online_discovery_chips") {
            Column {
                EditorialSectionHeader(
                    eyebrow = "DISCOVERY EDITION",
                    title = "Explore Genres & Moods"
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("online_discovery_chips_row")
                ) {
                    items(OnlineDiscoveryCategory.entries, key = { it.name }) { category ->
                        PaperFilterChip(
                            label = category.label,
                            selected = trendingState.selectedCategory == category,
                            onClick = { onSelectCategory(category) },
                            modifier = Modifier.testTag("online_chip_${category.name.lowercase()}")
                        )
                    }
                }
            }
        }

        // Circular Popular Artists Row when Top Artists chip is active
        if (trendingState.selectedCategory == OnlineDiscoveryCategory.TOP_ARTISTS) {
            item(key = "online_popular_artists") {
                Column(modifier = Modifier.testTag("online_popular_artists_section")) {
                    EditorialSectionHeader(
                        eyebrow = "FEATURED VOICES · TOP ARTISTS",
                        title = "Popular Artists"
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        trendingState.isArtistsLoading && trendingState.popularArtists.isEmpty() -> {
                            PaperSkeletonList(count = 2)
                        }
                        trendingState.popularArtists.isNotEmpty() -> {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.testTag("online_popular_artists_row")
                            ) {
                                items(
                                    trendingState.popularArtists,
                                    key = { "${it.providerType.name}:${it.id}" }
                                ) { artist ->
                                    OnlinePopularArtistCard(
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
                        }
                        else -> {
                            PaperEmptyState(
                                icon = Icons.Rounded.Person,
                                title = "No Popular Artists Found",
                                subtitle = "Tap Retry to refresh artist profiles from available sources.",
                                actionLabel = "Retry",
                                onAction = onRefreshTrending
                            )
                        }
                    }
                }
            }
        }

        // Personalized Recommendation Shelves (Daily Mix, Because You Listen To, On Repeat, Jump Back In)
        if (recommendationsState.dailyMix.isNotEmpty()) {
            item(key = "online_daily_mix") {
                Column(modifier = Modifier.testTag("online_daily_mix_section")) {
                    OnlineTrackShelfSection(
                        eyebrow = "DAILY EDITION · 80/20 MIX",
                        title = recommendationsState.dailyMixTitle,
                        tracks = recommendationsState.dailyMix.take(15),
                        onSeeAll = null,
                        onPlayAt = { idx -> onPlayTracks(recommendationsState.dailyMix, idx, false) },
                        rowModifier = Modifier.testTag("online_daily_mix_carousel")
                    )
                    if (recommendationsState.dailyMixSubtitle.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = recommendationsState.dailyMixSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else if (recommendationsState.isLoading && (onRepeatTracks.isNotEmpty() || jumpBackInTracks.isNotEmpty())) {
            item(key = "online_daily_mix_loading") {
                Column {
                    EditorialSectionHeader(
                        eyebrow = "DAILY EDITION · PREPARING MIX",
                        title = "Made For You (Daily Mix)"
                    )
                    Spacer(Modifier.height(10.dp))
                    PaperSkeletonList(count = 2)
                }
            }
        }

        if (!recommendationsState.becauseYouListenToArtist.isNullOrBlank() &&
            recommendationsState.becauseYouListenToTracks.isNotEmpty()
        ) {
            item(key = "online_because_you_listen") {
                OnlineTrackShelfSection(
                    eyebrow = "ARTIST SPOTLIGHT · TASTE MATCH",
                    title = "Because You Listen To ${recommendationsState.becauseYouListenToArtist}",
                    tracks = recommendationsState.becauseYouListenToTracks.take(12),
                    onSeeAll = null,
                    onPlayAt = { idx ->
                        onPlayTracks(recommendationsState.becauseYouListenToTracks, idx, false)
                    },
                    rowModifier = Modifier.testTag("online_because_artist_carousel")
                )
            }
        }

        if (onRepeatTracks.isNotEmpty()) {
            item(key = "online_on_repeat") {
                OnlineTrackShelfSection(
                    eyebrow = "INSTANT ROOM RECALL · TOP ROTATION",
                    title = "On Repeat",
                    tracks = onRepeatTracks.take(12),
                    onSeeAll = { onOpenLibrarySection(OnlineLibrarySection.MOST_PLAYED) },
                    onPlayAt = { idx -> onPlayTracks(onRepeatTracks, idx, false) },
                    rowModifier = Modifier.testTag("online_on_repeat_carousel")
                )
            }
        }

        if (jumpBackInTracks.isNotEmpty()) {
            item(key = "online_jump_back_in") {
                OnlineTrackShelfSection(
                    eyebrow = "INSTANT ROOM RECALL · RECENT",
                    title = "Jump Back In",
                    tracks = jumpBackInTracks.take(12),
                    onSeeAll = { onOpenLibrarySection(OnlineLibrarySection.RECENTLY_PLAYED) },
                    onPlayAt = { idx -> onPlayTracks(jumpBackInTracks, idx, false) },
                    rowModifier = Modifier.testTag("online_jump_back_in_carousel")
                )
            }
        }

        // Trending & Selected Category Single-Track Shelf (always shown; primary fallback for brand-new users)
        item(key = "online_trending") {
            val isDefaultTrending = trendingState.selectedCategory == OnlineDiscoveryCategory.TRENDING
            val shelfLabel = effectiveTrendingTracks.firstOrNull()?.providerLabel?.takeIf { it.isNotBlank() }
                ?: trendingState.providerUsedLabel
                ?: trendingState.providerUsed?.displayName
            val shelfEyebrow = buildString {
                append(if (isDefaultTrending) "TOP CHARTS" else trendingState.selectedCategory.label.uppercase())
                shelfLabel?.let { append(" · ${it.uppercase()}") }
            }
            val shelfTitle = when (trendingState.selectedCategory) {
                OnlineDiscoveryCategory.TRENDING -> "Trending & Popular"
                OnlineDiscoveryCategory.TOP_ARTISTS -> "Popular Artist Singles"
                else -> "${trendingState.selectedCategory.label} Picks"
            }

            Column {
                EditorialSectionHeader(
                    eyebrow = shelfEyebrow,
                    title = shelfTitle,
                    trailing = {
                        TextButton(onClick = onRefreshTrending) {
                            Text("Refresh", color = palette.accent)
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
                when {
                    (trendingState.isLoading || recommendationsState.isLoading) && effectiveTrendingTracks.isEmpty() -> {
                        PaperSkeletonList(count = 3)
                    }
                    effectiveTrendingTracks.isEmpty() && !trendingState.errorMessage.isNullOrBlank() -> {
                        PaperEmptyState(
                            icon = Icons.Rounded.CloudOff,
                            title = "Could Not Load ${trendingState.selectedCategory.label} Feed",
                            subtitle = trendingState.errorMessage,
                            actionLabel = "Retry Providers",
                            onAction = onRefreshTrending
                        )
                    }
                    effectiveTrendingTracks.isNotEmpty() -> {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.testTag("online_trending_carousel")
                        ) {
                            items(effectiveTrendingTracks.take(15), key = { it.id }) { track ->
                                OnlineTrackShelfCard(
                                    track = track,
                                    onClick = {
                                        val idx = effectiveTrendingTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                                        onPlayTracks(effectiveTrendingTracks, idx, false)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // New Releases Carousel
        if (trendingState.newReleases.isNotEmpty()) {
            item(key = "online_new_releases") {
                OnlineTrackShelfSection(
                    eyebrow = "FRESH EDITIONS",
                    title = "New Releases",
                    tracks = trendingState.newReleases.take(15),
                    onSeeAll = null,
                    onPlayAt = { idx -> onPlayTracks(trendingState.newReleases, idx, false) },
                    rowModifier = Modifier.testTag("online_new_releases_carousel")
                )
            }
        }

        // Curated Playlists Carousel
        if (trendingState.curatedPlaylists.isNotEmpty()) {
            item(key = "online_curated_playlists") {
                Column {
                    EditorialSectionHeader(
                        eyebrow = "EDITORIAL PICKS",
                        title = "Curated Playlists"
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.testTag("online_curated_playlists_carousel")
                    ) {
                        items(
                            trendingState.curatedPlaylists.take(15),
                            key = { "${it.providerType.name}:${it.providerId}:${it.id}:${it.url}" }
                        ) { playlist ->
                            OnlineCuratedPlaylistCard(
                                playlist = playlist,
                                onClick = { onOpenCuratedPlaylist(playlist) }
                            )
                        }
                    }
                }
            }
        }

        // Related Recommendations when available from current playback
        if (onlineSnapshot.relatedTracks.isNotEmpty()) {
            item(key = "online_related_recs") {
                OnlineTrackShelfSection(
                    eyebrow = "UP NEXT & SIMILAR",
                    title = "Recommended Tracks",
                    tracks = onlineSnapshot.relatedTracks.take(12),
                    onSeeAll = null,
                    onPlayAt = { idx -> onPlayTracks(onlineSnapshot.relatedTracks, idx, false) }
                )
            }
        }

        if (recentlyPlayed.isNotEmpty()) {
            item(key = "online_recent_played") {
                OnlineTrackShelfSection(
                    eyebrow = "STREAM FOOTPRINTS",
                    title = "Recently Played",
                    tracks = recentlyPlayed.take(12),
                    onSeeAll = { onOpenLibrarySection(OnlineLibrarySection.RECENTLY_PLAYED) },
                    onPlayAt = { idx -> onPlayTracks(recentlyPlayed, idx, false) }
                )
            }
        }

        if (mostPlayed.isNotEmpty()) {
            item(key = "online_most_played") {
                OnlineTrackShelfSection(
                    eyebrow = "HEAVY STREAM ROTATION",
                    title = "Most Played",
                    tracks = mostPlayed.take(12),
                    onSeeAll = { onOpenLibrarySection(OnlineLibrarySection.MOST_PLAYED) },
                    onPlayAt = { idx -> onPlayTracks(mostPlayed, idx, false) }
                )
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "online_favorites") {
                OnlineTrackShelfSection(
                    eyebrow = "BOOKMARKED STREAMS",
                    title = "Online Favorites",
                    tracks = favorites.take(12),
                    onSeeAll = { onOpenLibrarySection(OnlineLibrarySection.FAVORITES) },
                    onPlayAt = { idx -> onPlayTracks(favorites, idx, false) }
                )
            }
        }

        if (playlists.isNotEmpty()) {
            item(key = "online_playlists_shelf") {
                Column {
                    EditorialSectionHeader(
                        eyebrow = "CURATED ONLINE FOLIOS",
                        title = "Online Playlists",
                        trailing = {
                            TextButton(onClick = { onOpenLibrarySection(OnlineLibrarySection.PLAYLISTS) }) {
                                Text("See all", color = palette.accent)
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(playlists, key = { it.id }) { pl ->
                            PaperCard(
                                modifier = Modifier.width(142.dp),
                                containerColor = palette.paperElevated,
                                elevation = 3.dp,
                                onClick = { onOpenPlaylist(pl) },
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                PrintedArtwork(
                                    trackUri = pl.coverUrl,
                                    customCoverUri = pl.coverUrl,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    mattePadding = 3.dp,
                                    targetSizePx = 280
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(pl.name, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${pl.tracks.size} tracks", style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary)
                            }
                        }
                    }
                }
            }
        }

        item(key = "online_stats") {
            val totalPlays = recentlyPlayed.sumOf { it.playCount }.coerceAtLeast(mostPlayed.sumOf { it.playCount })
            val totalListenMin = (mostPlayed.sumOf { it.totalListenMs } / 60_000L)
            val topArtist = mostPlayed.firstOrNull()?.artist ?: "—"
            Column {
                EditorialSectionHeader(eyebrow = "ONLINE JOURNAL", title = "Stream Statistics")
                Spacer(Modifier.height(10.dp))
                PaperDebossedWell(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OnlineStatCell("Streams", totalPlays.toString(), Modifier.weight(1f))
                            OnlineStatCell("Listen Time", "${totalListenMin}m", Modifier.weight(1f))
                            OnlineStatCell("Favorites", favorites.size.toString(), Modifier.weight(1f))
                        }
                        PaperDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("TOP STREAMED ARTIST", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                                Text(topArtist, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("PLAYLISTS", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                                Text("${playlists.size} saved", style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineContinueCard(
    track: OnlineTrack,
    categoryLabel: String,
    isCategoryOverride: Boolean,
    isPlaying: Boolean,
    isResolving: Boolean,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("online_continue_card"),
        containerColor = palette.paperCream,
        elevation = 5.dp,
        cornerRadius = 8.dp,
        onClick = onClick,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrintedArtwork(
                trackUri = track.thumbnailUrl,
                customCoverUri = track.thumbnailUrl,
                modifier = Modifier.size(84.dp),
                mattePadding = 4.dp,
                targetSizePx = 500
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .width(1.dp)
                    .height(72.dp)
                    .background(palette.divider)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isPlaying) AnimatedInkEqualizer(isPlaying = true)
                    Text(
                        text = when {
                            isResolving -> "STREAMING · RESOLVING"
                            isPlaying -> "ONLINE · PLAYING NOW"
                            isCategoryOverride -> "ONLINE FEATURE · ${categoryLabel.uppercase()}"
                            else -> "ONLINE FEATURE"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InkStampTag(text = track.effectiveBadge(), accented = isPlaying)
                    if (track.durationMs > 0L) {
                        Text(
                            text = formatDuration(track.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary,
                            maxLines = 1
                        )
                    }
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
                    Icon(Icons.Rounded.PlayArrow, "Play online track", tint = palette.onAccent)
                }
            }
        }
    }
}

@Composable
private fun OnlinePopularArtistCard(
    artist: OnlineArtistSummary,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = Modifier
            .width(124.dp)
            .testTag("online_popular_artist_${artist.id}"),
        containerColor = palette.paperElevated,
        elevation = 3.dp,
        cornerRadius = 14.dp,
        onClick = onClick,
        contentPadding = PaddingValues(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PrintedArtwork(
                trackUri = artist.thumbnailUrl,
                customCoverUri = artist.thumbnailUrl,
                artworkShape = ArtworkShape.VINYL_SLEEVE,
                mattePadding = 3.dp,
                targetSizePx = 256,
                contentDescription = "${artist.name} portrait",
                modifier = Modifier.size(84.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleSmall,
                color = palette.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (artist.subscriberText.isNotBlank()) {
                Text(
                    text = artist.subscriberText.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(4.dp))
            val artistBadge = if (artist.providerType == OnlineProviderType.DYNAMIC) {
                artist.providerId.ifBlank { artist.providerType.badge }.uppercase()
            } else {
                artist.providerType.badge
            }
            InkStampTag(text = artistBadge, accented = false)
        }
    }
}

@Composable
private fun OnlineTrackShelfSection(
    eyebrow: String,
    title: String,
    tracks: List<OnlineTrack>,
    onSeeAll: (() -> Unit)?,
    onPlayAt: (Int) -> Unit,
    rowModifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    Column {
        EditorialSectionHeader(
            eyebrow = eyebrow,
            title = title,
            trailing = if (onSeeAll != null) {
                { TextButton(onClick = onSeeAll) { Text("See all", color = palette.accent) } }
            } else null
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = rowModifier
        ) {
            items(tracks.size) { idx ->
                OnlineTrackShelfCard(
                    track = tracks[idx],
                    onClick = { onPlayAt(idx) }
                )
            }
        }
    }
}

@Composable
private fun OnlineCuratedPlaylistCard(
    playlist: OnlineAlbumSummary,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = Modifier
            .width(148.dp)
            .testTag("online_curated_playlist_${playlist.id}"),
        containerColor = palette.paperElevated,
        elevation = 3.dp,
        onClick = onClick,
        contentPadding = PaddingValues(8.dp)
    ) {
        PrintedArtwork(
            trackUri = playlist.thumbnailUrl,
            customCoverUri = playlist.thumbnailUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            mattePadding = 3.dp,
            targetSizePx = 500,
            contentDescription = "${playlist.title} playlist artwork"
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.title,
            style = MaterialTheme.typography.titleSmall,
            color = palette.inkPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = playlist.artist.ifBlank { playlist.providerType.displayName },
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            InkStampTag(text = "PLAYLIST", accented = true)
            if (playlist.trackCount > 0) {
                Text(
                    text = "${playlist.trackCount} tracks",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun OnlineTrackShelfCard(
    track: OnlineTrack,
    onClick: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = Modifier.width(142.dp),
        containerColor = palette.paperElevated,
        elevation = 3.dp,
        onClick = onClick,
        contentPadding = PaddingValues(8.dp)
    ) {
        PrintedArtwork(
            trackUri = track.thumbnailUrl,
            customCoverUri = track.thumbnailUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            mattePadding = 3.dp,
            targetSizePx = 500
        )
        Spacer(Modifier.height(8.dp))
        Text(track.title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            InkStampTag(text = track.effectiveBadge(), accented = false)
            if (track.durationMs > 0L) {
                Text(
                    text = formatDuration(track.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun OnlineStatCell(label: String, value: String, modifier: Modifier = Modifier) {
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
