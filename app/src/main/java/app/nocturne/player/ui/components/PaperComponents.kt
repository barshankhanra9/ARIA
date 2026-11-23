package app.nocturne.player.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nocturne.player.data.library.ArtworkCache
import app.nocturne.player.data.model.ArtworkShape
import app.nocturne.player.data.model.Track
import app.nocturne.player.data.security.InputAndUrlSecurity
import app.nocturne.player.ui.theme.AriaPaperTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.random.Random

private object PaperGrainPatternCache {
    private var lightTile: ImageBitmap? = null
    private var darkTile: ImageBitmap? = null

    fun getTile(isDark: Boolean): ImageBitmap {
        val existing = if (isDark) darkTile else lightTile
        if (existing != null) return existing

        val size = 160
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rng = Random(if (isDark) 4219 else 1942)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val fiberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 0.9f
            style = Paint.Style.STROKE
        }

        // Subtle laid-paper horizontal watermark lines (newsprint feel)
        val laidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 0.7f
            style = Paint.Style.STROKE
            color = if (isDark) {
                android.graphics.Color.argb(9, 232, 222, 204)
            } else {
                android.graphics.Color.argb(9, 108, 90, 68)
            }
        }
        var yLine = 8f
        while (yLine < size) {
            canvas.drawLine(0f, yLine, size.toFloat(), yLine, laidPaint)
            yLine += 16f
        }

        // Fine paper grain speckles
        for (i in 0 until 820) {
            val x = rng.nextFloat() * size
            val y = rng.nextFloat() * size
            val isLightSpeck = rng.nextBoolean()
            dotPaint.color = when {
                isDark && isLightSpeck -> android.graphics.Color.argb(14, 240, 232, 218)
                isDark -> android.graphics.Color.argb(22, 0, 0, 0)
                isLightSpeck -> android.graphics.Color.argb(20, 255, 253, 248)
                else -> android.graphics.Color.argb(17, 90, 72, 52)
            }
            canvas.drawCircle(x, y, if (rng.nextFloat() > 0.72f) 1.0f else 0.6f, dotPaint)
        }

        // Fine microscopic paper fibers
        for (i in 0 until 32) {
            val x1 = rng.nextFloat() * size
            val y1 = rng.nextFloat() * size
            val dx = (rng.nextFloat() - 0.5f) * 20f
            val dy = (rng.nextFloat() - 0.5f) * 14f
            fiberPaint.color = if (isDark) {
                android.graphics.Color.argb(12, 224, 214, 198)
            } else {
                android.graphics.Color.argb(14, 118, 98, 76)
            }
            canvas.drawLine(x1, y1, x1 + dx, y1 + dy, fiberPaint)
        }

        val imageBitmap = bmp.asImageBitmap()
        if (isDark) darkTile = imageBitmap else lightTile = imageBitmap
        return imageBitmap
    }
}

fun Modifier.paperGrainBackground(
    baseColor: Color? = null,
    intensityOverride: Float? = null
): Modifier = composed {
    val style = AriaPaperTheme.style
    val palette = style.palette
    val resolvedColor = baseColor ?: palette.paperBackground
    val intensity = (intensityOverride ?: style.textureIntensity).coerceIn(0f, 1f)
    val tile = remember(palette.isDark) { PaperGrainPatternCache.getTile(palette.isDark) }
    val shaderBrush = remember(tile) {
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }

    drawBehind {
        drawRect(color = resolvedColor)
        if (intensity > 0.02f && !palette.isAmoled) {
            drawRect(brush = shaderBrush, alpha = intensity)
        } else if (intensity > 0.02f && palette.isAmoled) {
            drawRect(brush = shaderBrush, alpha = intensity * 0.25f)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    containerColor: Color = AriaPaperTheme.palette.paperElevated,
    cornerRadius: Dp = 8.dp,
    elevation: Dp = 4.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = AriaPaperTheme.palette
    val motion = AriaPaperTheme.motion
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && (onClick != null || onLongClick != null)) motion.pressScale else 1f,
        animationSpec = motion.springSpec,
        label = "paper_press_scale"
    )

    val shape = RoundedCornerShape(cornerRadius)
    val borderColor = if (selected) palette.accent else palette.divider.copy(alpha = 0.92f)
    val borderWidth = if (selected) 1.4.dp else 0.9.dp

    val clickableMod = when {
        onLongClick != null -> Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = androidx.compose.material3.ripple(color = palette.accent),
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick
        )
        onClick != null -> Modifier.clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.material3.ripple(color = palette.accent),
            onClick = onClick
        )
        else -> Modifier
    }

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (elevation > 0.dp && !palette.isAmoled) {
                    Modifier.shadow(
                        elevation = if (isPressed) (elevation / 2) else elevation,
                        shape = shape,
                        ambientColor = palette.ambientShadow.copy(alpha = 0.22f),
                        spotColor = palette.ambientShadow.copy(alpha = 0.24f)
                    )
                } else Modifier
            )
            .clip(shape)
            .paperGrainBackground(baseColor = if (selected) palette.accentSoft.compositeOver(containerColor) else containerColor)
            .drawBehind {
                if (!palette.isAmoled) {
                    drawLine(
                        color = palette.edgeHighlight.copy(alpha = if (palette.isDark) 0.12f else 0.7f),
                        start = Offset(0f, 1f),
                        end = Offset(size.width, 1f),
                        strokeWidth = 1.5f
                    )
                }
            }
            .border(BorderStroke(borderWidth, borderColor), shape)
            .then(clickableMod),
        shape = shape,
        color = Color.Transparent,
        contentColor = palette.inkPrimary
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = this.alpha
    return Color(
        red = this.red * a + background.red * (1f - a),
        green = this.green * a + background.green * (1f - a),
        blue = this.blue * a + background.blue * (1f - a),
        alpha = 1f
    )
}

