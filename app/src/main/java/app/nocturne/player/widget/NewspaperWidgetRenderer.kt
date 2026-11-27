package app.nocturne.player.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import androidx.core.content.res.ResourcesCompat
import app.nocturne.player.R
import app.nocturne.player.data.online.OnlineHttpClient
import app.nocturne.player.data.security.InputAndUrlSecurity
import okhttp3.Request
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class WidgetButtonIcon {
    SHUFFLE,
    PREVIOUS,
    PLAY,
    PAUSE,
    NEXT,
    LIKE
}

object NewspaperWidgetRenderer {
    private const val PAPER_LIGHT = 0xFFF4ECD8.toInt()
    private const val PAPER_AGED = 0xFFEBE0C6.toInt()
    private const val INK_DARK = 0xFF1C1917.toInt()
    private const val INK_MUTED = 0xFF57534E.toInt()
    private const val INK_RULE = 0xFF8C8273.toInt()
    private const val STAMP_ACCENT = 0xFF9F2D1D.toInt()

    private val artworkMemCache = object : LruCache<String, Bitmap>(6) {}

    @Volatile
    private var cachedSerif: Typeface? = null

    @Volatile
    private var cachedMono: Typeface? = null

    private fun serifTypeface(context: Context): Typeface {
        cachedSerif?.let { return it }
        val loaded = runCatching { ResourcesCompat.getFont(context, R.font.playfair_display) }.getOrNull()
            ?: Typeface.SERIF
        cachedSerif = loaded
        return loaded
    }

    private fun monoTypeface(context: Context): Typeface {
        cachedMono?.let { return it }
        val loaded = runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull()
            ?: Typeface.MONOSPACE
        cachedMono = loaded
        return loaded
    }

