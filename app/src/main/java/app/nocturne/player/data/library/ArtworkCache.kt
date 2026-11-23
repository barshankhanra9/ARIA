package app.nocturne.player.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

object ArtworkCache {
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(8 * 1024, 32 * 1024)

    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun getCached(key: String): ImageBitmap? = cache.get(key)?.asImageBitmap()

    fun invalidate(keyPrefix: String) {
        cache.snapshot().keys.filter { it.startsWith(keyPrefix) }.forEach { cache.remove(it) }
    }

    suspend fun loadArtwork(
        context: Context,
        trackUri: String,
        customCoverUri: String? = null,
        folderPath: String = "",
        preferFolderCover: Boolean = true,
        targetSizePx: Int = 512
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${customCoverUri ?: trackUri}@$targetSizePx"
        cache.get(cacheKey)?.let { return@withContext it.asImageBitmap() }

        var bitmap: Bitmap? = null

        // 1. Custom cover override (from Tag Editor or Playlist artwork picker)
        if (!customCoverUri.isNullOrBlank()) {
            val parsedCover = runCatching { Uri.parse(customCoverUri) }.getOrNull()
            if (InputAndUrlSecurity.isSafePlayableLocalUri(parsedCover)) {
                bitmap = decodeUriSampled(context, parsedCover!!, targetSizePx)
            }
        }

        // 2. Embedded artwork via MediaMetadataRetriever
        if (bitmap == null && trackUri.isNotBlank()) {
            val parsedTrack = runCatching { Uri.parse(trackUri) }.getOrNull()
            if (InputAndUrlSecurity.isSafePlayableLocalUri(parsedTrack)) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, parsedTrack!!)
                    val bytes = retriever.embeddedPicture
                    if (bytes != null) {
                        bitmap = decodeBytesSampled(bytes, targetSizePx)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }

        // 3. System MediaStore thumbnail (API 29+)
        if (bitmap == null && Build.VERSION.SDK_INT >= 29 && trackUri.startsWith("content://media/")) {
            bitmap = runCatching {
                context.contentResolver.loadThumbnail(
                    Uri.parse(trackUri),
                    Size(targetSizePx, targetSizePx),
                    null
                )
            }.getOrNull()
        }

        // 4. Folder cover art fallback (cover.jpg, folder.jpg, album.jpg)
        if (bitmap == null && preferFolderCover && folderPath.isNotBlank()) {
            val folder = File(folderPath)
            if (folder.exists() && folder.isDirectory) {
                val candidates = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "album.jpg", "front.jpg")
                val coverFile = candidates.asSequence()
                    .map { File(folder, it) }
                    .firstOrNull { it.exists() && it.canRead() }
                if (coverFile != null) {
                    bitmap = decodeFileSampled(coverFile, targetSizePx)
                }
            }
        }

        if (bitmap != null) {
            cache.put(cacheKey, bitmap)
            bitmap.asImageBitmap()
        } else {
            null
        }
    }

    fun loadArtworkBytes(context: Context, uri: Uri, maxDim: Int = 800): ByteArray? {
        val bmp = decodeUriSampled(context, uri, maxDim) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return out.toByteArray()
    }

    private fun decodeUriSampled(context: Context, uri: Uri, targetPx: Int): Bitmap? {
        if (!InputAndUrlSecurity.isSafePlayableLocalUri(uri)) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetPx, targetPx)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFileSampled(file: File, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetPx, targetPx)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBytesSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetPx, targetPx)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            val halfH = height / 2
            val halfW = width / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
