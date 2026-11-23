package app.nocturne.player.data.online

import app.nocturne.player.data.model.DynamicLyricsProviderConfig
import app.nocturne.player.data.model.DynamicProviderConfig
import app.nocturne.player.data.model.EndpointOperationTemplate
import app.nocturne.player.data.model.ProviderEndpointConfig
import app.nocturne.player.data.model.ProviderResponseMapping
import app.nocturne.player.data.model.ProviderRoutesConfig
import app.nocturne.player.data.model.RemoteConfigErrorType
import app.nocturne.player.data.model.RemoteProvidersConfig
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.io.IOException

class RemoteConfigValidationException(
    val errorType: RemoteConfigErrorType,
    override val message: String,
    cause: Throwable? = null
) : IOException(message, cause)

object RemoteConfigValidator {

    const val MAX_SUPPORTED_SCHEMA_VERSION = 2
    private val SUPPORTED_METHODS = setOf("GET", "POST")
    private val KNOWN_ADAPTER_TYPES = setOf("jiosaavn", "piped", "invidious", "audius", "newpipe", "generic_rest")
    private val BUILTIN_EXTRACTOR_IDS = setOf("youtube", "youtube_music", "yt", "ytmusic", "soundcloud", "bandcamp", "newpipe")
    private val PROVIDER_CONTAINER_KEYS = listOf("providers", "musicProviders", "streamingProviders", "audioProviders", "sources")
    private val LYRICS_CONTAINER_KEYS = listOf("lyricsProviders", "lyrics_providers", "lyrics")
    private val METADATA_ROOT_KEYS = setOf(
        "schemaVersion", "schema_version", "schema",
        "configVersion", "config_version", "version",
        "refreshIntervalSeconds", "refresh_interval_seconds", "refreshInterval", "ttlSeconds", "ttl",
        "repository", "repo", "name", "description", "updatedAt", "updated_at",
        "lyricsProviders", "lyrics_providers", "lyrics", "lrclib"
    )
    private val ENDPOINT_OPERATIONS = setOf("search", "metadata", "playback", "all")

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
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) {
            throw RemoteConfigValidationException(
                RemoteConfigErrorType.JSON_PARSE_ERROR,
                "Remote provider configuration is empty"
            )
        }

        val parsedElement = try {
            json.parseToJsonElement(trimmed)
        } catch (e: SerializationException) {
            val looksLikeHtml = trimmed.startsWith("<")
            val detail = if (looksLikeHtml) {
                "Response returned HTML/text instead of JSON"
            } else {
                "Invalid JSON syntax: ${e.message?.lineSequence()?.firstOrNull()?.take(120) ?: "malformed JSON"}"
            }
            throw RemoteConfigValidationException(RemoteConfigErrorType.JSON_PARSE_ERROR, detail, e)
        } catch (e: IllegalArgumentException) {
            throw RemoteConfigValidationException(
                RemoteConfigErrorType.JSON_PARSE_ERROR,
                "Invalid JSON payload: ${e.message?.take(120) ?: "parse error"}",
                e
            )
        }

        val rawRoot = parsedElement as? JsonObject
            ?: throw RemoteConfigValidationException(
                RemoteConfigErrorType.UNSUPPORTED_SCHEMA_ERROR,
                "Unsupported configuration schema: root JSON must be an object"
            )

        val root = unwrapEnvelopeIfNeeded(rawRoot)

        val providersKey = PROVIDER_CONTAINER_KEYS.firstOrNull { root.containsKey(it) }
        val schemaVersionPrimitive = (root["schemaVersion"] ?: root["schema_version"] ?: root["schema"])
        val schemaVersion = if (schemaVersionPrimitive != null && schemaVersionPrimitive !is JsonNull) {
            val prim = schemaVersionPrimitive as? JsonPrimitive
                ?: throw RemoteConfigValidationException(
                    RemoteConfigErrorType.UNSUPPORTED_SCHEMA_ERROR,
                    "Unsupported remote provider schemaVersion format"
                )
            prim.intOrNull
                ?: prim.contentOrNull?.trim()?.toDoubleOrNull()?.takeIf { it >= 0.0 && it == it.toInt().toDouble() }?.toInt()
                ?: throw RemoteConfigValidationException(
                    RemoteConfigErrorType.UNSUPPORTED_SCHEMA_ERROR,
                    "Unsupported remote provider schemaVersion: ${prim.contentOrNull}"
                )
        } else {
            if (providersKey != null) 2 else 1
        }

        if (schemaVersion < 1 || schemaVersion > MAX_SUPPORTED_SCHEMA_VERSION) {
            throw RemoteConfigValidationException(
                RemoteConfigErrorType.UNSUPPORTED_SCHEMA_ERROR,
                "Unsupported remote provider schemaVersion: $schemaVersion (supported: 1..$MAX_SUPPORTED_SCHEMA_VERSION)"
            )
        }

        val configVersion = (root.strValue("configVersion", "config_version", "version")
            ?.takeIf { it.isNotBlank() && it.length <= 64 })
            ?: "v$schemaVersion"

        val refreshIntervalSeconds = (root.intValue(
            "refreshIntervalSeconds",
            "refresh_interval_seconds",
            "refreshInterval",
            "ttlSeconds",
            "ttl"
        ) ?: 1800).coerceIn(60, 86_400)

        val parsedProviders = mutableListOf<DynamicProviderConfig>()
        val parsedLyrics = mutableListOf<DynamicLyricsProviderConfig>()

        if (providersKey != null) {
            parseV2Providers(
                element = root[providersKey],
                allowLoopback = allowLoopback,
                outProviders = parsedProviders,
                outLyrics = parsedLyrics
            )
        } else {
            parseLegacyV1Providers(
                obj = root,
                allowLoopback = allowLoopback,
                outProviders = parsedProviders,
                outLyrics = parsedLyrics
            )
        }

        val lyricsKey = LYRICS_CONTAINER_KEYS.firstOrNull { root.containsKey(it) }
        if (lyricsKey != null) {
            parseLyricsElement(root[lyricsKey], allowLoopback, parsedLyrics)
        }
        if (root.containsKey("lrclib")) {
            parseLyricsElement(root["lrclib"], allowLoopback, parsedLyrics, fallbackId = "lrclib")
        }

        val dedupedProviders = parsedProviders
            .distinctBy { it.id.lowercase() }
            .sortedBy { it.priority }

        val dedupedLyrics = parsedLyrics
            .distinctBy { it.id.lowercase() }
            .sortedBy { it.priority }

        if (dedupedProviders.isEmpty() && dedupedLyrics.isEmpty()) {
            throw RemoteConfigValidationException(
                RemoteConfigErrorType.UNSUPPORTED_SCHEMA_ERROR,
                "Remote configuration contained zero valid providers"
            )
        }

        return RemoteProvidersConfig(
            schemaVersion = schemaVersion,
            configVersion = configVersion,
            refreshIntervalSeconds = refreshIntervalSeconds,
            providers = dedupedProviders,
            lyricsProviders = dedupedLyrics,
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

    private fun unwrapEnvelopeIfNeeded(root: JsonObject): JsonObject {
        val hasDirectSchema = PROVIDER_CONTAINER_KEYS.any { root.containsKey(it) } ||
            root.containsKey("jiosaavn") ||
            root.containsKey("piped") ||
            root.containsKey("invidious") ||
            root.containsKey("audius") ||
            root.containsKey("youtube")
        if (hasDirectSchema) return root

        for (wrapperKey in listOf("config", "data")) {
            val nested = root[wrapperKey] as? JsonObject ?: continue
            val nestedHasSchema = PROVIDER_CONTAINER_KEYS.any { nested.containsKey(it) } ||
                nested.containsKey("jiosaavn") ||
                nested.containsKey("piped") ||
                nested.containsKey("invidious") ||
                nested.containsKey("audius")
            if (nestedHasSchema) return nested
        }
        return root
    }

    private fun parseV2Providers(
        element: JsonElement?,
        allowLoopback: Boolean,
        outProviders: MutableList<DynamicProviderConfig>,
        outLyrics: MutableList<DynamicLyricsProviderConfig>,
        inheritedCategory: String? = null
    ) {
        when (element) {
            is JsonArray -> {
                element.forEachIndexed { idx, item ->
                    val obj = item as? JsonObject ?: return@forEachIndexed
                    val rawId = obj.strValue("id", "providerId", "provider_id", "key")
                    val id = InputAndUrlSecurity.sanitizeProviderId(rawId) ?: return@forEachIndexed
                    if (isLyricsEntry(id, obj, inheritedCategory)) {
                        parseSingleLyricsObject(id, obj, allowLoopback, idx + 1)?.let(outLyrics::add)
                    } else {
                        parseSingleProviderObject(
                            id = id,
                            obj = obj,
                            allowLoopback = allowLoopback,
                            fallbackCategory = inheritedCategory
                        )?.let(outProviders::add)
                    }
                }
            }
            is JsonObject -> {
                for ((key, value) in element.entries) {
                    when (value) {
                        is JsonArray -> {
                            if (looksLikeCategoryArray(value)) {
                                val categoryName = formatCategoryLabel(key)
                                if (key.equals("lyrics", ignoreCase = true) || key.equals("lyricsProviders", ignoreCase = true)) {
                                    parseLyricsElement(value, allowLoopback, outLyrics)
                                } else {
                                    parseV2Providers(
                                        element = value,
                                        allowLoopback = allowLoopback,
                                        outProviders = outProviders,
                                        outLyrics = outLyrics,
                                        inheritedCategory = categoryName
                                    )
                                }
                            } else {
                                val id = InputAndUrlSecurity.sanitizeProviderId(key) ?: continue
                                if (isLyricsId(id)) {
                                    parseLyricsUrlArray(id, value, allowLoopback)?.let(outLyrics::add)
                                } else {
                                    val eps = extractEndpointsFromArray(value, allowLoopback)
                                    val candidate = buildProviderFromEndpoints(
                                        id = id,
                                        endpoints = eps,
                                        categories = listOfNotNull(inheritedCategory),
                                        allowLoopback = allowLoopback
                                    )
                                    if (candidate != null) outProviders.add(candidate)
                                }
                            }
                        }
                        is JsonObject -> {
                            if (looksLikeProviderObject(key, value)) {
                                val rawId = value.strValue("id", "providerId", "provider_id", "key") ?: key
                                val id = InputAndUrlSecurity.sanitizeProviderId(rawId) ?: continue
                                if (isLyricsEntry(id, value, inheritedCategory)) {
                                    parseSingleLyricsObject(id, value, allowLoopback, 1)?.let(outLyrics::add)
                                } else {
                                    parseSingleProviderObject(
                                        id = id,
                                        obj = value,
                                        allowLoopback = allowLoopback,
                                        fallbackCategory = inheritedCategory
                                    )?.let(outProviders::add)
                                }
                            } else {
                                val categoryName = formatCategoryLabel(key)
                                parseV2Providers(
                                    element = value,
                                    allowLoopback = allowLoopback,
                                    outProviders = outProviders,
                                    outLyrics = outLyrics,
                                    inheritedCategory = categoryName
                                )
                            }
                        }
                        is JsonPrimitive -> {
                            val id = InputAndUrlSecurity.sanitizeProviderId(key) ?: continue
                            val str = value.contentOrNull?.trim()
                            val boolVal = value.booleanOrNull
                            when {
                                !str.isNullOrBlank() && str.startsWith("http", ignoreCase = true) -> {
                                    val ep = ProviderEndpointConfig(url = str, priority = 1)
                                    if (isLyricsId(id)) {
                                        sanitizeEndpoint(ep, allowLoopback)?.let { safeEp ->
                                            outLyrics.add(
                                                DynamicLyricsProviderConfig(
                                                    id = id,
                                                    displayName = id.uppercase(),
                                                    enabled = true,
                                                    priority = 1,
                                                    endpoints = listOf(safeEp)
                                                )
                                            )
                                        }
                                    } else {
                                        buildProviderFromEndpoints(
                                            id = id,
                                            endpoints = listOf(ep),
                                            categories = listOfNotNull(inheritedCategory),
                                            allowLoopback = allowLoopback
                                        )?.let(outProviders::add)
                                    }
                                }
                                boolVal != null && id in BUILTIN_EXTRACTOR_IDS -> {
                                    val candidate = DynamicProviderConfig(
                                        id = id,
                                        displayName = defaultDisplayNameForId(id),
                                        adapterType = "newpipe",
                                        enabled = boolVal,
                                        priority = defaultPriorityForId(id),
                                        capabilities = defaultCapabilitiesForAdapter("newpipe"),
                                        categories = listOfNotNull(inheritedCategory)
                                    )
                                    sanitizeProviderConfig(candidate, allowLoopback)?.let(outProviders::add)
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
            else -> throw RemoteConfigValidationException(
                RemoteConfigErrorType.UNSUPPORTED_SCHEMA_ERROR,
                "'providers' must be a JSON array or object map"
            )
        }
    }

    private fun looksLikeCategoryArray(arr: JsonArray): Boolean {
        val objects = arr.mapNotNull { it as? JsonObject }
        if (objects.isEmpty()) return false
        return objects.any { obj ->
            obj.containsKey("id") ||
                obj.containsKey("providerId") ||
                obj.containsKey("adapterType") ||
                obj.containsKey("adapter") ||
                obj.containsKey("endpoints") ||
                obj.containsKey("urls") ||
                obj.containsKey("instances") ||
                obj.containsKey("servers") ||
                obj.containsKey("routes") ||
                obj.containsKey("mapping") ||
                obj.containsKey("displayName") ||
                obj.containsKey("name")
        }
    }

    private fun looksLikeProviderObject(key: String, obj: JsonObject): Boolean {
        val lowerKey = key.lowercase()
        if (lowerKey in setOf("jiosaavn", "piped", "invidious", "audius", "youtube", "soundcloud", "bandcamp", "newpipe", "lrclib")) {
            return true
        }
        val providerFieldHints = setOf(
            "id", "providerId", "provider_id",
            "adapterType", "adapter_type", "adapter", "type", "engine",
            "enabled", "isEnabled", "active", "disabled",
            "priority", "capabilities",
            "baseUrl", "base_url", "url", "apiUrl", "api_url", "endpoint", "host", "instance",
            "endpoints", "urls", "baseUrls", "instances", "servers", "mirrors", "hosts", "nodes",
            "routes", "mapping"
        )
        return obj.keys.any { it in providerFieldHints }
    }

    private fun isLyricsEntry(id: String, obj: JsonObject, inheritedCategory: String?): Boolean {
        if (isLyricsId(id)) return true
        val rawAdapter = obj.strValue("adapterType", "adapter_type", "adapter", "type", "kind")?.lowercase()
        if (rawAdapter == "lyrics" || rawAdapter == "lrclib") return true
        val rawCat = obj.strValue("category", "group")?.lowercase()
        if (rawCat == "lyrics") return true
        if (inheritedCategory.equals("Lyrics", ignoreCase = true)) return true
        return false
    }

    private fun isLyricsId(id: String): Boolean {
        val lower = id.lowercase()
        return lower == "lrclib" || lower == "lyrics"
    }

    private fun parseSingleProviderObject(
        id: String,
        obj: JsonObject,
        allowLoopback: Boolean,
        fallbackCategory: String? = null
    ): DynamicProviderConfig? {
        val rawEndpoints = extractEndpointsFromObject(obj)

        val rawAdapter = (obj.strValue("adapterType", "adapter_type", "adapter", "type", "engine", "kind")
            ?: defaultAdapterForId(id, hasHttpEndpoints = rawEndpoints.isNotEmpty())).trim().lowercase()

        val adapterType = when (rawAdapter) {
            "rest", "dynamic", "generic", "json", "api", "http" -> "generic_rest"
            "saavn", "jiosaavn" -> "jiosaavn"
            "piped" -> "piped"
            "invidious" -> "invidious"
            "audius" -> "audius"
            "newpipe", "newpipe_extractor", "extractor", "builtin", "native" -> "newpipe"
            "youtube", "youtube_music", "yt", "ytmusic", "soundcloud", "bandcamp" -> {
                if (rawEndpoints.isEmpty()) "newpipe" else "generic_rest"
            }
            in KNOWN_ADAPTER_TYPES -> rawAdapter
            else -> if (id.lowercase() in BUILTIN_EXTRACTOR_IDS && rawEndpoints.isEmpty()) "newpipe" else "generic_rest"
        }

        val enabled = resolveEnabledFlag(obj)
        val priority = (obj.intValue("priority", "order", "rank") ?: defaultPriorityForId(id)).coerceIn(0, 10_000)
        val displayName = InputAndUrlSecurity.sanitizeSingleLineText(
            obj.strValue("displayName", "display_name", "name", "title", "label")
                ?: defaultDisplayNameForId(id),
            48
        ).ifBlank { id.uppercase() }

        val capabilities = (obj["capabilities"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { c -> c.isNotBlank() } }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultCapabilitiesForAdapter(adapterType)

        val categories = extractCategories(obj, fallbackCategory)

        val headers = InputAndUrlSecurity.sanitizeRemoteHeaderMap(
            parseStringMap(obj["headers"] as? JsonObject)
        )

        val timeoutMs = ((obj["timeoutMs"] ?: obj["timeout_ms"]) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?.coerceIn(1_500L, 30_000L) ?: 10_000L
        val maxRetries = (obj.intValue("maxRetries", "max_retries") ?: 1).coerceIn(0, 3)
        val cooldownMs = ((obj["cooldownMs"] ?: obj["cooldown_ms"]) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?.coerceIn(5_000L, 3_600_000L) ?: 60_000L
        val verifyStreamReachable = (obj["verifyStreamReachable"] ?: obj["verify_stream_reachable"])
            ?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false

        val routes = (obj["routes"] as? JsonObject)?.let { routesObj ->
            parseRoutesResilient(routesObj)
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
            verifyStreamReachable = verifyStreamReachable,
            categories = categories
        )
        return sanitizeProviderConfig(candidate, allowLoopback)
    }

    private fun parseRoutesResilient(routesObj: JsonObject): ProviderRoutesConfig {
        runCatching { json.decodeFromJsonElement<ProviderRoutesConfig>(routesObj) }.getOrNull()?.let { return it }

        fun readRoute(key: String): EndpointOperationTemplate? {
            return when (val el = routesObj[key]) {
                is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let {
                    EndpointOperationTemplate(path = it)
                }
                is JsonObject -> runCatching { json.decodeFromJsonElement<EndpointOperationTemplate>(el) }.getOrNull()
                    ?: el.strValue("path", "endpoint", "url")?.let { EndpointOperationTemplate(path = it) }
                else -> null
            }
        }

        return ProviderRoutesConfig(
            searchAll = readRoute("searchAll"),
            searchSongs = readRoute("searchSongs"),
            searchArtists = readRoute("searchArtists"),
            searchAlbums = readRoute("searchAlbums"),
            suggestions = readRoute("suggestions"),
            trending = readRoute("trending"),
            trackDetails = readRoute("trackDetails"),
            artistDetails = readRoute("artistDetails"),
            albumDetails = readRoute("albumDetails"),
            playlistDetails = readRoute("playlistDetails"),
            resolveStream = readRoute("resolveStream")
        )
    }

    private fun extractEndpointsFromObject(obj: JsonObject): MutableList<ProviderEndpointConfig> {
        val rawEndpoints = mutableListOf<ProviderEndpointConfig>()
        val singleKeys = listOf("baseUrl", "base_url", "url", "apiUrl", "api_url", "endpoint", "host", "instance")
        for (key in singleKeys) {
            when (val el = obj[key]) {
                is JsonPrimitive -> {
                    val str = el.contentOrNull?.trim()
                    if (!str.isNullOrBlank()) {
                        rawEndpoints += ProviderEndpointConfig(url = str, priority = 1)
                    }
                }
                is JsonObject -> {
                    parseEndpointElement(el, rawEndpoints.size + 1)?.let(rawEndpoints::add)
                }
                else -> Unit
            }
        }

        val arrayKeys = listOf(
            "endpoints", "urls", "baseUrls", "base_urls",
            "instances", "servers", "mirrors", "hosts", "nodes", "apiUrls", "links"
        )
        for (key in arrayKeys) {
            when (val el = obj[key]) {
                is JsonArray -> {
                    el.forEachIndexed { idx, item ->
                        parseEndpointElement(item, rawEndpoints.size + idx + 1)?.let(rawEndpoints::add)
                    }
                }
                is JsonPrimitive -> {
                    el.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
                        rawEndpoints += ProviderEndpointConfig(url = url, priority = rawEndpoints.size + 1)
                    }
                }
                is JsonObject -> {
                    if (el.strValue("url", "baseUrl", "base_url", "href", "endpoint", "host", "apiUrl", "api") != null) {
                        parseEndpointElement(el, rawEndpoints.size + 1)?.let(rawEndpoints::add)
                    } else {
                        el.entries.forEachIndexed { idx, (subKey, subVal) ->
                            val opHint = subKey.lowercase().takeIf { it in ENDPOINT_OPERATIONS }
                            when (subVal) {
                                is JsonPrimitive -> subVal.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
                                    rawEndpoints += ProviderEndpointConfig(
                                        url = url,
                                        priority = idx + 1,
                                        operations = if (opHint != null) listOf(opHint) else listOf("search", "metadata", "playback")
                                    )
                                }
                                is JsonObject -> parseEndpointElement(subVal, idx + 1)?.let { parsed ->
                                    rawEndpoints += if (opHint != null) parsed.copy(operations = listOf(opHint)) else parsed
                                }
                                is JsonArray -> subVal.forEachIndexed { arrIdx, arrEl ->
                                    parseEndpointElement(arrEl, arrIdx + 1)?.let { parsed ->
                                        rawEndpoints += if (opHint != null) parsed.copy(operations = listOf(opHint)) else parsed
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        return rawEndpoints
    }

    private fun parseEndpointElement(el: JsonElement, defaultPriority: Int): ProviderEndpointConfig? {
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
                ProviderEndpointConfig(url = url, priority = defaultPriority)
            }
            is JsonObject -> {
                runCatching { json.decodeFromJsonElement<ProviderEndpointConfig>(el) }.getOrNull()
                    ?: parseEndpointFallback(el, defaultPriority)
            }
            else -> null
        }
    }

    private fun extractEndpointsFromArray(arr: JsonArray, allowLoopback: Boolean): List<ProviderEndpointConfig> {
        return arr.mapIndexedNotNull { idx, el ->
            parseEndpointElement(el, idx + 1)?.let { sanitizeEndpoint(it, allowLoopback) }
        }.distinctBy { it.url }.take(15)
    }

    private fun buildProviderFromEndpoints(
        id: String,
        endpoints: List<ProviderEndpointConfig>,
        categories: List<String>,
        allowLoopback: Boolean
    ): DynamicProviderConfig? {
        val adapter = defaultAdapterForId(id, hasHttpEndpoints = endpoints.isNotEmpty())
        val candidate = DynamicProviderConfig(
            id = id,
            displayName = defaultDisplayNameForId(id),
            adapterType = adapter,
            enabled = true,
            priority = defaultPriorityForId(id),
            capabilities = defaultCapabilitiesForAdapter(adapter),
            endpoints = endpoints,
            categories = categories
        )
        return sanitizeProviderConfig(candidate, allowLoopback)
    }

    private fun parseEndpointFallback(obj: JsonObject, defaultPriority: Int): ProviderEndpointConfig? {
        val url = obj.strValue("url", "baseUrl", "base_url", "href", "endpoint", "host", "apiUrl", "api")
            ?: return null
        val priority = obj.intValue("priority", "order") ?: defaultPriority
        val enabled = resolveEnabledFlag(obj)
        val ops = (obj["operations"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }
            ?: listOf("search", "metadata", "playback")
        val timeoutMs = ((obj["timeoutMs"] ?: obj["timeout_ms"]) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
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
        allowLoopback: Boolean,
        outProviders: MutableList<DynamicProviderConfig>,
        outLyrics: MutableList<DynamicLyricsProviderConfig>
    ) {
        val knownLegacyOrder = mapOf(
            "jiosaavn" to 1,
            "youtube" to 2,
            "audius" to 3,
            "piped" to 4,
            "invidious" to 5,
            "soundcloud" to 6,
            "bandcamp" to 7
        )

        for ((rawKey, value) in obj.entries) {
            if (rawKey in METADATA_ROOT_KEYS) continue
            val id = InputAndUrlSecurity.sanitizeProviderId(rawKey) ?: continue
            val defaultPri = knownLegacyOrder[id] ?: 50
            when (value) {
                is JsonArray -> {
                    if (looksLikeCategoryArray(value)) {
                        parseV2Providers(
                            element = value,
                            allowLoopback = allowLoopback,
                            outProviders = outProviders,
                            outLyrics = outLyrics,
                            inheritedCategory = formatCategoryLabel(rawKey)
                        )
                    } else {
                        val endpoints = extractEndpointsFromArray(value, allowLoopback)
                        if (endpoints.isNotEmpty()) {
                            val adapter = defaultAdapterForId(id, hasHttpEndpoints = true)
                            outProviders += DynamicProviderConfig(
                                id = id,
                                displayName = defaultDisplayNameForId(id),
                                adapterType = adapter,
                                enabled = true,
                                priority = defaultPri,
                                capabilities = defaultCapabilitiesForAdapter(adapter),
                                endpoints = endpoints
                            )
                        }
                    }
                }
                is JsonObject -> {
                    if (looksLikeProviderObject(rawKey, value)) {
                        parseSingleProviderObject(id, value, allowLoopback)?.let(outProviders::add)
                    } else {
                        parseV2Providers(
                            element = value,
                            allowLoopback = allowLoopback,
                            outProviders = outProviders,
                            outLyrics = outLyrics,
                            inheritedCategory = formatCategoryLabel(rawKey)
                        )
                    }
                }
                is JsonPrimitive -> {
                    val str = value.contentOrNull?.trim()
                    val boolVal = value.booleanOrNull
                    if (!str.isNullOrBlank() && str.startsWith("http", ignoreCase = true)) {
                        val valid = InputAndUrlSecurity.validateRemoteBaseUrl(str, allowLoopback)
                        if (valid != null) {
                            val adapter = defaultAdapterForId(id, hasHttpEndpoints = true)
                            outProviders += DynamicProviderConfig(
                                id = id,
                                displayName = defaultDisplayNameForId(id),
                                adapterType = adapter,
                                enabled = true,
                                priority = defaultPri,
                                capabilities = defaultCapabilitiesForAdapter(adapter),
                                endpoints = listOf(ProviderEndpointConfig(url = valid, priority = 1))
                            )
                        }
                    } else if (boolVal != null && id in BUILTIN_EXTRACTOR_IDS) {
                        outProviders += DynamicProviderConfig(
                            id = id,
                            displayName = defaultDisplayNameForId(id),
                            adapterType = "newpipe",
                            enabled = boolVal,
                            priority = defaultPri,
                            capabilities = defaultCapabilitiesForAdapter("newpipe")
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private fun parseLyricsElement(
        element: JsonElement?,
        allowLoopback: Boolean,
        outLyrics: MutableList<DynamicLyricsProviderConfig>,
        fallbackId: String? = null
    ) {
        when (element) {
            is JsonArray -> {
                if (element.any { it is JsonObject && (it.containsKey("id") || it.containsKey("endpoints") || it.containsKey("urls")) }) {
                    element.forEachIndexed { index, el ->
                        val obj = el as? JsonObject ?: return@forEachIndexed
                        val id = InputAndUrlSecurity.sanitizeProviderId(
                            obj.strValue("id", "providerId", "key") ?: fallbackId ?: "lrclib_$index"
                        ) ?: return@forEachIndexed
                        parseSingleLyricsObject(id, obj, allowLoopback, index + 1)?.let(outLyrics::add)
                    }
                } else {
                    parseLyricsUrlArray(fallbackId ?: "lrclib", element, allowLoopback)?.let(outLyrics::add)
                }
            }
            is JsonObject -> {
                val id = InputAndUrlSecurity.sanitizeProviderId(
                    element.strValue("id", "providerId", "key") ?: fallbackId ?: "lrclib"
                ) ?: return
                parseSingleLyricsObject(id, element, allowLoopback, 1)?.let(outLyrics::add)
            }
            is JsonPrimitive -> {
                val url = element.contentOrNull?.trim() ?: return
                val valid = InputAndUrlSecurity.validateRemoteBaseUrl(url, allowLoopback) ?: return
                outLyrics += DynamicLyricsProviderConfig(
                    id = fallbackId ?: "lrclib",
                    displayName = (fallbackId ?: "lrclib").uppercase(),
                    enabled = true,
                    priority = 1,
                    endpoints = listOf(ProviderEndpointConfig(url = valid, priority = 1))
                )
            }
            else -> Unit
        }
    }

    private fun parseSingleLyricsObject(
        id: String,
        obj: JsonObject,
        allowLoopback: Boolean,
        defaultPriority: Int
    ): DynamicLyricsProviderConfig? {
        val enabled = resolveEnabledFlag(obj)
        val priority = (obj.intValue("priority", "order") ?: defaultPriority).coerceIn(0, 1000)
        val rawEndpoints = extractEndpointsFromObject(obj)
        val sanitized = rawEndpoints
            .mapNotNull { sanitizeEndpoint(it, allowLoopback) }
            .distinctBy { it.url }
            .sortedBy { it.priority }
            .take(15)
        if (enabled && sanitized.isEmpty()) return null
        return DynamicLyricsProviderConfig(
            id = id,
            displayName = InputAndUrlSecurity.sanitizeSingleLineText(
                obj.strValue("displayName", "display_name", "name") ?: id.uppercase(),
                48
            ).ifBlank { id.uppercase() },
            enabled = enabled,
            priority = priority,
            endpoints = sanitized,
            timeoutMs = ((obj["timeoutMs"] ?: obj["timeout_ms"]) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?.coerceIn(1_500L, 20_000L) ?: 8_000L
        )
    }

    private fun parseLyricsUrlArray(
        id: String,
        arr: JsonArray,
        allowLoopback: Boolean
    ): DynamicLyricsProviderConfig? {
        val endpoints = extractEndpointsFromArray(arr, allowLoopback)
        if (endpoints.isEmpty()) return null
        return DynamicLyricsProviderConfig(
            id = id,
            displayName = if (id.equals("lrclib", ignoreCase = true)) "LRCLIB" else id.uppercase(),
            enabled = true,
            priority = 1,
            endpoints = endpoints
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
        val cleanCategories = cfg.categories
            .map { InputAndUrlSecurity.sanitizeSingleLineText(it, 40) }
            .filter { it.isNotBlank() }
            .distinct()

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
            routes = sanitizedRoutes,
            categories = cleanCategories
        )
    }

    private fun sanitizeEndpoint(
        ep: ProviderEndpointConfig,
        allowLoopback: Boolean
    ): ProviderEndpointConfig? {
        val validUrl = InputAndUrlSecurity.validateRemoteBaseUrl(ep.url, allowLoopback) ?: return null
        val ops = ep.operations
            .map { it.trim().lowercase() }
            .filter { it in ENDPOINT_OPERATIONS }
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

    private fun extractCategories(obj: JsonObject, fallbackCategory: String?): List<String> {
        val list = mutableListOf<String>()
        when (val cats = obj["categories"] ?: obj["tags"]) {
            is JsonArray -> cats.forEach { el ->
                (el as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let {
                    list += formatCategoryLabel(it)
                }
            }
            is JsonPrimitive -> cats.contentOrNull?.split(',')?.forEach { part ->
                part.trim().takeIf { it.isNotBlank() }?.let { list += formatCategoryLabel(it) }
            }
            else -> Unit
        }
        val singleCat = obj.strValue("category", "group")
        if (!singleCat.isNullOrBlank()) {
            list += formatCategoryLabel(singleCat)
        }
        if (list.isEmpty() && !fallbackCategory.isNullOrBlank()) {
            list += formatCategoryLabel(fallbackCategory)
        }
        return list.distinct()
    }

    private fun formatCategoryLabel(raw: String): String {
        val clean = InputAndUrlSecurity.sanitizeSingleLineText(raw.replace('_', ' ').replace('-', ' '), 40)
        if (clean.isBlank()) return ""
        return clean.split(Regex("\\s+")).joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun resolveEnabledFlag(obj: JsonObject): Boolean {
        val enabledPrim = (obj["enabled"] ?: obj["isEnabled"] ?: obj["is_enabled"] ?: obj["active"] ?: obj["isActive"]) as? JsonPrimitive
        enabledPrim?.booleanOrNull?.let { return it }

        val disabledPrim = obj["disabled"] as? JsonPrimitive
        disabledPrim?.booleanOrNull?.let { return !it }

        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        if (status == "disabled" || status == "inactive" || status == "off") return false
        if (status == "enabled" || status == "active" || status == "on") return true
        return true
    }

    private fun parseStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            val strVal = (v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            k to strVal
        }.toMap()
    }

    private fun JsonObject.strValue(vararg keys: String): String? {
        for (key in keys) {
            val v = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun JsonObject.intValue(vararg keys: String): Int? {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            prim.intOrNull?.let { return it }
            prim.contentOrNull?.trim()?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun defaultAdapterForId(id: String, hasHttpEndpoints: Boolean = false): String = when (id.lowercase()) {
        "jiosaavn", "saavn" -> "jiosaavn"
        "piped" -> "piped"
        "invidious" -> "invidious"
        "audius" -> "audius"
        "youtube", "youtube_music", "yt", "ytmusic", "soundcloud", "bandcamp", "newpipe" ->
            if (hasHttpEndpoints) "generic_rest" else "newpipe"
        else -> "generic_rest"
    }

    private fun defaultDisplayNameForId(id: String): String = when (id.lowercase()) {
        "jiosaavn" -> "JioSaavn"
        "piped" -> "Piped"
        "invidious" -> "Invidious"
        "audius" -> "Audius"
        "youtube" -> "YouTube"
        "soundcloud" -> "SoundCloud"
        "bandcamp" -> "Bandcamp"
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
