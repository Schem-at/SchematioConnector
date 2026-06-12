package io.schemat.connector.core.modapi.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPatch
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.logging.Logger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Apache HttpComponents implementation of [ApiTransport].
 *
 * Generalized sibling of [io.schemat.connector.core.http.HttpUtil] (which stays for
 * bukkit/existing-service compatibility): same timeouts, optional trust-all-certificates
 * SSL context (dev only), and 50MB response cap - but the bearer token is supplied
 * per-request instead of being fixed at construction time.
 *
 * @property apiEndpoint base URL of the API (e.g. "https://schemat.io/api/v1")
 */
class HttpTransport(
    private val apiEndpoint: String,
    private val logger: Logger,
    trustAllCertificates: Boolean = false,
) : ApiTransport, Closeable {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val CONNECTION_REQUEST_TIMEOUT_MS = 5_000
        private const val MAX_RESPONSE_SIZE = 50 * 1024 * 1024 // 50MB max response

        /** Build the full request URL: normalized base + path + URL-encoded query string. */
        fun buildUrl(base: String, request: ApiRequest): String {
            val normalizedBase = base.trimEnd('/')
            val path = if (request.path.startsWith("/")) request.path else "/${request.path}"
            if (request.query.isEmpty()) return normalizedBase + path
            val queryString = request.query.entries.joinToString("&") { (key, value) ->
                URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
            }
            return "$normalizedBase$path?$queryString"
        }
    }

    private val trustAllSslContext: SSLContext? = if (trustAllCertificates) {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
    } else null

    private val requestConfig: RequestConfig = RequestConfig.custom()
        .setConnectTimeout(CONNECT_TIMEOUT_MS)
        .setSocketTimeout(SOCKET_TIMEOUT_MS)
        .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MS)
        .build()

    private val httpClient: CloseableHttpClient = HttpClients.custom()
        .setDefaultRequestConfig(requestConfig)
        .apply {
            if (trustAllCertificates && trustAllSslContext != null) {
                setSSLContext(trustAllSslContext)
                setSSLHostnameVerifier { _, _ -> true }
                logger.warning("SSL certificate verification is DISABLED - do not use in production!")
            }
        }
        .build()

    override fun close() {
        httpClient.close()
    }

    override suspend fun execute(request: ApiRequest, bearerToken: String?): ApiResponse =
        withContext(Dispatchers.IO) {
            val url = buildUrl(apiEndpoint, request)
            val httpRequest: HttpRequestBase = when (request.method) {
                HttpMethod.GET -> HttpGet(url)
                HttpMethod.POST -> HttpPost(url)
                HttpMethod.PUT -> HttpPut(url)
                HttpMethod.PATCH -> HttpPatch(url)
                HttpMethod.DELETE -> HttpDelete(url)
            }
            httpRequest.addHeader("Accept", "application/json")
            if (bearerToken != null) {
                httpRequest.addHeader("Authorization", "Bearer $bearerToken")
            }
            if (httpRequest is HttpEntityEnclosingRequestBase) {
                request.jsonBody?.let {
                    httpRequest.entity = StringEntity(it, ContentType.APPLICATION_JSON)
                }
                request.multipart?.let { multipart ->
                    val builder = MultipartEntityBuilder.create()
                    multipart.fields.forEach { (name, value) -> builder.addTextBody(name, value) }
                    multipart.files.forEach { file ->
                        builder.addBinaryBody(file.fieldName, file.bytes, ContentType.create(file.contentType), file.fileName)
                    }
                    httpRequest.entity = builder.build()
                }
            }

            try {
                val response = httpClient.execute(httpRequest)
                try {
                    val status = response.statusLine.statusCode
                    val headers = response.allHeaders.associate { it.name to it.value }
                    val entity = response.entity
                    val bytes: ByteArray? = if (entity != null) {
                        val contentLength = entity.contentLength
                        if (contentLength > MAX_RESPONSE_SIZE) {
                            EntityUtils.consume(entity)
                            throw TransportException("Response too large: $contentLength bytes (max: $MAX_RESPONSE_SIZE)")
                        }
                        val content = ByteArrayOutputStream()
                        entity.content.use { inputStream ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytes = 0L
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                totalBytes += bytesRead
                                if (totalBytes > MAX_RESPONSE_SIZE) {
                                    throw TransportException("Response exceeded max size during transfer (max: $MAX_RESPONSE_SIZE)")
                                }
                                content.write(buffer, 0, bytesRead)
                            }
                        }
                        content.toByteArray()
                    } else {
                        null
                    }
                    ApiResponse(status, bytes, headers)
                } finally {
                    if (response is Closeable) response.close()
                }
            } catch (e: TransportException) {
                throw e
            } catch (e: IOException) {
                logIOException(e, "${request.method} ${request.path}")
                throw TransportException("Network failure for ${request.method} ${request.path}: ${e.message}", e)
            }
        }

    /** Log IO exceptions gracefully - connection errors are expected when the API is down. */
    private fun logIOException(e: IOException, context: String) {
        val msg = e.message ?: "Unknown error"
        if (msg.contains("Connection refused") ||
            msg.contains("Connection reset") ||
            msg.contains("timed out") ||
            msg.contains("No route to host") ||
            msg.contains("Network is unreachable")
        ) {
            logger.warning("API unavailable ($context): $msg")
        } else {
            logger.severe("$context: $msg")
        }
    }
}
