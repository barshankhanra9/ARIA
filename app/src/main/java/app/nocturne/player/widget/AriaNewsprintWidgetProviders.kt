package app.nocturne.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import app.nocturne.player.MainActivity
import app.nocturne.player.PlaybackService
import app.nocturne.player.R
import app.nocturne.player.data.db.AriaDatabase
import app.nocturne.player.data.db.TrackStatsEntity
import app.nocturne.player.data.model.LyricsData
import app.nocturne.player.data.online.OnlineDependencies
import app.nocturne.player.playback.ActiveAudioEngine
import app.nocturne.player.playback.PlaybackRuntimeBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

abstract class BaseNewsprintWidgetProvider(
    private val kind: NewsprintWidgetKind
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        WidgetUpdater.scope.launch {
            try {
                for (widgetId in appWidgetIds) {
                    val options = appWidgetManager.getAppWidgetOptions(widgetId)
                    WidgetUpdater.updateSingleWidgetSync(context, appWidgetManager, kind, widgetId, options)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val pending = goAsync()
        WidgetUpdater.scope.launch {
            try {
                WidgetUpdater.updateSingleWidgetSync(context, appWidgetManager, kind, appWidgetId, newOptions)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetStatePrefs.removeWidgetOverride(context, it) }
    }
}

class DailyStripWidgetProvider : BaseNewsprintWidgetProvider(NewsprintWidgetKind.DAILY_STRIP)
class FrontPageWidgetProvider : BaseNewsprintWidgetProvider(NewsprintWidgetKind.FRONT_PAGE)
class SundayBroadsheetWidgetProvider : BaseNewsprintWidgetProvider(NewsprintWidgetKind.SUNDAY_BROADSHEET)

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_TOGGLE_FAVORITE) {
            val pending = goAsync()
            WidgetUpdater.scope.launch {
                try {
                    val state = WidgetStatePrefs.loadState(context)
                    val db = AriaDatabase.getInstance(context.applicationContext)
                    if (state.isOnline && state.trackId.isNotBlank()) {
                        val existing = db.onlineTrackDao().getById(state.trackId)
                        val newFav = !(existing?.isFavorite ?: state.isFavorite)
                        val snapTrack = PlaybackRuntimeBus.onlineSnapshot.value.currentTrack
                            ?.takeIf { it.id == state.trackId }
                        if (snapTrack != null) {
                            OnlineDependencies.getRepository(context).toggleFavorite(snapTrack)
                        } else if (existing != null) {
                            db.onlineTrackDao().upsert(
                                existing.copy(
                                    isFavorite = newFav,
                                    favoritedAt = if (newFav) System.currentTimeMillis() else 0L
                                )
                            )
                        }
                        WidgetStatePrefs.updateFavoriteOnly(context, newFav)
                    } else if (state.uriString.isNotBlank()) {
                        val existing = db.trackStatsDao().getByUri(state.uriString)
                        val newFav = !(existing?.isFavorite ?: state.isFavorite)
                        db.trackStatsDao().upsert(
                            (existing ?: TrackStatsEntity(uriString = state.uriString)).copy(isFavorite = newFav)
                        )
                        WidgetStatePrefs.updateFavoriteOnly(context, newFav)
                    } else {
                        WidgetStatePrefs.updateFavoriteOnly(context, !state.isFavorite)
                    }
                    WidgetUpdater.refreshAllWidgetsSync(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_FAVORITE = "app.nocturne.player.widget.ACTION_TOGGLE_FAVORITE"
    }
}

object WidgetUpdater {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastPeriodicRefreshAt = 0L

    fun syncAndRefreshFromPlayback(
        context: Context,
        activeEngine: ActiveAudioEngine,
        forceImmediate: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!forceImmediate && now - lastPeriodicRefreshAt < 950L) {
            return
        }
        lastPeriodicRefreshAt = now

        val onlineSnap = PlaybackRuntimeBus.onlineSnapshot.value
        val offlineSnap = PlaybackRuntimeBus.offlineSnapshot.value
        val lyricsEntry = PlaybackRuntimeBus.preloadedLyrics.value
        val existing = WidgetStatePrefs.loadState(context)

        val stateToSave: WidgetPlaybackState = if (activeEngine == ActiveAudioEngine.ONLINE && onlineSnap.currentTrack != null) {
            val tr = onlineSnap.currentTrack
            val pos = onlineSnap.positionMs
            val activeLine = if (lyricsEntry?.trackUri == tr.toUiTrack().uriString) {
                resolveActiveLyricText(lyricsEntry.lyrics, pos)
            } else null

            val isFavFromDbOrState = if (existing.trackId == tr.id) existing.isFavorite else tr.isFavorite
            existing.copy(
                title = tr.title.ifBlank { "Online Stream" },
                artist = tr.artist.ifBlank { tr.providerLabel },
                album = tr.album.ifBlank { tr.providerLabel },
                formatBadge = onlineSnap.activeStreamLabel.ifBlank { "STREAM" },
                providerLabel = onlineSnap.activeProviderLabel.ifBlank { tr.providerLabel },
                isPlaying = onlineSnap.isPlaying,
                shuffleEnabled = onlineSnap.shuffleEnabled,
                isFavorite = isFavFromDbOrState,
                positionMs = pos,
                durationMs = onlineSnap.durationMs.coerceAtLeast(tr.durationMs),
                artworkUri = tr.thumbnailUrl ?: existing.artworkUri,
                activeLyricLine = activeLine,
                isOnline = true,
                trackId = tr.id,
                uriString = tr.toUiTrack().uriString
            )
        } else if (offlineSnap.mediaItems.isNotEmpty() && offlineSnap.currentIndex in offlineSnap.mediaItems.indices) {
            val item = offlineSnap.mediaItems[offlineSnap.currentIndex]
            val meta = item.mediaMetadata
            val uriStr = item.localConfiguration?.uri?.toString().orEmpty()
            val pos = offlineSnap.positionMs
            val activeLine = if (lyricsEntry?.trackUri == uriStr) {
                resolveActiveLyricText(lyricsEntry.lyrics, pos)
            } else null

            existing.copy(
                title = meta.title?.toString()?.takeIf { it.isNotBlank() } ?: "Local Audio",
                artist = meta.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
                album = meta.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: "Local Press",
                formatBadge = "LOCAL",
                providerLabel = "LOCAL ARCHIVE",
                isPlaying = offlineSnap.isPlaying,
                shuffleEnabled = offlineSnap.shuffleEnabled,
                isFavorite = if (existing.uriString == uriStr) existing.isFavorite else false,
                positionMs = pos,
                durationMs = offlineSnap.durationMs,
                artworkUri = meta.artworkUri?.toString() ?: uriStr,
                activeLyricLine = activeLine,
                isOnline = false,
                trackId = "",
                uriString = uriStr
            )
        } else {
            return
        }

        WidgetStatePrefs.saveState(context, stateToSave)
        scope.launch {
            // Reconcile accurate favorite flag from Room in IO before drawing
            val db = AriaDatabase.getInstance(context.applicationContext)
            val accurateFav = if (stateToSave.isOnline && stateToSave.trackId.isNotBlank()) {
                runCatching { db.onlineTrackDao().getById(stateToSave.trackId)?.isFavorite }.getOrNull()
                    ?: stateToSave.isFavorite
            } else if (stateToSave.uriString.isNotBlank()) {
                runCatching { db.trackStatsDao().getByUri(stateToSave.uriString)?.isFavorite }.getOrNull()
                    ?: stateToSave.isFavorite
            } else {
                stateToSave.isFavorite
            }
            if (accurateFav != stateToSave.isFavorite) {
                WidgetStatePrefs.updateFavoriteOnly(context, accurateFav)
            }
            refreshAllWidgetsSync(context)
        }
    }

    fun refreshAllWidgets(context: Context) {
        scope.launch {
            refreshAllWidgetsSync(context)
        }
    }

    fun refreshWidgetById(context: Context, kind: NewsprintWidgetKind, appWidgetId: Int) {
        scope.launch {
            val manager = AppWidgetManager.getInstance(context)
            val options = manager.getAppWidgetOptions(appWidgetId)
            updateSingleWidgetSync(context, manager, kind, appWidgetId, options)
        }
    }

    internal fun refreshAllWidgetsSync(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val pairs = listOf(
            NewsprintWidgetKind.DAILY_STRIP to DailyStripWidgetProvider::class.java,
            NewsprintWidgetKind.FRONT_PAGE to FrontPageWidgetProvider::class.java,
            NewsprintWidgetKind.SUNDAY_BROADSHEET to SundayBroadsheetWidgetProvider::class.java
        )
        for ((kind, cls) in pairs) {
            val ids = manager.getAppWidgetIds(ComponentName(context, cls))
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                updateSingleWidgetSync(context, manager, kind, id, options)
            }
        }
    }

    internal fun updateSingleWidgetSync(
        context: Context,
        manager: AppWidgetManager,
        kind: NewsprintWidgetKind,
        appWidgetId: Int,
        options: Bundle?
    ) {
        val state = WidgetStatePrefs.loadState(context)
        val sharpCorners = WidgetStatePrefs.getWidgetSharpCorners(context, appWidgetId)
        val defaultWidth = when (kind) {
            NewsprintWidgetKind.DAILY_STRIP -> 300
            NewsprintWidgetKind.FRONT_PAGE -> 180
            NewsprintWidgetKind.SUNDAY_BROADSHEET -> 310
        }
        val defaultHeight = when (kind) {
            NewsprintWidgetKind.DAILY_STRIP -> 84
            NewsprintWidgetKind.FRONT_PAGE -> 180
            NewsprintWidgetKind.SUNDAY_BROADSHEET -> 260
        }
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defaultWidth)
            ?.takeIf { it > 0 } ?: defaultWidth
        val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defaultHeight)
            ?.takeIf { it > 0 } ?: defaultHeight

        val artBitmap = NewspaperWidgetRenderer.loadArtworkBitmapSync(context, state.artworkUri)
        val sheetBitmap = NewspaperWidgetRenderer.renderWidgetBitmap(
            context = context,
            kind = kind,
            state = state,
            widthDp = minW,
            heightDp = minH,
            sharpCorners = sharpCorners,
            artworkBitmap = artBitmap
        )

        val layoutId = when (kind) {
            NewsprintWidgetKind.DAILY_STRIP -> R.layout.widget_daily_strip
            NewsprintWidgetKind.FRONT_PAGE -> R.layout.widget_front_page
            NewsprintWidgetKind.SUNDAY_BROADSHEET -> R.layout.widget_sunday_broadsheet
        }
        val views = RemoteViews(context.packageName, layoutId)
        views.setImageViewBitmap(R.id.widget_canvas_image, sheetBitmap)

        // Tap album art / masthead -> immediately open Now Playing in MainActivity
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
        }
        val openAppPi = PendingIntent.getActivity(
            context,
            200 + appWidgetId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_open_player_area, openAppPi)

        // Wire reliable transport PendingIntents (getForegroundService works even from background/killed state)
        views.setImageViewBitmap(
            R.id.widget_btn_prev,
            NewspaperWidgetRenderer.renderButtonBitmap(context, WidgetButtonIcon.PREVIOUS)
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_prev,
            serviceActionPendingIntent(context, PlaybackService.ACTION_PREVIOUS, 301)
        )

        views.setImageViewBitmap(
            R.id.widget_btn_play_pause,
            NewspaperWidgetRenderer.renderButtonBitmap(
                context = context,
                icon = if (state.isPlaying) WidgetButtonIcon.PAUSE else WidgetButtonIcon.PLAY,
                emphasizePrimary = true
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_play_pause,
            serviceActionPendingIntent(context, PlaybackService.ACTION_TOGGLE_PLAY, 302)
        )

        views.setImageViewBitmap(
            R.id.widget_btn_next,
            NewspaperWidgetRenderer.renderButtonBitmap(context, WidgetButtonIcon.NEXT)
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_next,
            serviceActionPendingIntent(context, PlaybackService.ACTION_NEXT, 303)
        )

        if (kind == NewsprintWidgetKind.FRONT_PAGE || kind == NewsprintWidgetKind.SUNDAY_BROADSHEET) {
            views.setImageViewBitmap(
                R.id.widget_btn_like,
                NewspaperWidgetRenderer.renderButtonBitmap(
                    context = context,
                    icon = WidgetButtonIcon.LIKE,
                    isActive = state.isFavorite
                )
            )
            val favIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActionReceiver.ACTION_TOGGLE_FAVORITE
            }
            val favPi = PendingIntent.getBroadcast(
                context,
                304,
                favIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_like, favPi)
        }

        if (kind == NewsprintWidgetKind.SUNDAY_BROADSHEET) {
            views.setImageViewBitmap(
                R.id.widget_btn_shuffle,
                NewspaperWidgetRenderer.renderButtonBitmap(
                    context = context,
                    icon = WidgetButtonIcon.SHUFFLE,
                    isActive = state.shuffleEnabled
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_shuffle,
                serviceActionPendingIntent(context, PlaybackService.ACTION_TOGGLE_SHUFFLE, 305)
            )
        }

        manager.updateAppWidget(appWidgetId, views)
    }

    private fun serviceActionPendingIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).apply {
            this.action = action
        }
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private fun resolveActiveLyricText(lyrics: LyricsData, positionMs: Long): String? {
        if (!lyrics.hasContent || lyrics.isInstrumental) return null
        if (lyrics.syncedLines.isNotEmpty()) {
            val active = lyrics.syncedLines.lastOrNull { it.timeMs <= positionMs }
                ?: lyrics.syncedLines.firstOrNull()
            return active?.text?.takeIf { it.isNotBlank() }
        }
        return lyrics.rawText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }
}
