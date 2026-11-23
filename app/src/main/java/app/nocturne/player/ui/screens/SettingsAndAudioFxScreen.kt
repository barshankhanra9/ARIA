package app.nocturne.player.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.BuildConfig
import app.nocturne.player.R
import app.nocturne.player.data.model.AnimationIntensity
import app.nocturne.player.data.model.ArtworkShape
import app.nocturne.player.data.model.ArtworkTapAction
import app.nocturne.player.data.model.AudioEngineTelemetry
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.model.PaperAccent
import app.nocturne.player.data.model.ProviderConnectivityTestResult
import app.nocturne.player.data.model.ProviderDiagnosticsSnapshot
import app.nocturne.player.data.model.RemoteConfigConnectionState
import app.nocturne.player.data.model.RemoteConfigErrorType
import app.nocturne.player.data.model.RemoteConfigStatus
import app.nocturne.player.data.model.RemoteConfigUrlHelper
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.model.ReplayGainMode
import app.nocturne.player.data.model.SortField
import app.nocturne.player.data.model.SortOrder
import app.nocturne.player.data.model.SwipeVerticalAction
import app.nocturne.player.data.model.ThemeMode
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.ui.components.EditorialSectionHeader
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.PaperAlertDialog
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDebossedWell
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.paperGrainBackground
import app.nocturne.player.ui.theme.AriaPaperTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: UserSettings,
    telemetry: AudioEngineTelemetry,
    onUpdateSettings: ((UserSettings) -> UserSettings) -> Unit,
    onOpenEqualizer: () -> Unit,
    onRescanLibrary: () -> Unit,
    onPickMusicFolder: () -> Unit,
    onRemoveSafFolder: (String) -> Unit,
    onToggleExcludeFolder: (String) -> Unit,
    onExportBackup: (Uri) -> Unit,
    onImportBackup: (Uri) -> Unit,
    remoteConfig: RemoteProvidersConfig = RemoteProvidersConfig.DEFAULT,
    remoteConfigStatus: RemoteConfigStatus = RemoteConfigStatus(),
    remoteConfigConnectionState: RemoteConfigConnectionState = RemoteConfigConnectionState.Idle,
    providerDiagnostics: List<ProviderDiagnosticsSnapshot> = emptyList(),
    providerTestResults: Map<String, ProviderConnectivityTestResult> = emptyMap(),
    isTestingProviders: Boolean = false,
    isRefreshingRemoteConfig: Boolean = false,
    lastRemoteConfigError: String? = null,
    onSaveRemoteConfigUrl: (String) -> Unit = {},
    onTestRemoteConfigUrl: (String) -> Unit = {},
    onRefreshRemoteConfig: (String?) -> Unit = {},
    onClearRemoteConfigFeedback: () -> Unit = {},
    onRollbackRemoteConfig: () -> Unit = {},
    onTestProvider: (String) -> Unit = {},
    onTestAllProviders: () -> Unit = {}
) {
    val palette = AriaPaperTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    val syncTimeFormatter = remember { SimpleDateFormat("MMM d · HH:mm:ss", Locale.getDefault()) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) onExportBackup(uri) }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportBackup(uri) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen_list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            EditorialSectionHeader(eyebrow = "PREFERENCES", title = "Settings")
            Spacer(Modifier.height(12.dp))
            PaperCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_brand_header_card"),
                containerColor = palette.paperElevated,
                elevation = 3.dp,
                cornerRadius = 16.dp,
                onClick = { showAboutDialog = true },
                contentPadding = PaddingValues(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        painter = painterResource(R.drawable.aria_app_logo),
                        contentDescription = "ARIA app logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, palette.divider, RoundedCornerShape(14.dp))
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ARIA",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "Modern Paper Audio · v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
                        )
                    }
                    InkStampTag(text = "PLAYER", accented = true)
                }
            }
        }

        // 1. AUDIO & EQUALIZER
        item {
            SettingsCategoryCard(title = "Audio & Processing", icon = Icons.Rounded.Equalizer) {
                TactilePaperButton(
                    text = "Open Equalizer & Audio Effects",
                    icon = Icons.Rounded.Tune,
                    onClick = onOpenEqualizer,
                    modifier = Modifier.fillMaxWidth().testTag("open_eq_btn")
                )
                Spacer(Modifier.height(10.dp))
                PaperDebossedWell(cornerRadius = 10.dp, contentPadding = PaddingValues(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("LIVE ENGINE OUTPUT", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                        Text(
                            text = if (telemetry.sampleRateHz > 0) {
                                "${telemetry.sampleRateHz} Hz · ${telemetry.channelCount} ch · ${telemetry.encodingName}"
                            } else {
                                "Start playback to inspect active audio sink telemetry"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "ReplayGain: ${settings.replayGainMode.label} · EQ: ${if (settings.eqEnabled) "Active" else "Bypassed"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary
                        )
                    }
                }
            }
        }

        // 2. APPEARANCE
        item {
            SettingsCategoryCard(title = "Paper Appearance", icon = Icons.Rounded.Palette) {
                Text("THEME SURFACE", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        PaperFilterChip(
                            label = mode.label.substringBefore(" "),
                            selected = settings.themeMode == mode,
                            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("INK ACCENT", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(PaperAccent.entries) { _, acc ->
                        PaperFilterChip(
                            label = acc.label,
                            selected = settings.accent == acc,
                            onClick = { onUpdateSettings { it.copy(accent = acc) } }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Paper Grain & Fiber Intensity: ${(settings.paperTextureIntensity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
                Slider(
                    value = settings.paperTextureIntensity,
                    onValueChange = { v -> onUpdateSettings { it.copy(paperTextureIntensity = v) } },
                    colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                )

                Text("ARTWORK FRAME SHAPE", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(ArtworkShape.entries) { _, shape ->
                        PaperFilterChip(
                            label = shape.label,
                            selected = settings.artworkShape == shape,
                            onClick = { onUpdateSettings { it.copy(artworkShape = shape) } }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("ANIMATION INTENSITY", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimationIntensity.entries.forEach { anim ->
                        PaperFilterChip(
                            label = anim.label.substringBefore(" "),
                            selected = settings.animationIntensity == anim,
                            onClick = { onUpdateSettings { it.copy(animationIntensity = anim) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Typography Scale: ${(settings.fontScaleMultiplier * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
                Slider(
                    value = settings.fontScaleMultiplier,
                    onValueChange = { v -> onUpdateSettings { it.copy(fontScaleMultiplier = (v * 20f).roundToInt() / 20f) } },
                    valueRange = 0.85f..1.20f,
                    colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                )

                SettingsSwitchRow(
                    title = "Default Grid Layout in Library",
                    subtitle = "Use printed photo grid cards by default",
                    checked = settings.defaultGridLayout,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(defaultGridLayout = c) } }
                )
            }
        }

        // 3. PLAYBACK & GESTURES
        item {
            SettingsCategoryCard(title = "Playback & Gestures", icon = Icons.Rounded.PlayCircle) {
                SettingsSwitchRow(
                    title = "Respect Audio Focus",
                    subtitle = "Pause or duck when navigation or calls speak",
                    checked = settings.audioFocusEnabled,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(audioFocusEnabled = c) } }
                )
                SettingsSwitchRow(
                    title = "Gapless Playback",
                    subtitle = "Seamless track transitions without silence",
                    checked = settings.gaplessPlayback,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(gaplessPlayback = c) } }
                )
                if (!settings.gaplessPlayback) {
                    Text(
                        "Crossfade Duration: ${settings.crossfadeSeconds}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                    Slider(
                        value = settings.crossfadeSeconds.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(crossfadeSeconds = v.roundToInt()) } },
                        valueRange = 0f..12f,
                        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                    )
                }
                SettingsSwitchRow(
                    title = "Pause on Headphone Unplug",
                    subtitle = "Stop automatically when wired or BT audio disconnects",
                    checked = settings.pauseOnHeadsetDisconnect,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(pauseOnHeadsetDisconnect = c) } }
                )
                SettingsSwitchRow(
                    title = "Resume on Bluetooth Connect",
                    subtitle = "Auto-resume paused queue when BT audio connects",
                    checked = settings.resumeOnBluetoothConnect,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(resumeOnBluetoothConnect = c) } }
                )
                SettingsSwitchRow(
                    title = "Restore Queue on Launch",
                    subtitle = "Reload last track and position after app restart",
                    checked = settings.resumePlaybackOnStart,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(resumePlaybackOnStart = c) } }
                )
                SettingsSwitchRow(
                    title = "Horizontal Artwork Swipe",
                    subtitle = "Swipe left/right on Now Playing cover to change track",
                    checked = settings.swipeHorizontalTrackChange,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(swipeHorizontalTrackChange = c) } }
                )

                Spacer(Modifier.height(8.dp))
                Text("SWIPE UP ON ARTWORK", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(SwipeVerticalAction.entries) { _, act ->
                        PaperFilterChip(
                            label = act.label,
                            selected = settings.swipeUpAction == act,
                            onClick = { onUpdateSettings { it.copy(swipeUpAction = act) } }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("TAP ARTWORK ACTION", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(ArtworkTapAction.entries) { _, act ->
                        PaperFilterChip(
                            label = act.label,
                            selected = settings.artworkTapAction == act,
                            onClick = { onUpdateSettings { it.copy(artworkTapAction = act) } }
                        )
                    }
                }
            }
        }

        // 4. LIBRARY & FOLDERS
        item {
            SettingsCategoryCard(title = "Library & Storage", icon = Icons.Rounded.CreateNewFolder) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TactilePaperButton(text = "Add Folder", icon = Icons.Rounded.CreateNewFolder, onClick = onPickMusicFolder, modifier = Modifier.weight(1f))
                    TactilePaperButton(text = "Rescan", icon = Icons.Rounded.Refresh, onClick = onRescanLibrary, modifier = Modifier.weight(1f), filled = false)
                }
                Spacer(Modifier.height(10.dp))
                if (settings.includedSafFolderUris.isNotEmpty()) {
                    Text("ADDED SAF FOLDERS", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                    Spacer(Modifier.height(4.dp))
                    settings.includedSafFolderUris.forEach { treeUri ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = Uri.parse(treeUri).lastPathSegment ?: treeUri,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveSafFolder(treeUri) }) {
                                Icon(Icons.Rounded.Delete, "Remove folder", tint = palette.danger, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    PaperDivider()
                }
                if (settings.excludedFolders.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("EXCLUDED FOLDERS (tap to re-include)", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                    settings.excludedFolders.forEach { ex ->
                        Text(
                            text = "↺ $ex",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleExcludeFolder(ex) }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
                SettingsSwitchRow(
                    title = "Include Hidden Folders",
                    subtitle = "Scan dot-prefixed folders and files",
                    checked = settings.includeHiddenFolders,
                    onCheckedChange = { c ->
                        onUpdateSettings { it.copy(includeHiddenFolders = c) }
                        onRescanLibrary()
                    }
                )
                SettingsSwitchRow(
                    title = "Prefer Folder Cover Art",
                    subtitle = "Use cover.jpg / folder.jpg when embedded art is missing",
                    checked = settings.preferFolderCoverArt,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(preferFolderCoverArt = c) } }
                )

                Text("DEFAULT SORT FIELD", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(SortField.entries) { _, f ->
                        PaperFilterChip(
                            label = f.label,
                            selected = settings.defaultSortField == f,
                            onClick = { onUpdateSettings { it.copy(defaultSortField = f) } }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortOrder.entries.forEach { ord ->
                        PaperFilterChip(
                            label = ord.label,
                            selected = settings.defaultSortOrder == ord,
                            onClick = { onUpdateSettings { it.copy(defaultSortOrder = ord) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 5. REMOTE PROVIDERS & CONFIG
        item {
            SettingsCategoryCard(title = "Streaming & Remote Config", icon = Icons.Rounded.CloudSync) {
                val activeSourceUrl = remoteConfigStatus.sourceUrl.ifBlank { settings.remoteProviderConfigUrl }
                val sourceMeta = remember(activeSourceUrl) {
                    RemoteConfigUrlHelper.parseSourceMetadata(activeSourceUrl)
                }
                val categories = remember(remoteConfigStatus.categories, remoteConfig) {
                    remoteConfigStatus.categories.ifEmpty { remoteConfig.distinctCategories() }
                }
                val totalProviders = if (remoteConfig.providers.isNotEmpty()) {
                    remoteConfig.providers.size
                } else {
                    remoteConfigStatus.providerCount
                }
                val enabledProviders = if (remoteConfig.providers.isNotEmpty()) {
                    remoteConfig.enabledProviders().size
                } else {
                    remoteConfigStatus.enabledProviderCount
                }
                val lyricsEndpointsCount = if (remoteConfig.lrclib.isNotEmpty()) {
                    remoteConfig.lrclib.size
                } else {
                    remoteConfigStatus.lyricsEndpointCount
                }

                PaperDebossedWell(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("github_config_status_card"),
                    cornerRadius = 12.dp,
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "GITHUB PROVIDER CONFIGURATION",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent,
                                modifier = Modifier.weight(1f)
                            )
                            val syncBadge = when {
                                remoteConfigStatus.wasNotModified304 -> "304 UP-TO-DATE"
                                remoteConfigStatus.isFromCache -> "CACHED"
                                else -> "SYNCED"
                            }
                            InkStampTag(
                                text = syncBadge,
                                accented = !remoteConfigStatus.isFromCache
                            )
                        }

                        val sourceHeadline = buildString {
                            append(sourceMeta.repositoryDisplay)
                            if (sourceMeta.fileName.isNotBlank()) {
                                append(" · ")
                                append(sourceMeta.fileName)
                            }
                            if (!sourceMeta.branchOrRef.isNullOrBlank()) {
                                append(" (")
                                append(sourceMeta.branchOrRef)
                                append(")")
                            }
                        }
                        Text(
                            text = sourceHeadline,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("remote_config_source_summary")
                        )

                        val syncTimeText = if (remoteConfigStatus.lastSyncedAtMs > 0L) {
                            syncTimeFormatter.format(Date(remoteConfigStatus.lastSyncedAtMs))
                        } else {
                            "Pending initial sync"
                        }
                        Text(
                            text = "Config v${remoteConfigStatus.configVersion} · Schema v${remoteConfigStatus.schemaVersion} · $syncTimeText",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary,
                            modifier = Modifier.testTag("remote_config_version_summary")
                        )
                        Text(
                            text = "$enabledProviders/$totalProviders providers enabled · Lyrics endpoints: $lyricsEndpointsCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkPrimary,
                            modifier = Modifier.testTag("remote_config_counts_summary")
                        )

                        if (categories.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "PROVIDER CATEGORIES",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accent
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.testTag("remote_config_categories_row")
                                ) {
                                    items(categories) { category ->
                                        InkStampTag(text = category, accented = false)
                                    }
                                }
                            }
                        }

                        val effectiveError = remoteConfigStatus.lastError?.takeIf { it.isNotBlank() }
                            ?: lastRemoteConfigError?.takeIf { it.isNotBlank() }
                        if (effectiveError != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.testTag("remote_config_status_error")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = "Configuration warning",
                                    tint = palette.danger,
                                    modifier = Modifier.size(15.dp)
                                )
                                val errorPrefix = if (remoteConfigStatus.lastErrorType != RemoteConfigErrorType.NONE) {
                                    "${remoteConfigStatus.lastErrorType.label}: "
                                } else {
                                    ""
                                }
                                Text(
                                    text = "$errorPrefix$effectiveError",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.danger
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                var draftUrl by rememberSaveable { mutableStateOf(settings.remoteProviderConfigUrl) }
                LaunchedEffect(settings.remoteProviderConfigUrl) {
                    draftUrl = settings.remoteProviderConfigUrl
                }

                val isBusy = isRefreshingRemoteConfig ||
                    remoteConfigConnectionState is RemoteConfigConnectionState.Testing ||
                    remoteConfigConnectionState is RemoteConfigConnectionState.Saving
                val hasUnsavedDraft = draftUrl.trim() != settings.remoteProviderConfigUrl.trim()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PROVIDER JSON URL",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                    if (draftUrl.trim() != UserSettings.DEFAULT_REMOTE_CONFIG_URL) {
                        TextButton(
                            onClick = {
                                draftUrl = UserSettings.DEFAULT_REMOTE_CONFIG_URL
                                onClearRemoteConfigFeedback()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("remote_config_reset_default_btn")
                        ) {
                            Text(
                                text = "Reset Default",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = draftUrl,
                    onValueChange = { updated ->
                        draftUrl = updated
                        if (remoteConfigConnectionState !is RemoteConfigConnectionState.Idle) {
                            onClearRemoteConfigFeedback()
                        }
                    },
                    placeholder = {
                        Text(
                            text = UserSettings.DEFAULT_REMOTE_CONFIG_URL,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            tint = palette.accent
                        )
                    },
                    trailingIcon = {
                        if (draftUrl.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    draftUrl = ""
                                    onClearRemoteConfigFeedback()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear URL",
                                    tint = palette.inkSecondary
                                )
                            }
                        }
                    },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboard?.hide() }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.divider,
                        focusedContainerColor = palette.paperBackground,
                        unfocusedContainerColor = palette.paperBackground,
                        focusedTextColor = palette.inkPrimary,
                        unfocusedTextColor = palette.inkPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_config_url_input")
                )

                if (hasUnsavedDraft) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Edited URL is not active yet. Test Connection or Save URL to validate JSON and schema first.",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkSecondary,
                        modifier = Modifier.testTag("remote_config_unsaved_hint")
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TactilePaperButton(
                        text = if (remoteConfigConnectionState is RemoteConfigConnectionState.Saving) "Saving…" else "Save URL",
                        icon = Icons.Rounded.Save,
                        enabled = !isBusy && draftUrl.isNotBlank(),
                        onClick = {
                            keyboard?.hide()
                            onSaveRemoteConfigUrl(draftUrl)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("remote_config_save_url_btn")
                    )
                    TactilePaperButton(
                        text = if (remoteConfigConnectionState is RemoteConfigConnectionState.Testing) "Testing…" else "Test Connection",
                        icon = Icons.Rounded.WifiTethering,
                        filled = false,
                        enabled = !isBusy && draftUrl.isNotBlank(),
                        onClick = {
                            keyboard?.hide()
                            onTestRemoteConfigUrl(draftUrl)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("remote_config_test_connection_btn")
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TactilePaperButton(
                        text = if (isRefreshingRemoteConfig) "Syncing…" else "Refresh Config",
                        icon = Icons.Rounded.Refresh,
                        filled = false,
                        enabled = !isBusy,
                        onClick = {
                            keyboard?.hide()
                            onRefreshRemoteConfig(null)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("remote_config_refresh_btn")
                    )
                    if (remoteConfigStatus.canRollback) {
                        TactilePaperButton(
                            text = "Rollback",
                            icon = Icons.Rounded.Restore,
                            filled = false,
                            enabled = !isBusy,
                            onClick = onRollbackRemoteConfig,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("remote_config_rollback_btn")
                        )
                    }
                    TactilePaperButton(
                        text = if (isTestingProviders) "Testing…" else "Test All",
                        icon = Icons.Rounded.PlayArrow,
                        filled = false,
                        enabled = !isTestingProviders && !isBusy,
                        onClick = onTestAllProviders,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("remote_test_all_btn")
                    )
                }

                if (remoteConfigConnectionState !is RemoteConfigConnectionState.Idle) {
                    Spacer(Modifier.height(10.dp))
                    PaperDebossedWell(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remote_config_feedback_card"),
                        cornerRadius = 12.dp,
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        when (val state = remoteConfigConnectionState) {
                            is RemoteConfigConnectionState.Testing,
                            is RemoteConfigConnectionState.Saving -> {
                                val actionTitle = if (state is RemoteConfigConnectionState.Saving) {
                                    "VALIDATING & SAVING URL…"
                                } else {
                                    "TESTING CONNECTION…"
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.accent
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = actionTitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.accent
                                        )
                                        Text(
                                            text = "Validating URL, fetching JSON, and verifying provider schema…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = palette.inkSecondary
                                        )
                                    }
                                }
                            }
                            is RemoteConfigConnectionState.Success -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = "Connection valid",
                                            tint = palette.accent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = state.actionLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.accent,
                                            modifier = Modifier.weight(1f)
                                        )
                                        InkStampTag(text = "${state.latencyMs}ms", accented = true)
                                    }
                                    val metaLine = buildString {
                                        append(state.sourceMetadata.repositoryDisplay)
                                        if (state.sourceMetadata.fileName.isNotBlank()) {
                                            append(" · ")
                                            append(state.sourceMetadata.fileName)
                                        }
                                    }
                                    Text(
                                        text = "$metaLine · Config v${state.configVersion} · Schema v${state.schemaVersion}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkPrimary
                                    )
                                    Text(
                                        text = "${state.enabledProviders}/${state.totalProviders} providers enabled · Lyrics endpoints: ${state.lyricsEndpointCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkSecondary
                                    )
                                    if (state.categories.isNotEmpty()) {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(state.categories) { cat ->
                                                InkStampTag(text = cat, accented = false)
                                            }
                                        }
                                    }
                                }
                            }
                            is RemoteConfigConnectionState.Error -> {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Warning,
                                            contentDescription = "Connection error",
                                            tint = palette.danger,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        InkStampTag(
                                            text = state.errorType.label.ifBlank { "Error" },
                                            accented = false
                                        )
                                    }
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.danger
                                    )
                                    Text(
                                        text = "Last working URL and active configuration were preserved.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkSecondary
                                    )
                                }
                            }
                            RemoteConfigConnectionState.Idle -> Unit
                        }
                    }
                }

                if (providerDiagnostics.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "PROVIDER & ENDPOINT DIAGNOSTICS",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        providerDiagnostics.forEach { diag ->
                            ProviderDiagnosticsCard(
                                diagnostics = diag,
                                testResult = providerTestResults[diag.providerId.lowercase()],
                                isTesting = isTestingProviders,
                                onTestProvider = { onTestProvider(diag.providerId) }
                            )
                        }
                    }
                }
            }
        }

        // 6. BACKUP, PRIVACY & ABOUT
        item {
            SettingsCategoryCard(title = "Backup & Information", icon = Icons.Rounded.Info) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TactilePaperButton(
                        text = "Export Backup",
                        icon = Icons.Rounded.Backup,
                        onClick = { exportBackupLauncher.launch("aria_paper_backup.json") },
                        modifier = Modifier.weight(1f)
                    )
                    TactilePaperButton(
                        text = "Restore",
                        icon = Icons.Rounded.Restore,
                        filled = false,
                        onClick = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                PaperDivider()
                TextButton(onClick = { showPrivacyDialog = true }) {
                    Icon(Icons.Rounded.Lock, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Offline & Privacy Pledge", color = palette.inkPrimary)
                }
                TextButton(onClick = { showLicensesDialog = true }) {
                    Icon(Icons.Rounded.Info, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open-Source Licenses", color = palette.inkPrimary)
                }
                TextButton(onClick = { showAboutDialog = true }) {
                    Image(
                        painter = painterResource(R.drawable.aria_app_logo),
                        contentDescription = "ARIA app logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .border(0.5.dp, palette.divider, RoundedCornerShape(5.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("About ARIA (${BuildConfig.VERSION_NAME})", color = palette.inkPrimary)
                }
            }
        }
    }

    if (showPrivacyDialog) {
        PaperAlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = "Offline & Privacy Pledge",
            text = {
                Text("ARIA operates 100% offline. No audio, listening statistics, tags, or playlists ever leave your device. No accounts, analytics, or ads exist.")
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("OK", color = palette.accent) }
            }
        )
    }

    if (showLicensesDialog) {
        PaperAlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = "Open-Source Libraries",
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• AndroidX Media3 / ExoPlayer (Apache 2.0)")
                    Text("• Jetpack Compose & Material 3 (Apache 2.0)")
                    Text("• AndroidX Room & DataStore (Apache 2.0)")
                    Text("• Kotlinx Serialization (Apache 2.0)")
                    Text("• Google Fonts: Playfair Display, Plus Jakarta Sans, JetBrains Mono (OFL)")
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text("Close", color = palette.accent) }
            }
        )
    }

    if (showAboutDialog) {
        PaperAlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = "ARIA · Modern Paper Audio",
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        painter = painterResource(R.drawable.aria_app_logo),
                        contentDescription = "ARIA app logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, palette.divider, RoundedCornerShape(18.dp))
                    )
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}\nA tactile offline & streaming music journal powered by AndroidX Media3.",
                        color = palette.inkPrimary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Close", color = palette.accent) }
            }
        )
    }
}

