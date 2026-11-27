package app.nocturne.player.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.BuildConfig
import app.nocturne.player.R
import app.nocturne.player.data.model.AnimationIntensity
import app.nocturne.player.data.model.ArtworkShape
import app.nocturne.player.data.model.ArtworkTapAction
import app.nocturne.player.data.model.AudioEngineTelemetry
import app.nocturne.player.data.model.PaperAccent
import app.nocturne.player.data.model.RemoteConfigStatus
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.model.ReplayGainMode
import app.nocturne.player.data.model.SortField
import app.nocturne.player.data.model.SortOrder
import app.nocturne.player.data.model.StreamingQuality
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
import app.nocturne.player.widget.DailyStripWidgetProvider
import app.nocturne.player.widget.FrontPageWidgetProvider
import app.nocturne.player.widget.SundayBroadsheetWidgetProvider
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
    lastRemoteConfigError: String? = null,
    onOpenStreamingConfig: () -> Unit = {},
    onNotifyMessage: (String) -> Unit = {}
) {
    val palette = AriaPaperTheme.palette
    val context = LocalContext.current
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

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

        // 5. STREAMING & REMOTE CONFIG ENTRY CARD
        item {
            val totalMusic = if (remoteConfig.providers.isNotEmpty()) {
                remoteConfig.providers.size
            } else {
                remoteConfigStatus.providerCount
            }
            val enabledMusic = if (remoteConfig.providers.isNotEmpty()) {
                remoteConfig.enabledProviders().size
            } else {
                remoteConfigStatus.enabledProviderCount
            }
            val resolvedLyrics = remember(remoteConfig) {
                remoteConfig.resolvedLyricsProvidersForDiagnostics()
            }
            val totalLyrics = if (resolvedLyrics.isNotEmpty()) {
                resolvedLyrics.size
            } else {
                remoteConfigStatus.totalLyricsProviderCount
            }
            val enabledLyrics = if (resolvedLyrics.isNotEmpty()) {
                resolvedLyrics.count { it.enabled }
            } else {
                remoteConfigStatus.enabledLyricsProviderCount
            }
            val effectiveError = remoteConfigStatus.lastError?.takeIf { it.isNotBlank() }
                ?: lastRemoteConfigError?.takeIf { it.isNotBlank() }
            val syncLabel = when {
                effectiveError != null -> "Sync warning"
                remoteConfigStatus.wasNotModified304 -> "Up to date"
                remoteConfigStatus.isFromCache -> "Cached"
                remoteConfigStatus.lastSyncedAtMs > 0L -> "Synced"
                else -> "Pending sync"
            }
            val overrideSuffix = if (remoteConfigStatus.hasLocalOverrides) " · Overridden" else ""
            val subtitleText = "$syncLabel • $enabledMusic/$totalMusic music, $enabledLyrics/$totalLyrics lyrics$overrideSuffix"

            PaperCard(
                containerColor = palette.paperElevated,
                elevation = 3.dp,
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp),
                onClick = onOpenStreamingConfig,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .testTag("open_streaming_remote_config_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudSync,
                        contentDescription = "Streaming and Remote Config",
                        tint = palette.accent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Streaming & Remote Config",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (effectiveError != null) palette.danger else palette.inkSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("streaming_config_entry_subtitle")
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "Open Streaming & Remote Config",
                        tint = palette.inkSecondary
                    )
                }
            }
        }

        // 5b. AUDIO STREAMING QUALITY (Wi-Fi & Mobile Data)
        item {
            SettingsCategoryCard(title = "Streaming Quality", icon = Icons.Rounded.GraphicEq) {
                Text(
                    text = "Applied to JioSaavn (96 / 160 / 320 kbps) and YouTube / Piped streams based on your active network connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
                Spacer(Modifier.height(10.dp))
                Text("WI-FI STREAMING QUALITY", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamingQuality.entries.forEach { q ->
                        PaperFilterChip(
                            label = q.shortLabel,
                            selected = settings.wifiStreamingQuality == q,
                            onClick = { onUpdateSettings { it.copy(wifiStreamingQuality = q) } },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("wifi_quality_${q.name}")
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("MOBILE DATA STREAMING QUALITY", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamingQuality.entries.forEach { q ->
                        PaperFilterChip(
                            label = q.shortLabel,
                            selected = settings.mobileDataStreamingQuality == q,
                            onClick = { onUpdateSettings { it.copy(mobileDataStreamingQuality = q) } },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("mobile_quality_${q.name}")
                        )
                    }
                }
            }
        }

        // 5c. NEWSPRINT HOME SCREEN WIDGETS
        item {
            SettingsCategoryCard(title = "Newsprint Home Widgets", icon = Icons.Rounded.Newspaper) {
                SettingsSwitchRow(
                    title = "Sharp Newsprint Corners",
                    subtitle = "Use square cut-paper corners instead of rounded widget cards",
                    checked = settings.widgetSharpCorners,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(widgetSharpCorners = c) } },
                    modifier = Modifier.testTag("widget_sharp_corners_switch")
                )
                Spacer(Modifier.height(8.dp))
                Text("ADD WIDGET EDITION TO HOME SCREEN", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Spacer(Modifier.height(6.dp))

                fun requestPin(providerCls: Class<*>, label: String) {
                    val awm = AppWidgetManager.getInstance(context)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && awm.isRequestPinAppWidgetSupported) {
                        awm.requestPinAppWidget(ComponentName(context, providerCls), null, null)
                    } else {
                        onNotifyMessage("Long-press your home screen -> Widgets -> ARIA ($label)")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TactilePaperButton(
                        text = "The Daily Strip (4×1)",
                        icon = Icons.Rounded.Widgets,
                        filled = false,
                        onClick = { requestPin(DailyStripWidgetProvider::class.java, "The Daily Strip") },
                        modifier = Modifier.fillMaxWidth().testTag("pin_widget_daily_strip")
                    )
                    TactilePaperButton(
                        text = "Front Page Headline (2×2)",
                        icon = Icons.Rounded.Widgets,
                        filled = false,
                        onClick = { requestPin(FrontPageWidgetProvider::class.java, "Front Page Headline") },
                        modifier = Modifier.fillMaxWidth().testTag("pin_widget_front_page")
                    )
                    TactilePaperButton(
                        text = "Sunday Broadsheet (4×3 + Lyrics)",
                        icon = Icons.Rounded.Widgets,
                        filled = false,
                        onClick = { requestPin(SundayBroadsheetWidgetProvider::class.java, "Sunday Broadsheet") },
                        modifier = Modifier.fillMaxWidth().testTag("pin_widget_sunday_broadsheet")
                    )
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
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    Row(
        modifier = modifier
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
