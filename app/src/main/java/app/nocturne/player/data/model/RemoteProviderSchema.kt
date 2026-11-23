package app.nocturne.player.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class OperationType(val key: String, val label: String) {
    SEARCH("search", "Search"),
    METADATA("metadata", "Metadata"),
    PLAYBACK("playback", "Playback")
}

@Serializable
data class ProviderEndpointConfig(
    val url: String,
    val priority: Int = 1,
    val enabled: Boolean = true,
    val operations: List<String> = listOf("search", "metadata", "playback"),
    val timeoutMs: Long? = null,
    val headers: Map<String, String> = emptyMap()
) {
    fun supports(op: OperationType): Boolean {
        if (!enabled) return false
        if (operations.isEmpty()) return true
        return operations.any { it.equals(op.key, ignoreCase = true) || it.equals("all", ignoreCase = true) }
    }
}

@Serializable
data class PaginationRule(
    val type: String = "none", // "page", "offset", "cursor", "none"
    val paramName: String = "page",
    val startValue: Int = 1,
    val pageSize: Int = 20,
    val nextTokenPath: String? = null,
    val hasMorePath: String? = null,
    val minResultsForNextPage: Int = 15
)

@Serializable
data class EndpointOperationTemplate(
    val path: String = "",
    val method: String = "GET",
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String? = null,
    val pagination: PaginationRule? = null,
    val resultsPath: String? = null
)

@Serializable
data class ProviderRoutesConfig(
    val searchAll: EndpointOperationTemplate? = null,
    val searchSongs: EndpointOperationTemplate? = null,
    val searchArtists: EndpointOperationTemplate? = null,
    val searchAlbums: EndpointOperationTemplate? = null,
    val suggestions: EndpointOperationTemplate? = null,
    val trending: EndpointOperationTemplate? = null,
    val trackDetails: EndpointOperationTemplate? = null,
    val artistDetails: EndpointOperationTemplate? = null,
    val albumDetails: EndpointOperationTemplate? = null,
    val playlistDetails: EndpointOperationTemplate? = null,
    val resolveStream: EndpointOperationTemplate? = null
)

@Serializable
data class TrackFieldMapping(
    val idPath: String = "id",
    val titlePath: String = "title|name",
    val artistPath: String = "artist|author|uploaderName",
    val artistIdPath: String? = null,
    val artistUrlPath: String? = null,
    val albumPath: String? = null,
    val albumUrlPath: String? = null,
    val durationPath: String = "duration",
    val durationUnit: String = "seconds", // "seconds" or "ms"
    val thumbnailPath: String? = "thumbnail|image|artwork",
    val sourceUrlPath: String? = "url",
    val sourceUrlPrefix: String? = null,
    val viewCountPath: String? = null,
    val publishedPath: String? = null,
    val isLivePath: String? = null,
    val streamUrlPath: String? = null,
    val streamsArrayPath: String? = null,
    val streamItemUrlPath: String = "url|link",
    val streamItemQualityPath: String? = "quality",
    val streamItemBitratePath: String? = "bitrate",
    val streamItemBitrateUnit: String = "kbps", // "bps" or "kbps"
    val streamItemMimePath: String? = "mimeType|type",
    val streamItemCodecPath: String? = "codec|encoding"
)

@Serializable
data class ArtistFieldMapping(
    val idPath: String = "id",
    val namePath: String = "name|author|title",
    val urlPath: String? = "url",
    val avatarPath: String? = "avatar|image|thumbnail",
    val bannerPath: String? = "banner",
    val subscriberPath: String? = "subscribers|followerCount",
    val descriptionPath: String? = "description|bio",
    val tracksPath: String? = "tracks|topSongs|relatedStreams|latestVideos",
    val albumsPath: String? = "albums|topAlbums|playlists"
)

@Serializable
data class AlbumFieldMapping(
    val idPath: String = "id",
    val titlePath: String = "name|title",
    val artistPath: String? = "artist|author|uploader",
    val urlPath: String? = "url",
    val thumbnailPath: String? = "thumbnail|image",
    val descriptionPath: String? = "description",
    val trackCountPath: String? = "songCount|trackCount|videoCount",
    val tracksPath: String? = "songs|tracks|relatedStreams|videos"
)