@Composable
fun PaperDebossedWell(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val palette = AriaPaperTheme.palette
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(palette.paperPressed)
            .drawBehind {
                // Subtle top-inner shadow and bottom-inner paper highlight for debossed depth
                drawLine(
                    color = palette.ambientShadow.copy(alpha = if (palette.isDark) 0.45f else 0.12f),
                    start = Offset(0f, 1.5f),
                    end = Offset(size.width, 1.5f),
                    strokeWidth = 3f
                )
                drawLine(
                    color = palette.edgeHighlight.copy(alpha = 0.45f),
                    start = Offset(0f, size.height - 1f),
                    end = Offset(size.width, size.height - 1f),
                    strokeWidth = 2f
                )
            }
            .border(1.dp, palette.divider.copy(alpha = 0.8f), shape)
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun PrintedArtwork(
    trackUri: String?,
    customCoverUri: String? = null,
    folderPath: String = "",
    modifier: Modifier = Modifier,
    artworkShape: ArtworkShape = AriaPaperTheme.style.artworkShape,
    mattePadding: Dp = 4.dp,
    preferFolderCover: Boolean = true,
    targetSizePx: Int = 512,
    contentDescription: String = "Album artwork"
) {
    val context = LocalContext.current
    val palette = AriaPaperTheme.palette
    val motion = AriaPaperTheme.motion

    val remoteUrl = remember(customCoverUri, trackUri) {
        val candidate = customCoverUri?.takeIf { it.isNotBlank() } ?: trackUri
        InputAndUrlSecurity.validateRemoteHttpsUrl(candidate)
    }

    var imageBitmap by remember(trackUri, customCoverUri, remoteUrl, targetSizePx) {
        mutableStateOf(
            if (remoteUrl == null) {
                ArtworkCache.getCached("${customCoverUri ?: trackUri}@$targetSizePx")
            } else null
        )
    }

    LaunchedEffect(trackUri, customCoverUri, remoteUrl, folderPath, preferFolderCover, targetSizePx) {
        imageBitmap = if (remoteUrl == null && (!trackUri.isNullOrBlank() || !customCoverUri.isNullOrBlank())) {
            ArtworkCache.loadArtwork(
                context = context,
                trackUri = trackUri.orEmpty(),
                customCoverUri = customCoverUri,
                folderPath = folderPath,
                preferFolderCover = preferFolderCover,
                targetSizePx = targetSizePx
            )
        } else {
            null
        }
    }

    val outerShape: Shape = when (artworkShape) {
        ArtworkShape.PHOTO_PRINT -> RoundedCornerShape(4.dp)
        ArtworkShape.SOFT_SQUARE -> RoundedCornerShape(10.dp)
        ArtworkShape.ROUNDED_JOURNAL -> RoundedCornerShape(16.dp)
        ArtworkShape.VINYL_SLEEVE -> CircleShape
    }
    val innerShape: Shape = when (artworkShape) {
        ArtworkShape.PHOTO_PRINT -> RoundedCornerShape(2.dp)
        ArtworkShape.SOFT_SQUARE -> RoundedCornerShape(7.dp)
        ArtworkShape.ROUNDED_JOURNAL -> RoundedCornerShape(12.dp)
        ArtworkShape.VINYL_SLEEVE -> CircleShape
    }

    Box(
        modifier = modifier
            .then(
                if (!palette.isAmoled) {
                    Modifier.shadow(
                        elevation = 5.dp,
                        shape = outerShape,
                        ambientColor = palette.ambientShadow.copy(alpha = 0.25f),
                        spotColor = palette.ambientShadow.copy(alpha = 0.25f)
                    )
                } else Modifier
            )
            .clip(outerShape)
            .background(palette.photoMatte)
            .border(1.dp, palette.divider.copy(alpha = 0.7f), outerShape)
            .padding(mattePadding)
            .clip(innerShape)
            .background(palette.paperCream),
        contentAlignment = Alignment.Center
    ) {
        if (remoteUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(palette.paperCream, palette.paperPressed)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (artworkShape == ArtworkShape.VINYL_SLEEVE) {
                        Icons.Rounded.Album
                    } else {
                        Icons.Rounded.MusicNote
                    },
                    contentDescription = null,
                    tint = palette.accent.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxSize(0.38f)
                )
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(remoteUrl)
                        .crossfade(!motion.isReduced)
                        .size(targetSizePx.coerceIn(96, 1024))
                        .build(),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Crossfade(
                targetState = imageBitmap,
                animationSpec = tween(durationMillis = motion.durationMs),
                label = "artwork_crossfade"
            ) { bmp ->
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        palette.paperCream,
                                        palette.paperPressed
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (artworkShape == ArtworkShape.VINYL_SLEEVE) {
                                Icons.Rounded.Album
                            } else {
                                Icons.Rounded.MusicNote
                            },
                            contentDescription = null,
                            tint = palette.accent.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxSize(0.42f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistArtworkCollage(
    tracks: List<Track>,
    customCoverUri: String?,
    modifier: Modifier = Modifier
) {
    val palette = AriaPaperTheme.palette
    if (!customCoverUri.isNullOrBlank() || tracks.size < 4) {
        PrintedArtwork(
            trackUri = tracks.firstOrNull()?.uriString,
            customCoverUri = customCoverUri ?: tracks.firstOrNull()?.customCoverUri,
            folderPath = tracks.firstOrNull()?.folderPath.orEmpty(),
            modifier = modifier,
            mattePadding = 4.dp,
            targetSizePx = 384
        )
    } else {
        val sample = tracks.take(4)
        Box(
            modifier = modifier
                .shadow(4.dp, RoundedCornerShape(10.dp), ambientColor = palette.ambientShadow.copy(alpha = 0.2f))
                .clip(RoundedCornerShape(10.dp))
                .background(palette.photoMatte)
                .border(1.dp, palette.divider, RoundedCornerShape(10.dp))
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    PrintedArtwork(
                        trackUri = sample[0].uriString,
                        customCoverUri = sample[0].customCoverUri,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        artworkShape = ArtworkShape.PHOTO_PRINT,
                        mattePadding = 0.dp,
                        targetSizePx = 256
                    )
                    PrintedArtwork(
                        trackUri = sample[1].uriString,
                        customCoverUri = sample[1].customCoverUri,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        artworkShape = ArtworkShape.PHOTO_PRINT,
                        mattePadding = 0.dp,
                        targetSizePx = 256
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    PrintedArtwork(
                        trackUri = sample[2].uriString,
                        customCoverUri = sample[2].customCoverUri,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        artworkShape = ArtworkShape.PHOTO_PRINT,
                        mattePadding = 0.dp,
                        targetSizePx = 256
                    )
                    PrintedArtwork(
                        trackUri = sample[3].uriString,
                        customCoverUri = sample[3].customCoverUri,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        artworkShape = ArtworkShape.PHOTO_PRINT,
                        mattePadding = 0.dp,
                        targetSizePx = 256
                    )
                }
            }
        }
    }
}

@Composable
fun PaperDivider(
    modifier: Modifier = Modifier,
    doubleRule: Boolean = false
) {
    val palette = AriaPaperTheme.palette
    val ruleColor = palette.inkPrimary.copy(alpha = if (palette.isDark) 0.24f else 0.28f)
    val subColor = palette.divider
    if (doubleRule) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.5.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(ruleColor)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .background(subColor)
            )
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(1.dp)
                .drawBehind {
                    drawLine(
                        color = subColor,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.2f
                    )
                }
        )
    }
}

@Composable
fun InkStampTag(
    text: String,
    modifier: Modifier = Modifier,
    accented: Boolean = false
) {
    val palette = AriaPaperTheme.palette
    val borderColor = if (accented) palette.accent.copy(alpha = 0.65f) else palette.divider
    val bgColor = if (accented) palette.accentSoft else palette.paperCream
    val textColor = if (accented) palette.accent else palette.inkSecondary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bgColor)
            .border(0.8.dp, borderColor, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1
        )
    }
}

