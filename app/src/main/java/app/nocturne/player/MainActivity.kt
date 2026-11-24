package app.nocturne.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nocturne.player.data.model.LibrarySection
import app.nocturne.player.data.model.OnlineLibrarySection
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.PlaybackMode
import app.nocturne.player.data.model.Playlist
import app.nocturne.player.data.model.Track
import app.nocturne.player.playback.ActiveAudioEngine
import app.nocturne.player.ui.MainViewModel
import app.nocturne.player.ui.PlaybackUiState
import app.nocturne.player.ui.components.AddToOnlinePlaylistDialog
import app.nocturne.player.ui.components.AddToPlaylistDialog
import app.nocturne.player.ui.components.MiniPlayerBar
import app.nocturne.player.ui.components.OnlineTrackContextMenuSheet
import app.nocturne.player.ui.components.OnlineTrackDetailsDialog
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.TrackContextMenuSheet
import app.nocturne.player.ui.components.TrackDetailsDialog
import app.nocturne.player.ui.components.paperGrainBackground
import app.nocturne.player.ui.screens.EqualizerAndAudioFxDialog
import app.nocturne.player.ui.screens.HomeScreen
import app.nocturne.player.ui.screens.LibraryDrillTarget
import app.nocturne.player.ui.screens.LibraryScreen
import app.nocturne.player.ui.screens.LyricsReaderDialog
import app.nocturne.player.ui.screens.NowPlayingScreen
import app.nocturne.player.ui.screens.OnlineAlbumDetailScreen
import app.nocturne.player.ui.screens.OnlineArtistDetailScreen
import app.nocturne.player.ui.screens.OnlineHomeScreen
import app.nocturne.player.ui.screens.OnlineLibraryScreen
import app.nocturne.player.ui.screens.OnlinePlaylistViewTarget
import app.nocturne.player.ui.screens.OnlinePlaylistsScreen
import app.nocturne.player.ui.screens.OnlineSearchScreen
import app.nocturne.player.ui.screens.PlaylistViewTarget
import app.nocturne.player.ui.screens.PlaylistsScreen
import app.nocturne.player.ui.screens.QueueManagementSheet
import app.nocturne.player.ui.screens.RelatedTracksSheet
import app.nocturne.player.ui.screens.SearchScreen
import app.nocturne.player.ui.screens.SettingsScreen
import app.nocturne.player.ui.screens.TagEditorScreen
import app.nocturne.player.ui.theme.AriaPaperTheme
import app.nocturne.player.ui.theme.ModernPaperTheme

private enum class AppTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("Home", Icons.Rounded.Home, Icons.Outlined.Home),
    LIBRARY("Library", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
    SEARCH("Search", Icons.Rounded.Search, Icons.Outlined.Search),
    PLAYLISTS("Playlists", Icons.AutoMirrored.Rounded.PlaylistPlay, Icons.AutoMirrored.Outlined.QueueMusic),
    SETTINGS("Settings", Icons.Rounded.Settings, Icons.Outlined.Settings)
}

private data class OnlineNavTarget(
    val idOrUrl: String,
    val providerType: OnlineProviderType,
    val providerId: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AriaAppRoot()
        }
    }
}

