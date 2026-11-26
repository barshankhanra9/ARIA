package app.nocturne.player.data.online

import app.nocturne.player.data.model.DynamicLyricsProviderConfig
import app.nocturne.player.data.model.DynamicProviderConfig
import app.nocturne.player.data.model.EndpointHealthInfo
import app.nocturne.player.data.model.LyricsProviderDiagnosticsSnapshot
import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.model.ProviderDiagnosticsSnapshot
import app.nocturne.player.data.model.ProviderHealthSnapshot
import app.nocturne.player.data.security.InputAndUrlSecurity
import java.util.concurrent.ConcurrentHashMap

class ProviderHealthTracker(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val failureThreshold: Int = 2,
    private val baseCooldownMs: Long = 30_000L,
    private val maxCooldownMs: Long = 5 * 60_000L
) {
    private data class MutableHealth(
        var consecutiveFailures: Int = 0,
        var lastFailureAtMs: Long = 0L,
        var lastSuccessAtMs: Long = 0L,
        var cooldownUntilMs: Long = 0L,
        var ewmaLatencyMs: Long = 450L,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var activeInstanceUrl: String? = null,
        var lastError: String? = null
    )

    private data class EndpointOpKey(
        val providerId: String,
        val endpointUrl: String,
        val operation: OperationType
    )

    private data class ProviderOpKey(
        val providerId: String,
        val operation: OperationType
    )

    private val providerStates = ConcurrentHashMap<String, MutableHealth>()
    private val endpointStates = ConcurrentHashMap<EndpointOpKey, MutableHealth>()
    private val lastSuccessfulEndpointByOp = ConcurrentHashMap<ProviderOpKey, String>()

    private fun providerState(providerId: String): MutableHealth =
        providerStates.getOrPut(providerId.lowercase()) { MutableHealth() }

    private fun endpointState(providerId: String, endpointUrl: String, operation: OperationType): MutableHealth =
        endpointStates.getOrPut(EndpointOpKey(providerId.lowercase(), endpointUrl.trimEnd('/'), operation)) {
            MutableHealth(activeInstanceUrl = endpointUrl.trimEnd('/'))
        }

    // ---- Legacy enum compatibility APIs ----

    @Synchronized
    fun recordSuccess(type: OnlineProviderType, latencyMs: Long, instanceUrl: String? = null) {
        recordProviderSuccess(type.defaultProviderId(), latencyMs, instanceUrl)
    }

    @Synchronized
    fun recordFailure(type: OnlineProviderType, error: String? = null) {
        recordProviderFailure(type.defaultProviderId(), error)
    }

    @Synchronized
    fun isAvailable(type: OnlineProviderType): Boolean = isProviderAvailable(type.defaultProviderId())

    @Synchronized
    fun selectOrderedCandidates(candidates: List<OnlineProviderType>): List<OnlineProviderType> {
        val now = clockMs()
        val healthy = candidates.filter { now >= providerState(it.defaultProviderId()).cooldownUntilMs }
        return if (healthy.isNotEmpty()) {
            healthy
        } else {
            candidates.sortedBy { providerState(it.defaultProviderId()).cooldownUntilMs }
        }
    }

    @Synchronized
    fun snapshotAll(types: List<OnlineProviderType> = OnlineProviderType.entries): List<ProviderHealthSnapshot> {
        val now = clockMs()
        return types.map { type ->
            val st = providerState(type.defaultProviderId())
            val remaining = (st.cooldownUntilMs - now).coerceAtLeast(0L)
            ProviderHealthSnapshot(
                providerType = type,
                isAvailable = remaining == 0L,
                ewmaLatencyMs = st.ewmaLatencyMs,
                consecutiveFailures = st.consecutiveFailures,
                cooldownRemainingMs = remaining,
                activeInstanceUrl = st.activeInstanceUrl
            )
        }
    }

    // ---- Dynamic Provider ID + Endpoint + OperationType APIs ----

    @Synchronized
    fun recordProviderSuccess(
        providerId: String,
        latencyMs: Long,
        instanceUrl: String? = null,
        operation: OperationType? = null
    ) {
        val id = providerId.lowercase()
        val now = clockMs()
        val st = providerState(id)
        st.consecutiveFailures = 0
        st.cooldownUntilMs = 0L
        st.successCount += 1
        st.lastSuccessAtMs = now
        st.lastError = null
        st.ewmaLatencyMs = if (st.successCount == 1) {
            latencyMs.coerceAtLeast(1L)
        } else {
            ((st.ewmaLatencyMs * 7 + latencyMs.coerceAtLeast(1L) * 3) / 10)
        }
        if (!instanceUrl.isNullOrBlank()) {
            val cleanUrl = instanceUrl.trimEnd('/')
            st.activeInstanceUrl = cleanUrl
            if (operation != null) {
                lastSuccessfulEndpointByOp[ProviderOpKey(id, operation)] = cleanUrl
            }
        }
    }

    @Synchronized
    fun recordProviderFailure(
        providerId: String,
        error: String? = null,
        customCooldownMs: Long? = null
    ) {
        val now = clockMs()
        val st = providerState(providerId.lowercase())
        st.consecutiveFailures += 1
        st.failureCount += 1
        st.lastFailureAtMs = now
        st.lastError = InputAndUrlSecurity.sanitizeErrorForDiagnostics(error)
        if (st.consecutiveFailures >= failureThreshold) {
            val base = customCooldownMs?.coerceAtLeast(1_000L) ?: baseCooldownMs
            val exponent = (st.consecutiveFailures - failureThreshold).coerceIn(0, 4)
            val cooldown = (base * (1L shl exponent)).coerceAtMost(maxCooldownMs)
            st.cooldownUntilMs = now + cooldown
        }
    }

    @Synchronized
    fun recordEndpointSuccess(
        providerId: String,
        operation: OperationType,
        endpointUrl: String,
        latencyMs: Long
    ) {
        val id = providerId.lowercase()
        val cleanUrl = endpointUrl.trimEnd('/')
        val now = clockMs()
        val epSt = endpointState(id, cleanUrl, operation)
        epSt.consecutiveFailures = 0
        epSt.cooldownUntilMs = 0L
        epSt.successCount += 1
        epSt.lastSuccessAtMs = now
        epSt.lastError = null
        epSt.ewmaLatencyMs = if (epSt.successCount == 1) {
            latencyMs.coerceAtLeast(1L)
        } else {
            ((epSt.ewmaLatencyMs * 7 + latencyMs.coerceAtLeast(1L) * 3) / 10)
        }
        lastSuccessfulEndpointByOp[ProviderOpKey(id, operation)] = cleanUrl
        recordProviderSuccess(id, latencyMs, cleanUrl, operation)
    }

    @Synchronized
    fun recordEndpointFailure(
        providerId: String,
        operation: OperationType,
        endpointUrl: String,
        error: String? = null,
        endpointFailureThreshold: Int = 1,
        customCooldownMs: Long? = null
    ) {
        val id = providerId.lowercase()
        val cleanUrl = endpointUrl.trimEnd('/')
        val now = clockMs()
        val epSt = endpointState(id, cleanUrl, operation)
        epSt.consecutiveFailures += 1
        epSt.failureCount += 1
        epSt.lastFailureAtMs = now
        val sanitizedErr = InputAndUrlSecurity.sanitizeErrorForDiagnostics(error)
        epSt.lastError = sanitizedErr
        if (epSt.consecutiveFailures >= endpointFailureThreshold) {
            val base = customCooldownMs?.coerceAtLeast(1_000L) ?: baseCooldownMs
            val exponent = (epSt.consecutiveFailures - endpointFailureThreshold).coerceIn(0, 4)
            val cooldown = (base * (1L shl exponent)).coerceAtMost(maxCooldownMs)
            epSt.cooldownUntilMs = now + cooldown
        }
        providerState(id).lastError = sanitizedErr
    }

    @Synchronized
    fun isProviderAvailable(providerId: String): Boolean {
        val st = providerState(providerId.lowercase())
        return clockMs() >= st.cooldownUntilMs
    }

    @Synchronized
    fun selectOrderedProviderIds(candidateIds: List<String>): List<String> {
        val now = clockMs()
        val healthy = candidateIds.filter { now >= providerState(it.lowercase()).cooldownUntilMs }
        return if (healthy.isNotEmpty()) {
            healthy
        } else {
            candidateIds.sortedBy { providerState(it.lowercase()).cooldownUntilMs }
        }
    }

    @Synchronized
    fun selectOrderedEndpoints(
        providerId: String,
        operation: OperationType,
        configuredEndpoints: List<String>
    ): List<String> {
        val id = providerId.lowercase()
        val distinctUrls = configuredEndpoints.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        if (distinctUrls.size <= 1) return distinctUrls

        val now = clockMs()
        val lastGood = lastSuccessfulEndpointByOp[ProviderOpKey(id, operation)]

        val healthy = distinctUrls.filter { url ->
            now >= endpointState(id, url, operation).cooldownUntilMs
        }
        return if (healthy.isNotEmpty()) {
            if (lastGood != null && lastGood in healthy) {
                listOf(lastGood) + healthy.filterNot { it == lastGood }
            } else {
                healthy
            }
        } else {
            // All endpoints are in cooldown -> sort by soonest expiry so health rechecks recover automatically
            distinctUrls.sortedBy { url -> endpointState(id, url, operation).cooldownUntilMs }
        }
    }

    @Synchronized
    fun activeEndpointFor(providerId: String, operation: OperationType): String? {
        return lastSuccessfulEndpointByOp[ProviderOpKey(providerId.lowercase(), operation)]
    }

    @Synchronized
    fun resetEndpointCooldowns(providerId: String? = null) {
        if (providerId == null) {
            providerStates.values.forEach { it.cooldownUntilMs = 0L; it.consecutiveFailures = 0 }
            endpointStates.values.forEach { it.cooldownUntilMs = 0L; it.consecutiveFailures = 0 }
        } else {
            val id = providerId.lowercase()
            providerStates[id]?.let { it.cooldownUntilMs = 0L; it.consecutiveFailures = 0 }
            endpointStates.entries.filter { it.key.providerId == id }.forEach {
                it.value.cooldownUntilMs = 0L
                it.value.consecutiveFailures = 0
            }
        }
    }

    @Synchronized
    fun diagnosticsForProvider(
        config: DynamicProviderConfig,
        hasLocalOverride: Boolean = false
    ): ProviderDiagnosticsSnapshot {
        val id = config.id.lowercase()
        val now = clockMs()
        val provSt = providerState(id)
        val lyricsCapable = config.adapterType.equals("jiosaavn", ignoreCase = true) ||
            config.capabilities.any { it.equals("lyrics", ignoreCase = true) || it.equals("all", ignoreCase = true) } ||
            config.endpointsFor(OperationType.LYRICS).isNotEmpty()

        val opHealth = OperationType.entries.associateWith { op ->
            val eps = when (op) {
                OperationType.LYRICS -> if (lyricsCapable) {
                    config.endpointsFor(OperationType.LYRICS).ifEmpty {
                        config.endpoints.filter { it.enabled }.sortedBy { it.priority }
                    }
                } else {
                    emptyList()
                }
                else -> config.endpointsFor(op)
            }
            eps.map { ep ->
                val epSt = endpointState(id, ep.url, op)
                val remaining = (epSt.cooldownUntilMs - now).coerceAtLeast(0L)
                EndpointHealthInfo(
                    url = ep.url,
                    priority = ep.priority,
                    operation = op,
                    isHealthy = remaining == 0L,
                    cooldownRemainingMs = remaining,
                    consecutiveFailures = epSt.consecutiveFailures,
                    ewmaLatencyMs = if (epSt.lastSuccessAtMs > 0L) epSt.ewmaLatencyMs else 0L,
                    lastSuccessAtMs = epSt.lastSuccessAtMs,
                    lastError = epSt.lastError
                )
            }
        }

        val activeSearch = lastSuccessfulEndpointByOp[ProviderOpKey(id, OperationType.SEARCH)]
            ?: config.endpointsFor(OperationType.SEARCH).firstOrNull()?.url
        val activeMeta = lastSuccessfulEndpointByOp[ProviderOpKey(id, OperationType.METADATA)]
            ?: config.endpointsFor(OperationType.METADATA).firstOrNull()?.url
        val activePlay = lastSuccessfulEndpointByOp[ProviderOpKey(id, OperationType.PLAYBACK)]
            ?: config.endpointsFor(OperationType.PLAYBACK).firstOrNull()?.url
        val activeLyrics = if (lyricsCapable) {
            lastSuccessfulEndpointByOp[ProviderOpKey(id, OperationType.LYRICS)]
                ?: opHealth[OperationType.LYRICS]?.firstOrNull()?.url
        } else null

        return ProviderDiagnosticsSnapshot(
            providerId = config.id,
            displayName = config.displayName,
            adapterType = config.adapterType,
            enabled = config.enabled,
            isAvailable = config.enabled && now >= provSt.cooldownUntilMs,
            priority = config.priority,
            hasLocalOverride = hasLocalOverride,
            supportsLyrics = lyricsCapable,
            configuredEndpoints = config.endpoints.map { it.url }.distinct(),
            activeSearchEndpoint = activeSearch,
            activeMetadataEndpoint = activeMeta,
            activePlaybackEndpoint = activePlay,
            activeLyricsEndpoint = activeLyrics,
            operationHealth = opHealth,
            lastSuccessfulRequestMs = provSt.lastSuccessAtMs,
            lastError = provSt.lastError,
            ewmaLatencyMs = if (provSt.lastSuccessAtMs > 0L) provSt.ewmaLatencyMs else 0L
        )
    }

    @Synchronized
    fun diagnosticsForLyricsProvider(
        config: DynamicLyricsProviderConfig,
        effectiveEndpoints: List<String>,
        hasLocalOverride: Boolean = false
    ): LyricsProviderDiagnosticsSnapshot {
        val id = config.id.lowercase()
        val now = clockMs()
        val provSt = providerState(id)
        val urls = effectiveEndpoints.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        val epInfos = urls.mapIndexed { idx, url ->
            val explicitPri = config.endpoints.firstOrNull { it.url.trimEnd('/').equals(url, ignoreCase = true) }?.priority
                ?: (idx + 1)
            val epSt = endpointState(id, url, OperationType.LYRICS)
            val remaining = (epSt.cooldownUntilMs - now).coerceAtLeast(0L)
            EndpointHealthInfo(
                url = url,
                priority = explicitPri,
                operation = OperationType.LYRICS,
                isHealthy = remaining == 0L && epSt.consecutiveFailures == 0,
                cooldownRemainingMs = remaining,
                consecutiveFailures = epSt.consecutiveFailures,
                ewmaLatencyMs = if (epSt.lastSuccessAtMs > 0L) epSt.ewmaLatencyMs else 0L,
                lastSuccessAtMs = epSt.lastSuccessAtMs,
                lastError = epSt.lastError
            )
        }
        val activeEp = lastSuccessfulEndpointByOp[ProviderOpKey(id, OperationType.LYRICS)]
            ?: urls.firstOrNull()
        val anyUnhealthy = epInfos.any { !it.isHealthy }
        val isHealthy = config.enabled && now >= provSt.cooldownUntilMs && (!anyUnhealthy || epInfos.any { it.isHealthy })
        val resolvedLatency = epInfos.firstOrNull { it.ewmaLatencyMs > 0L }?.ewmaLatencyMs
            ?: if (provSt.lastSuccessAtMs > 0L) provSt.ewmaLatencyMs else 0L

        return LyricsProviderDiagnosticsSnapshot(
            providerId = config.id,
            displayName = config.displayName,
            adapterType = config.adapterType,
            enabled = config.enabled,
            isHealthy = isHealthy && !anyUnhealthy,
            priority = config.priority,
            hasLocalOverride = hasLocalOverride,
            configuredEndpoints = urls,
            activeEndpoint = activeEp,
            endpoints = epInfos,
            lastSuccessfulRequestMs = provSt.lastSuccessAtMs,
            lastError = epInfos.firstNotNullOfOrNull { it.lastError } ?: provSt.lastError,
            ewmaLatencyMs = resolvedLatency
        )
    }
}
