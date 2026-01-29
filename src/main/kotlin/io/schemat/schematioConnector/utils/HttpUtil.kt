package io.schemat.schematioConnector.utils

import com.auth0.jwt.JWT
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.bukkit.map.MapPalette
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.logging.Logger
import javax.imageio.ImageIO

/**
 * HTTP client utility for communicating with the schemat.io API.
 *
 * Provides methods for making authenticated HTTP requests to the schemat.io API,
 * including support for JSON payloads, multipart file uploads, and binary downloads.
 * All network operations are performed using Kotlin coroutines on the IO dispatcher.
 *
 * ## Authentication
 *
 * All requests include a Bearer token in the Authorization header. The token is a JWT
 * that may contain permission claims (e.g., `canManagePasswords`).
 *
 * ## Error Handling
 *
 * Connection errors (refused, timeout, unreachable) are logged at WARNING level
 * since they're expected when the API is unavailable. Other errors are logged at SEVERE.
 * Methods return null or error codes rather than throwing exceptions.
 *
 * ## Thread Safety
 *
 * This class is safe to use from multiple coroutines. Each method creates its own
 * HTTP client instance.
 *
 * @property apiKey The JWT token for API authentication
 * @property apiEndpoint The base URL of the schemat.io API (e.g., "https://schemat.io/api/v1")
 * @property logger Logger for debug and error messages
 */