@Composable
fun AriaAppRoot(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playbackMode by viewModel.playbackMode.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val displayedPlayback by viewModel.displayedPlayback.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val selectedUris by viewModel.selectedTrackUris.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val sleepTimer by viewModel.sleepTimer.collectAsStateWithLifecycle()
    val lyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
    val presetPreview by viewModel.presetBandPreview.collectAsStateWithLifecycle()
    val remoteConfig by viewModel.remoteConfig.collectAsStateWithLifecycle()
    val remoteConfigStatus by viewModel.remoteConfigStatus.collectAsStateWithLifecycle()
    val isRefreshingRemoteConfig by viewModel.isRefreshingRemoteConfig.collectAsStateWithLifecycle()
    val lastRemoteConfigError by viewModel.lastRemoteConfigError.collectAsStateWithLifecycle()
    val remoteConfigConnectionState by viewModel.remoteConfigConnectionState.collectAsStateWithLifecycle()

    val onlineSnapshot by viewModel.online.snapshot.collectAsStateWithLifecycle()
    val onlineTrending by viewModel.online.trendingState.collectAsStateWithLifecycle()
    val onlineSearch by viewModel.online.searchState.collectAsStateWithLifecycle()
    val onlineArtistDetail by viewModel.online.artistDetailState.collectAsStateWithLifecycle()
    val onlineAlbumDetail by viewModel.online.albumDetailState.collectAsStateWithLifecycle()
    val onlineAllTracks by viewModel.online.allTracks.collectAsStateWithLifecycle()
    val onlineFavorites by viewModel.online.favorites.collectAsStateWithLifecycle()
    val onlineRecentlyPlayed by viewModel.online.recentlyPlayed.collectAsStateWithLifecycle()
    val onlineMostPlayed by viewModel.online.mostPlayed.collectAsStateWithLifecycle()
    val onlineHistory by viewModel.online.history.collectAsStateWithLifecycle()
    val onlinePlaylists by viewModel.online.playlists.collectAsStateWithLifecycle()
    val onlineRecentSearches by viewModel.online.recentSearches.collectAsStateWithLifecycle()
    val providerDiagnostics by viewModel.online.providerDiagnostics.collectAsStateWithLifecycle()
    val providerTestResults by viewModel.online.providerTestResults.collectAsStateWithLifecycle()
    val isTestingProviders by viewModel.online.isTestingProviders.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var librarySection by rememberSaveable { mutableStateOf(LibrarySection.ALL_SONGS) }
    var libraryDrill by remember { mutableStateOf<LibraryDrillTarget?>(null) }
    var playlistTarget by remember { mutableStateOf<PlaylistViewTarget?>(null) }

    var onlineLibrarySection by rememberSaveable { mutableStateOf(OnlineLibrarySection.FAVORITES) }
    var onlinePlaylistTarget by remember { mutableStateOf<OnlinePlaylistViewTarget?>(null) }
    var lastArtistNav by remember { mutableStateOf<OnlineNavTarget?>(null) }
    var lastAlbumNav by remember { mutableStateOf<OnlineNavTarget?>(null) }

    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showOnlineQueueSheet by remember { mutableStateOf(false) }
    var showRelatedSheet by remember { mutableStateOf(false) }
    var showLyricsDialog by remember { mutableStateOf(false) }
    var showAudioFxDialog by remember { mutableStateOf(false) }
    var tagEditorTrack by remember { mutableStateOf<Track?>(null) }
    var contextMenuTrack by remember { mutableStateOf<Track?>(null) }
    var onlineContextMenuTrack by remember { mutableStateOf<OnlineTrack?>(null) }
    var detailsTrack by remember { mutableStateOf<Track?>(null) }
    var onlineDetailsTrack by remember { mutableStateOf<OnlineTrack?>(null) }
    var addToPlaylistTracks by remember { mutableStateOf<List<Track>?>(null) }
    var addToOnlinePlaylistTracks by remember { mutableStateOf<List<OnlineTrack>?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.uiMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val requiredAudioPermission = remember {
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasAudioPermission by remember {
        mutableStateOf(hasAudioScanPermission(context))
    }
    var requestedPermissionsOnce by rememberSaveable { mutableStateOf(false) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[requiredAudioPermission] == true || hasAudioScanPermission(context)
        hasAudioPermission = granted
        if (granted) {
            viewModel.rescanLibrary()
        }
    }

    val launchPermissionOrSettings: () -> Unit = {
        val needed = buildList {
            if (!hasAudioScanPermission(context)) add(requiredAudioPermission)
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val activity = context as? Activity
        val permanentlyDenied = requestedPermissionsOnce &&
            activity != null &&
            !hasAudioScanPermission(context) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, requiredAudioPermission)

        if (permanentlyDenied) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        } else if (needed.isNotEmpty()) {
            requestedPermissionsOnce = true
            permissionsLauncher.launch(needed.toTypedArray())
        } else {
            viewModel.rescanLibrary()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.addSafFolder(uri)
    }

    val audioFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addSinglePickedAudioUri(uri)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val grantedNow = hasAudioScanPermission(context)
                hasAudioPermission = grantedNow
                if (grantedNow) {
                    viewModel.rescanLibrary()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val grantedNow = hasAudioScanPermission(context)
        hasAudioPermission = grantedNow
        if (grantedNow) {
            viewModel.rescanLibrary()
        }
        val missing = buildList {
            if (!grantedNow) add(requiredAudioPermission)
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            requestedPermissionsOnce = true
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    ModernPaperTheme(
        themeMode = settings.themeMode,
        accent = settings.accent,
        paperTextureIntensity = settings.paperTextureIntensity,
        artworkShape = settings.artworkShape,
        animationIntensity = settings.animationIntensity,
        fontScaleMultiplier = settings.fontScaleMultiplier
    ) {
        val palette = AriaPaperTheme.palette
        val motion = AriaPaperTheme.motion

        Box(
            modifier = Modifier
                .fillMaxSize()
                .paperGrainBackground(palette.paperBackground)
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.navigationBars,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Column(
                        modifier = Modifier.paperGrainBackground(palette.paperCream)
                    ) {
                        val activePlaybackUi = displayedPlayback.playbackUi
                        MiniPlayerBar(
                            visible = !showNowPlaying && activePlaybackUi.currentTrack != null,
                            track = activePlaybackUi.currentTrack,
                            isPlaying = activePlaybackUi.isPlaying,
                            positionMs = activePlaybackUi.positionMs,
                            durationMs = activePlaybackUi.durationMs,
                            preferFolderCover = settings.preferFolderCoverArt,
                            onExpand = { showNowPlaying = true },
                            onTogglePlay = { viewModel.toggleDisplayedPlayPause() },
                            onNext = { viewModel.skipDisplayedNext() },
                            onPrevious = { viewModel.skipDisplayedPrevious() },
                            isBuffering = displayedPlayback.isBuffering || displayedPlayback.isResolving,
                            errorMessage = displayedPlayback.errorMessage
                        )
                        PaperDivider(doubleRule = true)
                        NavigationBar(
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp
                        ) {
                            AppTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentTab = tab },
                                    modifier = Modifier.testTag("nav_tab_${tab.name}"),
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = palette.accent,
                                        selectedTextColor = palette.accent,
                                        indicatorColor = palette.accentSoft,
                                        unselectedIconColor = palette.inkSecondary,
                                        unselectedTextColor = palette.inkSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(innerPadding)
                ) {
                    BackHandler(
                        enabled = currentTab != AppTab.HOME &&
                            !showNowPlaying &&
                            !showAudioFxDialog &&
                            !showLyricsDialog &&
                            tagEditorTrack == null &&
                            libraryDrill == null &&
                            playlistTarget == null &&
                            onlinePlaylistTarget == null &&
                            onlineArtistDetail.page == null && !onlineArtistDetail.isLoading &&
                            onlineAlbumDetail.page == null && !onlineAlbumDetail.isLoading
                    ) {
                        currentTab = AppTab.HOME
                    }

                    AnimatedContent(
                        targetState = Pair(playbackMode, currentTab),
                        transitionSpec = {
                            if (motion.isReduced) {
                                fadeIn(tween(60)) togetherWith fadeOut(tween(40))
                            } else {
                                val forward = targetState.second.ordinal >= initialState.second.ordinal
                                val dir = if (forward) 1 else -1
                                (slideInHorizontally(tween(motion.durationMs, easing = FastOutSlowInEasing)) { dir * it / 6 } +
                                    fadeIn(tween(motion.durationMs))) togetherWith
                                    (slideOutHorizontally(tween(motion.durationMs / 2, easing = FastOutSlowInEasing)) { -dir * it / 10 } +
                                        fadeOut(tween(motion.durationMs / 2)))
                            }
                        },
                        label = "page_turn_transition"
                    ) { (mode, tab) ->
                        when (tab) {
                            AppTab.HOME -> if (mode == PlaybackMode.ONLINE) {
                                OnlineHomeScreen(
                                    onlineSnapshot = onlineSnapshot,
                                    trendingState = onlineTrending,
                                    recentlyPlayed = onlineRecentlyPlayed,
                                    mostPlayed = onlineMostPlayed,
                                    favorites = onlineFavorites,
                                    playlists = onlinePlaylists,
                                    isOfflinePlaying = playback.isPlaying,
                                    onSwitchPlaybackMode = { viewModel.switchPlaybackMode(it) },
                                    onRefreshTrending = { viewModel.online.refreshTrending() },
                                    onOpenSearch = { currentTab = AppTab.SEARCH },
                                    onSelectCategory = { category -> viewModel.online.selectDiscoveryCategory(category) },
                                    onOpenArtist = { urlOrId, p, pid ->
                                        lastArtistNav = OnlineNavTarget(urlOrId, p, pid)
                                        viewModel.online.openArtist(urlOrId, p, pid)
                                    },
                                    onOpenPlayer = { showNowPlaying = true },
                                    onOpenQueue = { showOnlineQueueSheet = true },
                                    onPlayTracks = { tracks, idx, shuf -> viewModel.online.playTracks(tracks, idx, shuf) },
                                    onOpenLibrarySection = { sec ->
                                        onlineLibrarySection = sec
                                        currentTab = AppTab.LIBRARY
                                    },
                                    onOpenPlaylist = { pl ->
                                        onlinePlaylistTarget = OnlinePlaylistViewTarget.Custom(pl.id)
                                        currentTab = AppTab.PLAYLISTS
                                    },
                                    onOpenCuratedPlaylist = { al ->
                                        val target = al.url.ifBlank { al.id }
                                        lastAlbumNav = OnlineNavTarget(target, al.providerType, al.providerId)
                                        viewModel.online.openAlbum(target, al.providerType, al.providerId)
                                    }
                                )
                            } else {
                                HomeScreen(
                                    library = library,
                                    playback = playback,
                                    playlists = playlists,
                                    settings = settings,
                                    hasAudioPermission = hasAudioPermission,
                                    onRequestAudioPermission = launchPermissionOrSettings,
                                    onRescanLibrary = { viewModel.rescanLibrary() },
                                    onShuffleAll = { viewModel.shuffleAll() },
                                    onOpenLibrarySection = { sec ->
                                        librarySection = sec
                                        libraryDrill = null
                                        currentTab = AppTab.LIBRARY
                                    },
                                    onOpenSearch = { currentTab = AppTab.SEARCH },
                                    onOpenPlayer = { showNowPlaying = true },
                                    onPlayTracks = { tracks, idx -> viewModel.playTracks(tracks, idx) },
                                    onOpenAlbum = { name ->
                                        librarySection = LibrarySection.ALBUMS
                                        libraryDrill = LibraryDrillTarget.AlbumDetail(name)
                                        currentTab = AppTab.LIBRARY
                                    },
                                    onOpenArtist = { name ->
                                        librarySection = LibrarySection.ARTISTS
                                        libraryDrill = LibraryDrillTarget.ArtistDetail(name)
                                        currentTab = AppTab.LIBRARY
                                    },
                                    onOpenGenre = { name ->
                                        librarySection = LibrarySection.GENRES
                                        libraryDrill = LibraryDrillTarget.GenreDetail(name)
                                        currentTab = AppTab.LIBRARY
                                    },
                                    onOpenPlaylist = { pl ->
                                        playlistTarget = PlaylistViewTarget.Custom(pl.id)
                                        currentTab = AppTab.PLAYLISTS
                                    },
                                    onPickMusicFile = { audioFilePicker.launch(arrayOf("audio/*")) },
                                    onPickMusicFolder = { folderPicker.launch(null) },
                                    onMoveSection = { id, dir -> viewModel.moveHomeSection(id, dir) },
                                    onToggleSectionVisibility = { id -> viewModel.toggleHomeSectionVisibility(id) },
                                    onToggleSectionCompact = { id -> viewModel.toggleHomeSectionCompact(id) },
                                    onResetSections = { viewModel.resetHomeSections() },
                                    playbackMode = playbackMode,
                                    isOnlinePlaying = onlineSnapshot.isPlaying,
                                    onSwitchPlaybackMode = { viewModel.switchPlaybackMode(it) }
                                )
                            }

                            AppTab.LIBRARY -> if (mode == PlaybackMode.ONLINE) {
                                OnlineLibraryScreen(
                                    section = onlineLibrarySection,
                                    favorites = onlineFavorites,
                                    history = onlineHistory,
                                    recentlyPlayed = onlineRecentlyPlayed,
                                    mostPlayed = onlineMostPlayed,
                                    allTracks = onlineAllTracks,
                                    playlists = onlinePlaylists,
                                    onlineSnapshot = onlineSnapshot,
                                    onSelectSection = { onlineLibrarySection = it },
                                    onPlayTracks = { list, idx, shuf -> viewModel.online.playTracks(list, idx, shuf) },
                                    onToggleFavorite = { viewModel.online.toggleFavorite(it) },
                                    onOpenContextMenu = { onlineContextMenuTrack = it },
                                    onClearHistory = { viewModel.online.clearListeningHistory() },
                                    onJumpQueueIndex = { viewModel.online.jumpToQueueIndex(it) },
                                    onRemoveQueueItem = { viewModel.online.removeQueueItem(it) },
                                    onClearQueue = { viewModel.online.clearQueue() },
                                    onOpenQueueSheet = { showOnlineQueueSheet = true },
                                    onOpenArtist = { urlOrId, p, pid ->
                                        lastArtistNav = OnlineNavTarget(urlOrId, p, pid)
                                        viewModel.online.openArtist(urlOrId, p, pid)
                                    },
                                    onOpenPlaylist = { pl ->
                                        onlinePlaylistTarget = OnlinePlaylistViewTarget.Custom(pl.id)
                                        currentTab = AppTab.PLAYLISTS
                                    }
                                )
                            } else {
                                LibraryScreen(
                                    section = librarySection,
                                    drillTarget = libraryDrill,
                                    library = library,
                                    playback = playback,
                                    playlists = playlists,
                                    settings = settings,
                                    selectedUris = selectedUris,
                                    hasAudioPermission = hasAudioPermission,
                                    onRequestAudioPermission = launchPermissionOrSettings,
                                    onSelectSection = {
                                        librarySection = it
                                        libraryDrill = null
                                    },
                                    onSetDrillTarget = { libraryDrill = it },
                                    onSortTracks = { list, f, o -> viewModel.sortTracks(list, f, o) },
                                    onUpdateSort = { sec, f, o ->
                                        viewModel.updateSettings { s ->
                                            s.copy(
                                                sectionSortFieldOverrides = s.sectionSortFieldOverrides + (sec.name to f),
                                                sectionSortOrderOverrides = s.sectionSortOrderOverrides + (sec.name to o)
                                            )
                                        }
                                    },
                                    onToggleGridForSection = { sec ->
                                        viewModel.updateSettings { s ->
                                            val cur = s.sectionGridOverrides[sec.name] ?: s.defaultGridLayout
                                            s.copy(sectionGridOverrides = s.sectionGridOverrides + (sec.name to !cur))
                                        }
                                    },
                                    onPlayTracks = { list, idx, shuf -> viewModel.playTracks(list, idx, shuf) },
                                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                                    onToggleSelectTrack = { viewModel.toggleTrackSelection(it) },
                                    onSelectAll = { viewModel.selectAllTracks(it) },
                                    onClearSelection = { viewModel.clearSelection() },
                                    onBatchPlayNext = { viewModel.playNext(it) },
                                    onBatchAddToQueue = { viewModel.addToQueue(it) },
                                    onBatchAddToPlaylist = { addToPlaylistTracks = it },
                                    onBatchFavorite = { viewModel.favoriteSelected() },
                                    onOpenContextMenu = { contextMenuTrack = it },
                                    onOpenPlaylist = { pl ->
                                        playlistTarget = PlaylistViewTarget.Custom(pl.id)
                                        currentTab = AppTab.PLAYLISTS
                                    },
                                    onOpenQueueSheet = { showQueueSheet = true },
                                    onRescanLibrary = { viewModel.rescanLibrary() },
                                    onPickMusicFolder = { folderPicker.launch(null) },
                                    onPickAudioFile = { audioFilePicker.launch(arrayOf("audio/*")) },
                                    onToggleExcludeFolder = { viewModel.toggleExcludeFolder(it) }
                                )
                            }

                            AppTab.SEARCH -> if (mode == PlaybackMode.ONLINE) {
                                OnlineSearchScreen(
                                    state = onlineSearch,
                                    recentSearches = onlineRecentSearches,
                                    currentPlayingTrackId = onlineSnapshot.currentTrack?.id,
                                    onQueryChange = { viewModel.online.updateSearchQuery(it) },
                                    onSelectFilter = { viewModel.online.selectSearchFilter(it) },
                                    onExecuteSearch = { viewModel.online.executeSearch(it) },
                                    onLoadNextPage = { viewModel.online.loadNextSearchPage() },
                                    onClearSearch = { viewModel.online.clearSearch() },
                                    onDeleteRecentSearch = { viewModel.online.deleteRecentSearch(it) },
                                    onClearSearchHistory = { viewModel.online.clearSearchHistory() },
                                    onPlayTracks = { list, idx -> viewModel.online.playTracks(list, idx, false) },
                                    onToggleFavorite = { viewModel.online.toggleFavorite(it) },
                                    onOpenContextMenu = { onlineContextMenuTrack = it },
                                    onOpenArtist = { urlOrId, p, pid ->
                                        lastArtistNav = OnlineNavTarget(urlOrId, p, pid)
                                        viewModel.online.openArtist(urlOrId, p, pid)
                                    },
                                    onOpenAlbum = { urlOrId, p, pid ->
                                        lastAlbumNav = OnlineNavTarget(urlOrId, p, pid)
                                        viewModel.online.openAlbum(urlOrId, p, pid)
                                    }
                                )
                            } else {
                                SearchScreen(
                                    library = library,
                                    playlists = playlists,
                                    recentSearches = recentSearches,
                                    preferFolderCover = settings.preferFolderCoverArt,
                                    onRecordSearch = { viewModel.recordSearch(it) },
                                    onDeleteRecentSearch = { viewModel.deleteRecentSearch(it) },
                                    onClearSearchHistory = { viewModel.clearSearchHistory() },
                                    onPlayTrack = { list, idx -> viewModel.playTracks(list, idx) },
                                    onOpenAlbum = { name ->
                                        librarySection = LibrarySection.ALBUMS
                                        libraryDrill = LibraryDrillTarget.AlbumDetail(name)
                                        currentTab = AppTab.LIBRARY
                                    },
                                    onOpenArtist = { name ->
                                        librarySection = LibrarySection.ARTISTS
                                        libraryDrill = LibraryDrillTarget.ArtistDetail(name)
                                        currentTab = AppTab.LIBRARY
                                    },
                                    onOpenGenre = { name ->
                                        librarySection = LibrarySection.GENRES
                                        libraryDrill = LibraryDrillTarget.GenreDetail(name)
                                        currentTab = AppTab.LIBRARY
                                    },
                                    onOpenPlaylist = { pl: Playlist ->
                                        playlistTarget = PlaylistViewTarget.Custom(pl.id)
                                        currentTab = AppTab.PLAYLISTS
                                    },
                                    onOpenFolder = { path ->
                                        librarySection = LibrarySection.FOLDERS
                                        libraryDrill = LibraryDrillTarget.FolderDetail(path)
                                        currentTab = AppTab.LIBRARY
                                    }
                                )
                            }

                            AppTab.PLAYLISTS -> if (mode == PlaybackMode.ONLINE) {
                                OnlinePlaylistsScreen(
                                    playlists = onlinePlaylists,
                                    selectedTarget = onlinePlaylistTarget,
                                    onSelectTarget = { onlinePlaylistTarget = it },
                                    onResolveSmart = { viewModel.online.resolveSmartPlaylist(it) },
                                    onPlayTracks = { list, idx, shuf -> viewModel.online.playTracks(list, idx, shuf) },
                                    onCreatePlaylist = { name, desc -> viewModel.online.createPlaylist(name, desc) },
                                    onRenamePlaylist = { id, n, d -> viewModel.online.renamePlaylist(id, n, d) },
                                    onDeletePlaylist = { viewModel.online.deletePlaylist(it) },
                                    onRemoveTrackFromPlaylist = { id, idx -> viewModel.online.removeTrackFromPlaylist(id, idx) },
                                    onReorderPlaylistTracks = { id, f, t -> viewModel.online.reorderPlaylistTracks(id, f, t) }
                                )
                            } else {
                                PlaylistsScreen(
                                    library = library,
                                    playlists = playlists,
                                    selectedTarget = playlistTarget,
                                    preferFolderCover = settings.preferFolderCoverArt,
                                    onSelectTarget = { playlistTarget = it },
                                    onResolveSmart = { viewModel.resolveSmartPlaylist(it) },
                                    onPlayTracks = { list, idx, shuf -> viewModel.playTracks(list, idx, shuf) },
                                    onCreatePlaylist = { name, desc -> viewModel.createPlaylist(name, desc) },
                                    onRenamePlaylist = { id, n, d -> viewModel.renamePlaylist(id, n, d) },
                                    onDeletePlaylist = { viewModel.deletePlaylist(it) },
                                    onUpdatePlaylistCover = { id, c -> viewModel.updatePlaylistCover(id, c) },
                                    onRemoveTrackFromPlaylist = { id, idx -> viewModel.removeTrackFromPlaylist(id, idx) },
                                    onReorderPlaylistTracks = { id, f, t -> viewModel.reorderPlaylistTracks(id, f, t) },
                                    onExportPlaylistM3u8 = { pl, uri -> viewModel.exportPlaylistM3u8(pl, uri) },
                                    onImportPlaylistM3u8 = { uri -> viewModel.importPlaylistM3u8(uri) }
                                )
                            }

                            AppTab.SETTINGS -> SettingsScreen(
                                settings = settings,
                                telemetry = telemetry,
                                onUpdateSettings = { viewModel.updateSettings(it) },
                                onOpenEqualizer = { showAudioFxDialog = true },
                                onRescanLibrary = { viewModel.rescanLibrary() },
                                onPickMusicFolder = { folderPicker.launch(null) },
                                onRemoveSafFolder = { viewModel.removeSafFolder(it) },
                                onToggleExcludeFolder = { viewModel.toggleExcludeFolder(it) },
                                onExportBackup = { viewModel.exportFullBackup(it) },
                                onImportBackup = { viewModel.importFullBackup(it) },
                                remoteConfig = remoteConfig,
                                remoteConfigStatus = remoteConfigStatus,
                                providerDiagnostics = providerDiagnostics,
                                providerTestResults = providerTestResults,
                                isTestingProviders = isTestingProviders,
                                isRefreshingRemoteConfig = isRefreshingRemoteConfig,
                                lastRemoteConfigError = lastRemoteConfigError,
                                remoteConfigConnectionState = remoteConfigConnectionState,
                                onSaveRemoteConfigUrl = { viewModel.saveRemoteConfigUrl(it) },
                                onTestRemoteConfigUrl = { viewModel.testRemoteConfigUrl(it) },
                                onClearRemoteConfigFeedback = { viewModel.clearRemoteConfigConnectionState() },
                                onRefreshRemoteConfig = { viewModel.refreshRemoteConfig(it) },
                                onRollbackRemoteConfig = { viewModel.rollbackRemoteConfig() },
                                onTestProvider = { viewModel.online.testProviderConnectivity(it) },
                                onTestAllProviders = { viewModel.online.testAllProvidersConnectivity() }
                            )
                        }
                    }
                }
            }

            // Online Artist & Album Detail Overlays
            AnimatedVisibility(
                visible = onlineArtistDetail.isLoading || onlineArtistDetail.page != null || onlineArtistDetail.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OnlineArtistDetailScreen(
                    state = onlineArtistDetail,
                    currentPlayingTrackId = onlineSnapshot.currentTrack?.id,
                    onBack = { viewModel.online.clearArtistDetail() },
                    onRetry = lastArtistNav?.let { nav -> { viewModel.online.openArtist(nav.idOrUrl, nav.providerType, nav.providerId) } },
                    onPlayTracks = { list, idx, shuf -> viewModel.online.playTracks(list, idx, shuf) },
                    onToggleFavorite = { viewModel.online.toggleFavorite(it) },
                    onOpenContextMenu = { onlineContextMenuTrack = it },
                    onOpenAlbum = { idOrUrl, prov, pid ->
                        lastAlbumNav = OnlineNavTarget(idOrUrl, prov, pid)
                        viewModel.online.openAlbum(idOrUrl, prov, pid)
                    }
                )
            }

            AnimatedVisibility(
                visible = onlineAlbumDetail.isLoading || onlineAlbumDetail.page != null || onlineAlbumDetail.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OnlineAlbumDetailScreen(
                    state = onlineAlbumDetail,
                    currentPlayingTrackId = onlineSnapshot.currentTrack?.id,
                    onBack = { viewModel.online.clearAlbumDetail() },
                    onRetry = lastAlbumNav?.let { nav -> { viewModel.online.openAlbum(nav.idOrUrl, nav.providerType, nav.providerId) } },
                    onPlayTracks = { list, idx, shuf -> viewModel.online.playTracks(list, idx, shuf) },
                    onToggleFavorite = { viewModel.online.toggleFavorite(it) },
                    onOpenContextMenu = { onlineContextMenuTrack = it }
                )
            }

            // Full-Screen Immersive Now Playing Broadsheet Overlay (Supports both Offline & Online)
            AnimatedVisibility(
                visible = showNowPlaying,
                enter = slideInVertically(tween(motion.durationMs, easing = FastOutSlowInEasing)) { it / 2 } +
                    scaleIn(tween(motion.durationMs), initialScale = 0.96f) +
                    fadeIn(tween(motion.durationMs)),
                exit = slideOutVertically(tween(motion.durationMs, easing = FastOutSlowInEasing)) { it / 2 } +
                    scaleOut(tween(motion.durationMs), targetScale = 0.96f) +
                    fadeOut(tween(motion.durationMs / 2))
            ) {
                val activeUi = displayedPlayback.playbackUi
                val isOnlineNow = displayedPlayback.engine == ActiveAudioEngine.ONLINE
                NowPlayingScreen(
                    playback = activeUi,
                    settings = settings,
                    telemetry = telemetry,
                    sleepTimer = sleepTimer,
                    lyrics = lyrics,
                    onDismiss = { showNowPlaying = false },
                    onTogglePlay = { viewModel.toggleDisplayedPlayPause() },
                    onNext = { viewModel.skipDisplayedNext() },
                    onPrevious = { viewModel.skipDisplayedPrevious() },
                    onSeek = { viewModel.seekDisplayedTo(it) },
                    onToggleShuffle = { viewModel.toggleDisplayedShuffle() },
                    onCycleRepeat = { viewModel.cycleDisplayedRepeatMode() },
                    onToggleFavorite = { viewModel.toggleFavoriteForUiTrack(it) },
                    onToggleRemainingTime = {
                        viewModel.updateSettings { it.copy(showRemainingDuration = !it.showRemainingDuration) }
                    },
                    onChangeSpeed = { viewModel.setDisplayedPlaybackSpeed(it) },
                    onStartSleepTimer = { viewModel.startSleepTimer(it) },
                    onCancelSleepTimer = { viewModel.cancelSleepTimer() },
                    onOpenQueue = {
                        if (isOnlineNow) showOnlineQueueSheet = true else showQueueSheet = true
                    },
                    onOpenLyrics = {
                        activeUi.currentTrack?.let {
                            if (!lyrics.hasContent && !lyrics.isLoading) viewModel.loadLyricsFor(it)
                        }
                        showLyricsDialog = true
                    },
                    onOpenAudioFx = { showAudioFxDialog = true },
                    onOpenTagEditor = { tagEditorTrack = it },
                    onOpenAddToPlaylist = { tr ->
                        if (tr.isOnline) {
                            val resolved = viewModel.online.resolveOnlineTrackById(tr.onlineTrackId) ?: onlineSnapshot.currentTrack
                            if (resolved != null) addToOnlinePlaylistTracks = listOf(resolved)
                        } else {
                            addToPlaylistTracks = listOf(tr)
                        }
                    },
                    onOpenDetails = { tr ->
                        if (tr.isOnline) {
                            onlineDetailsTrack = viewModel.online.resolveOnlineTrackById(tr.onlineTrackId) ?: onlineSnapshot.currentTrack
                        } else {
                            detailsTrack = tr
                        }
                    },
                    onNavigateToAlbum = { album ->
                        showNowPlaying = false
                        if (isOnlineNow) {
                            val cur = onlineSnapshot.currentTrack
                            val url = cur?.albumUrl?.takeIf { it.isNotBlank() }
                            if (url != null) {
                                lastAlbumNav = OnlineNavTarget(url, cur.providerType, cur.effectiveProviderId())
                                viewModel.online.openAlbum(url, cur.providerType, cur.effectiveProviderId())
                            } else {
                                viewModel.switchPlaybackMode(PlaybackMode.ONLINE)
                                currentTab = AppTab.SEARCH
                                viewModel.online.executeSearch(album)
                            }
                        } else {
                            librarySection = LibrarySection.ALBUMS
                            libraryDrill = LibraryDrillTarget.AlbumDetail(album)
                            currentTab = AppTab.LIBRARY
                        }
                    },
                    onNavigateToArtist = { artist ->
                        showNowPlaying = false
                        if (isOnlineNow) {
                            val cur = onlineSnapshot.currentTrack
                            val target = cur?.artistUrl?.takeIf { it.isNotBlank() } ?: cur?.artistId?.takeIf { it.isNotBlank() }
                            if (target != null && cur != null) {
                                lastArtistNav = OnlineNavTarget(target, cur.providerType, cur.effectiveProviderId())
                                viewModel.online.openArtist(target, cur.providerType, cur.effectiveProviderId())
                            } else {
                                viewModel.switchPlaybackMode(PlaybackMode.ONLINE)
                                currentTab = AppTab.SEARCH
                                viewModel.online.executeSearch(artist)
                            }
                        } else {
                            librarySection = LibrarySection.ARTISTS
                            libraryDrill = LibraryDrillTarget.ArtistDetail(artist)
                            currentTab = AppTab.LIBRARY
                        }
                    },
                    isBuffering = displayedPlayback.isBuffering,
                    isResolving = displayedPlayback.isResolving,
                    activeStreamLabel = displayedPlayback.activeStreamLabel,
                    errorMessage = displayedPlayback.errorMessage,
                    onRetryStream = if (isOnlineNow) {
                        { viewModel.online.retryCurrentTrack() }
                    } else null,
                    onOpenRelated = if (isOnlineNow) {
                        { showRelatedSheet = true }
                    } else null,
                    onRetryLyrics = { viewModel.retryLoadLyrics() }
                )
            }

            // Full-screen Lyrics Reader Overlay (Offline & Online)
            val lyricsTrack = displayedPlayback.playbackUi.currentTrack
            AnimatedVisibility(visible = showLyricsDialog && lyricsTrack != null, enter = fadeIn(), exit = fadeOut()) {
                lyricsTrack?.let { tr ->
                    LyricsReaderDialog(
                        track = tr,
                        lyrics = lyrics,
                        positionMs = displayedPlayback.playbackUi.positionMs,
                        settings = settings,
                        onDismiss = { showLyricsDialog = false },
                        onSeek = { viewModel.seekDisplayedTo(it) },
                        onUpdateFontSize = { sz -> viewModel.updateSettings { it.copy(lyricsFontSizeSp = sz) } },
                        onUpdateSyncOffset = { off -> viewModel.updateSettings { it.copy(lyricsSyncOffsetMs = off) } },
                        onSaveLyrics = { text -> viewModel.saveLyricsForTrack(tr, text) },
                        onRetry = { viewModel.forceRefreshLyricsFor(tr) }
                    )
                }
            }

            // Full-screen Equalizer & Audio FX Overlay
            AnimatedVisibility(visible = showAudioFxDialog, enter = fadeIn(), exit = fadeOut()) {
                EqualizerAndAudioFxDialog(
                    settings = settings,
                    telemetry = telemetry,
                    presetPreviewLevels = presetPreview,
                    onUpdateSettings = { viewModel.updateSettings(it) },
                    onPreviewPreset = { viewModel.previewEqPreset(it) },
                    onDismiss = { showAudioFxDialog = false }
                )
            }

            // Full-screen Tag Editor Overlay
            AnimatedVisibility(visible = tagEditorTrack != null, enter = fadeIn(), exit = fadeOut()) {
                tagEditorTrack?.let { tr ->
                    TagEditorScreen(
                        track = tr,
                        onDismiss = { tagEditorTrack = null },
                        onSave = { payload, cover -> viewModel.saveTrackTags(tr, payload, cover) },
                        onResultMessage = { msg ->
                            viewModel.rescanLibrary()
                            uiScope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    )
                }
            }

            // Offline Queue Management Bottom Sheet
            if (showQueueSheet) {
                QueueManagementSheet(
                    playback = playback,
                    preferFolderCover = settings.preferFolderCoverArt,
                    onDismiss = { showQueueSheet = false },
                    onJumpToIndex = { viewModel.jumpToQueueIndex(it) },
                    onMoveItem = { f, t -> viewModel.moveQueueItem(f, t) },
                    onRemoveItem = { viewModel.removeQueueItem(it) },
                    onClearQueue = { viewModel.clearQueue() },
                    onShuffleQueue = { viewModel.shuffleCurrentQueue() },
                    onSaveQueueAsPlaylist = { name -> viewModel.saveQueueAsPlaylist(name) }
                )
            }

            // Online Queue Management Bottom Sheet
            if (showOnlineQueueSheet) {
                val onlineQueueUi = remember(onlineSnapshot) {
                    val uiTracks = onlineSnapshot.queue.map { it.toUiTrack() }
                    PlaybackUiState(
                        isConnected = onlineSnapshot.isReady,
                        isPlaying = onlineSnapshot.isPlaying,
                        currentTrack = onlineSnapshot.currentTrack?.toUiTrack(),
                        currentUri = onlineSnapshot.currentTrack?.toUiTrack()?.uriString.orEmpty(),
                        queueTracks = uiTracks,
                        currentQueueIndex = onlineSnapshot.currentIndex.coerceIn(0, (uiTracks.size - 1).coerceAtLeast(0)),
                        positionMs = onlineSnapshot.positionMs,
                        durationMs = onlineSnapshot.durationMs,
                        shuffleEnabled = onlineSnapshot.shuffleEnabled,
                        repeatMode = onlineSnapshot.repeatMode,
                        playbackSpeed = onlineSnapshot.playbackSpeed
                    )
                }
                QueueManagementSheet(
                    playback = onlineQueueUi,
                    preferFolderCover = false,
                    onDismiss = { showOnlineQueueSheet = false },
                    onJumpToIndex = { viewModel.online.jumpToQueueIndex(it) },
                    onMoveItem = { f, t -> viewModel.online.moveQueueItem(f, t) },
                    onRemoveItem = { viewModel.online.removeQueueItem(it) },
                    onClearQueue = { viewModel.online.clearQueue() },
                    onShuffleQueue = { viewModel.online.shuffleCurrentQueue() },
                    onSaveQueueAsPlaylist = { name -> viewModel.online.saveQueueAsPlaylist(name) }
                )
            }

            // Related Tracks Bottom Sheet
            if (showRelatedSheet) {
                RelatedTracksSheet(
                    relatedTracks = onlineSnapshot.relatedTracks,
                    currentPlayingTrackId = onlineSnapshot.currentTrack?.id,
                    onDismiss = { showRelatedSheet = false },
                    onPlayTracks = { list, idx -> viewModel.online.playTracks(list, idx, false) },
                    onToggleFavorite = { viewModel.online.toggleFavorite(it) },
                    onOpenContextMenu = { onlineContextMenuTrack = it }
                )
            }

            // Offline Track Context Menu Sheet
            contextMenuTrack?.let { tr ->
                TrackContextMenuSheet(
                    track = tr,
                    preferFolderCover = settings.preferFolderCoverArt,
                    onDismiss = { contextMenuTrack = null },
                    onPlayNow = { viewModel.playTracks(listOf(tr), 0) },
                    onPlayNext = { viewModel.playNext(listOf(tr)) },
                    onAddToQueue = { viewModel.addToQueue(listOf(tr)) },
                    onAddToPlaylist = { addToPlaylistTracks = listOf(tr) },
                    onToggleFavorite = { viewModel.toggleFavorite(tr) },
                    onGoToAlbum = {
                        librarySection = LibrarySection.ALBUMS
                        libraryDrill = LibraryDrillTarget.AlbumDetail(tr.album)
                        currentTab = AppTab.LIBRARY
                    },
                    onGoToArtist = {
                        librarySection = LibrarySection.ARTISTS
                        libraryDrill = LibraryDrillTarget.ArtistDetail(tr.artist)
                        currentTab = AppTab.LIBRARY
                    },
                    onEditTags = { tagEditorTrack = tr },
                    onViewLyrics = {
                        viewModel.loadLyricsFor(tr)
                        showLyricsDialog = true
                    },
                    onViewDetails = { detailsTrack = tr }
                )
            }

            // Online Track Context Menu Sheet
            onlineContextMenuTrack?.let { tr ->
                OnlineTrackContextMenuSheet(
                    track = tr,
                    onDismiss = { onlineContextMenuTrack = null },
                    onPlayNow = { viewModel.online.playTracks(listOf(tr), 0, false) },
                    onPlayNext = { viewModel.online.playNext(listOf(tr)) },
                    onAddToQueue = { viewModel.online.addToQueue(listOf(tr)) },
                    onAddToPlaylist = { addToOnlinePlaylistTracks = listOf(tr) },
                    onToggleFavorite = { viewModel.online.toggleFavorite(tr) },
                    onOpenArtist = (tr.artistUrl?.takeIf { it.isNotBlank() } ?: tr.artistId?.takeIf { it.isNotBlank() })?.let { target ->
                        {
                            lastArtistNav = OnlineNavTarget(target, tr.providerType, tr.effectiveProviderId())
                            viewModel.online.openArtist(target, tr.providerType, tr.effectiveProviderId())
                        }
                    },
                    onOpenAlbum = tr.albumUrl?.takeIf { it.isNotBlank() }?.let { target ->
                        {
                            lastAlbumNav = OnlineNavTarget(target, tr.providerType, tr.effectiveProviderId())
                            viewModel.online.openAlbum(target, tr.providerType, tr.effectiveProviderId())
                        }
                    },
                    onViewDetails = { onlineDetailsTrack = tr }
                )
            }

            // Add To Offline Playlist Dialog
            addToPlaylistTracks?.let { tracksToAdd ->
                AddToPlaylistDialog(
                    playlists = playlists,
                    onDismiss = { addToPlaylistTracks = null },
                    onSelectPlaylist = { plId ->
                        viewModel.addTracksToPlaylist(plId, tracksToAdd.map { it.uriString })
                        viewModel.clearSelection()
                    },
                    onCreateAndAdd = { name ->
                        viewModel.createPlaylist(name, "", tracksToAdd.map { it.uriString })
                        viewModel.clearSelection()
                    }
                )
            }

            // Add To Online Playlist Dialog
            addToOnlinePlaylistTracks?.let { tracksToAdd ->
                AddToOnlinePlaylistDialog(
                    playlists = onlinePlaylists,
                    onDismiss = { addToOnlinePlaylistTracks = null },
                    onSelectPlaylist = { plId ->
                        viewModel.online.addTracksToPlaylist(plId, tracksToAdd)
                    },
                    onCreateAndAdd = { name ->
                        viewModel.online.createPlaylist(name, "", tracksToAdd)
                    }
                )
            }

            // Offline Track Details Dialog
            detailsTrack?.let { tr ->
                TrackDetailsDialog(track = tr, onDismiss = { detailsTrack = null })
            }

            // Online Track Details Dialog
            onlineDetailsTrack?.let { tr ->
                OnlineTrackDetailsDialog(
                    track = tr,
                    activeStreamLabel = if (onlineSnapshot.currentTrack?.id == tr.id) onlineSnapshot.activeStreamLabel else "",
                    onDismiss = { onlineDetailsTrack = null }
                )
            }
        }
    }
}

private fun hasAudioScanPermission(context: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}
