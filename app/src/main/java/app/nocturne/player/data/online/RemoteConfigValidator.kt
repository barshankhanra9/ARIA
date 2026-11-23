package app.nocturne.player.data.online

import app.nocturne.player.data.model.DynamicLyricsProviderConfig
import app.nocturne.player.data.model.DynamicProviderConfig
import app.nocturne.player.data.model.EndpointOperationTemplate
import app.nocturne.player.data.model.ProviderEndpointConfig
import app.nocturne.player.data.model.ProviderResponseMapping
import app.nocturne.player.data.model.ProviderRoutesConfig
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

object RemoteConfigValidator {

    const val MAX_SUPPORTED_SCHEMA_VERSION = 2
    private val SUPPORTED_METHODS = setOf("GET", "POST")
    private val KNOWN_ADAPTER_TYPES = setOf("jiosaavn", "piped", "invidious", "audius", "newpipe", "generic_rest")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    @Throws(IOException::class)
    fun parseAndValidate(
        rawJson: String,
        allowLoopback: Boolean = false,
        etag: String? = null,
        lastModified: String? = null,
        updatedAtMs: Long = System.currentTimeMillis()
    ): RemoteProvidersConfig {
        if (rawJson.isBlank()) {
            throw IOException("Remote provider configuration is empty")
        }

        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject
            ?: throw IOException("Remote provider configuration must be a JSON object")

        val schemaVersion = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: if (root.containsKey("providers")) 2 else 1

        if (schemaVersion < 1 || schemaVersion > MAX_SUPPORTED_SCHEMA_VERSION) {
            throw IOException("Unsupported remote provider schemaVersion: $schemaVersion")
        }

        val configVersion = (root["configVersion"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 64 }
            ?: (root["version"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it.length <= 64 }
            ?: "v$schemaVersion"

        val refreshIntervalSeconds = ((root["refreshIntervalSeconds"] as? JsonPrimitive)?.intOrNull ?: 1800)
            .coerceIn(60, 86_400)

        val providers = if (root.containsKey("providers")) {
            parseV2Providers(root["providers"], allowLoopback)
        } else {
            parseLegacyV1Providers(root, allowLoopback)
        }

        val lyricsProviders = if (root.containsKey("lyricsProviders")) {
            parseLyricsProviders(root["lyricsProviders"], allowLoopback)
        } else {
            parseLegacyLyricsProviders(root, allowLoopback)
        }

        if (providers.isEmpty() && lyricsProviders.isEmpty()) {
            throw IOException("Remote configuration contained zero valid providers")
        }

        return RemoteProvidersConfig(
            schemaVersion = schemaVersion,
            configVersion = configVersion,
            refreshIntervalSeconds = refreshIntervalSeconds,
            providers = providers.sortedBy { it.priority },
            lyricsProviders = lyricsProviders.sortedBy { it.priority },
            etag = etag,
            lastModified = lastModified,
            updatedAtMs = updatedAtMs
        )
    }

    fun sanitizePersistedConfig(
        config: RemoteProvidersConfig,
        allowLoopback: Boolean = false
    ): RemoteProvidersConfig {
        val sanitizedProviders = config.providers.mapNotNull { sanitizeProviderConfig(it, allowLoopback) }
        val sanitizedLyrics = config.lyricsProviders.mapNotNull { lp ->
            val id = InputAndUrlSecurity.sanitizeProviderId(lp.id) ?: return@mapNotNull null
            val endpoints = lp.endpoints.mapNotNull { ep -> sanitizeEndpoint(ep, allowLoopback) }
            if (endpoints.isEmpty() && lp.enabled) return@mapNotNull null
            lp.copy(
                id = id,
                displayName = InputAndUrlSecurity.sanitizeSingleLineText(lp.displayName, 48).ifBlank { id.uppercase() },
                endpoints = endpoints,
                timeoutMs = lp.timeoutMs.coerceIn(1_000L, 20_000L)
            )
        }
        return config.copy(
            providers = sanitizedProviders.sortedBy { it.priority },
            lyricsProviders = sanitizedLyrics.sortedBy { it.priority }
        )
    }

    private fun parseV2Providers(
        element: JsonElement?,
        allowLoopback: Boolean
    ): List<DynamicProviderConfig> {
        val rawItems: List<Pair<String?, JsonObject>> = when (element) {
            is JsonArray -> element.mapNotNull { item ->
                (item as? JsonObject)?.let { null to it }
            }
            is JsonObject -> element.entries.mapNotNull { (key, value) ->
                (value as? JsonObject)?.let { key to it }
            }
            else -> throw IOException("'providers' must be a JSON array or object map")
        }

        val seenIds = mutableSetOf<String>()
        val result = mutableListOf<DynamicProviderConfig>()

        for ((fallbackId, obj) in rawItems) {
            val rawId = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: fallbackId
            val id = InputAndUrlSecurity.sanitizeProviderId(rawId) ?: continue
            if (!seenIds.add(id)) continue

            val parsed = parseSingleProviderObject(id, obj, allowLoopback) ?: continue
            result += parsed
        }
        return result
    }

    private fun parseSingleProviderObject(
        id: String,
        obj: JsonObject,
        allowLoopback: Boolean
    ): DynamicProviderConfig? {
        val rawAdapter = ((obj["adapterType"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["type"] as? JsonPrimitive)?.contentOrNull
            ?: defaultAdapterForId(id)).trim().lowercase()
        val adapterType = when (rawAdapter) {
            "rest", "dynamic", "generic", "json" -> "generic_rest"
            in KNOWN_ADAPTER_TYPES -> rawAdapter
            else -> "generic_rest"
        }

        val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
        val priority = ((obj["priority"] as? JsonPrimitive)?.intOrNull ?: defaultPriorityForId(id)).coerceIn(0, 10_000)
        val displayName = InputAndUrlSecurity.sanitizeSingleLineText(
            (obj["displayName"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["name"] as? JsonPrimitive)?.contentOrNull
                ?: id.replaceFirstChar { it.uppercase() },
            48
        ).ifBlank { id.uppercase() }

        val capabilities = (obj["capabilities"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { c -> c.isNotBlank() } }
            ?.distinct()
            ?: defaultCapabilitiesForAdapter(adapterType)

        val rawEndpoints = mutableListOf<ProviderEndpointConfig>()
        val singleBaseUrl = (obj["baseUrl"] as? JsonPrimitive)?.contentOrNull
        if (!singleBaseUrl.isNullOrBlank()) {
            rawEndpoints += ProviderEndpointConfig(url = singleBaseUrl, priority = 1)
        }
        val endpointsArray = obj["endpoints"] as? JsonArray ?: obj["urls"] as? JsonArray
        if (endpointsArray != null) {
            endpointsArray.forEachIndexed { idx, el ->
                when (el) {
                    is JsonPrimitive -> el.contentOrNull?.let { url ->
                        rawEndpoints += ProviderEndpointConfig(url = url, priority = idx + 1)
                    }
                    is JsonObject -> {
                        val decodedEp = runCatching {
                            json.decodeFromJsonElement<ProviderEndpointConfig>(el)
                        }.getOrNull() ?: parseEndpointFallback(el, idx + 1)
                        if (decodedEp != null) rawEndpoints += decodedEp
                    }
                    else -> Unit
                }
            }
        }

        val headers = InputAndUrlSecurity.sanitizeRemoteHeaderMap(
            parseStringMap(obj["headers"] as? JsonObject)
        )

        val timeoutMs = ((obj["timeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 10_000L)
            .coerceIn(1_500L, 30_000L)
        val maxRetries = ((obj["maxRetries"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(0, 3)
        val cooldownMs = ((obj["cooldownMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 60_000L)
            .coerceIn(5_000L, 3_600_000L)
        val verifyStreamReachable = (obj["verifyStreamReachable"] as? JsonPrimitive)?.booleanOrNull ?: false

        val routes = (obj["routes"] as? JsonObject)?.let { routesObj ->
            runCatching { json.decodeFromJsonElement<ProviderRoutesConfig>(routesObj) }.getOrNull()
        } ?: ProviderRoutesConfig()

        val mapping = (obj["mapping"] as? JsonObject)?.let { mapObj ->
            runCatching { json.decodeFromJsonElement<ProviderResponseMapping>(mapObj) }.getOrNull()
        } ?: ProviderResponseMapping()

        val candidate = DynamicProviderConfig(
            id = id,
            displayName = displayName,
            adapterType = adapterType,
            enabled = enabled,
            priority = priority,
            capabilities = capabilities,
            endpoints = rawEndpoints,
            timeoutMs = timeoutMs,
            maxRetries = maxRetries,
            cooldownMs = cooldownMs,
            headers = headers,
            routes = routes,
            mapping = mapping,
            verifyStreamReachable = verifyStreamReachable
        )
        return sanitizeProviderConfig(candidate, allowLoopback)
    }

    private fun parseEndpointFallback(obj: JsonObject, defaultPriority: Int): ProviderEndpointConfig? {
        val url = (obj["url"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["baseUrl"] as? JsonPrimitive)?.contentOrNull
            ?: return null
        val priority = (obj["priority"] as? JsonPrimitive)?.intOrNull ?: defaultPriority
        val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
        val ops = (obj["operations"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }
            ?: listOf("search", "metadata", "playback")
        val timeoutMs = (obj["timeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val headers = parseStringMap(obj["headers"] as? JsonObject)
        return ProviderEndpointConfig(
            url = url,
            priority = priority,
            enabled = enabled,
            operations = ops,
            timeoutMs = timeoutMs,
            headers = headers
        )
    }

    private fun parseLegacyV1Providers(
        obj: JsonObject,
        allowLoopback: Boolean
    ): List<DynamicProviderConfig> {
        val result = mutableListOf<DynamicProviderConfig>()

        fun extractLegacyEndpoints(key: String): List<ProviderEndpointConfig> {
            val arr = obj[key] as? JsonArray ?: return emptyList()
            return arr.mapIndexedNotNull { idx, el ->
                val raw = (el as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
                val valid = InputAndUrlSecurity.validateRemoteBaseUrl(raw, allowLoopback) ?: return@mapIndexedNotNull null
                ProviderEndpointConfig(url = valid, priority = idx + 1)
            }.distinctBy { it.url }.take(15)
        }

        val legacyOrder = listOf(
            "jiosaavn" to 1,
            "audius" to 2,
            "piped" to 3,
            "invidious" to 4
        )
        for ((id, priority) in legacyOrder) {
            val endpoints = extractLegacyEndpoints(id)
            if (endpoints.isNotEmpty()) {
                result += DynamicProviderConfig(
                    id = id,
                    displayName = defaultDisplayNameForId(id),
                    adapterType = id,
                    enabled = true,
                    priority = priority,
                    capabilities = defaultCapabilitiesForAdapter(id),
                    endpoints = endpoints
                )
            }
        }
        return result
    }

    private fun parseLyricsProviders(
        element: JsonElement?,
        allowLoopback: Boolean
    ): List<DynamicLyricsProviderConfig> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapIndexedNotNull { index, el ->
            val obj = el as? JsonObject ?: return@mapIndexedNotNull null
            val id = InputAndUrlSecurity.sanitizeProviderId((obj["id"] as? JsonPrimitive)?.contentOrNull ?: "lrclib_$index")
                ?: return@mapIndexedNotNull null
            val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
            val priority = ((obj["priority"] as? JsonPrimitive)?.intOrNull ?: (index + 1)).coerceIn(0, 1000)
            val endpoints = ((obj["endpoints"] as? JsonArray) ?: (obj["urls"] as? JsonArray))
                ?.mapIndexedNotNull { idx, epEl ->
                    when (epEl) {
                        is JsonPrimitive -> epEl.contentOrNull?.let { url ->
                            sanitizeEndpoint(ProviderEndpointConfig(url = url, priority = idx + 1), allowLoopback)
                        }
                        is JsonObject -> parseEndpointFallback(epEl, idx + 1)?.let { ep ->
                            sanitizeEndpoint(ep, allowLoopback)
                        }
                        else -> null
                    }
                }
                ?.distinctBy { it.url }
                .orEmpty()
            if (enabled && endpoints.isEmpty()) return@mapIndexedNotNull null
            DynamicLyricsProviderConfig(
                id = id,
                displayName = InputAndUrlSecurity.sanitizeSingleLineText(
                    (obj["displayName"] as? JsonPrimitive)?.contentOrNull ?: id.uppercase(),
                    48
                ),
                enabled = enabled,
                priority = priority,
                endpoints = endpoints,
                timeoutMs = ((obj["timeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 8_000L)
                    .coerceIn(1_500L, 20_000L)
            )
        }
    }

    private fun parseLegacyLyricsProviders(
        obj: JsonObject,
        allowLoopback: Boolean
    ): List<DynamicLyricsProviderConfig> {
        val arr = obj["lrclib"] as? JsonArray ?: return emptyList()
        val endpoints = arr.mapIndexedNotNull { idx, el ->
            val raw = (el as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
            val valid = InputAndUrlSecurity.validateRemoteBaseUrl(raw, allowLoopback) ?: return@mapIndexedNotNull null
            ProviderEndpointConfig(url = valid, priority = idx + 1)
        }.distinctBy { it.url }.take(15)
        if (endpoints.isEmpty()) return emptyList()
        return listOf(
            DynamicLyricsProviderConfig(
                id = "lrclib",
                displayName = "LRCLIB",
                enabled = true,
                priority = 1,
                endpoints = endpoints
            )
        )
    }

    private fun sanitizeProviderConfig(
        cfg: DynamicProviderConfig,
        allowLoopback: Boolean
    ): DynamicProviderConfig? {
        val id = InputAndUrlSecurity.sanitizeProviderId(cfg.id) ?: return null
        val sanitizedEndpoints = cfg.endpoints
            .mapNotNull { sanitizeEndpoint(it, allowLoopback) }
            .distinctBy { it.url }
            .sortedBy { it.priority }
            .take(15)

        val isNewPipe = cfg.adapterType.equals("newpipe", ignoreCase = true)
        if (!isNewPipe && cfg.enabled && sanitizedEndpoints.isEmpty()) {
            return null
        }

        val sanitizedRoutes = sanitizeRoutes(cfg.routes)
        return cfg.copy(
            id = id,
            displayName = InputAndUrlSecurity.sanitizeSingleLineText(cfg.displayName, 48).ifBlank { id.uppercase() },
            adapterType = cfg.adapterType.trim().lowercase().ifBlank { "generic_rest" },
            priority = cfg.priority.coerceIn(0, 10_000),
            endpoints = sanitizedEndpoints,
            timeoutMs = cfg.timeoutMs.coerceIn(1_500L, 30_000L),
            maxRetries = cfg.maxRetries.coerceIn(0, 3),
            cooldownMs = cfg.cooldownMs.coerceIn(5_000L, 3_600_000L),
            headers = InputAndUrlSecurity.sanitizeRemoteHeaderMap(cfg.headers),
            routes = sanitizedRoutes
        )
    }

    private fun sanitizeEndpoint(
        ep: ProviderEndpointConfig,
        allowLoopback: Boolean
    ): ProviderEndpointConfig? {
        val validUrl = InputAndUrlSecurity.validateRemoteBaseUrl(ep.url, allowLoopback) ?: return null
        val ops = ep.operations
            .map { it.trim().lowercase() }
            .filter { it in setOf("search", "metadata", "playback", "all") }
            .distinct()
            .ifEmpty { listOf("search", "metadata", "playback") }
        return ep.copy(
            url = validUrl,
            priority = ep.priority.coerceIn(0, 10_000),
            operations = ops,
            timeoutMs = ep.timeoutMs?.coerceIn(1_500L, 30_000L),
            headers = InputAndUrlSecurity.sanitizeRemoteHeaderMap(ep.headers)
        )
    }

    private fun sanitizeRoutes(routes: ProviderRoutesConfig): ProviderRoutesConfig {
        fun cleanOp(op: EndpointOperationTemplate?): EndpointOperationTemplate? {
            if (op == null) return null
            val cleanPath = InputAndUrlSecurity.sanitizeEndpointPathTemplate(op.path) ?: return null
            val method = op.method.trim().uppercase().let { if (it in SUPPORTED_METHODS) it else "GET" }
            val cleanParams = op.queryParams.entries
                .filter { it.key.isNotBlank() && it.key.length <= 64 && !InputAndUrlSecurity.hasControlChars(it.value) }
                .take(16)
                .associate { it.key.trim() to it.value }
            val cleanBody = op.bodyTemplate?.takeIf { it.length <= 4096 && !InputAndUrlSecurity.hasControlChars(it) }
            return op.copy(
                path = cleanPath,
                method = method,
                queryParams = cleanParams,
                headers = InputAndUrlSecurity.sanitizeRemoteHeaderMap(op.headers),
                bodyTemplate = cleanBody
            )
        }
        return ProviderRoutesConfig(
            searchAll = cleanOp(routes.searchAll),
            searchSongs = cleanOp(routes.searchSongs),
            searchArtists = cleanOp(routes.searchArtists),
            searchAlbums = cleanOp(routes.searchAlbums),
            suggestions = cleanOp(routes.suggestions),
            trending = cleanOp(routes.trending),
            trackDetails = cleanOp(routes.trackDetails),
            artistDetails = cleanOp(routes.artistDetails),
            albumDetails = cleanOp(routes.albumDetails),
            playlistDetails = cleanOp(routes.playlistDetails),
            resolveStream = cleanOp(routes.resolveStream)
        )
    }

    private fun parseStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            val strVal = (v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            k to strVal
        }.toMap()
    }

    private fun defaultAdapterForId(id: String): String = when (id.lowercase()) {
        "jiosaavn" -> "jiosaavn"
        "piped" -> "piped"
        "invidious" -> "invidious"
        "audius" -> "audius"
        "youtube", "soundcloud", "bandcamp", "newpipe" -> "newpipe"
        else -> "generic_rest"
    }

    private fun defaultDisplayNameForId(id: String): String = when (id.lowercase()) {
        "jiosaavn" -> "JioSaavn"
        "piped" -> "Piped"
        "invidious" -> "Invidious"
        "audius" -> "Audius"
        "youtube" -> "YouTube"
        else -> id.replaceFirstChar { it.uppercase() }
    }

    private fun defaultPriorityForId(id: String): Int = when (id.lowercase()) {
        "jiosaavn" -> 1
        "youtube" -> 2
        "audius" -> 3
        "piped" -> 4
        "invidious" -> 5
        "soundcloud" -> 6
        "bandcamp" -> 7
        else -> 50
    }

    private fun defaultCapabilitiesForAdapter(adapterType: String): List<String> = when (adapterType) {
        "newpipe" -> listOf("search", "suggestions", "trending", "artist", "album", "stream")
        else -> listOf("search", "suggestions", "trending", "artist", "album", "stream")
    }
}