class HttpUtil(private val apiKey: String, private val apiEndpoint: String, private val logger: Logger) : Closeable {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val CONNECTION_REQUEST_TIMEOUT_MS = 5_000
        private const val MAX_RESPONSE_SIZE = 50 * 1024 * 1024 // 50MB max response
    }

    private val gson = Gson()

    private val requestConfig: RequestConfig = RequestConfig.custom()
        .setConnectTimeout(CONNECT_TIMEOUT_MS)
        .setSocketTimeout(SOCKET_TIMEOUT_MS)
        .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MS)
        .build()

    private val httpClient: CloseableHttpClient = HttpClients.custom()
        .setDefaultRequestConfig(requestConfig)
        .build()

    override fun close() {
        httpClient.close()
    }
    
    /**
     * Log IO exceptions gracefully - connection errors are expected when API is down
     */
    private fun logIOException(e: IOException, context: String) {
        val msg = e.message ?: "Unknown error"
        if (msg.contains("Connection refused") || 
            msg.contains("Connection reset") ||
            msg.contains("timed out") ||
            msg.contains("No route to host") ||
            msg.contains("Network is unreachable")) {
            // Expected errors when API is down - don't spam logs
            logger.warning("API unavailable ($context): $msg")
        } else {
            logger.severe("$context: $msg")
            e.printStackTrace()
        }
    }

    // Custom HttpGet class that supports request body
    private class HttpGetWithEntity(uri: String) : HttpEntityEnclosingRequestBase() {
        init {
            this.uri = URI.create(uri)
        }

        override fun getMethod(): String = "GET"
    }

    suspend fun checkConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URI("$apiEndpoint/check").toURL()
                logger.info("Checking connection to $apiEndpoint")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = SOCKET_TIMEOUT_MS
                connection.doOutput = true

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = gson.fromJson(response, Map::class.java)
                    jsonResponse["success"] as Boolean
                } else {
                    logger.warning("Failed to connect to the API, response code: $responseCode")
                    false
                }
            } catch (e: Exception) {
                // Connection errors are expected when API is down - don't spam logs
                val msg = e.message ?: "Unknown error"
                if (msg.contains("Connection refused") ||
                    msg.contains("Connection reset") ||
                    msg.contains("timed out") ||
                    msg.contains("No route to host") ||
                    msg.contains("Network is unreachable")) {
                    // Silent - the caller will handle the false return
                } else {
                    logger.warning("Connection check failed: $msg")
                }
                false
            } finally {
                connection?.disconnect()
            }
        }
    }

    suspend fun sendMultiPartRequest(endpoint: String, multipart: HttpEntity): HttpEntity? {
        return withContext(Dispatchers.IO) {
            try {
                val uploadFile = HttpPost("$apiEndpoint$endpoint")
                uploadFile.addHeader("Authorization", "Bearer $apiKey")
                uploadFile.entity = multipart
                val response = httpClient.execute(uploadFile)
                response.entity
            } catch (e: IOException) {
                logger.severe("Exception occurred while sending multipart request: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun sendGetRequestFullResponse(endpoint: String, progressCallback: (Float) -> Unit): HttpResponse? {
        return withContext(Dispatchers.IO) {
            val fullUrl = "$apiEndpoint$endpoint"
            val httpGet = HttpGet(fullUrl)
            httpGet.addHeader("Authorization", "Bearer $apiKey")

            try {
                logger.info("Sending GET request to: $fullUrl")
                val response = httpClient.execute(httpGet)
                val entity = response.entity
                if (entity != null) {
                    val totalBytes = entity.contentLength
                    if (totalBytes > MAX_RESPONSE_SIZE) {
                        logger.warning("Response too large: $totalBytes bytes (max: $MAX_RESPONSE_SIZE)")
                        EntityUtils.consume(entity)
                        return@withContext null
                    }
                    var bytesTransferred: Long = 0

                    val content = ByteArrayOutputStream()
                    entity.content.use { inputStream ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            content.write(buffer, 0, bytesRead)
                            bytesTransferred += bytesRead
                            if (bytesTransferred > MAX_RESPONSE_SIZE) {
                                logger.warning("Response exceeded max size during transfer")
                                EntityUtils.consume(entity)
                                return@withContext null
                            }
                            val progress = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0.5f
                            progressCallback(progress)
                        }
                    }

                    // Replace the original entity with a new one that uses our buffered content
                    EntityUtils.consume(entity)
                    response.entity = ByteArrayEntity(content.toByteArray())
                }
                response
            } catch (e: IOException) {
                logIOException(e, "GET request (full response)")
                null
            }
        }
    }

    suspend fun sendGetRequest(endpoint: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val fullUrl = "$apiEndpoint$endpoint"
                val httpGet = HttpGet(fullUrl)
                httpGet.addHeader("Authorization", "Bearer $apiKey")
                httpGet.addHeader("Accept", "application/json")

                val response = httpClient.execute(httpGet)
                val entity = response.entity
                if (entity != null) {
                    EntityUtils.toString(entity)
                } else {
                    null
                }
            } catch (e: IOException) {
                logIOException(e, "GET request")
                null
            }
        }
    }

    /**
     * Send a POST request with JSON body
     * Returns pair of (statusCode, responseBody)
     */
    suspend fun sendPostRequest(endpoint: String, jsonBody: String): Pair<Int, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val fullUrl = "$apiEndpoint$endpoint"
                val httpPost = HttpPost(fullUrl)
                httpPost.addHeader("Authorization", "Bearer $apiKey")
                httpPost.addHeader("Content-Type", "application/json")
                httpPost.addHeader("Accept", "application/json")
                httpPost.entity = StringEntity(jsonBody)

                logger.info("POST $fullUrl")

                val response = httpClient.execute(httpPost)
                val statusCode = response.statusLine.statusCode
                val entity = response.entity
                val body = if (entity != null) EntityUtils.toString(entity) else null

                logger.info("Response: $statusCode")

                Pair(statusCode, body)
            } catch (e: IOException) {
                logIOException(e, "POST request")
                Pair(-1, null)
            }
        }
    }

    /**
     * Send a POST request with JSON body and receive binary response
     * Returns triple of (statusCode, responseBytes, errorBody)
     */
    suspend fun sendPostRequestForBinary(endpoint: String, jsonBody: String): Triple<Int, ByteArray?, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val fullUrl = "$apiEndpoint$endpoint"
                val httpPost = HttpPost(fullUrl)
                httpPost.addHeader("Authorization", "Bearer $apiKey")
                httpPost.addHeader("Content-Type", "application/json")
                httpPost.addHeader("Accept", "application/octet-stream")
                httpPost.entity = StringEntity(jsonBody)

                logger.info("POST (binary) $fullUrl")

                val response = httpClient.execute(httpPost)
                val statusCode = response.statusLine.statusCode
                val entity = response.entity

                logger.info("Response: $statusCode")

                if (statusCode == 200 && entity != null) {
                    val contentLength = entity.contentLength
                    if (contentLength > MAX_RESPONSE_SIZE) {
                        logger.warning("Binary response too large: $contentLength bytes")
                        EntityUtils.consume(entity)
                        return@withContext Triple(statusCode, null, "Response too large")
                    }
                    val bytes = entity.content.readBytes()
                    if (bytes.size > MAX_RESPONSE_SIZE) {
                        logger.warning("Binary response exceeded max size: ${bytes.size} bytes")
                        return@withContext Triple(statusCode, null, "Response too large")
                    }
                    logger.info("Received ${bytes.size} bytes")
                    Triple(statusCode, bytes, null)
                } else {
                    val errorBody = if (entity != null) EntityUtils.toString(entity) else null
                    Triple(statusCode, null, errorBody)
                }
            } catch (e: IOException) {
                logIOException(e, "POST request (binary)")
                Triple(-1, null, null)
            }
        }
    }

    suspend fun sendGetRequestWithBody(endpoint: String, requestBody: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val fullUrl = "$apiEndpoint$endpoint"

                val httpGet = HttpGetWithEntity(fullUrl)

                httpGet.addHeader("Authorization", "Bearer $apiKey")
                httpGet.addHeader("Content-Type", "application/json")
                httpGet.addHeader("Accept", "application/json")
                httpGet.entity = StringEntity(requestBody)

                val response = httpClient.execute(httpGet)
                val entity = response.entity
                if (entity != null) {
                    EntityUtils.toString(entity)
                } else {
                    null
                }
            } catch (e: IOException) {
                logIOException(e, "GET request with body")
                null
            }
        }
    }

    suspend fun sendGetRequestWithBodyFullResponse(endpoint: String, requestBody: String, progressCallback: (Float) -> Unit): HttpResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val fullUrl = "$apiEndpoint$endpoint"

                val httpGet = HttpGetWithEntity(fullUrl)

                httpGet.addHeader("Authorization", "Bearer $apiKey")
                httpGet.addHeader("Content-Type", "application/json")
                httpGet.addHeader("Accept", "application/json")
                httpGet.entity = StringEntity(requestBody)

                logger.info("Sending GET request with body to: $fullUrl")
                val response = httpClient.execute(httpGet)
                val entity = response.entity
                if (entity != null) {
                    val totalBytes = entity.contentLength
                    if (totalBytes > MAX_RESPONSE_SIZE) {
                        logger.warning("Response too large: $totalBytes bytes (max: $MAX_RESPONSE_SIZE)")
                        EntityUtils.consume(entity)
                        return@withContext null
                    }
                    var bytesTransferred: Long = 0

                    val content = ByteArrayOutputStream()
                    entity.content.use { inputStream ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            content.write(buffer, 0, bytesRead)
                            bytesTransferred += bytesRead
                            if (bytesTransferred > MAX_RESPONSE_SIZE) {
                                logger.warning("Response exceeded max size during transfer")
                                EntityUtils.consume(entity)
                                return@withContext null
                            }
                            val progress = if (totalBytes > 0) {
                                bytesTransferred.toFloat() / totalBytes
                            } else {
                                0.5f // Unknown progress
                            }
                            progressCallback(progress)
                        }
                    }

                    // Replace the original entity with a new one that uses our buffered content
                    EntityUtils.consume(entity)
                    response.entity = ByteArrayEntity(content.toByteArray())
                }
                response
            } catch (e: IOException) {
                logIOException(e, "GET request with body (full response)")
                null
            }
        }
    }

    /**
     * Check if the configured token has a specific permission/scope
     */
    fun hasPermission(permission: String): Boolean {
        return try {
            val decodedJWT = JWT.decode(apiKey)
            val permissions = decodedJWT.claims["permissions"]?.asList(String::class.java)
            permissions?.contains(permission) == true
        } catch (e: Exception) {
            logger.warning("Failed to decode JWT token: ${e.message}")
            false
        }
    }
    
    /**
     * Check if the token allows password management.
     * Checks multiple possible locations:
     * 1. Top-level claim "can_manage_passwords" (boolean)
     * 2. Permissions array containing "can_manage_password" or "canManagePasswords"
     */
    fun canManagePasswords(): Boolean {
        return try {
            val decodedJWT = JWT.decode(apiKey)

            // Check top-level boolean claim first (newer token format)
            val topLevelClaim = decodedJWT.claims["can_manage_passwords"]?.asBoolean()
            if (topLevelClaim == true) {
                return true
            }

            // Check permissions array (supports both naming conventions)
            val permissions = decodedJWT.claims["permissions"]?.asList(String::class.java)
            permissions?.any { it == "can_manage_password" || it == "canManagePasswords" } == true
        } catch (e: Exception) {
            logger.warning("Failed to decode JWT token for password permission check: ${e.message}")
            false
        }
    }

    suspend fun setPassword(playerUuid: String, password: String): Pair<Int, List<String>> {
        if (!canManagePasswords()) {
            logger.warning("Token does not have canManagePasswords permission")
            return Pair(403, listOf("This server's token doesn't have permission to manage passwords."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val fullUrl = URI("$apiEndpoint/password-set").toString()

                val postRequest = HttpPost(fullUrl)
                postRequest.addHeader("Content-Type", "application/json")
                postRequest.addHeader("Accept", "application/json")
                postRequest.addHeader("Authorization", "Bearer $apiKey")

                val jsonObject = JsonObject().apply {
                    addProperty("player_uuid", playerUuid)
                    addProperty("password", password)
                }
                postRequest.entity = StringEntity(gson.toJson(jsonObject))

                val response = httpClient.execute(postRequest)
                val statusCode = response.statusLine.statusCode
                val responseBody = EntityUtils.toString(response.entity)
                logger.info("Set password response status: $statusCode")
                when (statusCode) {
                    200 -> Pair(200, listOf("Password successfully changed!"))
                    422 -> {
                        val errors = parseErrors(responseBody)
                        Pair(422, errors)
                    }

                    else -> Pair(statusCode, listOf("An unexpected error occurred. Please try again later."))
                }
            } catch (e: Exception) {
                logger.severe("Exception occurred while setting password: ${e.message}")
                e.printStackTrace()
                Pair(500, listOf("An internal error occurred. Please try again later."))
            }
        }
    }

    private fun parseErrors(responseBody: String): List<String> {
        val errorMessages = mutableListOf<String>()

        try {
            val jsonObject = gson.fromJson(responseBody, JsonObject::class.java) ?: return errorMessages

            // Try to get "errors" object
            val errors = jsonObject.get("errors")
            if (errors != null && errors.isJsonObject) {
                errors.asJsonObject.entrySet().forEach { (field, messages) ->
                    if (messages != null && messages.isJsonArray) {
                        messages.asJsonArray.forEach { message ->
                            if (message != null && !message.isJsonNull) {
                                try {
                                    errorMessages.add("$field: ${message.asString}")
                                } catch (e: Exception) {
                                    // Skip non-string messages
                                }
                            }
                        }
                    }
                }
            }

            // Also check for a single "message" or "error" field
            if (errorMessages.isEmpty()) {
                jsonObject.get("message")?.let {
                    if (!it.isJsonNull && it.isJsonPrimitive) {
                        errorMessages.add(it.asString)
                    }
                }
                jsonObject.get("error")?.let {
                    if (!it.isJsonNull && it.isJsonPrimitive) {
                        errorMessages.add(it.asString)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("Failed to parse error response: ${e.message}")
        }

        return errorMessages
    }

    private fun isAllowedImageUrl(imageUrl: String): Boolean {
        return try {
            val url = URI(imageUrl).toURL()
            val host = url.host.lowercase()

            // Only allow HTTPS
            if (url.protocol != "https") {
                logger.warning("Rejected non-HTTPS image URL: $imageUrl")
                return false
            }

            // Allow only trusted domains (schemat.io and common CDNs)
            val allowedDomains = listOf(
                "schemat.io",
                "cdn.schemat.io",
                "api.schemat.io",
                "localhost",
            )

            val isAllowed = allowedDomains.any { domain ->
                host == domain || host.endsWith(".$domain")
            }

            if (!isAllowed) {
                logger.warning("Rejected image URL from untrusted domain: $host")
            }

            isAllowed
        } catch (e: Exception) {
            logger.warning("Failed to parse image URL: $imageUrl")
            false
        }
    }

    fun fetchImageAsByteArray(imageUrl: String): ByteArray? {
        if (!isAllowedImageUrl(imageUrl)) {
            return null
        }

        return try {
            val url = URI(imageUrl).toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = SOCKET_TIMEOUT_MS

            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val image = ImageIO.read(connection.inputStream)
                    imageToMapByteArray(image)
                } else {
                    logger.warning("Failed to fetch image, response code: ${connection.responseCode}")
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            logger.severe("Exception occurred while fetching image: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    @Suppress("DEPRECATION") // MapPalette.matchColor is deprecated but no replacement exists
    private fun imageToMapByteArray(image: BufferedImage): ByteArray {
        val scaledImage = scaleImage(image)
        val byteArray = ByteArray(128 * 128)

        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val rgb = scaledImage.getRGB(x, y)
                val color = Color(rgb, true)
                val mapColor = MapPalette.matchColor(color)
                byteArray[y * 128 + x] = mapColor.toByte()
            }
        }

        return byteArray
    }

    private fun scaleImage(image: BufferedImage): BufferedImage {
        val scaledImage = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        val g = scaledImage.createGraphics()
        g.drawImage(image, 0, 0, 128, 128, null)
        g.dispose()
        return scaledImage
    }
}