    fun loadArtworkBitmapSync(context: Context, artworkUri: String?): Bitmap? {
        if (artworkUri.isNullOrBlank()) return null
        artworkMemCache.get(artworkUri)?.let { cached ->
            if (!cached.isRecycled) return cached
        }
        val uri = runCatching { Uri.parse(artworkUri) }.getOrNull() ?: return null
        val decoded: Bitmap? = when {
            InputAndUrlSecurity.isSafePlayableLocalUri(uri) ||
                InputAndUrlSecurity.isSafeAppPrivateDownloadUri(context, uri) -> {
                decodeLocalArtworkSync(context, uri, artworkUri, 240)
            }
            InputAndUrlSecurity.validateRemoteHttpsUrl(artworkUri) != null -> {
                runCatching {
                    val req = Request.Builder().url(artworkUri).build()
                    OnlineHttpClient.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        decodeSampledBytes(bytes, 240)
                    }
                }.getOrNull()
            }
            else -> null
        }
        if (decoded != null) {
            artworkMemCache.put(artworkUri, decoded)
        }
        return decoded
    }

    private fun decodeLocalArtworkSync(
        context: Context,
        uri: Uri,
        rawUri: String,
        targetSize: Int
    ): Bitmap? {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            if (f != null && f.exists() && f.canRead()) {
                decodeSampledBytes(runCatching { f.readBytes() }.getOrNull() ?: ByteArray(0), targetSize)
                    ?.let { return it }
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && rawUri.startsWith("content://media/")) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
            }.getOrNull()?.let { return it }
        }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                decodeSampledBytes(bytes, targetSize)
            }
        }.getOrNull()?.let { return it }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val embedded = retriever.embeddedPicture
            if (embedded != null) decodeSampledBytes(embedded, targetSize) else null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeSampledBytes(bytes: ByteArray, targetSize: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var inSample = 1
        while (bounds.outWidth / inSample > targetSize * 2 || bounds.outHeight / inSample > targetSize * 2) {
            inSample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun renderWidgetBitmap(
        context: Context,
        kind: NewsprintWidgetKind,
        state: WidgetPlaybackState,
        widthDp: Int,
        heightDp: Int,
        sharpCorners: Boolean,
        artworkBitmap: Bitmap?
    ): Bitmap {
        val density = context.resources.displayMetrics.density.coerceIn(1.25f, 2.5f)
        val rawW = (widthDp.coerceAtLeast(120) * density).roundToInt()
        val rawH = (heightDp.coerceAtLeast(56) * density).roundToInt()
        val scaleDown = min(1f, min(720f / rawW.toFloat(), 620f / rawH.toFloat()))
        val widthPx = (rawW * scaleDown).roundToInt().coerceAtLeast(180)
        val heightPx = (rawH * scaleDown).roundToInt().coerceAtLeast(80)
        val px = scaleDown * density

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val clipPath = Path()
        val outerRect = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
        val cornerRadius = if (sharpCorners) 0f else 16f * px
        clipPath.addRoundRect(outerRect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        drawAgedPaperBackground(canvas, widthPx.toFloat(), heightPx.toFloat(), px)
        drawDoubleNewspaperBorder(canvas, widthPx.toFloat(), heightPx.toFloat(), cornerRadius, px)

        when (kind) {
            NewsprintWidgetKind.DAILY_STRIP ->
                drawDailyStripContent(context, canvas, state, widthPx.toFloat(), heightPx.toFloat(), px, artworkBitmap)
            NewsprintWidgetKind.FRONT_PAGE ->
                drawFrontPageContent(context, canvas, state, widthPx.toFloat(), heightPx.toFloat(), px, artworkBitmap)
            NewsprintWidgetKind.SUNDAY_BROADSHEET ->
                drawSundayBroadsheetContent(context, canvas, state, widthPx.toFloat(), heightPx.toFloat(), px, artworkBitmap)
        }

        canvas.restore()
        return bitmap
    }

    private fun drawAgedPaperBackground(canvas: Canvas, w: Float, h: Float, px: Float) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w, h, PAPER_LIGHT, PAPER_AGED, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Subtle deterministic newsprint paper grain
        val grainDark = Paint().apply {
            color = Color.argb(14, 28, 25, 23)
            strokeWidth = max(1f, 0.8f * px)
        }
        val grainWarm = Paint().apply {
            color = Color.argb(18, 120, 95, 60)
            strokeWidth = max(1f, 0.8f * px)
        }
        var seed = 1337
        val step = max(6f, 5.5f * px)
        var y = step * 0.5f
        while (y < h) {
            var x = step * 0.5f
            while (x < w) {
                seed = (seed * 1664525 + 1013904223)
                val jitterX = ((seed ushr 16) and 0x7) - 3
                val jitterY = ((seed ushr 8) and 0x7) - 3
                if (((seed ushr 24) and 0x3) == 0) {
                    canvas.drawPoint(x + jitterX, y + jitterY, if ((seed and 1) == 0) grainDark else grainWarm)
                }
                x += step
            }
            y += step
        }
    }

    private fun drawDoubleNewspaperBorder(
        canvas: Canvas,
        w: Float,
        h: Float,
        cornerRadius: Float,
        px: Float
    ) {
        val outerInset = 2.5f * px
        val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, 1.75f * px)
            color = INK_DARK
        }
        val outerRect = RectF(outerInset, outerInset, w - outerInset, h - outerInset)
        canvas.drawRoundRect(outerRect, max(0f, cornerRadius - outerInset), max(0f, cornerRadius - outerInset), outerPaint)

        val innerInset = 5.5f * px
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(0.8f, 0.75f * px)
            color = INK_MUTED
        }
        val innerRect = RectF(innerInset, innerInset, w - innerInset, h - innerInset)
        canvas.drawRoundRect(innerRect, max(0f, cornerRadius - innerInset), max(0f, cornerRadius - innerInset), innerPaint)
    }

    private fun drawHorizontalRule(canvas: Canvas, x1: Float, x2: Float, y: Float, px: Float, dashed: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dashed) INK_RULE else INK_DARK
            strokeWidth = max(1f, (if (dashed) 0.85f else 1.25f) * px)
            style = Paint.Style.STROKE
            if (dashed) {
                pathEffect = DashPathEffect(floatArrayOf(3.5f * px, 2.5f * px), 0f)
            }
        }
        canvas.drawLine(x1, y, x2, y, p)
    }

    private fun drawVerticalDashedRule(canvas: Canvas, x: Float, y1: Float, y2: Float, px: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK_RULE
            strokeWidth = max(1f, 0.9f * px)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(3.5f * px, 2.5f * px), 0f)
        }
        canvas.drawLine(x, y1, x, y2, p)
    }

    private fun drawVintageFramedArtwork(
        context: Context,
        canvas: Canvas,
        rect: RectF,
        px: Float,
        artworkBitmap: Bitmap?
    ) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER_AGED
            style = Paint.Style.FILL
        }
        canvas.drawRect(rect, fillPaint)

        if (artworkBitmap != null && !artworkBitmap.isRecycled) {
            val scale = max(rect.width() / artworkBitmap.width, rect.height() / artworkBitmap.height)
            val drawW = artworkBitmap.width * scale
            val drawH = artworkBitmap.height * scale
            val left = rect.centerX() - drawW / 2f
            val top = rect.centerY() - drawH / 2f
            val destRect = RectF(left, top, left + drawW, top + drawH)

            // Warm sepia + editorial high-contrast print treatment
            val saturation = ColorMatrix().apply { setSaturation(0.28f) }
            val warmSepia = ColorMatrix(
                floatArrayOf(
                    1.08f, 0.04f, 0.0f, 0f, -8f,
                    0.02f, 1.01f, 0.0f, 0f, -10f,
                    0.0f, 0.0f, 0.86f, 0f, -14f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            saturation.postConcat(warmSepia)
            val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(saturation)
            }
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawBitmap(artworkBitmap, null, destRect, artPaint)
            // Subtle ink print vignette
            val tintPaint = Paint().apply {
                color = Color.argb(18, 28, 25, 23)
            }
            canvas.drawRect(rect, tintPaint)
            canvas.restore()
        } else {
            // Vintage halftone/engraving vinyl disc fallback
            val cx = rect.centerX()
            val cy = rect.centerY()
            val r = min(rect.width(), rect.height()) * 0.36f
            val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK_DARK
                style = Paint.Style.STROKE
                strokeWidth = max(1f, 1.2f * px)
            }
            canvas.drawCircle(cx, cy, r, discPaint)
            canvas.drawCircle(cx, cy, r * 0.65f, discPaint.apply { strokeWidth = max(0.8f, 0.7f * px) })
            canvas.drawCircle(cx, cy, r * 0.24f, discPaint)
            val mono = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = monoTypeface(context)
                color = INK_MUTED
                textSize = max(7f, rect.width() * 0.14f)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("LP", cx, cy + r * 0.78f, mono)
        }

        // Ink border around cover
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK_DARK
            style = Paint.Style.STROKE
            strokeWidth = max(1.2f, 1.4f * px)
        }
        canvas.drawRect(rect, borderPaint)

        // Corner registration marks (┌ ┐ └ ┘)
        val regOff = 2.6f * px
        val regLen = min(6.5f * px, rect.width() * 0.18f)
        val regPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK_DARK
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 1.0f * px)
        }
        // Top-left
        canvas.drawLine(rect.left - regOff, rect.top - regOff, rect.left - regOff + regLen, rect.top - regOff, regPaint)
        canvas.drawLine(rect.left - regOff, rect.top - regOff, rect.left - regOff, rect.top - regOff + regLen, regPaint)
        // Top-right
        canvas.drawLine(rect.right + regOff - regLen, rect.top - regOff, rect.right + regOff, rect.top - regOff, regPaint)
        canvas.drawLine(rect.right + regOff, rect.top - regOff, rect.right + regOff, rect.top - regOff + regLen, regPaint)
        // Bottom-left
        canvas.drawLine(rect.left - regOff, rect.bottom + regOff, rect.left - regOff + regLen, rect.bottom + regOff, regPaint)
        canvas.drawLine(rect.left - regOff, rect.bottom + regOff - regLen, rect.left - regOff, rect.bottom + regOff, regPaint)
        // Bottom-right
        canvas.drawLine(rect.right + regOff - regLen, rect.bottom + regOff, rect.right + regOff, rect.bottom + regOff, regPaint)
        canvas.drawLine(rect.right + regOff, rect.bottom + regOff - regLen, rect.right + regOff, rect.bottom + regOff, regPaint)
    }

    private fun drawDailyStripContent(
        context: Context,
        canvas: Canvas,
        state: WidgetPlaybackState,
        w: Float,
        h: Float,
        px: Float,
        artworkBitmap: Bitmap?
    ) {
        val pad = 10f * px
        val innerLeft = pad
        val innerRight = w - pad

        val mastheadPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(monoTypeface(context), Typeface.BOLD)
            color = INK_MUTED
            textSize = 7.8f * px
            letterSpacing = 0.08f
        }
        val statusTag = if (state.isPlaying) "NOW SPINNING" else "STANDBY"
        val mastheadText = "THE DAILY RECORD  •  $statusTag  •  ${state.formatBadge.uppercase(Locale.US)}"
        val mastheadBaseline = 16.5f * px
        val truncatedMasthead = TextUtils.ellipsize(mastheadText, mastheadPaint, innerRight - innerLeft, TextUtils.TruncateAt.END).toString()
        canvas.drawText(truncatedMasthead, innerLeft, mastheadBaseline, mastheadPaint)

        val ruleY = mastheadBaseline + 4f * px
        drawHorizontalRule(canvas, innerLeft, innerRight, ruleY, px, dashed = true)

        val contentTop = ruleY + 5f * px
        val contentBottom = h - 9.5f * px
        val availH = max(24f * px, contentBottom - contentTop)

        val artSize = min(availH - 4f * px, 64f * px)
        val artTop = contentTop + (availH - artSize) / 2f
        val artRect = RectF(innerLeft + 2.5f * px, artTop, innerLeft + 2.5f * px + artSize, artTop + artSize)
        drawVintageFramedArtwork(context, canvas, artRect, px, artworkBitmap)

        // Reserve 148dp on right for the 3 crisp ink-outlined transport buttons
        val controlsReservedW = 148f * px
        val textLeft = artRect.right + 10f * px
        val textRight = max(textLeft + 36f * px, w - controlsReservedW)
        val textW = (textRight - textLeft).roundToInt().coerceAtLeast(24)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(serifTypeface(context), Typeface.BOLD)
            color = INK_DARK
            textSize = if (availH > 62f * px) 15.5f * px else 13.5f * px
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoTypeface(context)
            color = INK_MUTED
            textSize = 9.5f * px
        }

        val maxTitleLines = if (availH > 68f * px) 2 else 1
        val titleLayout = buildEllipsizedLayout(state.title, titlePaint, textW, maxTitleLines)
        val artistLine = TextUtils.ellipsize(
            "${state.artist.uppercase(Locale.US)} · ${state.providerLabel.uppercase(Locale.US)}",
            artistPaint,
            textW.toFloat(),
            TextUtils.TruncateAt.END
        ).toString()

        val totalTextH = titleLayout.height + 3f * px + artistPaint.textSize
        val textStartY = contentTop + max(0f, (availH - totalTextH) / 2f)

        canvas.save()
        canvas.translate(textLeft, textStartY)
        titleLayout.draw(canvas)
        canvas.restore()

        canvas.drawText(
            artistLine,
            textLeft,
            textStartY + titleLayout.height + 10f * px,
            artistPaint
        )
    }

    private fun drawFrontPageContent(
        context: Context,
        canvas: Canvas,
        state: WidgetPlaybackState,
        w: Float,
        h: Float,
        px: Float,
        artworkBitmap: Bitmap?
    ) {
        val pad = 11f * px
        val innerLeft = pad
        val innerRight = w - pad
        val innerW = innerRight - innerLeft

        // Top Dateline Header
        val datelinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(monoTypeface(context), Typeface.BOLD)
            color = INK_DARK
            textSize = 8f * px
            letterSpacing = 0.08f
        }
        val volBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoTypeface(context)
            color = INK_MUTED
            textSize = 7.5f * px
            textAlign = Paint.Align.RIGHT
        }
        val datelineY = 17f * px
        canvas.drawText("ARIA GAZETTE", innerLeft, datelineY, datelinePaint)
        canvas.drawText("VOL. I · ${state.formatBadge.uppercase(Locale.US)}", innerRight, datelineY, volBadgePaint)

        val topRuleY = datelineY + 4.5f * px
        drawHorizontalRule(canvas, innerLeft, innerRight, topRuleY, px, dashed = false)

        // Reserve 52dp at bottom for 4 controls (Prev, Play/Pause, Next, Like)
        val bottomReserved = 52f * px
        val bottomRuleY = h - bottomReserved - 3f * px
        drawHorizontalRule(canvas, innerLeft, innerRight, bottomRuleY, px, dashed = true)

        val contentTop = topRuleY + 7f * px
        val contentBottom = bottomRuleY - 5f * px
        val availH = max(48f * px, contentBottom - contentTop)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(serifTypeface(context), Typeface.BOLD)
            color = INK_DARK
            textSize = if (w > 205f * px) 14.5f * px else 12.5f * px
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoTypeface(context)
            color = INK_MUTED
            textSize = 9f * px
            textAlign = Paint.Align.CENTER
        }

        val textReserve = if (availH > 95f * px) 38f * px else 32f * px
        val maxArtByHeight = availH - textReserve - 8f * px
        val maxArtByWidth = innerW * 0.62f
        val artSize = min(maxArtByHeight, maxArtByWidth).coerceAtLeast(36f * px)
        val artLeft = (w - artSize) / 2f
        val artRect = RectF(artLeft, contentTop, artLeft + artSize, contentTop + artSize)
        drawVintageFramedArtwork(context, canvas, artRect, px, artworkBitmap)

        val titleTop = artRect.bottom + 7f * px
        val titleLayout = buildCenteredEllipsizedLayout(state.title, titlePaint, innerW.roundToInt().coerceAtLeast(40), 1)
        canvas.save()
        canvas.translate(innerLeft, titleTop)
        titleLayout.draw(canvas)
        canvas.restore()

        val artistLine = TextUtils.ellipsize(
            state.artist.uppercase(Locale.US),
            artistPaint,
            innerW,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(artistLine, w / 2f, titleTop + titleLayout.height + 9.5f * px, artistPaint)
    }

    private fun drawSundayBroadsheetContent(
        context: Context,
        canvas: Canvas,
        state: WidgetPlaybackState,
        w: Float,
        h: Float,
        px: Float,
        artworkBitmap: Bitmap?
    ) {
        val pad = 12f * px
        val innerLeft = pad
        val innerRight = w - pad
        val innerW = innerRight - innerLeft

        // Full Newspaper Masthead Banner
        val mastheadPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(serifTypeface(context), Typeface.BOLD)
            color = INK_DARK
            textSize = if (w > 290f * px) 16.5f * px else 14f * px
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.04f
        }
        val mastheadY = 23f * px
        canvas.drawText("THE SUNDAY BROADSHEET", w / 2f, mastheadY, mastheadPaint)

        val subMetaY = mastheadY + 11f * px
        val subMetaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoTypeface(context)
            color = INK_MUTED
            textSize = 7.5f * px
            letterSpacing = 0.06f
        }
        val rightMetaPaint = TextPaint(subMetaPaint).apply { textAlign = Paint.Align.RIGHT }
        drawHorizontalRule(canvas, innerLeft, innerRight, mastheadY + 3f * px, px, dashed = false)
        canvas.drawText("VOL. I · SIDE A", innerLeft, subMetaY, subMetaPaint)
        canvas.drawText("${state.providerLabel.uppercase(Locale.US)} · ${state.formatBadge.uppercase(Locale.US)}", innerRight, subMetaY, rightMetaPaint)

        val bannerBottomY = subMetaY + 4f * px
        drawHorizontalRule(canvas, innerLeft, innerRight, bannerBottomY, px, dashed = false)

        // Reserve bottom 52dp for transport controls + 22dp above it for printer's-rule progress bar
        val controlsTop = h - 52f * px
        val progressAreaTop = controlsTop - 20f * px
        val columnsTop = bannerBottomY + 8f * px
        val columnsBottom = progressAreaTop - 6f * px
        val columnsH = max(56f * px, columnsBottom - columnsTop)

        val splitX = innerLeft + innerW * 0.44f
        drawVerticalDashedRule(canvas, splitX, columnsTop, columnsBottom, px)

        // Left Column: Framed Artwork + Serif Track Headline + Artist Byline
        val leftColRight = splitX - 9f * px
        val leftColW = max(48f * px, leftColRight - innerLeft)
        val artSize = min(leftColW * 0.88f, columnsH * 0.56f).coerceAtLeast(40f * px)
        val artLeft = innerLeft + (leftColW - artSize) / 2f
        val artRect = RectF(artLeft, columnsTop + 2f * px, artLeft + artSize, columnsTop + 2f * px + artSize)
        drawVintageFramedArtwork(context, canvas, artRect, px, artworkBitmap)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(serifTypeface(context), Typeface.BOLD)
            color = INK_DARK
            textSize = 13f * px
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoTypeface(context)
            color = INK_MUTED
            textSize = 8.5f * px
        }
        val leftTitleY = artRect.bottom + 8f * px
        val remainingLeftH = columnsBottom - leftTitleY
        val titleMaxLines = if (remainingLeftH > 38f * px) 2 else 1
        val titleLayout = buildEllipsizedLayout(state.title, titlePaint, leftColW.roundToInt().coerceAtLeast(30), titleMaxLines)
        canvas.save()
        canvas.translate(innerLeft, leftTitleY)
        titleLayout.draw(canvas)
        canvas.restore()

        val bylineText = TextUtils.ellipsize(
            state.artist.uppercase(Locale.US),
            artistPaint,
            leftColW,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(bylineText, innerLeft, leftTitleY + titleLayout.height + 10f * px, artistPaint)

        // Right Column: Pull-Quote Active Lyric
        val rightColLeft = splitX + 10f * px
        val rightColW = max(56f * px, innerRight - rightColLeft)

        val sectionKickerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(monoTypeface(context), Typeface.BOLD)
            color = STAMP_ACCENT
            textSize = 7.8f * px
            letterSpacing = 0.08f
        }
        canvas.drawText("LYRIC DISPATCH", rightColLeft, columnsTop + 8f * px, sectionKickerPaint)
        drawHorizontalRule(canvas, rightColLeft, innerRight, columnsTop + 12f * px, px, dashed = true)

        val quoteGlyphPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(serifTypeface(context), Typeface.BOLD_ITALIC)
            color = INK_RULE
            textSize = 24f * px
        }
        canvas.drawText("“", rightColLeft - 1f * px, columnsTop + 34f * px, quoteGlyphPaint)

        val lyricRaw = state.activeLyricLine?.takeIf { it.isNotBlank() }
            ?: if (state.isPlaying) {
                "Notes drifting in warm mono from ${state.album.ifBlank { "the studio archive" }}."
            } else {
                "Press play to set the needle down and follow synced verses live."
            }

        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(serifTypeface(context), Typeface.ITALIC)
            color = INK_DARK
            textSize = if (columnsH > 110f * px) 13.5f * px else 11.5f * px
        }
        val quoteTop = columnsTop + 30f * px
        val maxQuoteLines = ((columnsBottom - quoteTop) / (quotePaint.textSize * 1.28f)).toInt().coerceIn(2, 5)
        val quoteLayout = buildEllipsizedLayout("“$lyricRaw”", quotePaint, rightColW.roundToInt().coerceAtLeast(36), maxQuoteLines)
        canvas.save()
        canvas.translate(rightColLeft, quoteTop)
        quoteLayout.draw(canvas)
        canvas.restore()

        val editionNotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoTypeface(context)
            color = INK_MUTED
            textSize = 7.5f * px
        }
        val albumLabel = TextUtils.ellipsize(
            "— ${state.album.uppercase(Locale.US)}",
            editionNotePaint,
            rightColW,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(albumLabel, rightColLeft, columnsBottom - 2f * px, editionNotePaint)

        // Printer's-rule progress bar
        drawHorizontalRule(canvas, innerLeft, innerRight, progressAreaTop, px, dashed = false)
        val progCenterY = progressAreaTop + 10f * px
        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoTypeface(context)
            color = INK_DARK
            textSize = 8.5f * px
        }
        val posStr = formatTime(state.positionMs)
        val durStr = formatTime(state.durationMs)
        val rightTimePaint = TextPaint(timePaint).apply { textAlign = Paint.Align.RIGHT }

        canvas.drawText(posStr, innerLeft, progCenterY + 3f * px, timePaint)
        canvas.drawText(durStr, innerRight, progCenterY + 3f * px, rightTimePaint)

        val barLeft = innerLeft + timePaint.measureText("00:00") + 8f * px
        val barRight = innerRight - timePaint.measureText("00:00") - 8f * px
        if (barRight > barLeft + 20f * px) {
            val trackRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK_RULE
                strokeWidth = max(1f, 1.2f * px)
            }
            canvas.drawLine(barLeft, progCenterY, barRight, progCenterY, trackRulePaint)
            // Printer's end ticks
            canvas.drawLine(barLeft, progCenterY - 3f * px, barLeft, progCenterY + 3f * px, trackRulePaint)
            canvas.drawLine(barRight, progCenterY - 3f * px, barRight, progCenterY + 3f * px, trackRulePaint)

            val fraction = if (state.durationMs > 0L) {
                (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val thumbX = barLeft + (barRight - barLeft) * fraction
            val activeRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK_DARK
                strokeWidth = max(2f, 2.4f * px)
            }
            canvas.drawLine(barLeft, progCenterY, thumbX, progCenterY, activeRulePaint)

            val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = STAMP_ACCENT
                style = Paint.Style.FILL
            }
            canvas.drawCircle(thumbX, progCenterY, 3.2f * px, headPaint)
        }
        drawHorizontalRule(canvas, innerLeft, innerRight, controlsTop - 1f * px, px, dashed = true)
    }

    fun renderButtonBitmap(
        context: Context,
        icon: WidgetButtonIcon,
        isActive: Boolean = false,
        emphasizePrimary: Boolean = false
    ): Bitmap {
        val density = context.resources.displayMetrics.density.coerceIn(1.5f, 3f)
        val sizePx = (34f * density).roundToInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val pad = 2.5f * density
        val box = RectF(pad, pad, sizePx - pad, sizePx - pad)

        // Ink-outlined editorial button frame
        val bgColor = when {
            emphasizePrimary -> INK_DARK
            isActive -> STAMP_ACCENT
            else -> PAPER_LIGHT
        }
        val fgColor = if (emphasizePrimary || isActive) PAPER_LIGHT else INK_DARK

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isActive) STAMP_ACCENT else INK_DARK
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
        }
        canvas.drawRect(box, fillPaint)
        canvas.drawRect(box, strokePaint)

        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            style = Paint.Style.STROKE
            strokeWidth = 1.7f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val r = sizePx * 0.22f
        when (icon) {
            WidgetButtonIcon.PLAY -> {
                val path = Path().apply {
                    moveTo(cx - r * 0.55f, cy - r * 0.85f)
                    lineTo(cx + r * 0.95f, cy)
                    lineTo(cx - r * 0.55f, cy + r * 0.85f)
                    close()
                }
                canvas.drawPath(path, iconPaint)
            }
            WidgetButtonIcon.PAUSE -> {
                val barW = r * 0.42f
                val gap = r * 0.34f
                canvas.drawRect(cx - gap - barW, cy - r * 0.8f, cx - gap, cy + r * 0.8f, iconPaint)
                canvas.drawRect(cx + gap, cy - r * 0.8f, cx + gap + barW, cy + r * 0.8f, iconPaint)
            }
            WidgetButtonIcon.PREVIOUS -> {
                val barW = r * 0.28f
                canvas.drawRect(cx - r * 0.85f, cy - r * 0.72f, cx - r * 0.85f + barW, cy + r * 0.72f, iconPaint)
                val path = Path().apply {
                    moveTo(cx + r * 0.8f, cy - r * 0.72f)
                    lineTo(cx - r * 0.35f, cy)
                    lineTo(cx + r * 0.8f, cy + r * 0.72f)
                    close()
                }
                canvas.drawPath(path, iconPaint)
            }
            WidgetButtonIcon.NEXT -> {
                val barW = r * 0.28f
                canvas.drawRect(cx + r * 0.85f - barW, cy - r * 0.72f, cx + r * 0.85f, cy + r * 0.72f, iconPaint)
                val path = Path().apply {
                    moveTo(cx - r * 0.8f, cy - r * 0.72f)
                    lineTo(cx + r * 0.35f, cy)
                    lineTo(cx - r * 0.8f, cy + r * 0.72f)
                    close()
                }
                canvas.drawPath(path, iconPaint)
            }
            WidgetButtonIcon.LIKE -> {
                val heart = Path().apply {
                    moveTo(cx, cy + r * 0.85f)
                    cubicTo(cx - r * 1.15f, cy + r * 0.1f, cx - r * 1.1f, cy - r * 0.9f, cx, cy - r * 0.28f)
                    cubicTo(cx + r * 1.1f, cy - r * 0.9f, cx + r * 1.15f, cy + r * 0.1f, cx, cy + r * 0.85f)
                    close()
                }
                if (isActive) {
                    canvas.drawPath(heart, iconPaint)
                } else {
                    canvas.drawPath(heart, iconStroke)
                }
            }
            WidgetButtonIcon.SHUFFLE -> {
                val left = cx - r * 0.85f
                val right = cx + r * 0.85f
                val top = cy - r * 0.55f
                val bot = cy + r * 0.55f
                val p1 = Path().apply {
                    moveTo(left, top)
                    lineTo(cx - r * 0.25f, top)
                    lineTo(cx + r * 0.25f, bot)
                    lineTo(right, bot)
                }
                val p2 = Path().apply {
                    moveTo(left, bot)
                    lineTo(cx - r * 0.25f, bot)
                    lineTo(cx + r * 0.25f, top)
                    lineTo(right, top)
                }
                canvas.drawPath(p1, iconStroke)
                canvas.drawPath(p2, iconStroke)
                // Arrowheads
                val aSize = r * 0.28f
                canvas.drawLine(right - aSize, top - aSize * 0.8f, right, top, iconStroke)
                canvas.drawLine(right - aSize, top + aSize * 0.8f, right, top, iconStroke)
                canvas.drawLine(right - aSize, bot - aSize * 0.8f, right, bot, iconStroke)
                canvas.drawLine(right - aSize, bot + aSize * 0.8f, right, bot, iconStroke)
            }
        }
        return bitmap
    }

    private fun buildEllipsizedLayout(
        text: String,
        paint: TextPaint,
        widthPx: Int,
        maxLines: Int
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun buildCenteredEllipsizedLayout(
        text: String,
        paint: TextPaint,
        widthPx: Int,
        maxLines: Int
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }
}
