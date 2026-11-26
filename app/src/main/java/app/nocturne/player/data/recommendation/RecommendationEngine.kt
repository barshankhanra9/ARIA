package app.nocturne.player.data.recommendation

import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OnlineTrack
import app.nocturne.player.data.online.OnlineProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.random.Random

data class HomeRecommendations(
    val dailyMixTitle: String = "Made For You (Daily Mix)",
    val dailyMixSubtitle: String = "",
    val dailyMix: List<OnlineTrack> = emptyList(),
    val becauseYouListenToArtist: String? = null,
    val becauseYouListenToTracks: List<OnlineTrack> = emptyList(),
    val onRepeat: List<OnlineTrack> = emptyList(),
    val jumpBackIn: List<OnlineTrack> = emptyList(),
    val trendingFallback: List<OnlineTrack> = emptyList(),
    val isBrandNewUser: Boolean = false,
    val dateSeedKey: Long = 0L
)

class RecommendationEngine(
    private val affinityRepository: AffinityRepository,
    private val providerManager: OnlineProviderManager,
    private val dailySeedProvider: () -> Long = { defaultDailySeed() }
) {

    suspend fun buildHomeRecommendations(dailyMixTargetSize: Int = 15): HomeRecommendations =
        withContext(Dispatchers.IO) {
            affinityRepository.applyWeeklyDecayIfDue()
            val dateSeed = dailySeedProvider()

            val topTracks = affinityRepository.getTopAffinityTracks(5)
            val topArtists = affinityRepository.getTopArtists(3)
            val onRepeat = affinityRepository.getOnRepeatTracksOnce(12)
            val jumpBackIn = affinityRepository.getJumpBackInTracksOnce(12)
            val familiarFavorites = affinityRepository.getFamiliarFavorites(30)
            val skippedIds = affinityRepository.getRecentlySkippedTrackIds()

            val hasHistory = topTracks.isNotEmpty() ||
                topArtists.isNotEmpty() ||
                onRepeat.isNotEmpty() ||
                jumpBackIn.isNotEmpty() ||
                familiarFavorites.isNotEmpty()

            if (!hasHistory) {
                val trending = runCatching {
                    providerManager.fetchTrendingTracksPreferringJioSaavn(limit = 20)
                }.getOrDefault(emptyList())
                return@withContext HomeRecommendations(
                    dailyMixTitle = "Made For You (Daily Mix)",
                    dailyMixSubtitle = "Top Charts starter mix · Listen to personalize",
                    dailyMix = emptyList(),
                    becauseYouListenToArtist = null,
                    becauseYouListenToTracks = emptyList(),
                    onRepeat = emptyList(),
                    jumpBackIn = emptyList(),
                    trendingFallback = trending,
                    isBrandNewUser = true,
                    dateSeedKey = dateSeed
                )
            }

            // Build familiar pool excluding recently skipped tracks
            val familiarPool = (familiarFavorites + onRepeat + topTracks)
                .let { OnlineProviderManager.deduplicateTracks(it) }
                .filterNot { it.id in skippedIds }
                .ifEmpty {
                    (onRepeat + topTracks + jumpBackIn)
                        .let { OnlineProviderManager.deduplicateTracks(it) }
                        .filterNot { it.id in skippedIds }
                }

            val knownIds = (familiarPool.map { it.id } + topTracks.map { it.id } +
                onRepeat.map { it.id } + jumpBackIn.map { it.id }).toSet()
            val knownSignatures = (familiarPool + topTracks + onRepeat + jumpBackIn)
                .map { AffinityRepository.trackSignature(it.title, it.artist) }
                .toSet()

            // Fetch song suggestions from Top 5 affinity tracks & top artist tracks in parallel
            val leadArtist = topArtists.firstOrNull()
            val (suggestionCandidates, topArtistTracks) = coroutineScope {
                val trackSugJobs = topTracks.take(5).map { seed ->
                    async {
                        runCatching {
                            providerManager.fetchTrackSuggestions(seed, limit = 8)
                        }.getOrDefault(emptyList())
                    }
                }
                val extraArtistJobs = topArtists.take(3).map { artist ->
                    async {
                        runCatching {
                            val pType = runCatching { OnlineProviderType.valueOf(artist.providerType) }
                                .getOrDefault(OnlineProviderType.JIOSAAVN)
                            providerManager.fetchArtistTopTracks(
                                artistName = artist.artistName,
                                artistId = artist.artistId,
                                artistUrl = artist.artistUrl,
                                hintProviderType = pType,
                                hintProviderId = artist.providerId,
                                limit = 12
                            )
                        }.getOrDefault(emptyList())
                    }
                }
                val sugTracks = trackSugJobs.awaitAll().flatten()
                val artistLists = extraArtistJobs.awaitAll()
                val leadList = artistLists.firstOrNull().orEmpty()
                (sugTracks + artistLists.flatten()) to leadList
            }

            var newCandidates = OnlineProviderManager.filterSingleSongs(
                tracks = OnlineProviderManager.deduplicateTracks(suggestionCandidates),
                forceFilter = true
            ).filterNot { candidate ->
                candidate.id in skippedIds ||
                    candidate.id in knownIds ||
                    AffinityRepository.trackSignature(candidate.title, candidate.artist) in knownSignatures
            }

            // If suggestions were sparse, augment with JioSaavn trending
            if (newCandidates.size < (dailyMixTargetSize * 0.8).roundToInt()) {
                val trendingFill = runCatching {
                    providerManager.fetchTrendingTracksPreferringJioSaavn(limit = 20)
                }.getOrDefault(emptyList()).filterNot { candidate ->
                    candidate.id in skippedIds ||
                        candidate.id in knownIds ||
                        AffinityRepository.trackSignature(candidate.title, candidate.artist) in knownSignatures
                }
                newCandidates = OnlineProviderManager.deduplicateTracks(newCandidates + trendingFill)
            }

            val dailyMix = composeDailyMix(
                newTracks = newCandidates,
                familiarTracks = familiarPool,
                targetSize = dailyMixTargetSize,
                dateSeed = dateSeed
            )

            val becauseArtistName = leadArtist?.artistName
                ?: topTracks.firstOrNull()?.let { AffinityRepository.extractPrimaryArtistDisplay(it.artist) }
            val becauseTracks = if (!becauseArtistName.isNullOrBlank()) {
                val baseArtistTracks = if (topArtistTracks.isNotEmpty()) {
                    topArtistTracks
                } else {
                    runCatching {
                        providerManager.fetchArtistTopTracks(artistName = becauseArtistName, limit = 12)
                    }.getOrDefault(emptyList())
                }
                OnlineProviderManager.filterSingleSongs(
                    tracks = OnlineProviderManager.deduplicateTracks(baseArtistTracks),
                    forceFilter = true
                ).filterNot { it.id in skippedIds }
                    .take(12)
            } else {
                emptyList()
            }

            HomeRecommendations(
                dailyMixTitle = "Made For You (Daily Mix)",
                dailyMixSubtitle = buildDailyMixSubtitle(topArtists.map { it.artistName }),
                dailyMix = dailyMix,
                becauseYouListenToArtist = becauseArtistName?.takeIf { becauseTracks.isNotEmpty() },
                becauseYouListenToTracks = becauseTracks,
                onRepeat = onRepeat,
                jumpBackIn = jumpBackIn,
                trendingFallback = emptyList(),
                isBrandNewUser = false,
                dateSeedKey = dateSeed
            )
        }

    /**
     * Composes a deterministic daily mix targeting 80% new suggested tracks + 20% familiar favorites
     * seeded by today's calendar date, interleaved for pleasant listening.
     */
    internal fun composeDailyMix(
        newTracks: List<OnlineTrack>,
        familiarTracks: List<OnlineTrack>,
        targetSize: Int = 15,
        dateSeed: Long = dailySeedProvider()
    ): List<OnlineTrack> {
        val rng = Random(dateSeed)
        val cleanNew = OnlineProviderManager.deduplicateTracks(newTracks).shuffled(rng)
        val cleanFamiliar = OnlineProviderManager.deduplicateTracks(familiarTracks).shuffled(Random(dateSeed xor 0x5A5A5A5AL))

        if (cleanNew.isEmpty() && cleanFamiliar.isEmpty()) return emptyList()
        if (cleanNew.isEmpty()) return cleanFamiliar.take(targetSize)
        if (cleanFamiliar.isEmpty()) return cleanNew.take(targetSize)

        val maxPossible = minOf(targetSize, cleanNew.size + cleanFamiliar.size)
        val desiredFamiliar = (maxPossible * 0.20).roundToInt().coerceAtLeast(1)
        val selectedFamiliarCount = minOf(desiredFamiliar, cleanFamiliar.size)
        val selectedNewCount = minOf(maxPossible - selectedFamiliarCount, cleanNew.size)

        // If new tracks are fewer than target 80%, fill remaining slots with familiar tracks up to target
        val extraFamiliarCount = minOf(
            (maxPossible - selectedNewCount - selectedFamiliarCount).coerceAtLeast(0),
            cleanFamiliar.size - selectedFamiliarCount
        )

        val pickedNew = cleanNew.take(selectedNewCount)
        val pickedFamiliar = cleanFamiliar.take(selectedFamiliarCount + extraFamiliarCount)

        // Interleave familiar tracks evenly (~1 familiar after every 4 new tracks for 80/20 rhythm)
        val result = ArrayList<OnlineTrack>(pickedNew.size + pickedFamiliar.size)
        var newIdx = 0
        var famIdx = 0
        while (newIdx < pickedNew.size || famIdx < pickedFamiliar.size) {
            repeat(4) {
                if (newIdx < pickedNew.size) {
                    result.add(pickedNew[newIdx++])
                }
            }
            if (famIdx < pickedFamiliar.size) {
                result.add(pickedFamiliar[famIdx++])
            }
        }
        return result
    }

    /**
     * Fetches up to [count] related tracks for Smart Infinite Radio when the queue nears or hits
     * its last track, enforcing:
     * 1) No duplicates from the last 2 hours (ID or normalized title+artist signature)
     * 2) No tracks already in [currentQueue]
     * 3) No recently skipped tracks
     * 4) No back-to-back songs by the same primary artist
     */
    suspend fun generateInfiniteRadioTracks(
        seedTrack: OnlineTrack?,
        currentQueue: List<OnlineTrack>,
        count: Int = 10
    ): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val recentExclusions = affinityRepository.getRecentlyPlayedExclusionKeys(
            AffinityRepository.TWO_HOURS_MS
        )
        val skippedIds = affinityRepository.getRecentlySkippedTrackIds()

        val queueIds = currentQueue.mapTo(HashSet()) { it.id }
        val queueSourceKeys = currentQueue
            .filter { it.sourceId.isNotBlank() }
            .mapTo(HashSet()) { "${it.providerType.name}:${it.sourceId}" }
        val queueSignatures = currentQueue.mapTo(HashSet()) {
            AffinityRepository.trackSignature(it.title, it.artist)
        }

        val effectiveSeed = seedTrack
            ?: currentQueue.lastOrNull()
            ?: affinityRepository.getTopAffinityTracks(1).firstOrNull()

        val rawCandidates = mutableListOf<OnlineTrack>()
        if (effectiveSeed != null) {
            runCatching {
                providerManager.fetchRelatedTracksForRadio(effectiveSeed, limit = count * 2)
            }.onSuccess { rawCandidates.addAll(it) }
        }

        fun isEligible(track: OnlineTrack): Boolean {
            if (track.id in queueIds) return false
            if (track.sourceId.isNotBlank() && "${track.providerType.name}:${track.sourceId}" in queueSourceKeys) return false
            if (track.id in skippedIds) return false
            val sig = AffinityRepository.trackSignature(track.title, track.artist)
            if (sig in queueSignatures) return false
            if (recentExclusions.contains(track)) return false
            return true
        }

        var filteredPool = OnlineProviderManager.filterSingleSongs(
            tracks = OnlineProviderManager.deduplicateTracks(rawCandidates),
            forceFilter = true
        ).filter(::isEligible)

        // Expand from top affinity tracks or top artists if pool is still short of count or lacks artist diversity
        val distinctArtistsCount = filteredPool.map { AffinityRepository.normalizeArtistKey(it.artist) }.distinct().size
        if (filteredPool.size < count || distinctArtistsCount < 3) {
            val extraSeeds = (currentQueue.takeLast(2).reversed() + affinityRepository.getTopAffinityTracks(4))
                .distinctBy { it.id }
                .filterNot { it.id == effectiveSeed?.id }
                .take(3)

            coroutineScope {
                val sugJobs = extraSeeds.map { extra ->
                    async {
                        runCatching {
                            providerManager.fetchRelatedTracksForRadio(extra, limit = 10)
                        }.getOrDefault(emptyList())
                    }
                }
                val topArtists = affinityRepository.getTopArtists(3)
                val artistJobs = topArtists.map { artist ->
                    async {
                        runCatching {
                            val pType = runCatching { OnlineProviderType.valueOf(artist.providerType) }
                                .getOrDefault(OnlineProviderType.JIOSAAVN)
                            providerManager.fetchArtistTopTracks(
                                artistName = artist.artistName,
                                artistId = artist.artistId,
                                artistUrl = artist.artistUrl,
                                hintProviderType = pType,
                                hintProviderId = artist.providerId,
                                limit = 8
                            )
                        }.getOrDefault(emptyList())
                    }
                }
                rawCandidates.addAll(sugJobs.awaitAll().flatten())
                rawCandidates.addAll(artistJobs.awaitAll().flatten())
            }

            filteredPool = OnlineProviderManager.filterSingleSongs(
                tracks = OnlineProviderManager.deduplicateTracks(rawCandidates),
                forceFilter = true
            ).filter(::isEligible)
        }

        // Last-resort fill from JioSaavn trending if still short of target count
        if (filteredPool.size < count) {
            val trending = runCatching {
                providerManager.fetchTrendingTracksPreferringJioSaavn(limit = 20)
            }.getOrDefault(emptyList())
            filteredPool = OnlineProviderManager.filterSingleSongs(
                tracks = OnlineProviderManager.deduplicateTracks(filteredPool + trending),
                forceFilter = true
            ).filter(::isEligible)
        }

        val initialLastArtistKey = (currentQueue.lastOrNull() ?: effectiveSeed)
            ?.let { AffinityRepository.normalizeArtistKey(it.artist) }
            .orEmpty()

        selectArtistDiverseRadioQueue(
            candidates = filteredPool,
            initialLastArtistKey = initialLastArtistKey,
            count = count
        )
    }

    /**
     * Picks up to [count] tracks greedily while guaranteeing no two consecutive tracks share
     * the same normalized primary artist (and the first track differs from [initialLastArtistKey]).
     */
    internal fun selectArtistDiverseRadioQueue(
        candidates: List<OnlineTrack>,
        initialLastArtistKey: String,
        count: Int = 10
    ): List<OnlineTrack> {
        val remaining = candidates.toMutableList()
        val chosen = ArrayList<OnlineTrack>(count)
        var lastArtistKey = initialLastArtistKey

        while (chosen.size < count && remaining.isNotEmpty()) {
            val nextIdx = remaining.indexOfFirst { track ->
                val key = AffinityRepository.normalizeArtistKey(track.artist)
                key.isBlank() || key != lastArtistKey
            }
            if (nextIdx < 0) {
                // Remaining tracks all match lastArtistKey; stop to strictly prevent back-to-back same artist
                break
            }
            val picked = remaining.removeAt(nextIdx)
            chosen.add(picked)
            lastArtistKey = AffinityRepository.normalizeArtistKey(picked.artist)
        }
        return chosen
    }

    private fun buildDailyMixSubtitle(topArtistNames: List<String>): String {
        val clean = topArtistNames.filter { it.isNotBlank() }.take(2)
        return if (clean.isNotEmpty()) {
            "Inspired by ${clean.joinToString(" & ")} · 80% new, 20% favorites"
        } else {
            "80% fresh discoveries, 20% familiar favorites · Refreshes daily"
        }
    }

    companion object {
        fun defaultDailySeed(calendar: Calendar = Calendar.getInstance()): Long {
            val year = calendar.get(Calendar.YEAR).toLong()
            val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR).toLong()
            return year * 1000L + dayOfYear
        }
    }
}
