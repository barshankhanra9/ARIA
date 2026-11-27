package app.nocturne.player.widget

import android.content.Context

enum class NewsprintWidgetKind {
    DAILY_STRIP,
    FRONT_PAGE,
    SUNDAY_BROADSHEET
}

data class WidgetPlaybackState(
    val title: String = "No Record Spinning",
    val artist: String = "Select a track in ARIA",
    val album: String = "Press Edition",
    val formatBadge: String = "STEREO",
    val providerLabel: String = "LOCAL ARCHIVE",
    val isPlaying: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val isFavorite: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val artworkUri: String? = null,
    val activeLyricLine: String? = null,
    val isOnline: Boolean = false,
    val trackId: String = "",
    val uriString: String = "",
    val sharpCorners: Boolean = true
)

object WidgetStatePrefs {
    private const val PREFS_NAME = "aria_newsprint_widget_state"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_ALBUM = "album"
    private const val KEY_FORMAT_BADGE = "format_badge"
    private const val KEY_PROVIDER_LABEL = "provider_label"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_FAVORITE = "favorite"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_DURATION_MS = "duration_ms"
    private const val KEY_ARTWORK_URI = "artwork_uri"
    private const val KEY_ACTIVE_LYRIC = "active_lyric"
    private const val KEY_IS_ONLINE = "is_online"
    private const val KEY_TRACK_ID = "track_id"
    private const val KEY_URI_STRING = "uri_string"
    private const val KEY_GLOBAL_SHARP_CORNERS = "global_sharp_corners"
    private const val KEY_WIDGET_CORNER_PREFIX = "widget_sharp_corners_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveState(context: Context, state: WidgetPlaybackState) {
        prefs(context).edit()
            .putString(KEY_TITLE, state.title)
            .putString(KEY_ARTIST, state.artist)
            .putString(KEY_ALBUM, state.album)
            .putString(KEY_FORMAT_BADGE, state.formatBadge)
            .putString(KEY_PROVIDER_LABEL, state.providerLabel)
            .putBoolean(KEY_IS_PLAYING, state.isPlaying)
            .putBoolean(KEY_SHUFFLE, state.shuffleEnabled)
            .putBoolean(KEY_FAVORITE, state.isFavorite)
            .putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0L))
            .putLong(KEY_DURATION_MS, state.durationMs.coerceAtLeast(0L))
            .putString(KEY_ARTWORK_URI, state.artworkUri)
            .putString(KEY_ACTIVE_LYRIC, state.activeLyricLine)
            .putBoolean(KEY_IS_ONLINE, state.isOnline)
            .putString(KEY_TRACK_ID, state.trackId)
            .putString(KEY_URI_STRING, state.uriString)
            .apply()
    }

    fun loadState(context: Context): WidgetPlaybackState {
        val p = prefs(context)
        return WidgetPlaybackState(
            title = p.getString(KEY_TITLE, null)?.takeIf { it.isNotBlank() } ?: "No Record Spinning",
            artist = p.getString(KEY_ARTIST, null)?.takeIf { it.isNotBlank() } ?: "Select a track in ARIA",
            album = p.getString(KEY_ALBUM, null)?.takeIf { it.isNotBlank() } ?: "Press Edition",
            formatBadge = p.getString(KEY_FORMAT_BADGE, null)?.takeIf { it.isNotBlank() } ?: "STEREO",
            providerLabel = p.getString(KEY_PROVIDER_LABEL, null)?.takeIf { it.isNotBlank() } ?: "LOCAL ARCHIVE",
            isPlaying = p.getBoolean(KEY_IS_PLAYING, false),
            shuffleEnabled = p.getBoolean(KEY_SHUFFLE, false),
            isFavorite = p.getBoolean(KEY_FAVORITE, false),
            positionMs = p.getLong(KEY_POSITION_MS, 0L),
            durationMs = p.getLong(KEY_DURATION_MS, 0L),
            artworkUri = p.getString(KEY_ARTWORK_URI, null),
            activeLyricLine = p.getString(KEY_ACTIVE_LYRIC, null),
            isOnline = p.getBoolean(KEY_IS_ONLINE, false),
            trackId = p.getString(KEY_TRACK_ID, null).orEmpty(),
            uriString = p.getString(KEY_URI_STRING, null).orEmpty(),
            sharpCorners = p.getBoolean(KEY_GLOBAL_SHARP_CORNERS, true)
        )
    }

    fun updateFavoriteOnly(context: Context, isFavorite: Boolean) {
        prefs(context).edit().putBoolean(KEY_FAVORITE, isFavorite).apply()
    }

    fun updateShuffleOnly(context: Context, shuffleEnabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHUFFLE, shuffleEnabled).apply()
    }

    fun setGlobalSharpCorners(context: Context, sharpCorners: Boolean) {
        val p = prefs(context)
        val editor = p.edit().putBoolean(KEY_GLOBAL_SHARP_CORNERS, sharpCorners)
        p.all.keys
            .filter { it.startsWith(KEY_WIDGET_CORNER_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun getGlobalSharpCorners(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_GLOBAL_SHARP_CORNERS, true)
    }

    fun setWidgetSharpCorners(context: Context, appWidgetId: Int, sharpCorners: Boolean) {
        prefs(context).edit()
            .putBoolean("$KEY_WIDGET_CORNER_PREFIX$appWidgetId", sharpCorners)
            .apply()
    }

    fun getWidgetSharpCorners(context: Context, appWidgetId: Int): Boolean {
        val p = prefs(context)
        val key = "$KEY_WIDGET_CORNER_PREFIX$appWidgetId"
        return if (p.contains(key)) {
            p.getBoolean(key, true)
        } else {
            p.getBoolean(KEY_GLOBAL_SHARP_CORNERS, true)
        }
    }

    fun removeWidgetOverride(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove("$KEY_WIDGET_CORNER_PREFIX$appWidgetId").apply()
    }
}
