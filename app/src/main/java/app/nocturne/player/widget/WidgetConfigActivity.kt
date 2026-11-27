package app.nocturne.player.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.nocturne.player.NocturneTheme
import app.nocturne.player.ui.components.InkStampTag
import app.nocturne.player.ui.components.PaperCard
import app.nocturne.player.ui.components.PaperDivider
import app.nocturne.player.ui.components.PaperFilterChip
import app.nocturne.player.ui.components.TactilePaperButton
import app.nocturne.player.ui.components.paperGrainBackground
import app.nocturne.player.ui.theme.AriaPaperTheme

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val providerClass = runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
        }.getOrNull()

        val kind = when (providerClass) {
            DailyStripWidgetProvider::class.java.name -> NewsprintWidgetKind.DAILY_STRIP
            FrontPageWidgetProvider::class.java.name -> NewsprintWidgetKind.FRONT_PAGE
            else -> NewsprintWidgetKind.SUNDAY_BROADSHEET
        }

        val initialSharp = WidgetStatePrefs.getWidgetSharpCorners(this, appWidgetId)
        val snapshotState = WidgetStatePrefs.loadState(this)

        setContent {
            NocturneTheme {
                val palette = AriaPaperTheme.palette
                var sharpCorners by remember { mutableStateOf(initialSharp) }

                val previewBitmap = remember(kind, sharpCorners, snapshotState) {
                    val previewW = when (kind) {
                        NewsprintWidgetKind.DAILY_STRIP -> 320
                        NewsprintWidgetKind.FRONT_PAGE -> 200
                        NewsprintWidgetKind.SUNDAY_BROADSHEET -> 320
                    }
                    val previewH = when (kind) {
                        NewsprintWidgetKind.DAILY_STRIP -> 96
                        NewsprintWidgetKind.FRONT_PAGE -> 200
                        NewsprintWidgetKind.SUNDAY_BROADSHEET -> 260
                    }
                    NewspaperWidgetRenderer.renderWidgetBitmap(
                        context = this@WidgetConfigActivity,
                        kind = kind,
                        state = snapshotState.copy(
                            title = if (snapshotState.trackId.isBlank() && snapshotState.uriString.isBlank()) {
                                "Midnight Pressing in C Minor"
                            } else snapshotState.title,
                            artist = if (snapshotState.trackId.isBlank() && snapshotState.uriString.isBlank()) {
                                "The ARIA Quartet"
                            } else snapshotState.artist,
                            activeLyricLine = snapshotState.activeLyricLine
                                ?: "“Silver needles trace the quiet grooves until morning breaks.”"
                        ),
                        widthDp = previewW,
                        heightDp = previewH,
                        sharpCorners = sharpCorners,
                        artworkBitmap = null
                    )
                }

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .paperGrainBackground(palette.paperBackground),
                    color = Color.Transparent
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Newspaper,
                                contentDescription = null,
                                tint = palette.accent
                            )
                            Column {
                                Text(
                                    text = "Configure Newsprint Widget",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = palette.inkPrimary
                                )
                                Text(
                                    text = when (kind) {
                                        NewsprintWidgetKind.DAILY_STRIP -> "The Daily Strip (4x1 / 4x2)"
                                        NewsprintWidgetKind.FRONT_PAGE -> "Front Page Headline (2x2 / 3x2)"
                                        NewsprintWidgetKind.SUNDAY_BROADSHEET -> "Sunday Broadsheet (4x3 / 4x4)"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.inkSecondary
                                )
                            }
                        }

                        PaperDivider(doubleRule = true)

                        PaperCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = palette.paperCream,
                            elevation = 2.dp,
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            InkStampTag(text = "LIVE PRESS PREVIEW", accented = true)
                            Spacer(Modifier.height(12.dp))
                            AndroidView(
                                factory = { ctx ->
                                    ImageView(ctx).apply {
                                        adjustViewBounds = true
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                    }
                                },
                                update = { imageView ->
                                    imageView.setImageBitmap(previewBitmap)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        PaperCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = palette.paperElevated,
                            elevation = 2.dp,
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Text(
                                text = "Edition Corner Trim",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Choose classic sharp broadsheet corners or modern rounded card edges. You can also adjust this anytime in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                PaperFilterChip(
                                    label = "Sharp Corners",
                                    selected = sharpCorners,
                                    onClick = { sharpCorners = true },
                                    leadingIcon = Icons.Rounded.CropSquare,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("widget_config_sharp_chip")
                                )
                                PaperFilterChip(
                                    label = "Rounded Corners",
                                    selected = !sharpCorners,
                                    onClick = { sharpCorners = false },
                                    leadingIcon = Icons.Rounded.RoundedCorner,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("widget_config_rounded_chip")
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        TactilePaperButton(
                            text = "Apply & Place Edition",
                            icon = Icons.Rounded.Check,
                            onClick = {
                                WidgetStatePrefs.setWidgetSharpCorners(
                                    this@WidgetConfigActivity,
                                    appWidgetId,
                                    sharpCorners
                                )
                                WidgetUpdater.refreshWidgetById(
                                    this@WidgetConfigActivity,
                                    kind,
                                    appWidgetId
                                )
                                val resultIntent = Intent().putExtra(
                                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                                    appWidgetId
                                )
                                setResult(Activity.RESULT_OK, resultIntent)
                                finish()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("widget_config_apply_btn")
                        )
                    }
                }
            }
        }
    }
}
