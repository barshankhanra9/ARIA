package app.nocturne.player.data.online

import android.net.Uri
import android.util.LruCache
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineAudioStream
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.StreamingQuality
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlin.math.abs

object StreamQualitySelector {

    fun selectBestAudioStream(
        streams: List<OnlineAudioStream>,
        speedTier: NetworkSpeedTier,
        allowedLoopbackOrigin: String? = null,
        preferWebmOpus: Boolean = false,
        preferredQuality: StreamingQuality? = null
    ): OnlineAudioStream? {
        val valid = streams.mapNotNull { stream ->
            val safeUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(stream.url, allowedLoopbackOrigin)
                ?: return@mapNotNull null
            if (safeUrl == stream.url) stream else stream.copy(url = safeUrl)
        }
        if (valid.isEmpty()) return null

        val shouldFilterOpus = preferWebmOpus &&
            speedTier != NetworkSpeedTier.SLOW_OR_METERED &&
            preferredQuality != StreamingQuality.LOW_96

        val pool = if (shouldFilterOpus) {
            valid.filter { s ->
                val mime = s.mimeType.lowercase()
                val codec = s.codec.lowercase()
                "webm" in mime || "opus" in codec
            }.ifEmpty { valid }
        } else {
            valid
        }

        val scored = pool.map { stream ->
            stream to scoreStream(stream, speedTier, preferredQuality)
        }
        return scored.maxByOrNull { it.second }?.first
    }

    private fun scoreStream(
        stream: OnlineAudioStream,
        tier: NetworkSpeedTier,
        preferredQuality: StreamingQuality? = null
    ): Int {
        val codecLower = stream.codec.lowercase()
        val mimeLower = stream.mimeType.lowercase()
        val codecBonus = when {
            "opus" in codecLower || "webm" in mimeLower -> 30
            "mp4a" in codecLower || "aac" in codecLower || "m4a" in mimeLower || "mp4" in mimeLower -> 25
            "mp3" in codecLower || "mpeg" in mimeLower -> 15
            else -> 5
        }

        val kbps = stream.bitrateKbps.coerceAtLeast(32)
        if (preferredQuality != null) {
            val qualityScore = when (preferredQuality) {
                StreamingQuality.LOW_96 -> {
                    if (kbps <= 112) {
                        600 - abs(kbps - 96) * 3
                    } else {
                        (300 - (kbps - 96)).coerceAtLeast(10)
                    }
                }
                StreamingQuality.MEDIUM_160 -> {
                    if (kbps in 110..192) {
                        600 - abs(kbps - 160) * 3
                    } else {
                        (360 - abs(kbps - 160)).coerceAtLeast(10)
                    }
                }
                StreamingQuality.HIGH_320 -> {
                    kbps.coerceAtMost(320) * 2
                }
            }
            return qualityScore + codecBonus
        }

        val bitrateScore = when (tier) {
            NetworkSpeedTier.FAST_UNMETERED -> kbps.coerceAtMost(320) * 2
            NetworkSpeedTier.STANDARD -> {
                if (kbps <= 192) kbps * 2 else (384 - (kbps - 192) * 3).coerceAtLeast(20)
            }
            NetworkSpeedTier.SLOW_OR_METERED -> {
                when {
                    kbps in 48..96 -> 320 + kbps
                    kbps in 32..47 -> 260 + kbps
                    kbps in 97..140 -> 280 - (kbps - 96)
                    else -> (200 - kbps / 4).coerceAtLeast(10)
                }
            }
            NetworkSpeedTier.OFFLINE -> kbps
        }
        return bitrateScore + codecBonus
    }

    fun parseExpiryFromUrl(url: String, nowMs: Long = System.currentTimeMillis()): Long {
        val expireSec = runCatching {
            Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()
        }.getOrNull()
        return if (expireSec != null && expireSec > 0L) {
            // Refresh 5 minutes before actual server expiry
            (expireSec * 1000L - 5 * 60_000L).coerceAtLeast(nowMs + 60_000L)
        } else {
            nowMs + DEFAULT_STREAM_TTL_MS
        }
    }

    private const val DEFAULT_STREAM_TTL_MS = 2 * 60 * 60_000L
}

class StreamResolutionCache(
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val cache = object : LruCache<String, OnlineStreamResolution>(120) {}

    @Synchronized
    fun getIfValid(trackId: String): OnlineStreamResolution? {
        val cached = cache.get(trackId) ?: return null
        return if (clockMs() < cached.selectedStream.expiresAtMs) {
            cached
        } else {
            cache.remove(trackId)
            null
        }
    }

    @Synchronized
    fun put(trackId: String, resolution: OnlineStreamResolution) {
        cache.put(trackId, resolution)
    }

    @Synchronized
    fun invalidate(trackId: String) {
        cache.remove(trackId)
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
    }
}