@Composable
fun EqualizerAndAudioFxDialog(
    settings: UserSettings,
    telemetry: AudioEngineTelemetry,
    presetPreviewLevels: List<Int>,
    onUpdateSettings: ((UserSettings) -> UserSettings) -> Unit,
    onPreviewPreset: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val palette = AriaPaperTheme.palette

    LaunchedEffect(presetPreviewLevels) {
        if (presetPreviewLevels.isNotEmpty()) {
            onUpdateSettings { it.copy(eqCustomBandLevelsMb = presetPreviewLevels) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .paperGrainBackground(palette.paperBackground)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, "Close Audio FX", tint = palette.inkPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text("AUDIO ENGINE & FX", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Text("Equalizer & Processing", style = MaterialTheme.typography.headlineSmall, color = palette.inkPrimary)
            }
            Switch(
                checked = settings.eqEnabled,
                onCheckedChange = { on -> onUpdateSettings { it.copy(eqEnabled = on) } },
                colors = SwitchDefaults.colors(checkedTrackColor = palette.accent)
            )
        }

        Spacer(Modifier.height(10.dp))
        PaperDivider()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Honest Device Capability & Output Status Card
            item {
                PaperDebossedWell(cornerRadius = 14.dp, contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("HARDWARE & ENGINE STATUS", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                        Text(
                            text = if (telemetry.audioSessionId != 0) {
                                "Session #${telemetry.audioSessionId} · ${telemetry.sampleRateHz} Hz · ${telemetry.channelCount}ch ${telemetry.encodingName}"
                            } else {
                                "Play a track to attach Android AudioEffect to active session"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "EQ: ${if (telemetry.equalizerSupported) "${telemetry.bandCount} bands" else "Unavailable"} · " +
                                "Bass: ${if (telemetry.bassBoostSupported) "Supported" else "Unavailable"} · " +
                                "Virtualizer: ${if (telemetry.virtualizerSupported) "Supported" else "Unavailable"} · " +
                                "Loudness: ${if (telemetry.loudnessSupported) "Supported" else "Unavailable"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary
                        )
                        if (telemetry.replayGainAppliedDb != 0f) {
                            Text(
                                text = "Active ReplayGain Adjustment: %+.1f dB".format(telemetry.replayGainAppliedDb),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent
                            )
                        }
                    }
                }
            }

            // Equalizer Presets & Bands
            item {
                PaperCard(containerColor = palette.paperElevated, elevation = 3.dp, contentPadding = PaddingValues(14.dp)) {
                    Text("EQUALIZER", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                    Spacer(Modifier.height(8.dp))
                    if (!telemetry.equalizerSupported || telemetry.bandCount == 0) {
                        Text(
                            "This audio session or device does not expose a hardware Equalizer instance yet. Start playback on a real audio sink to configure EQ bands.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
                        )
                    } else {
                        if (telemetry.presetNames.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    PaperFilterChip(
                                        label = "Custom",
                                        selected = settings.eqPresetIndex == -1,
                                        onClick = { onUpdateSettings { it.copy(eqPresetIndex = -1) } }
                                    )
                                }
                                itemsIndexed(telemetry.presetNames) { idx, name ->
                                    PaperFilterChip(
                                        label = name,
                                        selected = settings.eqPresetIndex == idx,
                                        onClick = {
                                            onUpdateSettings { it.copy(eqPresetIndex = idx, eqEnabled = true) }
                                            onPreviewPreset(idx)
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        val minMb = telemetry.minBandLevelMb.toFloat()
                        val maxMb = telemetry.maxBandLevelMb.toFloat().coerceAtLeast(minMb + 100f)
                        for (b in 0 until telemetry.bandCount) {
                            val freqHz = telemetry.bandCenterHz.getOrElse(b) { 0 }
                            val freqLabel = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"
                            val currentMb = settings.eqCustomBandLevelsMb.getOrElse(b) { 0 }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(freqLabel, style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary, modifier = Modifier.width(56.dp))
                                Slider(
                                    value = currentMb.toFloat().coerceIn(minMb, maxMb),
                                    onValueChange = { valMb ->
                                        val updated = MutableList(telemetry.bandCount) { idx ->
                                            if (idx == b) valMb.roundToInt()
                                            else settings.eqCustomBandLevelsMb.getOrElse(idx) { 0 }
                                        }
                                        onUpdateSettings { it.copy(eqPresetIndex = -1, eqCustomBandLevelsMb = updated) }
                                    },
                                    valueRange = minMb..maxMb,
                                    enabled = settings.eqEnabled,
                                    colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "%+.1f dB".format(currentMb / 100f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.inkPrimary,
                                    modifier = Modifier.width(54.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Bass Boost, Virtualizer, Loudness
            item {
                PaperCard(containerColor = palette.paperElevated, elevation = 3.dp, contentPadding = PaddingValues(14.dp)) {
                    Text("SPATIAL & DYNAMICS", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                    Spacer(Modifier.height(10.dp))

                    Text(
                        "Bass Boost: ${settings.bassBoostStrength / 10}% ${if (!telemetry.bassBoostSupported) "(Unsupported on device)" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkPrimary
                    )
                    Slider(
                        value = settings.bassBoostStrength.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(bassBoostStrength = v.roundToInt()) } },
                        valueRange = 0f..1000f,
                        enabled = settings.eqEnabled && telemetry.bassBoostSupported,
                        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                    )

                    Text(
                        "Virtualizer / Surround: ${settings.virtualizerStrength / 10}% ${if (!telemetry.virtualizerSupported) "(Unsupported on device)" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkPrimary
                    )
                    Slider(
                        value = settings.virtualizerStrength.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(virtualizerStrength = v.roundToInt()) } },
                        valueRange = 0f..1000f,
                        enabled = settings.eqEnabled && telemetry.virtualizerSupported,
                        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                    )

                    Text(
                        "Loudness Enhancement: +${settings.loudnessGainMb / 100f} dB ${if (!telemetry.loudnessSupported) "(Unsupported)" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkPrimary
                    )
                    Slider(
                        value = settings.loudnessGainMb.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(loudnessGainMb = v.roundToInt()) } },
                        valueRange = 0f..1500f,
                        enabled = settings.eqEnabled && telemetry.loudnessSupported,
                        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                    )
                }
            }

            // Channel Balance & ReplayGain
            item {
                PaperCard(containerColor = palette.paperElevated, elevation = 3.dp, contentPadding = PaddingValues(14.dp)) {
                    Text("BALANCE & REPLAYGAIN", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                    Spacer(Modifier.height(10.dp))

                    val balLabel = when {
                        settings.channelBalance < -0.05f -> "Left ${((-settings.channelBalance) * 100).roundToInt()}%"
                        settings.channelBalance > 0.05f -> "Right ${(settings.channelBalance * 100).roundToInt()}%"
                        else -> "Center"
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Channel Balance: $balLabel", style = MaterialTheme.typography.bodySmall, color = palette.inkPrimary)
                        if (settings.channelBalance != 0f) {
                            Text(
                                "Center",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent,
                                modifier = Modifier.clickable { onUpdateSettings { it.copy(channelBalance = 0f) } }
                            )
                        }
                    }
                    Slider(
                        value = settings.channelBalance,
                        onValueChange = { v -> onUpdateSettings { it.copy(channelBalance = v) } },
                        valueRange = -1f..1f,
                        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("REPLAYGAIN MODE", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReplayGainMode.entries.forEach { mode ->
                            PaperFilterChip(
                                label = mode.label,
                                selected = settings.replayGainMode == mode,
                                onClick = { onUpdateSettings { it.copy(replayGainMode = mode) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (settings.replayGainMode != ReplayGainMode.OFF) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "ReplayGain Preamp: %+.1f dB".format(settings.replayGainPreampDb),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
                        )
                        Slider(
                            value = settings.replayGainPreampDb,
                            onValueChange = { v -> onUpdateSettings { it.copy(replayGainPreampDb = (v * 2f).roundToInt() / 2f) } },
                            valueRange = -6f..6f,
                            colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = palette.paperElevated,
        elevation = 3.dp,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = palette.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = palette.inkPrimary)
        }
        Spacer(Modifier.height(10.dp))
        PaperDivider()
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = AriaPaperTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = palette.inkPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                uncheckedTrackColor = palette.paperPressed
            )
        )
    }
}

@Composable
private fun ProviderDiagnosticsCard(
    diagnostics: ProviderDiagnosticsSnapshot,
    testResult: ProviderConnectivityTestResult?,
    isTesting: Boolean,
    onTestProvider: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    val maxCooldownMs = diagnostics.operationHealth.values
        .flatten()
        .maxOfOrNull { it.cooldownRemainingMs } ?: 0L
    val healthLabel = when {
        !diagnostics.enabled -> "DISABLED"
        !diagnostics.isAvailable && maxCooldownMs > 0L -> "COOLDOWN ${(maxCooldownMs / 1000L).coerceAtLeast(1L)}s"
        !diagnostics.isAvailable -> "UNAVAILABLE"
        else -> "HEALTHY"
    }

    PaperDebossedWell(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider_diag_card_${diagnostics.providerId}"),
        cornerRadius = 12.dp,
        contentPadding = PaddingValues(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = diagnostics.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${diagnostics.providerId} · ${diagnostics.adapterType} · Priority #${diagnostics.priority}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                InkStampTag(
                    text = healthLabel,
                    accented = diagnostics.enabled && diagnostics.isAvailable
                )
            }

            val activeHosts = buildList {
                diagnostics.activeSearchEndpoint?.let { add("Search: ${compactHostLabel(it)}") }
                diagnostics.activeMetadataEndpoint?.let { add("Meta: ${compactHostLabel(it)}") }
                diagnostics.activePlaybackEndpoint?.let { add("Stream: ${compactHostLabel(it)}") }
            }
            if (activeHosts.isNotEmpty()) {
                Text(
                    text = activeHosts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkPrimary
                )
            }

            diagnostics.operationHealth.entries.forEach { (op, endpoints) ->
                if (endpoints.isNotEmpty()) {
                    Text(
                        text = op.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                    endpoints.forEach { ep ->
                        val statusPart = when {
                            ep.cooldownRemainingMs > 0L -> "Cooldown ${(ep.cooldownRemainingMs / 1000L).coerceAtLeast(1L)}s"
                            ep.consecutiveFailures > 0 -> "Fails ${ep.consecutiveFailures}"
                            else -> "OK"
                        }
                        val latencyPart = if (ep.ewmaLatencyMs > 0L) " · ${ep.ewmaLatencyMs}ms" else ""
                        Text(
                            text = "• [P${ep.priority}] ${compactHostLabel(ep.url)} · $statusPart$latencyPart",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ep.isHealthy) palette.inkPrimary else palette.danger,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!ep.lastError.isNullOrBlank()) {
                            Text(
                                text = "  ${ep.lastError}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (testResult != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (testResult.success) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = if (testResult.success) palette.accent else palette.danger,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = buildString {
                            append(if (testResult.success) "Reachable (${testResult.latencyMs}ms)" else "Test failed")
                            testResult.testedEndpoint?.let { append(" · ${compactHostLabel(it)}") }
                            if (testResult.failoverAttempts > 0) append(" · ${testResult.failoverAttempts} failover(s)")
                            testResult.errorSummary?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (testResult.success) palette.inkPrimary else palette.danger
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onTestProvider,
                    enabled = !isTesting && diagnostics.enabled,
                    modifier = Modifier.testTag("test_provider_btn_${diagnostics.providerId}")
                ) {
                    Text(
                        text = "Test ${diagnostics.displayName}",
                        color = if (diagnostics.enabled) palette.accent else palette.inkTertiary
                    )
                }
            }
        }
    }
}

private fun compactHostLabel(rawUrl: String): String {
    return runCatching {
        val parsed = Uri.parse(rawUrl)
        parsed.host?.takeIf { it.isNotBlank() } ?: rawUrl
    }.getOrDefault(rawUrl)
}
