package app.nocturne.player.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.model.EndpointHealthInfo
import app.nocturne.player.data.model.LyricsProviderDiagnosticsSnapshot
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.model.ProviderConnectivityTestResult
import app.nocturne.player.data.model.ProviderDiagnosticsSnapshot
import app.nocturne.player.data.model.RemoteConfigConnectionState
import app.nocturne.player.data.model.RemoteConfigErrorType
import app.nocturne.player.data.model.RemoteConfigStatus
import app.nocturne.player.data.model.RemoteConfigUrlHelper
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.model.UserSettings
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.PaperAlertDialog
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDebossedWell
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.paperGrainBackground
import app.nocturne.player.ui.theme.AriaPaperTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StreamingRemoteConfigScreen(
    settings: UserSettings,
    remoteConfig: RemoteProvidersConfig,
    remoteConfigStatus: RemoteConfigStatus,
    remoteConfigConnectionState: RemoteConfigConnectionState,
    providerDiagnostics: List<ProviderDiagnosticsSnapshot>,
    lyricsProviderDiagnostics: List<LyricsProviderDiagnosticsSnapshot>,
    providerTestResults: Map<String, ProviderConnectivityTestResult>,
    lyricsProviderTestResults: Map<String, ProviderConnectivityTestResult>,
    isTestingProviders: Boolean,
    isRefreshingRemoteConfig: Boolean,
    lastRemoteConfigError: String?,
    onBack: () -> Unit,
    onSaveRemoteConfigUrl: (String, Boolean) -> Unit,
    onTestRemoteConfigUrl: (String) -> Unit,
    onRefreshRemoteConfig: (String?, Boolean) -> Unit,
    onClearRemoteConfigFeedback: () -> Unit,
    onRollbackRemoteConfig: () -> Unit,
    onTestProvider: (String) -> Unit,
    onTestLyricsProvider: (String) -> Unit,
    onTestAllProviders: () -> Unit,
    onToggleMusicProviderEnabled: (String) -> Unit,
    onMoveMusicProviderUp: (String) -> Unit,
    onMoveMusicProviderDown: (String) -> Unit,
    onToggleLyricsProviderEnabled: (String) -> Unit,
    onMoveLyricsProviderUp: (String) -> Unit,
    onMoveLyricsProviderDown: (String) -> Unit,
    onResetOverrides: () -> Unit,
    onBuildDiagnosticsReport: () -> String,
    onNotifyMessage: (String) -> Unit
) {
    BackHandler(onBack = onBack)

    val palette = AriaPaperTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val syncTimeFormatter = remember { SimpleDateFormat("MMM d · HH:mm:ss", Locale.getDefault()) }

    var draftUrl by rememberSaveable { mutableStateOf(settings.remoteProviderConfigUrl) }
    LaunchedEffect(settings.remoteProviderConfigUrl) {
        draftUrl = settings.remoteProviderConfigUrl
    }

    var showRefreshOverrideDialog by remember { mutableStateOf(false) }

    val activeSourceUrl = remoteConfigStatus.sourceUrl.ifBlank { settings.remoteProviderConfigUrl }
    val sourceMeta = remember(activeSourceUrl) {
        RemoteConfigUrlHelper.parseSourceMetadata(activeSourceUrl)
    }
    val categories = remember(remoteConfigStatus.categories, remoteConfig) {
        remoteConfigStatus.categories.ifEmpty { remoteConfig.distinctCategories() }
    }
    val totalMusicProviders = if (remoteConfig.providers.isNotEmpty()) {
        remoteConfig.providers.size
    } else {
        remoteConfigStatus.providerCount
    }
    val enabledMusicProviders = if (remoteConfig.providers.isNotEmpty()) {
        remoteConfig.enabledProviders().size
    } else {
        remoteConfigStatus.enabledProviderCount
    }

    val allEndpoints = remember(providerDiagnostics, lyricsProviderDiagnostics) {
        val musicEps = providerDiagnostics.flatMap { diag ->
            if (!diag.enabled) emptyList() else diag.operationHealth.values.flatten()
        }
        val lyricsEps = lyricsProviderDiagnostics.flatMap { diag ->
            if (!diag.enabled) emptyList() else diag.endpoints
        }
        (musicEps + lyricsEps).distinctBy { "${it.operation.key}:${it.url}" }
    }
    val healthyEndpointCount = remember(allEndpoints) {
        allEndpoints.count { it.isHealthy && it.consecutiveFailures == 0 && it.cooldownRemainingMs == 0L }
    }
    val failingEndpointCount = remember(allEndpoints) {
        allEndpoints.count { !it.isHealthy || it.consecutiveFailures > 0 || it.cooldownRemainingMs > 0L }
    }
    val disabledProvidersCount = remember(providerDiagnostics, lyricsProviderDiagnostics) {
        providerDiagnostics.count { !it.enabled } + lyricsProviderDiagnostics.count { !it.enabled }
    }

    val isBusy = isRefreshingRemoteConfig ||
        remoteConfigConnectionState is RemoteConfigConnectionState.Testing ||
        remoteConfigConnectionState is RemoteConfigConnectionState.Saving
    val hasUnsavedDraft = draftUrl.trim() != settings.remoteProviderConfigUrl.trim()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .paperGrainBackground(palette.paperBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("streaming_remote_config_screen")
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("streaming_config_back_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back to Settings",
                    tint = palette.inkPrimary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "STREAMING ENGINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent
                )
                Text(
                    text = "Streaming & Remote Config",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val syncBadge = when {
                remoteConfigStatus.wasNotModified304 -> "304 FRESH"
                remoteConfigStatus.isFromCache -> "CACHED"
                else -> "SYNCED"
            }
            InkStampTag(
                text = syncBadge,
                accented = !remoteConfigStatus.isFromCache
            )
            Spacer(Modifier.width(6.dp))
        }

        PaperDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Compact Top Summary Bar + Copy Diagnostics Report
            item {
                PaperCard(
                    containerColor = palette.paperElevated,
                    elevation = 3.dp,
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("streaming_diagnostics_summary_bar")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "LIVE ENDPOINT HEALTH",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accent
                                )
                                Text(
                                    text = "$healthyEndpointCount healthy · $failingEndpointCount failing" +
                                        if (disabledProvidersCount > 0) " · $disabledProvidersCount disabled" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (failingEndpointCount > 0) palette.danger else palette.inkPrimary,
                                    modifier = Modifier.testTag("diagnostics_summary_counts")
                                )
                            }
                            if (remoteConfigStatus.hasLocalOverrides) {
                                InkStampTag(text = "OVERRIDDEN", accented = true)
                            }
                        }

                        TactilePaperButton(
                            text = "Copy Diagnostics Report",
                            icon = Icons.Rounded.ContentCopy,
                            filled = false,
                            onClick = {
                                val report = onBuildDiagnosticsReport()
                                clipboard.setText(AnnotatedString(report))
                                onNotifyMessage("Diagnostics report copied to clipboard")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("copy_diagnostics_report_btn")
                        )
                    }
                }
            }

            // Remote Configuration Source & Controls Card
            item {
                PaperCard(
                    containerColor = palette.paperElevated,
                    elevation = 3.dp,
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PaperDebossedWell(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("github_config_status_card"),
                            cornerRadius = 12.dp,
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "GITHUB PROVIDER CONFIGURATION",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accent
                                )

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
                                    text = "$enabledMusicProviders/$totalMusicProviders music · " +
                                        "${remoteConfigStatus.enabledLyricsProviderCount}/${remoteConfigStatus.totalLyricsProviderCount} lyrics active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkPrimary,
                                    modifier = Modifier.testTag("remote_config_counts_summary")
                                )

                                if (categories.isNotEmpty()) {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.testTag("remote_config_categories_row")
                                    ) {
                                        items(categories) { category ->
                                            InkStampTag(text = category, accented = false)
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
                                    modifier = Modifier
                                        .sizeIn(minHeight = 48.dp)
                                        .testTag("remote_config_reset_default_btn")
                                ) {
                                    Text(
                                        text = "Reset Default",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accent
                                    )
                                }
                            }
                        }

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
                                    contentDescription = "Remote config URL",
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
                            Text(
                                text = "Edited URL is not active yet. Test Connection or Save URL to validate JSON and schema first.",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkSecondary,
                                modifier = Modifier.testTag("remote_config_unsaved_hint")
                            )
                        }

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
                                    onSaveRemoteConfigUrl(draftUrl, false)
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
                                    if (remoteConfigStatus.hasLocalOverrides) {
                                        showRefreshOverrideDialog = true
                                    } else {
                                        onRefreshRemoteConfig(null, false)
                                    }
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

                        if (remoteConfigStatus.hasLocalOverrides) {
                            TactilePaperButton(
                                text = "Reset Overrides to Remote Defaults",
                                icon = Icons.Rounded.Restore,
                                filled = false,
                                enabled = !isBusy,
                                onClick = onResetOverrides,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("reset_provider_overrides_btn")
                            )
                        }

                        if (remoteConfigConnectionState !is RemoteConfigConnectionState.Idle) {
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
                    }
                }
            }

            // Music Providers Section
            if (providerDiagnostics.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PROVIDER & ENDPOINT DIAGNOSTICS",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent
                        )
                        Text(
                            text = "Music: $enabledMusicProviders/$totalMusicProviders",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary
                        )
                    }
                }

                items(
                    items = providerDiagnostics,
                    key = { "music_${it.providerId}" }
                ) { diag ->
                    val index = providerDiagnostics.indexOf(diag)
                    MusicProviderDiagnosticsCard(
                        diagnostics = diag,
                        testResult = providerTestResults[diag.providerId.lowercase()],
                        isTesting = isTestingProviders,
                        canMoveUp = index > 0,
                        canMoveDown = index < providerDiagnostics.lastIndex,
                        onToggleEnabled = { onToggleMusicProviderEnabled(diag.providerId) },
                        onMoveUp = { onMoveMusicProviderUp(diag.providerId) },
                        onMoveDown = { onMoveMusicProviderDown(diag.providerId) },
                        onTestProvider = { onTestProvider(diag.providerId) }
                    )
                }
            }

            // Dedicated Lyrics Providers Diagnostic Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("lyrics_providers_section_header"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LYRICS PROVIDERS",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                    Text(
                        text = "Lyrics endpoints: ${lyricsProviderDiagnostics.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkSecondary,
                        modifier = Modifier.testTag("lyrics_endpoints_count_label")
                    )
                }
            }

            if (lyricsProviderDiagnostics.isEmpty()) {
                item {
                    PaperDebossedWell(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 12.dp,
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Text(
                            text = "No lyrics endpoints configured in active providers.json.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
                        )
                    }
                }
            } else {
                items(
                    items = lyricsProviderDiagnostics,
                    key = { "lyrics_${it.providerId}" }
                ) { ldiag ->
                    val index = lyricsProviderDiagnostics.indexOf(ldiag)
                    LyricsProviderDiagnosticsCard(
                        diagnostics = ldiag,
                        testResult = lyricsProviderTestResults[ldiag.providerId.lowercase()],
                        isTesting = isTestingProviders,
                        canMoveUp = index > 0,
                        canMoveDown = index < lyricsProviderDiagnostics.lastIndex,
                        onToggleEnabled = { onToggleLyricsProviderEnabled(ldiag.providerId) },
                        onMoveUp = { onMoveLyricsProviderUp(ldiag.providerId) },
                        onMoveDown = { onMoveLyricsProviderDown(ldiag.providerId) },
                        onTestProvider = { onTestLyricsProvider(ldiag.providerId) }
                    )
                }
            }
        }
    }

    if (showRefreshOverrideDialog) {
        PaperAlertDialog(
            onDismissRequest = { showRefreshOverrideDialog = false },
            title = "Refresh With Local Overrides?",
            text = {
                Text(
                    text = "You have local enable/disable or priority overrides active. Would you like to keep your local overrides or reset back to the remote providers.json defaults?",
                    color = palette.inkPrimary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRefreshOverrideDialog = false
                        onRefreshRemoteConfig(null, true)
                    },
                    modifier = Modifier.testTag("refresh_reset_overrides_confirm_btn")
                ) {
                    Text("Reset Overrides", color = palette.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRefreshOverrideDialog = false
                        onRefreshRemoteConfig(null, false)
                    },
                    modifier = Modifier.testTag("refresh_keep_overrides_btn")
                ) {
                    Text("Keep Overrides", color = palette.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun MusicProviderDiagnosticsCard(
    diagnostics: ProviderDiagnosticsSnapshot,
    testResult: ProviderConnectivityTestResult?,
    isTesting: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleEnabled: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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

    PaperCard(
        containerColor = palette.paperElevated,
        elevation = 2.dp,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider_diag_card_${diagnostics.providerId}")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = diagnostics.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (diagnostics.hasLocalOverride) {
                            InkStampTag(text = "OVERRIDE", accented = true)
                        }
                    }
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
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = diagnostics.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.onAccent,
                        checkedTrackColor = palette.accent,
                        uncheckedTrackColor = palette.paperPressed
                    ),
                    modifier = Modifier.testTag("provider_toggle_${diagnostics.providerId}")
                )
            }

            val activeHosts = buildList {
                diagnostics.activeSearchEndpoint?.let { add("Search: ${compactHostLabel(it)}") }
                diagnostics.activeMetadataEndpoint?.let { add("Meta: ${compactHostLabel(it)}") }
                diagnostics.activePlaybackEndpoint?.let { add("Stream: ${compactHostLabel(it)}") }
                if (diagnostics.supportsLyrics) {
                    diagnostics.activeLyricsEndpoint?.let { add("Lyrics: ${compactHostLabel(it)}") }
                }
            }
            if (activeHosts.isNotEmpty()) {
                Text(
                    text = activeHosts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkPrimary
                )
            }

            val orderedOps = listOf(
                OperationType.SEARCH,
                OperationType.METADATA,
                OperationType.PLAYBACK,
                OperationType.LYRICS
            )
            orderedOps.forEach { op ->
                val endpoints = diagnostics.operationHealth[op].orEmpty()
                if (endpoints.isNotEmpty() && (op != OperationType.LYRICS || diagnostics.supportsLyrics)) {
                    EndpointOperationSection(
                        label = op.label.uppercase(),
                        endpoints = endpoints
                    )
                }
            }

            if (testResult != null) {
                ProbeResultBanner(result = testResult)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Order #${diagnostics.priority}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkSecondary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("provider_up_${diagnostics.providerId}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = "Increase ${diagnostics.displayName} priority",
                            tint = if (canMoveUp) palette.accent else palette.inkTertiary
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("provider_down_${diagnostics.providerId}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Decrease ${diagnostics.displayName} priority",
                            tint = if (canMoveDown) palette.accent else palette.inkTertiary
                        )
                    }
                }

                TextButton(
                    onClick = onTestProvider,
                    enabled = !isTesting && diagnostics.enabled,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("test_provider_btn_${diagnostics.providerId}")
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

@Composable
private fun LyricsProviderDiagnosticsCard(
    diagnostics: LyricsProviderDiagnosticsSnapshot,
    testResult: ProviderConnectivityTestResult?,
    isTesting: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleEnabled: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTestProvider: () -> Unit
) {
    val palette = AriaPaperTheme.palette
    val healthLabel = when {
        !diagnostics.enabled -> "DISABLED"
        !diagnostics.isHealthy -> "DEGRADED"
        else -> "HEALTHY"
    }

    PaperCard(
        containerColor = palette.paperElevated,
        elevation = 2.dp,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lyrics_provider_diag_card_${diagnostics.providerId}")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = diagnostics.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (diagnostics.hasLocalOverride) {
                            InkStampTag(text = "OVERRIDE", accented = true)
                        }
                    }
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
                    accented = diagnostics.enabled && diagnostics.isHealthy
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = diagnostics.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.onAccent,
                        checkedTrackColor = palette.accent,
                        uncheckedTrackColor = palette.paperPressed
                    ),
                    modifier = Modifier.testTag("lyrics_provider_toggle_${diagnostics.providerId}")
                )
            }

            if (diagnostics.endpoints.isNotEmpty()) {
                EndpointOperationSection(
                    label = "LYRICS ENDPOINTS",
                    endpoints = diagnostics.endpoints
                )
            } else {
                Text(
                    text = "No lyrics endpoints resolved",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSecondary
                )
            }

            if (!diagnostics.lastError.isNullOrBlank() && diagnostics.endpoints.none { !it.lastError.isNullOrBlank() }) {
                Text(
                    text = "Last failure: ${diagnostics.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.danger
                )
            }

            if (testResult != null) {
                ProbeResultBanner(result = testResult)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Order #${diagnostics.priority}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkSecondary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("lyrics_provider_up_${diagnostics.providerId}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = "Increase ${diagnostics.displayName} lyrics priority",
                            tint = if (canMoveUp) palette.accent else palette.inkTertiary
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("lyrics_provider_down_${diagnostics.providerId}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Decrease ${diagnostics.displayName} lyrics priority",
                            tint = if (canMoveDown) palette.accent else palette.inkTertiary
                        )
                    }
                }

                TextButton(
                    onClick = onTestProvider,
                    enabled = !isTesting && diagnostics.enabled,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("test_lyrics_provider_btn_${diagnostics.providerId}")
                ) {
                    Text(
                        text = "Test Provider",
                        color = if (diagnostics.enabled) palette.accent else palette.inkTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointOperationSection(
    label: String,
    endpoints: List<EndpointHealthInfo>
) {
    val palette = AriaPaperTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
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
                text = "• [P${ep.priority}] ${ep.url} · $statusPart$latencyPart",
                style = MaterialTheme.typography.bodySmall,
                color = if (ep.isHealthy && ep.consecutiveFailures == 0) palette.inkPrimary else palette.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!ep.lastError.isNullOrBlank()) {
                Text(
                    text = "  Error: ${ep.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProbeResultBanner(result: ProviderConnectivityTestResult) {
    val palette = AriaPaperTheme.palette
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = if (result.success) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = if (result.success) "Endpoint reachable" else "Endpoint probe failed",
            tint = if (result.success) palette.accent else palette.danger,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = buildString {
                append(if (result.success) "Reachable (${result.latencyMs}ms)" else "Test failed (${result.latencyMs}ms)")
                result.testedEndpoint?.let { append(" · ${compactHostLabel(it)}") }
                if (result.failoverAttempts > 0) append(" · ${result.failoverAttempts} failover(s)")
                result.errorSummary?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (result.success) palette.inkPrimary else palette.danger
        )
    }
}

private fun compactHostLabel(rawUrl: String): String {
    return runCatching {
        val parsed = Uri.parse(rawUrl)
        parsed.host?.takeIf { it.isNotBlank() } ?: rawUrl
    }.getOrDefault(rawUrl)
}
