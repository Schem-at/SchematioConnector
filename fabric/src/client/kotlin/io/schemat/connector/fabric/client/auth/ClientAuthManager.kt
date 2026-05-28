package io.schemat.connector.fabric.client.auth

import io.schemat.connector.core.auth.AuthResult
import io.schemat.connector.core.auth.MojangAuthProvider
import io.schemat.connector.core.http.HttpUtil
import kotlinx.coroutines.*
import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.Properties
import java.util.logging.Logger

class ClientAuthManager(private val configDir: Path) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-auth")
    }

    var httpUtil: HttpUtil? = null
        private set

    var isAuthenticated: Boolean = false
        private set

    var authError: String? = null
        private set

    private var jwt: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val javaLogger = Logger.getLogger("schematioconnector")

    private val apiEndpoint: String
    private val trustAllCertificates: Boolean

    init {
        val configFile = configDir.resolve("config.properties").toFile()
        val props = Properties()
        if (configFile.exists()) {
            configFile.inputStream().use { props.load(it) }
        }
        apiEndpoint = props.getProperty("api_endpoint") ?: "https://schemat.io/api/v1"
        trustAllCertificates = props.getProperty("trust_all_certificates")?.toBoolean() ?: false

        // Try loading a cached client token
        val cachedToken = props.getProperty("client_token")
        if (!cachedToken.isNullOrBlank()) {
            jwt = cachedToken
            httpUtil = HttpUtil(cachedToken, apiEndpoint, javaLogger, trustAllCertificates)
            isAuthenticated = true
            LOGGER.info("Loaded cached auth token")
        }
    }

    /**
     * Start automatic authentication using the Mojang session.
     * Runs in the background with a short delay to ensure the session is ready.
     */
    fun authenticateAsync() {
        scope.launch {
            delay(2000) // Wait for session to be fully ready
            authenticate()
        }
    }

    suspend fun authenticate(): AuthResult {
        val client = MinecraftClient.getInstance()
        val session = client.session

        val accessToken = session.accessToken
        val uuid = session.uuidOrNull?.toString()
        val username = session.username

        if (accessToken.isNullOrBlank() || uuid == null) {
            val error = "No valid Minecraft session available"
            LOGGER.warn(error)
            authError = error
            return AuthResult.Failure(error)
        }

        val authProvider = MojangAuthProvider(
            apiEndpoint = apiEndpoint,
            logger = javaLogger,
            trustAllCertificates = trustAllCertificates
        )

        val result = authProvider.authenticate(accessToken, uuid, username)

        when (result) {
            is AuthResult.Success -> {
                jwt = result.jwt
                httpUtil = HttpUtil(result.jwt, apiEndpoint, javaLogger, trustAllCertificates)
                isAuthenticated = true
                authError = null
                saveToken(result.jwt)
                LOGGER.info("Successfully authenticated as $username")
            }
            is AuthResult.Failure -> {
                authError = result.reason
                LOGGER.warn("Authentication failed: ${result.reason}")
            }
        }

        return result
    }

    private fun saveToken(token: String) {
        try {
            val configFile = configDir.resolve("config.properties").toFile()
            val props = Properties()
            if (configFile.exists()) {
                configFile.inputStream().use { props.load(it) }
            }
            props.setProperty("client_token", token)
            configFile.outputStream().use { props.store(it, "Schematio Connector Configuration") }
        } catch (e: Exception) {
            LOGGER.warn("Failed to cache auth token: ${e.message}")
        }
    }

    fun shutdown() {
        scope.cancel()
        httpUtil?.close()
    }
}
