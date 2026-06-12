package io.schemat.connector.fabric.client.services

import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.CachedSchematioApi
import io.schemat.connector.core.modapi.SchematioApi
import io.schemat.connector.core.modapi.dto.MeSnapshot
import io.schemat.connector.core.modapi.transport.HttpTransport
import io.schemat.connector.fabric.client.auth.ClientAuthManager
import io.schemat.connector.fabric.client.ui.PreviewImageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.logging.Logger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Client-side service container: the modapi client stack ([api]/[cached]) wired to
 * [ClientAuthManager], a background [scope] for API calls, and a cached [me] snapshot.
 *
 * One instance is held by [io.schemat.connector.fabric.client.SchematioClientMod]; UI code
 * launches work on [scope] and marshals results back to the render thread via [onMainThread].
 */
class ClientServices(val authManager: ClientAuthManager) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-services")

        /** Size cap for preview image downloads. */
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

        /** Trusted preview-image hosts (plus subdomains). */
        private val ALLOWED_IMAGE_DOMAINS = listOf(
            "schemat.io",
            "cdn.schemat.io",
            "api.schemat.io",
            "storage.schemat.io",
        )
    }

    private val javaLogger = Logger.getLogger("schematioconnector")

    private val transport = HttpTransport(
        apiEndpoint = authManager.apiEndpoint,
        logger = javaLogger,
        trustAllCertificates = authManager.trustAllCertificates,
    )

    /** Raw API client - per-request token from the auth manager, mutex-guarded re-auth on 401. */
    val api = SchematioApi(
        transport = transport,
        tokenProvider = authManager::tokenProvider,
        reauthenticate = { authManager.ensureAuthenticated() },
    )

    /** TTL-cached decorator for read endpoints; UI should prefer this for listings/identity. */
    val cached = CachedSchematioApi(api)

    /** Background scope for API calls; cancelled on [shutdown]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Shared preview-image texture cache for all schematic UI. Fetches go through
     * [fetchImageBytes] (relative-URL resolution, image-host allowlist, size cap).
     */
    val previewImages = PreviewImageManager(scope) { url -> fetchImageBytes(url) }

    /**
     * Shared head-avatar texture cache (co-author rows, confirm summary). schemat.io
     * `head_url`s go through [fetchImageBytes]; uuid-only lookups hit mc-heads.net.
     */
    val headAvatars = HeadAvatarManager(scope) { url -> fetchImageBytes(url) }

    /** Last successfully fetched `/mod/me` snapshot (identity + communities), or null. */
    @Volatile
    var me: MeSnapshot? = null
        private set

    /** Error from the most recent [refreshMe] attempt; null after a success. */
    @Volatile
    var meError: ApiError? = null
        private set

    /** Run [block] on the client render thread. */
    fun onMainThread(block: () -> Unit) {
        Minecraft.getInstance().execute(block)
    }

    /**
     * True when the last [refreshMe] attempt failed because the backend was unreachable.
     * UI uses this to disable mutating actions (with a tooltip) while offline.
     */
    fun isOffline(): Boolean = meError is ApiError.Offline

    // ---- preview image fetching (public URLs, no auth header) ----

    /** Plain JDK HTTP client for image bytes; trust-all SSL in dev setups. */
    private val imageHttpClient: HttpClient by lazy {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
        if (authManager.trustAllCertificates) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustAll), SecureRandom())
            builder.sslContext(sslContext)
        }
        builder.build()
    }

    /**
     * Fetch public image bytes (schematic previews). Mirrors the retired
     * `HttpUtil.fetchImageBytes`: relative URLs resolve against [ClientAuthManager.apiEndpoint],
     * hosts are allowlisted (schemat.io + CDNs; localhost/`.test` only with
     * trust-all-certificates), HTTPS-only in production, and responses are capped at
     * [MAX_IMAGE_BYTES]. No Authorization header is sent - preview URLs are public.
     * Returns null on any failure (callers treat it as "no preview").
     */
    suspend fun fetchImageBytes(url: String): ByteArray? {
        val absoluteUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            try {
                URI(authManager.apiEndpoint.trimEnd('/') + "/").resolve(url).toString()
            } catch (e: Exception) {
                LOGGER.warn("Failed to resolve relative image URL '{}': {}", url, e.message)
                return null
            }
        }
        if (!isAllowedImageUrl(absoluteUrl)) return null

        return try {
            val request = HttpRequest.newBuilder(URI(absoluteUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "image/*")
                .GET()
                .build()
            val response = withContext(Dispatchers.IO) {
                imageHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            }
            when {
                response.statusCode() != 200 -> {
                    LOGGER.warn("Failed to fetch image {} (status={})", absoluteUrl, response.statusCode())
                    null
                }
                response.body().size > MAX_IMAGE_BYTES -> {
                    LOGGER.warn("Image too large ({} bytes): {}", response.body().size, absoluteUrl)
                    null
                }
                else -> response.body()
            }
        } catch (e: Exception) {
            LOGGER.warn("Exception fetching image {}: {}", absoluteUrl, e.message)
            null
        }
    }

    /** Host allowlist for preview images (mirrors the retired HttpUtil behavior). */
    private fun isAllowedImageUrl(imageUrl: String): Boolean {
        return try {
            val uri = URI(imageUrl)
            val host = uri.host?.lowercase() ?: return false

            // In dev mode (trust-all), allow localhost and local dev domains
            if (authManager.trustAllCertificates &&
                (host == "localhost" || host == "127.0.0.1" || host.endsWith(".test"))
            ) {
                return true
            }

            // Only HTTPS in production
            if (uri.scheme != "https") {
                LOGGER.warn("Rejected non-HTTPS image URL: {}", imageUrl)
                return false
            }

            val allowed = ALLOWED_IMAGE_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") }
            if (!allowed) LOGGER.warn("Rejected image URL from untrusted domain: {}", host)
            allowed
        } catch (e: Exception) {
            LOGGER.warn("Failed to parse image URL: {}", imageUrl)
            false
        }
    }

    /**
     * Refresh the cached [me] snapshot via [CachedSchematioApi.me].
     * With [force] the read caches are dropped first so the snapshot is fetched live
     * (used after re-auth / explicit refresh). On failure the previous snapshot is kept
     * and the error recorded in [meError].
     */
    suspend fun refreshMe(force: Boolean = false): MeSnapshot? {
        if (force) cached.clear()
        when (val result = cached.me()) {
            is ApiResult.Success -> {
                me = result.value.value
                meError = null
            }
            is ApiResult.Failure -> {
                meError = result.error
                LOGGER.warn("Failed to refresh /mod/me: {}", result.error)
            }
        }
        return me
    }

    /** Cancel background work and release the HTTP transport. */
    fun shutdown() {
        scope.cancel()
        transport.close()
    }
}