@Composable
fun AnimatedInkEqualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = AriaPaperTheme.palette.accent
) {
    val motion = AriaPaperTheme.motion
    if (!isPlaying || motion.isReduced) {
        Row(
            modifier = modifier.height(14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val heights = if (isPlaying) listOf(0.55f, 0.95f, 0.7f) else listOf(0.3f, 0.45f, 0.3f)
            heights.forEach { frac ->
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height((14f * frac).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color)
                )
            }
        }
    } else {
        val transition = rememberInfiniteTransition(label = "ink_eq")
        val bar1 by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
            label = "b1"
        )
        val bar2 by transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(tween(340), RepeatMode.Reverse),
            label = "b2"
        )
        val bar3 by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(490), RepeatMode.Reverse),
            label = "b3"
        )
        Row(
            modifier = modifier.height(14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            listOf(bar1, bar2, bar3).forEach { frac ->
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height((14f * frac).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color)
                )
            }
        }
    }
}

@Composable
fun EditorialSectionHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = AriaPaperTheme.palette
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(palette.accent, RoundedCornerShape(1.dp))
                    )
                    Text(
                        text = eyebrow.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailing != null) {
                trailing()
            }
        }
        Spacer(Modifier.height(6.dp))
        PaperDivider(doubleRule = false)
    }
}

