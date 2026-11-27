package app.nocturne.player.data.security

import android.content.Context
import android.net.Uri
import app.nocturne.player.data.model.HomeSectionConfig
import app.nocturne.player.data.model.HomeSectionId
import app.nocturne.player.data.model.ProviderLocalOverride
import app.nocturne.player.data.model.UserSettings
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

object InputAndUrlSecurity {

    const val DEFAULT_MAX_RESPONSE_BYTES = 4L * 1024L * 1024L
    const val MAX_SEARCH_QUERY_LENGTH = 120
    const val MAX_TEXT_FIELD_LENGTH = 120
    const val MAX_DESCRIPTION_LENGTH = 500

    private val CONTROL_OR_BIDI_CHAR_REGEX = Regex("[\\p{Cc}\\p{Cf}\\u202A-\\u202E\\u2066-\\u2069]")
    private val IPV4_LITERAL_REGEX = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
    private val PROVIDER_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,48}$")
    private val FORBIDDEN_REMOTE_HEADERS = setOf(
        "host", "cookie", "set-cookie", "connection", "keep-alive",
        "transfer-encoding", "content-length", "upgrade", "te", "trailer",
        "proxy-authorization", "proxy-authenticate", "x-forwarded-for",
        "x-real-ip", "forwarded"
    )
    private val SENSITIVE_HEADER_KEYS = setOf(
        "authorization", "x-api-key", "api-key", "apikey", "x-auth-token", "token", "secret"
    )
    private val TOKEN_REDACT_REGEX = Regex("(?i)(bearer\\s+[a-z0-9._\\-~+/=]+|token=[a-z0-9._\\-~+/=]+|key=[a-z0-9._\\-~+/=]+|api_key=[a-z0-9._\\-~+/=]+)")
    private val TRUSTED_YOUTUBE_DOMAINS = setOf(
        "youtube.com",
        "youtu.be",
        "googlevideo.com",
        "ytimg.com",
        "youtube-nocookie.com"
    )
    private val BLOCKED_HOSTNAMES = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback",
        "metadata.google.internal",
        "metadata.goog"
    )
    private val BLOCKED_FILE_PATH_PREFIXES = listOf(
        "/data/",
        "/proc/",
        "/sys/",
        "/dev/",
        "/system/",
        "/vendor/",
        "/etc/"
    )

    fun hasControlChars(raw: String): Boolean = raw.any { it.isISOControl() }

    fun sanitizeSearchQuery(raw: String, maxLength: Int = MAX_SEARCH_QUERY_LENGTH): String {
        return raw
            .replace(CONTROL_OR_BIDI_CHAR_REGEX, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(maxLength)
    }

    fun sanitizeSingleLineText(raw: String, maxLength: Int = MAX_TEXT_FIELD_LENGTH): String {
        return raw
            .replace(CONTROL_OR_BIDI_CHAR_REGEX, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(maxLength)
    }

    fun sanitizeMultilineText(raw: String, maxLength: Int = MAX_DESCRIPTION_LENGTH): String {
        val cleaned = buildString(raw.length.coerceAtMost(maxLength)) {
            for (ch in raw) {
                if (ch == '\n' || (!ch.isISOControl() && ch !in '\u202A'..'\u202E' && ch !in '\u2066'..'\u2069')) {
                    append(ch)
                    if (length >= maxLength) break
                }
            }
        }
        return cleaned.trim()
    }

    fun sanitizeM3uField(raw: String, maxLength: Int = 240): String {
        return sanitizeSingleLineText(raw, maxLength)
    }

    fun sanitizePathSegment(raw: String?, maxLength: Int = 180): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.length > maxLength) return null
        if (trimmed == "." || trimmed == "..") return null
        if (trimmed.any { it.isISOControl() || it.isWhitespace() || it in charArrayOf('/', '\\', '?', '#', '&', '=', '%') }) {
            return null
        }
        return trimmed
    }

    fun sanitizeProviderId(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        return trimmed.takeIf { PROVIDER_ID_REGEX.matches(it) }
    }

    fun sanitizeEndpointPathTemplate(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (hasControlChars(trimmed) || trimmed.length > 512) return null
        if (trimmed.contains("..") || trimmed.contains("\\")) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("javascript:") || lower.startsWith("file:")) {
            return null
        }
        return trimmed
    }

    fun sanitizeRemoteHeaderMap(raw: Map<String, String>): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for ((k, v) in raw) {
            if (result.size >= 12) break
            val cleanKey = k.trim()
            if (cleanKey.isBlank() || cleanKey.length > 64) continue
            if (cleanKey.any { it.isISOControl() || it == ':' || it.isWhitespace() }) continue
            if (cleanKey.lowercase() in FORBIDDEN_REMOTE_HEADERS) continue
            val cleanVal = v.trim()
            if (cleanVal.length > 512 || cleanVal.any { it.isISOControl() }) continue
            result[cleanKey] = cleanVal
        }
        return result
    }

    fun sanitizeParamMap(raw: Map<String, String>): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for ((k, v) in raw) {
            if (result.size >= 16) break
            val cleanKey = k.trim()
            if (cleanKey.isBlank() || cleanKey.length > 64) continue
            if (cleanKey.any { it.isISOControl() || it.isWhitespace() }) continue
            val cleanVal = v.trim()
            if (cleanVal.length > 512 || cleanVal.any { it.isISOControl() }) continue
            result[cleanKey] = cleanVal
        }
        return result
    }

    fun redactHeaderValuesForDiagnostics(headers: Map<String, String>): Map<String, String> {
        return headers.mapValues { (k, v) ->
            if (k.lowercase() in SENSITIVE_HEADER_KEYS || k.lowercase().contains("auth") || k.lowercase().contains("secret")) {
                "***"
            } else {
                v.take(64)
            }
        }
    }

    fun sanitizeErrorForDiagnostics(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val redacted = TOKEN_REDACT_REGEX.replace(raw, "[REDACTED]")
        return sanitizeSingleLineText(redacted, 200)
    }

    fun isTrustedYouTubeHost(host: String?): Boolean = isExactOrSubdomain(host, TRUSTED_YOUTUBE_DOMAINS)

    fun isExactOrSubdomain(host: String?, domains: Set<String>): Boolean {
        val clean = host?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (clean.isBlank()) return false
        return domains.any { domain ->
            clean == domain || clean.endsWith(".$domain")
        }
    }

    fun isLoopbackHost(host: String?): Boolean {
        val clean = host?.trim()?.removeSurrounding("[", "]")?.lowercase().orEmpty()
        if (clean == "localhost" || clean == "127.0.0.1" || clean == "::1") return true
        return runCatching {
            if (isIpLiteralOrObfuscatedNumeric(clean)) {
                InetAddress.getByName(clean).isLoopbackAddress
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun validateRemoteHttpsUrl(
        rawUrl: String?,
        allowedLoopbackOrigin: String? = null
    ): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isBlank() || hasControlChars(trimmed)) return null

        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null

        val cleanHost = parsed.host.trim().trimEnd('.').lowercase()
        if (cleanHost.isBlank()) return null

        if (allowedLoopbackOrigin != null) {
            val allowed = allowedLoopbackOrigin.trim().toHttpUrlOrNull()
            if (allowed != null &&
                parsed.scheme == allowed.scheme &&
                cleanHost == allowed.host.lowercase() &&
                parsed.port == allowed.port &&
                isLoopbackHost(cleanHost)
            ) {
                return parsed.newBuilder().fragment(null).build().toString()
            }
        }

        if (!parsed.isHttps) return null
        if (!isPublicHostnameAndNotPrivateLiteral(cleanHost)) return null
        return parsed.newBuilder().fragment(null).build().toString()
    }

    sealed interface RemoteConfigUrlValidationResult {
        data class Valid(val normalizedUrl: String) : RemoteConfigUrlValidationResult
        data class Invalid(val reason: String) : RemoteConfigUrlValidationResult
    }

    fun validateRemoteConfigUrlDetailed(
        rawUrl: String?,
        allowedLoopbackOrigin: String? = null
    ): RemoteConfigUrlValidationResult {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return RemoteConfigUrlValidationResult.Invalid("Provider JSON URL cannot be empty")
        }
        if (hasControlChars(trimmed) || trimmed.any { it.isWhitespace() }) {
            return RemoteConfigUrlValidationResult.Invalid("URL contains invalid whitespace or control characters")
        }

        val parsed = trimmed.toHttpUrlOrNull()
            ?: return RemoteConfigUrlValidationResult.Invalid("Malformed URL. Expected a valid https:// raw JSON URL")

        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return RemoteConfigUrlValidationResult.Invalid("URL must not contain embedded credentials")
        }

        val cleanHost = parsed.host.trim().trimEnd('.').lowercase()
        if (cleanHost.isBlank()) {
            return RemoteConfigUrlValidationResult.Invalid("URL is missing a host name")
        }

        if (cleanHost == "github.com" || cleanHost == "www.github.com") {
            return RemoteConfigUrlValidationResult.Invalid(
                "Use a GitHub Raw JSON URL (raw.githubusercontent.com) instead of a github.com page URL"
            )
        }

        if (allowedLoopbackOrigin != null) {
            val allowed = allowedLoopbackOrigin.trim().toHttpUrlOrNull()
            if (allowed != null &&
                parsed.scheme == allowed.scheme &&
                cleanHost == allowed.host.lowercase() &&
                parsed.port == allowed.port &&
                isLoopbackHost(cleanHost)
            ) {
                return RemoteConfigUrlValidationResult.Valid(
                    parsed.newBuilder().fragment(null).build().toString()
                )
            }
        }

        if (!parsed.isHttps) {
            return RemoteConfigUrlValidationResult.Invalid("Only HTTPS URLs are allowed")
        }
        if (!isPublicHostnameAndNotPrivateLiteral(cleanHost)) {
            return RemoteConfigUrlValidationResult.Invalid("Blocked private, loopback, or restricted host: $cleanHost")
        }

        return RemoteConfigUrlValidationResult.Valid(
            parsed.newBuilder().fragment(null).build().toString()
        )
    }

    fun validateRemoteBaseUrl(
        rawUrl: String?,
        allowLoopback: Boolean = false
    ): String? {
        val trimmed = rawUrl?.trim()?.trimEnd('/').orEmpty()
        if (trimmed.isBlank() || hasControlChars(trimmed)) return null

        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.query != null || parsed.fragment != null) return null

        val cleanHost = parsed.host.trim().trimEnd('.').lowercase()
        if (cleanHost.isBlank()) return null

        if (allowLoopback && (parsed.scheme == "http" || parsed.scheme == "https") && isLoopbackHost(cleanHost)) {
            return parsed.toString().trimEnd('/')
        }

        if (!parsed.isHttps) return null
        if (!isPublicHostnameAndNotPrivateLiteral(cleanHost)) return null
        return parsed.toString().trimEnd('/')
    }

    fun isPublicHostnameAndNotPrivateLiteral(host: String): Boolean {
        val clean = host.trim().trimEnd('.').removeSurrounding("[", "]").lowercase()
        if (clean.isBlank()) return false
        if (clean in BLOCKED_HOSTNAMES ||
            clean.endsWith(".localhost") ||
            clean.endsWith(".local") ||
            clean.endsWith(".internal") ||
            clean.endsWith(".localdomain") ||
            clean.endsWith(".arpa")
        ) {
            return false
        }
        if (isIpLiteralOrObfuscatedNumeric(clean)) {
            val addr = runCatching { InetAddress.getByName(clean) }.getOrNull() ?: return false
            // Disallow non-canonical integer/hex/octal IPv4 obfuscations when host wasn't standard dotted-quad or IPv6
            if (addr is Inet4Address && !IPV4_LITERAL_REGEX.matches(clean)) return false
            return isPublicIpAddress(addr)
        }
        return true
    }

    private fun isIpLiteralOrObfuscatedNumeric(host: String): Boolean {
        if (host.contains(':')) return true
        if (IPV4_LITERAL_REGEX.matches(host)) return true
        if (host.all { it.isDigit() }) return true
        if (host.startsWith("0x", ignoreCase = true)) return true
        val labels = host.split('.')
        return labels.isNotEmpty() && labels.all { label ->
            label.isNotEmpty() && (label.all { it.isDigit() } || label.startsWith("0x", ignoreCase = true))
        }
    }

    fun isPublicIpAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return false
        }
        val bytes = addr.address
        if (addr is Inet4Address && bytes.size == 4) {
            return isPublicIpv4Bytes(bytes)
        }
        if (addr is Inet6Address && bytes.size == 16) {
            val first = bytes[0].toInt() and 0xFF
            // Unique local addresses (fc00::/7)
            if ((first and 0xFE) == 0xFC) return false
            // Documentation 2001:db8::/32
            if (first == 0x20 && (bytes[1].toInt() and 0xFF) == 0x01 &&
                (bytes[2].toInt() and 0xFF) == 0x0D && (bytes[3].toInt() and 0xFF) == 0xB8
            ) {
                return false
            }
            // IPv4-mapped IPv6 (::ffff:0:0/96)
            val isV4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
            if (isV4Mapped) {
                return isPublicIpv4Bytes(bytes.copyOfRange(12, 16))
            }
        }
        return true
    }

    private fun isPublicIpv4Bytes(bytes: ByteArray): Boolean {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        return when {
            b0 == 0 -> false // 0.0.0.0/8 "this network"
            b0 == 10 -> false // 10.0.0.0/8 RFC1918
            b0 == 100 && b1 in 64..127 -> false // 100.64.0.0/10 CGNAT
            b0 == 127 -> false // 127.0.0.0/8 loopback
            b0 == 169 && b1 == 254 -> false // 169.254.0.0/16 link-local / cloud metadata
            b0 == 172 && b1 in 16..31 -> false // 172.16.0.0/12 RFC1918
            b0 == 192 && b1 == 0 && b2 == 0 -> false // 192.0.0.0/24
            b0 == 192 && b1 == 0 && b2 == 2 -> false // 192.0.2.0/24 TEST-NET-1
            b0 == 192 && b1 == 168 -> false // 192.168.0.0/16 RFC1918
            b0 == 198 && (b1 == 18 || b1 == 19) -> false // 198.18.0.0/15 benchmarking
            b0 == 198 && b1 == 51 && b2 == 100 -> false // 198.51.100.0/24 TEST-NET-2
            b0 == 203 && b1 == 0 && b2 == 113 -> false // 203.0.113.0/24 TEST-NET-3
            b0 >= 224 -> false // Multicast (224+) and Reserved/Broadcast (240+)
            else -> true
        }
    }

    fun ssrfSafeDns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val cleanHost = hostname.trim().trimEnd('.').removeSurrounding("[", "]")
            if (!isPublicHostnameAndNotPrivateLiteral(cleanHost)) {
                throw UnknownHostException("Blocked restricted host: $cleanHost")
            }
            val resolved = Dns.SYSTEM.lookup(hostname)
            val publicOnly = resolved.filter(::isPublicIpAddress)
            if (publicOnly.isEmpty()) {
                throw UnknownHostException("Blocked non-public address for host: $cleanHost")
            }
            return publicOnly
        }
    }

    val ssrfNetworkGuardInterceptor = Interceptor { chain ->
        val req = chain.request()
        val url = req.url
        if (!url.isHttps) {
            throw IOException("Blocked cleartext HTTP request to ${url.host}")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IOException("Blocked URL containing credentials")
        }
        val cleanHost = url.host.trim().trimEnd('.').lowercase()
        if (!isPublicHostnameAndNotPrivateLiteral(cleanHost)) {
            throw IOException("Blocked restricted host: $cleanHost")
        }
        val remoteIp = chain.connection()?.socket()?.inetAddress
        if (remoteIp != null && !isPublicIpAddress(remoteIp)) {
            throw IOException("Blocked connection to non-public network address")
        }
        chain.proceed(req)
    }

    @Throws(IOException::class)
    fun readBodyBounded(
        body: ResponseBody?,
        maxBytes: Long = DEFAULT_MAX_RESPONSE_BYTES
    ): String {
        if (body == null) return ""
        val declaredLength = body.contentLength()
        if (declaredLength > maxBytes) {
            throw IOException("Response body exceeds maximum allowed size ($maxBytes bytes)")
        }
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw IOException("Response body exceeded maximum allowed size ($maxBytes bytes)")
                }
                out.write(buffer, 0, read)
            }
        }
        return String(out.toByteArray(), charset)
    }

    fun isSafePlayableLocalUri(uri: Uri?): Boolean {
        if (uri == null) return false
        return when (uri.scheme?.lowercase()) {
            "content" -> !uri.authority.isNullOrBlank()
            "file" -> {
                val path = uri.path.orEmpty()
                if (path.isBlank() || path.contains("..")) return false
                BLOCKED_FILE_PATH_PREFIXES.none { path.startsWith(it, ignoreCase = true) }
            }
            else -> false
        }
    }

    fun isSafeAppPrivateDownloadUri(context: Context, uri: Uri?): Boolean {
        if (uri == null || !uri.scheme.equals("file", ignoreCase = true)) return false
        val path = uri.path.orEmpty()
        if (path.isBlank() || path.contains("..")) return false
        return runCatching {
            val downloadsDir = File(context.filesDir, "downloads").canonicalPath
            val targetPath = File(path).canonicalPath
            targetPath == downloadsDir || targetPath.startsWith(downloadsDir + File.separator)
        }.getOrDefault(false)
    }

    fun isSafeArtworkUri(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || hasControlChars(trimmed)) return false
        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return false
        return when (parsed.scheme?.lowercase()) {
            "https" -> validateRemoteHttpsUrl(trimmed) != null
            "content", "file" -> isSafePlayableLocalUri(parsed)
            else -> false
        }
    }

    fun sanitizePlaylistCoverUri(raw: String?): String? {
        val trimmed = raw?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return trimmed.takeIf(::isSafeArtworkUri)
    }

    fun sanitizeUserSettings(raw: UserSettings): UserSettings {
        val trimmedConfigUrl = raw.remoteProviderConfigUrl.trim()
        val migratedRawUrl = if (trimmedConfigUrl == UserSettings.LEGACY_REMOTE_CONFIG_URL || trimmedConfigUrl.isBlank()) {
            UserSettings.DEFAULT_REMOTE_CONFIG_URL
        } else {
            trimmedConfigUrl
        }
        val validatedConfigUrl = validateRemoteHttpsUrl(migratedRawUrl)
            ?: UserSettings.DEFAULT_REMOTE_CONFIG_URL

        val safeSafUris = raw.includedSafFolderUris
            .asSequence()
            .map { it.trim() }
            .filter { uri ->
                uri.startsWith("content://", ignoreCase = true) && !hasControlChars(uri)
            }
            .distinct()
            .take(50)
            .toList()

        val safeExcluded = raw.excludedFolders
            .asSequence()
            .map { sanitizeSingleLineText(it, 260) }
            .filter { it.isNotBlank() && !it.contains("..") }
            .distinct()
            .take(200)
            .toList()

        val validSectionIds = HomeSectionId.entries.toSet()
        val safeHomeSections = raw.homeSections
            .filter { it.id in validSectionIds }
            .distinctBy { it.id }
            .let { list ->
                if (list.isEmpty()) {
                    HomeSectionId.entries.map {
                        HomeSectionConfig(id = it, visible = true, order = it.ordinal, compact = false)
                    }
                } else list
            }

        return raw.copy(
            paperTextureIntensity = raw.paperTextureIntensity.coerceIn(0f, 1f),
            fontScaleMultiplier = raw.fontScaleMultiplier.coerceIn(0.85f, 1.25f),
            sectionGridOverrides = raw.sectionGridOverrides.entries
                .take(32)
                .associate { sanitizeSingleLineText(it.key, 64) to it.value },
            homeSections = safeHomeSections,
            crossfadeSeconds = raw.crossfadeSeconds.coerceIn(0, 12),
            playbackSpeed = raw.playbackSpeed.coerceIn(0.5f, 2.0f),
            sleepTimerDefaultMinutes = raw.sleepTimerDefaultMinutes.coerceIn(1, 480),
            sectionSortFieldOverrides = raw.sectionSortFieldOverrides.entries
                .take(32)
                .associate { sanitizeSingleLineText(it.key, 64) to it.value },
            sectionSortOrderOverrides = raw.sectionSortOrderOverrides.entries
                .take(32)
                .associate { sanitizeSingleLineText(it.key, 64) to it.value },
            includedSafFolderUris = safeSafUris,
            excludedFolders = safeExcluded,
            eqPresetIndex = raw.eqPresetIndex.coerceIn(-1, 64),
            eqCustomBandLevelsMb = raw.eqCustomBandLevelsMb.take(16).map { it.coerceIn(-3000, 3000) },
            bassBoostStrength = raw.bassBoostStrength.coerceIn(0, 1000),
            virtualizerStrength = raw.virtualizerStrength.coerceIn(0, 1000),
            loudnessGainMb = raw.loudnessGainMb.coerceIn(0, 2000),
            channelBalance = raw.channelBalance.coerceIn(-1f, 1f),
            replayGainPreampDb = raw.replayGainPreampDb.coerceIn(-15f, 15f),
            lyricsFontSizeSp = raw.lyricsFontSizeSp.coerceIn(12f, 36f),
            lyricsSyncOffsetMs = raw.lyricsSyncOffsetMs.coerceIn(-30_000L, 30_000L),
            remoteProviderConfigUrl = validatedConfigUrl,
            musicProviderOverrides = sanitizeProviderOverrides(raw.musicProviderOverrides),
            lyricsProviderOverrides = sanitizeProviderOverrides(raw.lyricsProviderOverrides)
        )
    }

    private fun sanitizeProviderOverrides(
        raw: Map<String, ProviderLocalOverride>
    ): Map<String, ProviderLocalOverride> {
        if (raw.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, ProviderLocalOverride>()
        for ((k, v) in raw.entries.take(32)) {
            val id = sanitizeProviderId(k) ?: continue
            result[id] = ProviderLocalOverride(
                enabled = v.enabled,
                priority = v.priority?.coerceIn(0, 10_000)
            )
        }
        return result
    }
}
