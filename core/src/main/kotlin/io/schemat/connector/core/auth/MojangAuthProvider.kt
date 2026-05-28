package io.schemat.connector.core.auth

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.logging.Logger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Authenticates with the schemat.io API using Mojang's session server.
 *
 * Flow:
 * 1. Generate a serverId hash from the player UUID and a shared secret
 * 2. POST to Mojang's session server to prove we hold a valid MC session
 * 3. POST to the schemat.io API /auth/mojang endpoint with the same serverId
 * 4. The API calls Mojang's hasJoined to verify, then returns a JWT
 */
class MojangAuthProvider(
    private val apiEndpoint: String,
    private val logger: Logger = Logger.getLogger("MojangAuthProvider"),
    private val trustAllCertificates: Boolean = false
) {
    private val gson = Gson()

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

    suspend fun authenticate(
        accessToken: String,
        playerUuid: String,
        playerName: String
    ): AuthResult = withContext(Dispatchers.IO) {
        try {
            val uuidNoDashes = playerUuid.replace("-", "")
            val serverId = minecraftSha1Hex(
                "".toByteArray(Charsets.ISO_8859_1),
                AUTH_SECRET.toByteArray(Charsets.ISO_8859_1),
                uuidNoDashes.toByteArray(Charsets.ISO_8859_1)
            )

            logger.info("Authenticating with Mojang session server...")
            val joinResult = joinMojangSession(accessToken, uuidNoDashes, serverId)
            if (!joinResult) {
                return@withContext AuthResult.Failure("Failed to authenticate with Mojang session server")
            }

            logger.info("Exchanging session for API token...")
            val jwt = exchangeForJwt(playerName, playerUuid, serverId)
                ?: return@withContext AuthResult.Failure("Failed to obtain token from API")

            logger.info("Authentication successful")
            AuthResult.Success(jwt)
        } catch (e: Exception) {
            logger.warning("Authentication failed: ${e.message}")
            AuthResult.Failure(e.message ?: "Unknown authentication error")
        }
    }

    private fun joinMojangSession(
        accessToken: String,
        selectedProfile: String,
        serverId: String
    ): Boolean {
        val url = URI(MOJANG_SESSION_URL).toURL()
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true

            val body = JsonObject().apply {
                addProperty("accessToken", accessToken)
                addProperty("selectedProfile", selectedProfile)
                addProperty("serverId", serverId)
            }

            connection.outputStream.use { os ->
                os.write(gson.toJson(body).toByteArray(Charsets.UTF_8))
            }

            return connection.responseCode == 204
        } finally {
            connection.disconnect()
        }
    }

    private fun exchangeForJwt(
        username: String,
        uuid: String,
        serverId: String
    ): String? {
        val url = URI("$apiEndpoint/auth/mojang").toURL()
        val connection = url.openConnection() as HttpURLConnection
        try {
            if (trustAllCertificates && trustAllSslContext != null && connection is HttpsURLConnection) {
                connection.sslSocketFactory = trustAllSslContext.socketFactory
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true

            val body = JsonObject().apply {
                addProperty("username", username)
                addProperty("uuid", uuid)
                addProperty("serverId", serverId)
            }

            connection.outputStream.use { os ->
                os.write(gson.toJson(body).toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = gson.fromJson(response, JsonObject::class.java)
                return json.get("token")?.asString
            }

            logger.warning("API auth returned status ${connection.responseCode}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MOJANG_SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/join"
        private const val AUTH_SECRET = "schematio-connector"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000

        /**
         * Minecraft's non-standard SHA-1 hex digest.
         * Concatenates all byte arrays, hashes with SHA-1, then converts to
         * a two's-complement hex string (may have leading "-" for negative values).
         */
        fun minecraftSha1Hex(vararg parts: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1")
            for (part in parts) {
                digest.update(part)
            }
            return BigInteger(digest.digest()).toString(16)
        }
    }
}
