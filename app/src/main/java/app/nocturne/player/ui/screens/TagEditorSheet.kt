package app.nocturne.player.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.audio.EditableTagPayload
import app.nocturne.player.data.audio.TagWriteResult
import app.nocturne.player.data.library.ArtworkCache
import app.nocturne.player.data.model.Track
import app.nocturne.player.ui.components.PaperAlertDialog
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PrintedArtwork
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.paperGrainBackground
import app.nocturne.player.ui.theme.AriaPaperTheme
import kotlinx.coroutines.launch

@Composable
fun TagEditorScreen(
    track: Track,
    onDismiss: () -> Unit,
    onSave: suspend (EditableTagPayload, String?) -> TagWriteResult,
    onResultMessage: (String) -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val palette = AriaPaperTheme.palette
    val scope = rememberCoroutineScope()

    var title by remember(track.uriString) { mutableStateOf(track.title) }
    var artist by remember(track.uriString) { mutableStateOf(track.artist) }
    var album by remember(track.uriString) { mutableStateOf(track.album) }
    var albumArtist by remember(track.uriString) { mutableStateOf(track.albumArtist) }
    var genre by remember(track.uriString) { mutableStateOf(track.genre) }
    var yearText by remember(track.uriString) { mutableStateOf(if (track.year > 0) track.year.toString() else "") }
    var trackNumText by remember(track.uriString) { mutableStateOf(if (track.trackNumber > 0) track.trackNumber.toString() else "") }
    var discNumText by remember(track.uriString) { mutableStateOf(if (track.discNumber > 0) track.discNumber.toString() else "") }
    var composer by remember(track.uriString) { mutableStateOf(track.composer) }
    var lyrics by remember(track.uriString) { mutableStateOf(track.embeddedLyrics.orEmpty()) }
    var pickedCoverUri by remember(track.uriString) { mutableStateOf(track.customCoverUri) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    fun buildPayload(): EditableTagPayload {
        val artBytes = pickedCoverUri?.let { ArtworkCache.loadArtworkBytes(context, android.net.Uri.parse(it)) }
        return EditableTagPayload(
            title = title.trim().ifBlank { track.title },
            artist = artist.trim().ifBlank { "Unknown Artist" },
            album = album.trim().ifBlank { "Unknown Album" },
            albumArtist = albumArtist.trim().ifBlank { artist.trim() },
            genre = genre.trim().ifBlank { "Unclassified" },
            year = yearText.trim().toIntOrNull() ?: 0,
            trackNumber = trackNumText.trim().toIntOrNull() ?: 0,
            discNumber = discNumText.trim().toIntOrNull() ?: 0,
            composer = composer.trim(),
            lyrics = lyrics,
            artworkBytes = artBytes
        )
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            scope.launch {
                isSaving = true
                when (val result = onSave(buildPayload(), pickedCoverUri)) {
                    TagWriteResult.SavedToFileAndIndex -> {
                        onResultMessage("Saved tags to audio file")
                        onDismiss()
                    }
                    TagWriteResult.SavedToIndexOnly -> {
                        onResultMessage("Saved tags in ARIA library index")
                        onDismiss()
                    }
                    is TagWriteResult.Failed -> onResultMessage(result.reason)
                    is TagWriteResult.RequiresSystemWritePermission -> Unit
                }
                isSaving = false
            }
        } else {
            onResultMessage("File write permission declined; changes kept in local index")
            onDismiss()
        }
    }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pickedCoverUri = uri.toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .paperGrainBackground(palette.paperBackground)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, "Close editor", tint = palette.inkPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text("TAG EDITOR", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                Text("Edit Track Metadata", style = MaterialTheme.typography.headlineSmall, color = palette.inkPrimary)
            }
            TactilePaperButton(
                text = if (isSaving) "Saving..." else "Save",
                icon = Icons.Rounded.Save,
                enabled = !isSaving,
                onClick = { showConfirmDialog = true }
            )
        }

        Spacer(Modifier.height(10.dp))
        PaperDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrintedArtwork(
                    trackUri = track.uriString,
                    customCoverUri = pickedCoverUri,
                    folderPath = track.folderPath,
                    modifier = Modifier.size(92.dp),
                    mattePadding = 4.dp
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Album Artwork", style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pick a photo from your device to embed or override cover art.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    TactilePaperButton(
                        text = "Choose Cover",
                        icon = Icons.Rounded.Image,
                        filled = false,
                        onClick = {
                            coverPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
            }

            TagField("Song Title", title) { title = it }
            TagField("Artist", artist) { artist = it }
            TagField("Album", album) { album = it }
            TagField("Album Artist", albumArtist) { albumArtist = it }
            TagField("Genre", genre) { genre = it }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TagField("Year", yearText, Modifier.weight(1f)) { yearText = it.filter(Char::isDigit).take(4) }
                TagField("Track #", trackNumText, Modifier.weight(1f)) { trackNumText = it.filter(Char::isDigit).take(3) }
                TagField("Disc #", discNumText, Modifier.weight(1f)) { discNumText = it.filter(Char::isDigit).take(2) }
            }

            TagField("Composer", composer) { composer = it }

            OutlinedTextField(
                value = lyrics,
                onValueChange = { lyrics = it },
                label = { Text("Lyrics / LRC") },
                minLines = 4,
                maxLines = 8,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.divider
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showConfirmDialog) {
        PaperAlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = "Save Metadata Changes?",
            text = {
                Text(
                    "ARIA updates its local library index immediately and writes ID3 tags safely for supported MP3 files without altering audio frames.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TactilePaperButton(
                    text = "Confirm Save",
                    onClick = {
                        showConfirmDialog = false
                        scope.launch {
                            isSaving = true
                            when (val result = onSave(buildPayload(), pickedCoverUri)) {
                                TagWriteResult.SavedToFileAndIndex -> {
                                    onResultMessage("Saved tags to file & library")
                                    onDismiss()
                                }
                                TagWriteResult.SavedToIndexOnly -> {
                                    onResultMessage("Saved tags in ARIA library index")
                                    onDismiss()
                                }
                                is TagWriteResult.RequiresSystemWritePermission -> {
                                    writePermissionLauncher.launch(
                                        IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                                    )
                                }
                                is TagWriteResult.Failed -> {
                                    onResultMessage(result.reason)
                                }
                            }
                            isSaving = false
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel", color = palette.inkSecondary)
                }
            }
        )
    }
}

@Composable
private fun TagField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValueChange: (String) -> Unit
) {
    val palette = AriaPaperTheme.palette
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.accent,
            unfocusedBorderColor = palette.divider
        ),
        modifier = modifier
    )
}
