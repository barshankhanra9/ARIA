package app.nocturne.player.data.online

import app.nocturne.player.data.security.InputAndUrlSecurity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object OnlineHttpClient {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240905.003) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

    const val YOUTUBE_REFERER = "https://www.youtube.com/"
    const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    fun defaultMediaHeaders(targetUrl: String? = null): Map<String, String> {
        val host = targetUrl?.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        return if (host.isNotBlank() && isYouTubeHost(host)) {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to YOUTUBE_REFERER,
                "Origin" to YOUTUBE_ORIGIN,
                "Accept-Language" to "en-US,en;q=0.9"
            )
        } else {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Accept-Language" to "en-US,en;q=0.9"
            )
        }
    }

    internal fun isYouTubeHost(host: String): Boolean {
        return InputAndUrlSecurity.isTrustedYouTubeHost(host)
    }

    private val mobileBrowserHeadersInterceptor = Interceptor { chain ->
        val original = chain.request()
        val host = original.url.host.lowercase()
        val builder = original.newBuilder()

        if (original.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", USER_AGENT)
        }
        if (original.header("Accept-Language").isNullOrBlank()) {
            builder.header("Accept-Language", "en-US,en;q=0.9")
        }
        if (isYouTubeHost(host)) {
            if (original.header("Referer").isNullOrBlank()) {
                builder.header("Referer", YOUTUBE_REFERER)
            }
            if (original.header("Origin").isNullOrBlank()) {
                builder.header("Origin", YOUTUBE_ORIGIN)
            }
        }
        chain.proceed(builder.build())
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .dns(InputAndUrlSecurity.ssrfSafeDns())
            .followRedirects(true)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .addInterceptor(mobileBrowserHeadersInterceptor)
            .addNetworkInterceptor(InputAndUrlSecurity.ssrfNetworkGuardInterceptor)
            .build()
    }
}

class OkHttpDownloader(
    private val client: OkHttpClient = OnlineHttpClient.client
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val rawUrl = request.url()
        val validatedUrl = InputAndUrlSecurity.validateRemoteHttpsUrl(rawUrl)
            ?: throw IOException("Blocked unsafe or non-HTTPS URL in extractor request")
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val body = dataToSend?.let { bytes ->
            val contentType = headers["Content-Type"]?.firstOrNull()
                ?: "application/x-www-form-urlencoded; charset=utf-8"
            bytes.toRequestBody(contentType.toMediaType())
        }

        val reqBuilder = okhttp3.Request.Builder()
            .url(validatedUrl)
            .method(httpMethod, body)
            .header("User-Agent", OnlineHttpClient.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")

        val host = validatedUrl.toHttpUrlOrNull()?.host.orEmpty()
        if (OnlineHttpClient.isYouTubeHost(host)) {
            reqBuilder.header("Referer", OnlineHttpClient.YOUTUBE_REFERER)
        }

        for ((headerName, headerValues) in headers) {
            if (headerName.equals("User-Agent", ignoreCase = true)) continue
            if (headerValues.size == 1) {
                reqBuilder.header(headerName, headerValues.first())
            } else {
                reqBuilder.removeHeader(headerName)
                headerValues.forEach { value ->
                    reqBuilder.addHeader(headerName, value)
                }
            }
        }

        client.newCall(reqBuilder.build()).execute().use { okResponse ->
            if (okResponse.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", validatedUrl)
            }
            val bodyString = InputAndUrlSecurity.readBodyBounded(okResponse.body)
            val latestUrl = okResponse.request.url.toString()
            return Response(
                okResponse.code,
                okResponse.message,
                okResponse.headers.toMultimap(),
                bodyString,
                latestUrl
            )
        }
    }

    companion object {
        private val initialized = AtomicBoolean(false)

        fun ensureNewPipeInitialized() {
            if (initialized.compareAndSet(false, true)) {
                NewPipe.init(OkHttpDownloader())
            }
        }
    }
}