@Serializable
data class StreamResolutionMapping(
    val itemPath: String? = null,
    val streamUrlPath: String? = null,
    val streamUrlTemplate: String? = null,
    val streamsArrayPath: String? = null,
    val streamItemUrlPath: String = "url|link",
    val streamItemBitratePath: String? = "bitrate",
    val streamItemBitrateUnit: String = "kbps", // "bps" or "kbps"
    val streamItemQualityPath: String? = "quality",
    val streamItemMimePath: String? = "mimeType|type",
    val streamItemCodecPath: String? = "codec|encoding",
    val titlePath: String? = "title|name",
    val artistPath: String? = "artist|uploader|author",
    val durationPath: String? = "duration|lengthSeconds",
    val durationUnit: String = "seconds",
    val thumbnailPath: String? = "thumbnailUrl|thumbnail|image",
    val relatedPath: String? = null
)

@Serializable
data class ProviderResponseMapping(
    val resultsPath: String? = null,
    val itemPath: String? = null,
    val suggestionsPath: String? = null,
    val suggestionsItemField: String? = null,
    val nextPageTokenPath: String? = null,
    val decodeHtmlEntities: Boolean = false,
    val track: TrackFieldMapping = TrackFieldMapping(),
    val artist: ArtistFieldMapping = ArtistFieldMapping(),
    val album: AlbumFieldMapping = AlbumFieldMapping(),
    val streamResolution: StreamResolutionMapping = StreamResolutionMapping()
)

@Serializable
data class DynamicProviderConfig(
    val id: String,
    val displayName: String = id.replaceFirstChar { it.uppercase() },
    val adapterType: String = "generic_rest", // "jiosaavn", "piped", "invidious", "audius", "newpipe", "generic_rest"
    val enabled: Boolean = true,
    val priority: Int = 100,
    val capabilities: List<String> = listOf("search", "suggestions", "trending", "artist", "album", "stream"),
    val endpoints: List<ProviderEndpointConfig> = emptyList(),
    val timeoutMs: Long = 10_000L,
    val maxRetries: Int = 1,
    val cooldownMs: Long = 60_000L,
    val headers: Map<String, String> = emptyMap(),
    val routes: ProviderRoutesConfig = ProviderRoutesConfig(),
    val mapping: ProviderResponseMapping = ProviderResponseMapping(),
    val verifyStreamReachable: Boolean = false
) {
    fun supportsCapability(cap: String): Boolean {
        if (!enabled) return false
        if (capabilities.isEmpty()) return true
        return capabilities.any { it.equals(cap, ignoreCase = true) || it.equals("all", ignoreCase = true) }
    }

    fun endpointsFor(operation: OperationType): List<ProviderEndpointConfig> =
        endpoints.filter { it.supports(operation) }.sortedBy { it.priority }
}

@Serializable
data class DynamicLyricsProviderConfig(
    val id: String = "lrclib",
    val displayName: String = "LRCLIB",
    val enabled: Boolean = true,
    val priority: Int = 1,
    val endpoints: List<ProviderEndpointConfig> = emptyList(),
    val timeoutMs: Long = 8_000L
)

data class RemoteConfigStatus(
    val sourceUrl: String = UserSettings.DEFAULT_REMOTE_CONFIG_URL,
    val schemaVersion: Int = 2,
    val configVersion: String = "0",
    val etag: String? = null,
    val lastModified: String? = null,
    val lastSyncedAtMs: Long = 0L,
    val isFromCache: Boolean = false,
    val wasNotModified304: Boolean = false,
    val canRollback: Boolean = false,
    val previousConfigVersion: String? = null,
    val providerCount: Int = 0,
    val enabledProviderCount: Int = 0,
    val lastError: String? = null
)

data class EndpointHealthInfo(
    val url: String,
    val priority: Int,
    val operation: OperationType,
    val isHealthy: Boolean,
    val cooldownRemainingMs: Long,
    val consecutiveFailures: Int,
    val ewmaLatencyMs: Long,
    val lastSuccessAtMs: Long,
    val lastError: String? = null
)

data class ProviderDiagnosticsSnapshot(
    val providerId: String,
    val displayName: String,
    val adapterType: String,
    val enabled: Boolean,
    val isAvailable: Boolean,
    val priority: Int,
    val configuredEndpoints: List<String>,
    val activeSearchEndpoint: String? = null,
    val activeMetadataEndpoint: String? = null,
    val activePlaybackEndpoint: String? = null,
    val operationHealth: Map<OperationType, List<EndpointHealthInfo>> = emptyMap(),
    val lastSuccessfulRequestMs: Long = 0L,
    val lastError: String? = null,
    val ewmaLatencyMs: Long = 0L
)

data class ProviderConnectivityTestResult(
    val providerId: String,
    val displayName: String,
    val success: Boolean,
    val testedEndpoint: String? = null,
    val latencyMs: Long = 0L,
    val failoverAttempts: Int = 0,
    val errorSummary: String? = null,
    val details: String = ""
)