@Composable
fun TactilePaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = true,
    enabled: Boolean = true
) {
    val palette = AriaPaperTheme.palette
    val motion = AriaPaperTheme.motion
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) motion.pressScale else 1f,
        animationSpec = motion.springSpec,
        label = "btn_scale"
    )
    val shape = RoundedCornerShape(12.dp)

    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = palette.onAccent,
                disabledContainerColor = palette.paperPressed,
                disabledContentColor = palette.inkTertiary
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 3.dp,
                pressedElevation = 1.dp
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            modifier = modifier
                .minimumInteractiveComponentSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            shape = shape,
            border = BorderStroke(1.dp, palette.divider),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = palette.paperElevated,
                contentColor = palette.inkPrimary
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            modifier = modifier
                .minimumInteractiveComponentSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PaperFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    val palette = AriaPaperTheme.palette
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) palette.accentSoft else palette.paperElevated,
        border = BorderStroke(
            width = if (selected) 1.2.dp else 1.dp,
            color = if (selected) palette.accent else palette.divider
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (selected) palette.accent else palette.inkSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) palette.accent else palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PaperEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val palette = AriaPaperTheme.palette
    PaperCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = palette.paperCream,
        elevation = 2.dp,
        contentPadding = PaddingValues(28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PaperDebossedWell(
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = palette.inkPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkSecondary,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(18.dp))
                TactilePaperButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
fun PaperSkeletonList(count: Int = 5, modifier: Modifier = Modifier) {
    val palette = AriaPaperTheme.palette
    val transition = rememberInfiniteTransition(label = "paper_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) {
            PaperCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = palette.paperElevated,
                elevation = 2.dp,
                contentPadding = PaddingValues(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.paperPressed.copy(alpha = alpha))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.65f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(palette.paperPressed.copy(alpha = alpha))
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(0.42f)
                                .height(11.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(palette.paperPressed.copy(alpha = alpha * 0.8f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PaperAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val palette = AriaPaperTheme.palette
    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = palette.paperElevated,
        titleContentColor = palette.inkPrimary,
        textContentColor = palette.inkSecondary,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = palette.inkPrimary
            )
        },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperModalSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = AriaPaperTheme.palette
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = palette.paperBackground,
        contentColor = palette.inkPrimary,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(palette.divider)
            )
        },
        content = content
    )
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0L) "%d:%02d:%02d".format(hrs, mins, secs) else "%d:%02d".format(mins, secs)
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%d KB".format(bytes / 1024L)
        else -> "$bytes B"
    }
}
