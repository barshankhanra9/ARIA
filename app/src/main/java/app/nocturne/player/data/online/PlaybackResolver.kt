package app.nocturne.player.data.online

import app.nocturne.player.data.audio.MetadataCleaner
import app.nocturne.player.data.model.LyricsCleaningConfig
import app.nocturne.player.data.model.NetworkSpeedTier
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineSearchFilter
import app.nocturne.player.data.model.OnlineStreamResolution
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.model.OperationType
import kotlin.math.abs

/**
 * Resolves playback streams using the track's primary provider first, then falls back cleanly
 * across Piped -> Invidious -> secondary audio providers matching cleaned title + artist
 * within +/-3 seconds while keeping original clean metadata & artwork on screen.
 */
class PlaybackResolver(
    private val healthTracker: ProviderHealthTracker,
    private val validateUrl: (String) -> Unit,
    private val cleaningConfigProvider: () -> LyricsCleaningConfig = { LyricsCleaningConfig() }
) {
    suspend fun resolve(
        track: OnlineTrack,
        adapters: Map<String, OnlineMusicProvider>,
        speedTier: NetworkSpeedTier,
        excludedIds: Set<String>
    ): OnlineStreamResolution {
        val nativeIds = selectPrimaryCandidates(track, adapters)
            .filter { it !in excludedIds }

        var lastError: Throwable? = null
        val triedIds = excludedIds.toMutableSet()

        // 1. Primary source playback attempt
        for (pid in nativeIds) {
            val adapter = adapters[pid] ?: continue
            triedIds.add(pid)
            val start = System.currentTimeMillis()
            try {
                val resolved = adapter.resolveStream(track, speedTier)
                validateUrl(resolved.selectedStream.url)
                healthTracker.recordProviderSuccess(
                    providerId = pid,
                    latencyMs = System.currentTimeMillis() - start,
                    instanceUrl = adapter.activeEndpointFor(OperationType.PLAYBACK),
                    operation = OperationType.PLAYBACK
                )
                return preservePrimaryMetadata(
                    original = track,
                    resolution = resolved,
                    resolvedPid = resolved.resolvedByProviderId.ifBlank { pid },
                    resolvedLabel = resolved.resolvedByLabel.ifBlank { adapter.displayName }
                )
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordProviderFailure(pid, t.message)
            }
        }

        // 2. Smart cross-provider fallback: Piped -> Invidious -> remaining capable adapters
        val fallback = resolveViaCrossProviderFallback(
            track = track,
            adapters = adapters,
            alreadyTriedIds = triedIds,
            speedTier = speedTier
        )
        if (fallback != null) {
            return fallback
        }

        throw lastError ?: IllegalStateException("Unable to resolve stream for ${track.title}")
    }

    private suspend fun resolveViaCrossProviderFallback(
        track: OnlineTrack,
        adapters: Map<String, OnlineMusicProvider>,
        alreadyTriedIds: Set<String>,
        speedTier: NetworkSpeedTier
    ): OnlineStreamResolution? {
        val cleaned = MetadataCleaner.clean(
            rawTitle = track.title,
            rawArtist = track.artist,
            config = cleaningConfigProvider(),
            providerHint = track.providerLabel
        )
        if (cleaned.title.isBlank() || cleaned.title.equals("Online Track", ignoreCase = true)) {
            return null
        }

        val officialAudioQuery = listOf(cleaned.title, cleaned.artist, "official audio")
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        val eligibleIds = adapters.keys
            .filter { it !in alreadyTriedIds }
            .filter { pid ->
                val adapter = adapters[pid] ?: return@filter false
                adapter.supportsCapability("search") && adapter.supportsCapability("stream")
            }

        if (eligibleIds.isEmpty()) return null

        // Enforce explicit fallback order: Piped -> Invidious -> remaining healthy providers
        val pipedIds = eligibleIds.filter {
            it == "piped" || adapters[it]?.providerType == OnlineProviderType.PIPED
        }
        val invidiousIds = eligibleIds.filter {
            it == "invidious" || adapters[it]?.providerType == OnlineProviderType.INVIDIOUS
        }
        val otherIds = healthTracker.selectOrderedProviderIds(
            eligibleIds.filter { it !in pipedIds && it !in invidiousIds }
        )
        val orderedFallbacks = pipedIds + invidiousIds + otherIds

        for (pid in orderedFallbacks) {
            val adapter = adapters[pid] ?: continue
            val start = System.currentTimeMillis()
            val searchFilter = when (adapter.providerType) {
                OnlineProviderType.PIPED, OnlineProviderType.INVIDIOUS, OnlineProviderType.YOUTUBE ->
                    OnlineSearchFilter.VIDEOS_REMIXES
                else -> OnlineSearchFilter.SONGS
            }
            val queryToRun = if (adapter.providerType == OnlineProviderType.JIOSAAVN || adapter.providerType == OnlineProviderType.AUDIUS) {
                listOf(cleaned.title, cleaned.artist).filter { it.isNotBlank() }.joinToString(" ")
            } else {
                officialAudioQuery
            }

            try {
                val page = adapter.search(queryToRun, searchFilter, null)
                val bestMatch = pickBestMatchWithinTolerance(
                    target = track,
                    cleanedTargetTitle = cleaned.title,
                    cleanedTargetArtist = cleaned.artist,
                    candidates = page.tracks
                ) ?: continue

                val resolved = adapter.resolveStream(bestMatch, speedTier)
                validateUrl(resolved.selectedStream.url)
                healthTracker.recordProviderSuccess(
                    providerId = pid,
                    latencyMs = System.currentTimeMillis() - start,
                    instanceUrl = adapter.activeEndpointFor(OperationType.PLAYBACK),
                    operation = OperationType.PLAYBACK
                )
                return preservePrimaryMetadata(
                    original = track,
                    resolution = resolved,
                    resolvedPid = resolved.resolvedByProviderId.ifBlank { pid },
                    resolvedLabel = resolved.resolvedByLabel.ifBlank { adapter.displayName }
                )
            } catch (t: Throwable) {
                healthTracker.recordProviderFailure(pid, t.message)
            }
        }
        return null
    }

    private fun preservePrimaryMetadata(
        original: OnlineTrack,
        resolution: OnlineStreamResolution,
        resolvedPid: String,
        resolvedLabel: String
    ): OnlineStreamResolution {
        val cleanThumb = original.thumbnailUrl ?: resolution.track.thumbnailUrl
        val mergedTrack = original.copy(
            title = original.title.ifBlank { resolution.track.title },
            artist = original.artist.ifBlank { resolution.track.artist },
            album = original.album.ifBlank { resolution.track.album },
            thumbnailUrl = cleanThumb,
            durationMs = if (original.durationMs > 0L) original.durationMs else resolution.track.durationMs,
            saavnEncryptedUrl = original.saavnEncryptedUrl ?: resolution.track.saavnEncryptedUrl
        )
        return resolution.copy(
            track = mergedTrack,
            resolvedByProviderId = resolvedPid,
            resolvedByLabel = resolvedLabel
        )
    }

    private fun selectPrimaryCandidates(
        track: OnlineTrack,
        adapters: Map<String, OnlineMusicProvider>
    ): List<String> {
        val effId = track.effectiveProviderId().lowercase()
        val ytFamily = setOf("youtube", "piped", "invidious")
        val rawCandidates = if (effId in ytFamily || track.providerType in setOf(
                OnlineProviderType.YOUTUBE,
                OnlineProviderType.PIPED,
                OnlineProviderType.INVIDIOUS
            )
        ) {
            listOf("piped", "invidious", "youtube").filter { adapters.containsKey(it) }
        } else if (adapters.containsKey(effId)) {
            listOf(effId)
        } else {
            adapters.filterValues { it.providerType == track.providerType }.keys.toList()
        }
        return healthTracker.selectOrderedProviderIds(rawCandidates)
    }

    internal fun pickBestMatchWithinTolerance(
        target: OnlineTrack,
        cleanedTargetTitle: String,
        cleanedTargetArtist: String,
        candidates: List<OnlineTrack>
    ): OnlineTrack? {
        if (candidates.isEmpty()) return null
        val targetTitleTokens = tokenize(cleanedTargetTitle)
        val targetArtistTokens = tokenize(cleanedTargetArtist)
        if (targetTitleTokens.isEmpty()) return null

        val scored = candidates.mapNotNull { candidate ->
            if (candidate.isLive) return@mapNotNull null

            // Enforce +/- 3 seconds duration tolerance when both durations are known
            if (target.durationMs > 0L && candidate.durationMs > 0L) {
                val diffMs = abs(target.durationMs - candidate.durationMs)
                if (diffMs > DURATION_TOLERANCE_MS) return@mapNotNull null
            }

            val cleanCandTitle = MetadataCleaner.cleanTitle(candidate.title)
            val candTitleTokens = tokenize(cleanCandTitle)
            if (candTitleTokens.isEmpty()) return@mapNotNull null

            val titleOverlap = targetTitleTokens.count { it in candTitleTokens }.toFloat() / targetTitleTokens.size.toFloat()
            if (titleOverlap < 0.45f) return@mapNotNull null

            val cleanCandArtist = MetadataCleaner.cleanArtist(candidate.artist)
            val candArtistTokens = tokenize(cleanCandArtist)
            val artistOverlap = if (targetArtistTokens.isNotEmpty()) {
                val matches = targetArtistTokens.count { it in candArtistTokens || it in candTitleTokens }
                matches.toFloat() / targetArtistTokens.size.toFloat()
            } else {
                0.5f
            }

            val officialAudioBonus = if (candidate.title.contains("official audio", ignoreCase = true) ||
                candidate.title.contains("audio", ignoreCase = true)
            ) 0.25f else 0f

            val durationScore = if (target.durationMs > 0L && candidate.durationMs > 0L) {
                1.0f - (abs(target.durationMs - candidate.durationMs).toFloat() / DURATION_TOLERANCE_MS.toFloat())
            } else 0.5f

            val totalScore = titleOverlap * 2.0f + artistOverlap * 1.2f + durationScore * 0.6f + officialAudioBonus
            candidate to totalScore
        }

        return scored.maxByOrNull { it.second }?.takeIf { it.second >= 1.1f }?.first
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    companion object {
        const val DURATION_TOLERANCE_MS = 3_000L
    }
}
