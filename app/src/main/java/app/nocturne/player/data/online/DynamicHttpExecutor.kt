package app.nocturne.player.data.online

import android.net.Uri
import app.nocturne.player.data.model.DynamicProviderConfig
import app.nocturne.player.data.model.EndpointOperationTemplate
import app.nocturne.player.data.model.OperationType
import app.nocturne.player.data.model.ProviderEndpointConfig
import app.nocturne.player.data.security.InputAndUrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ExecutedHttpResponse(
    val endpointUrl: String,
    val requestUrl: String,
    val body: String,
    val latencyMs: Long,
    val statusCode: Int
)

class DynamicHttpExecutor(
    private val baseClient: OkHttpClient = OnlineHttpClient.client,
    private val healthTracker: ProviderHealthTracker,
    private val allowLoopbackForTests: Boolean = false
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val timedClients = ConcurrentHashMap<Long, OkHttpClient>()

    suspend fun executeTemplated(
        config: DynamicProviderConfig,
        operation: OperationType,
        template: EndpointOperationTemplate,
        variables: Map<String, String> = emptyMap(),
        endpointOverride: List<String>? = null
    ): ExecutedHttpResponse = withContext(Dispatchers.IO) {
        val candidateConfigs = resolveCandidateEndpoints(config, operation, endpointOverride)
        if (candidateConfigs.isEmpty()) {
            throw IOException("No enabled endpoints available for ${config.id} (${operation.key})")
        }

        val orderedUrls = healthTracker.selectOrderedEndpoints(
            providerId = config.id,
            operation = operation,
            configuredEndpoints = candidateConfigs.map { it.url }
        ).let { if (config.endpointFailover) it else it.take(1) }
        val endpointByUrl = candidateConfigs.associateBy { it.url.trimEnd('/') }
        val effectiveVariables = if (config.params.isEmpty()) variables else config.params + variables

        var lastError: Throwable? = null
        val maxRetries = config.maxRetries.coerceIn(0, 3)

        for (endpointUrl in orderedUrls) {
            val epCfg = endpointByUrl[endpointUrl] ?: ProviderEndpointConfig(url = endpointUrl)
            val effectiveTimeoutMs = (epCfg.timeoutMs ?: config.timeoutMs).coerceIn(1_500L, 30_000L)

            val builtRequest = try {
                buildRequest(
                    baseUrl = endpointUrl,
                    template = template,
                    variables = effectiveVariables,
                    providerHeaders = config.headers,
                    endpointHeaders = epCfg.headers
                )
            } catch (t: Throwable) {
                lastError = t
                healthTracker.recordEndpointFailure(
                    providerId = config.id,
                    operation = operation,
                    endpointUrl = endpointUrl,
                    error = t.message,
                    customCooldownMs = config.cooldownMs
                )
                continue
            }

            var attempt = 0
            while (attempt <= maxRetries) {
                attempt++
                val startNs = System.nanoTime()
                try {
                    val timedClient = clientWithTimeout(effectiveTimeoutMs)
                    val responseBody = timedClient.newCall(builtRequest).execute().use { resp ->
                        val code = resp.code
                        if (code !in 200..299) {
                            throw HttpStatusException(code, "HTTP $code from $endpointUrl")
                        }
                        val bodyStr = InputAndUrlSecurity.readBodyBounded(resp.body)
                        if (bodyStr.isBlank() || bodyStr.trimStart().startsWith("<")) {
                            throw IOException("Invalid or HTML response from $endpointUrl")
                        }
                        bodyStr
                    }
                    val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    healthTracker.recordEndpointSuccess(
                        providerId = config.id,
                        operation = operation,
                        endpointUrl = endpointUrl,
                        latencyMs = latencyMs
                    )
                    return@withContext ExecutedHttpResponse(
                        endpointUrl = endpointUrl,
                        requestUrl = builtRequest.url.toString(),
                        body = responseBody,
                        latencyMs = latencyMs,
                        statusCode = 200
                    )
                } catch (t: Throwable) {
                    lastError = t
                    val shouldRetry = attempt <= maxRetries && isTransientFailure(t)
                    if (!shouldRetry) {
                        healthTracker.recordEndpointFailure(
                            providerId = config.id,
                            operation = operation,
                            endpointUrl = endpointUrl,
                            error = t.message,
                            customCooldownMs = config.cooldownMs
                        )
                        break
                    }
                }
            }
        }

        throw IOException(
            InputAndUrlSecurity.sanitizeErrorForDiagnostics(
                lastError?.message ?: "All endpoints failed for ${config.id} (${operation.key})"
            ) ?: "All endpoints failed for ${config.id}"
        )
    }

    suspend fun executeRawGet(
        providerId: String,
        operation: OperationType,
        configuredEndpoints: List<ProviderEndpointConfig>,
        pathAndQuery: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 10_000L,
        maxRetries: Int = 1,
        cooldownMs: Long = 60_000L,
        endpointFailover: Boolean = true
    ): ExecutedHttpResponse = withContext(Dispatchers.IO) {
        val enabledEps = configuredEndpoints
            .filter { it.supports(operation) }
            .sortedBy { it.priority }
        if (enabledEps.isEmpty()) {
            throw IOException("No enabled endpoints available for $providerId (${operation.key})")
        }
        val orderedUrls = healthTracker.selectOrderedEndpoints(
            providerId = providerId,
            operation = operation,
            configuredEndpoints = enabledEps.map { it.url }
        ).let { if (endpointFailover) it else it.take(1) }
        val epByUrl = enabledEps.associateBy { it.url.trimEnd('/') }
        var lastError: Throwable? = null
        val boundedRetries = maxRetries.coerceIn(0, 3)

        for (endpointUrl in orderedUrls) {
            val epCfg = epByUrl[endpointUrl] ?: ProviderEndpointConfig(url = endpointUrl)
            val cleanSuffix = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
            val fullUrl = "$endpointUrl$cleanSuffix"
            val allowedOrigin = if (allowLoopbackForTests) fullUrl else null
            val safeUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(fullUrl, allowedOrigin)
                ?: continue

            val reqBuilder = Request.Builder()
                .url(safeUrl)
                .header("Accept", "application/json")
            val mergedHeaders = InputAndUrlSecurity.sanitizeRemoteHeaderMap(headers + epCfg.headers)
            mergedHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
            val request = reqBuilder.build()

            val effectiveTimeoutMs = (epCfg.timeoutMs ?: timeoutMs).coerceIn(1_500L, 30_000L)
            var attempt = 0
            while (attempt <= boundedRetries) {
                attempt++
                val startNs = System.nanoTime()
                try {
                    val bodyStr = clientWithTimeout(effectiveTimeoutMs).newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw HttpStatusException(resp.code, "HTTP ${resp.code} from $endpointUrl")
                        }
                        val str = InputAndUrlSecurity.readBodyBounded(resp.body)
                        if (str.isBlank() || str.trimStart().startsWith("<")) {
                            throw IOException("Invalid or HTML response from $endpointUrl")
                        }
                        str
                    }
                    val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    healthTracker.recordEndpointSuccess(providerId, operation, endpointUrl, latencyMs)
                    return@withContext ExecutedHttpResponse(
                        endpointUrl = endpointUrl,
                        requestUrl = safeUrl,
                        body = bodyStr,
                        latencyMs = latencyMs,
                        statusCode = 200
                    )
                } catch (t: Throwable) {
                    lastError = t
                    if (attempt > boundedRetries || !isTransientFailure(t)) {
                        healthTracker.recordEndpointFailure(
                            providerId = providerId,
                            operation = operation,
                            endpointUrl = endpointUrl,
                            error = t.message,
                            customCooldownMs = cooldownMs
                        )
                        break
                    }
                }
            }
        }
        throw IOException(
            InputAndUrlSecurity.sanitizeErrorForDiagnostics(
                lastError?.message ?: "All endpoints failed for $providerId (${operation.key})"
            ) ?: "All endpoints failed for $providerId"
        )
    }

    suspend fun probeUrlReachable(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 5_000L
    ): Boolean = withContext(Dispatchers.IO) {
        val allowedOrigin = if (allowLoopbackForTests) url else null
        val safeUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(url, allowedOrigin) ?: return@withContext false
        val timedClient = clientWithTimeout(timeoutMs.coerceIn(1_500L, 12_000L))
        val safeHeaders = InputAndUrlSecurity.sanitizeRemoteHeaderMap(headers)
        runCatching {
            val headReq = Request.Builder()
                .url(safeUrl)
                .head()
                .header("Connection", "close")
                .apply { safeHeaders.forEach { (k, v) -> header(k, v) } }
                .build()
            timedClient.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 206) return@runCatching true
                if (resp.code == 405 || resp.code == 501) {
                    // Fallback to minimal byte range GET when server disallows HEAD
                    val rangeReq = Request.Builder()
                        .url(safeUrl)
                        .header("Range", "bytes=0-1")
                        .apply { safeHeaders.forEach { (k, v) -> header(k, v) } }
                        .build()
                    return@runCatching timedClient.newCall(rangeReq).execute().use { getResp ->
                        getResp.isSuccessful || getResp.code == 206
                    }
                }
                false
            }
        }.getOrDefault(false)
    }

    fun parseJson(body: String): JsonElement = json.parseToJsonElement(body)

    private fun resolveCandidateEndpoints(
        config: DynamicProviderConfig,
        operation: OperationType,
        endpointOverride: List<String>?
    ): List<ProviderEndpointConfig> {
        if (!endpointOverride.isNullOrEmpty()) {
            return endpointOverride.mapIndexedNotNull { idx, raw ->
                InputAndUrlSecurity.validateRemoteBaseUrl(raw, allowLoopbackForTests)?.let { clean ->
                    ProviderEndpointConfig(url = clean, priority = idx + 1)
                }
            }
        }
        return config.endpointsFor(operation)
    }

    private fun buildRequest(
        baseUrl: String,
        template: EndpointOperationTemplate,
        variables: Map<String, String>,
        providerHeaders: Map<String, String>,
        endpointHeaders: Map<String, String>
    ): Request {
        val cleanBase = InputAndUrlSecurity.validateRemoteBaseUrl(baseUrl, allowLoopbackForTests)
            ?: throw IOException("Invalid endpoint base URL")

        val substitutedPath = substituteUrlPlaceholders(template.path, variables)
        val normalizedPath = if (substitutedPath.isBlank()) "" else if (substitutedPath.startsWith("/")) substitutedPath else "/$substitutedPath"

        val baseParsed = "$cleanBase$normalizedPath".toHttpUrlOrNull()
            ?: throw IOException("Invalid combined endpoint URL")

        val urlBuilder = baseParsed.newBuilder()
        template.queryParams.forEach { (k, rawVal) ->
            val resolved = substituteRawPlaceholders(rawVal, variables)
            if (resolved.isNotBlank()) {
                urlBuilder.addQueryParameter(k, resolved)
            }
        }

        val finalUrl = urlBuilder.build().toString()
        val allowedOrigin = if (allowLoopbackForTests) finalUrl else null
        val validatedFinalUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(finalUrl, allowedOrigin)
            ?: throw IOException("Blocked unsafe expanded endpoint URL")

        val reqBuilder = Request.Builder()
            .url(validatedFinalUrl)
            .header("Accept", "application/json")

        val mergedHeaders = InputAndUrlSecurity.sanitizeRemoteHeaderMap(
            providerHeaders + endpointHeaders + template.headers
        )
        mergedHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        val method = template.method.uppercase()
        if (method == "POST") {
            val rawBody = template.bodyTemplate.orEmpty()
            val bodyJson = substituteJsonPlaceholders(rawBody, variables)
            reqBuilder.post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            reqBuilder.get()
        }

        return reqBuilder.build()
    }

    private fun substituteUrlPlaceholders(pattern: String, vars: Map<String, String>): String {
        var result = pattern
        for ((k, v) in vars) {
            result = result.replace("{$k}", Uri.encode(v))
        }
        return result
    }

    private fun substituteRawPlaceholders(pattern: String, vars: Map<String, String>): String {
        var result = pattern
        for ((k, v) in vars) {
            result = result.replace("{$k}", v)
        }
        return if (UNRESOLVED_PLACEHOLDER_REGEX.containsMatchIn(result)) "" else result
    }

    private fun substituteJsonPlaceholders(pattern: String, vars: Map<String, String>): String {
        var result = pattern
        for ((k, v) in vars) {
            val jsonEscaped = v
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            result = result.replace("{$k}", jsonEscaped)
        }
        return result
    }

    private fun clientWithTimeout(timeoutMs: Long): OkHttpClient {
        val clamped = timeoutMs.coerceIn(1_500L, 30_000L)
        return timedClients.computeIfAbsent(clamped) { ms ->
            baseClient.newBuilder()
                .connectTimeout(ms, TimeUnit.MILLISECONDS)
                .readTimeout(ms, TimeUnit.MILLISECONDS)
                .writeTimeout(ms, TimeUnit.MILLISECONDS)
                .callTimeout(ms + 2_000L, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    private fun isTransientFailure(t: Throwable): Boolean {
        return when (t) {
            is HttpStatusException -> false
            is IOException -> true
            else -> false
        }
    }

    class HttpStatusException(val code: Int, message: String) : IOException(message)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val UNRESOLVED_PLACEHOLDER_REGEX = Regex("\\{[a-zA-Z0-9_]+\\}")
    }
}
