package app.nocturne.player.data.audio

import app.nocturne.player.data.model.LyricsCleaningConfig
import java.util.concurrent.ConcurrentHashMap

data class CleanedMetadata(
    val title: String,
    val artist: String
)

object MetadataCleaner {

    private val defaultConfig = LyricsCleaningConfig()
    private val compiledRegexCache = ConcurrentHashMap<String, Regex>()
    private val featSuffixRegex = Regex("""\b(ft\.?|feat\.?|featuring)\b.*""", RegexOption.IGNORE_CASE)
    private val topicSuffixRegex = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val vevoSuffixRegex = Regex("""\s*official\s*|\s*vevo$""", RegexOption.IGNORE_CASE)
    private val multiSpaceRegex = Regex("""\s+""")
    private val danglingPunctuationRegex = Regex("""^[\s\-–—|/:,]+|[\s\-–—|/:,]+$""")

    fun clean(
        rawTitle: String,
        rawArtist: String,
        config: LyricsCleaningConfig = defaultConfig,
        providerHint: String = ""
    ): CleanedMetadata {
        val cleanedArtist = cleanArtist(rawArtist, config, providerHint)
        val cleanedTitle = cleanTitle(rawTitle, cleanedArtist, config)
        return CleanedMetadata(title = cleanedTitle, artist = cleanedArtist)
    }

    fun cleanTitle(
        rawTitle: String,
        cleanedArtist: String = "",
        config: LyricsCleaningConfig = defaultConfig
    ): String {
        if (rawTitle.isBlank()) return ""
        val bracketsRegex = compileSafe(config.stripBracketsRegex, defaultConfig.stripBracketsRegex)
        val keywordsRegex = compileSafe(config.stripKeywordsRegex, defaultConfig.stripKeywordsRegex)

        var working = rawTitle
            .replace(bracketsRegex, " ")
            .replace(featSuffixRegex, " ")
            .replace(keywordsRegex, " ")
            .replace(multiSpaceRegex, " ")
            .trim()

        val delimiters = config.titleDelimiters.ifEmpty { defaultConfig.titleDelimiters }
        for (delimiter in delimiters) {
            if (delimiter.isBlank() || !working.contains(delimiter)) continue
            val parts = working.split(delimiter)
                .map { it.replace(danglingPunctuationRegex, "").trim() }
                .filter { it.isNotBlank() }
            if (parts.size >= 2) {
                working = pickBestTitleSegment(parts, cleanedArtist)
                break
            }
        }

        return working
            .replace(keywordsRegex, " ")
            .replace(danglingPunctuationRegex, "")
            .replace(multiSpaceRegex, " ")
            .trim()
    }

    fun cleanArtist(
        rawArtist: String,
        config: LyricsCleaningConfig = defaultConfig,
        providerHint: String = ""
    ): String {
        if (rawArtist.isBlank()) return ""
        val bracketsRegex = compileSafe(config.stripBracketsRegex, defaultConfig.stripBracketsRegex)
        val cleaned = rawArtist
            .replace(topicSuffixRegex, "")
            .replace(bracketsRegex, " ")
            .replace(featSuffixRegex, " ")
            .replace(vevoSuffixRegex, "")
            .replace(multiSpaceRegex, " ")
            .replace(danglingPunctuationRegex, "")
            .trim()

        if (cleaned.isBlank() ||
            cleaned.equals("Unknown Artist", ignoreCase = true) ||
            cleaned.equals("YouTube", ignoreCase = true) ||
            cleaned.equals("Piped", ignoreCase = true) ||
            cleaned.equals("Invidious", ignoreCase = true) ||
            cleaned.equals("JioSaavn", ignoreCase = true) ||
            cleaned.equals("Audius", ignoreCase = true) ||
            (providerHint.isNotBlank() && cleaned.equals(providerHint, ignoreCase = true))
        ) {
            return ""
        }
        return cleaned
    }

    private fun pickBestTitleSegment(parts: List<String>, cleanedArtist: String): String {
        if (cleanedArtist.isNotBlank()) {
            val artistNorm = normalizeAlphaNum(cleanedArtist)
            if (artistNorm.isNotBlank()) {
                val firstNorm = normalizeAlphaNum(parts.first())
                // If format is "Artist - Song Title", drop the leading artist segment
                if (firstNorm == artistNorm || (firstNorm.length >= 3 && artistNorm.contains(firstNorm))) {
                    return parts.drop(1).firstOrNull { normalizeAlphaNum(it) != artistNorm } ?: parts[1]
                }
            }
        }
        return parts.first()
    }

    private fun normalizeAlphaNum(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun compileSafe(pattern: String, fallbackPattern: String): Regex {
        return compiledRegexCache.computeIfAbsent(pattern) { raw ->
            runCatching { Regex(raw, RegexOption.IGNORE_CASE) }
                .getOrElse {
                    compiledRegexCache.computeIfAbsent(fallbackPattern) { fb ->
                        Regex(fb, RegexOption.IGNORE_CASE)
                    }
                }
        }
    }
}
