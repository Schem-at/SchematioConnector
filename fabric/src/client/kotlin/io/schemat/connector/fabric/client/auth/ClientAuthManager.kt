package io.schemat.connector.fabric.client.auth

import io.schemat.connector.core.auth.AuthResult
import io.schemat.connector.core.auth.MojangAuthProvider
import io.schemat.connector.core.modapi.PlayerSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.Properties
import java.util.logging.Logger

/**
 * Owns the client's authentication state: the Mojang→`/auth/mojang` handshake,
 * `config.properties` token persistence, and the decoded [PlayerSession].
 *
 * Designed to back a [io.schemat.connector.core.modapi.SchematioApi]:
 * - [tokenProvider] is the non-blocking per-request token source,
 * - [ensureAuthenticated] is the `reauthenticate` lambda (mutex-guarded so concurrent
 *   401s trigger at most one handshake).
 */
class ClientAuthManager(private val configDir: Path) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-auth")
    }

    /** Base API URL from config.properties (default: production). */
    val apiEndpoint: String

    /** Dev-only flag from config.properties; forwarded to the HTTP transport. */
    val trustAllCertificates: Boolean

    /** Current decoded session, or null when unauthenticated. May hold an expired token briefly. */
    @Volatile
    var session: PlayerSession? = null
        private set

    var authError: String? = null
        private set

    /** True while a non-expired session token is held. */
    val isAuthenticated: Boolean
        get() = session?.isExpired() == false

    private val authMutex = Mutex()
    private val javaLogger = Logger.getLogger("schematioconnector")

    init {
        val configFile = configDir.resolve("config.properties").toFile()
        val props = Properties()
        if (configFile.exists()) {
            configFile.inputStream().use { props.load(it) }
        }
        apiEndpoint = props.getProperty("api_endpoint") ?: "https://schemat.io/api/v1"
        trustAllCertificates = props.getProperty("trust_all_certificates")?.toBoolean() ?: false

        // Load a cached client token; discard expired/malformed ones so the next
        // ensureAuthenticated() performs a fresh handshake.
        val cachedToken = props.getProperty("client_token")
        if (!cachedToken.isNullOrBlank()) {
            val cachedSession = PlayerSession.fromToken(cachedToken)
            if (cachedSession != null && !cachedSession.isExpired()) {
                session = cachedSession
                LOGGER.info("Loaded cached auth token")
            } else {
                LOGGER.info("Cached auth token is expired or malformed; will re-authenticate")
            }
        }
    }

    /**
     * The current valid session token, or null. Non-blocking - never triggers a handshake.
     * Intended for `SchematioApi(transport, authManager::tokenProvider, ...)`.
     */
    fun tokenProvider(): String? = session?.takeUnless { it.isExpired() }?.token

    /**
     * Ensure a valid (non-expired) session exists, performing the Mojang handshake if not.
     *
     * Safe under concurrent calls: the handshake runs under a [Mutex], and waiters
     * re-check the session after acquiring the lock so a handshake completed by another
     * coroutine is reused instead of repeated.
     *
     * @return true when a valid session is available afterwards.
     */
    suspend fun ensureAuthenticated(): Boolean {
        if (session?.isExpired() == false) return true
        return authMutex.withLock {
            if (session?.isExpired() == false) return@withLock true
            handshake() is AuthResult.Success
        }
    }

    /**
     * Discard the cached session (in-memory AND the persisted `client_token` in
     * config.properties) and perform a fresh Mojang handshake unconditionally.
     *
     * Unlike [ensureAuthenticated] - which reuses any non-expired cached token and
     * therefore never picks up NEW token claims (e.g. a permission the backend started
     * granting after the token was issued) - this always pulls a fresh token. Used by
     * the Settings "Re-authenticate" button and the upload permission-403 self-heal.
     *
     * Concurrency-safe: runs under the same [authMutex] as [ensureAuthenticated].
     *
     * @return true when a fresh valid session was obtained.
     */
    suspend fun forceReauthenticate(): Boolean {
        return authMutex.withLock {
            session = null
            // Drop the persisted token first so a failed handshake can't leave the
            // stale token around for the next launch.
            try {
                updateConfig { props -> props.remove("client_token") }
            } catch (e: Exception) {
                LOGGER.warn("Failed to clear cached auth token: ${e.message}")
            }
            handshake() is AuthResult.Success
        }
    }

    /** Perform the Mojang-session → `/auth/mojang` handshake. Call only under [authMutex]. */
    private suspend fun handshake(): AuthResult {
        val client = Minecraft.getInstance()
        val mcSession = client.user

        val accessToken = mcSession.accessToken
        val uuid = mcSession.profileId?.toString()
        val username = mcSession.name

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

        return when (val result = authProvider.authenticate(accessToken, uuid, username)) {
            is AuthResult.Success -> {
                val newSession = PlayerSession.fromToken(result.jwt)
                if (newSession == null) {
                    val error = "Server returned a malformed token"
                    LOGGER.warn(error)
                    authError = error
                    AuthResult.Failure(error)
                } else {
                    session = newSession
                    authError = null
                    saveToken(result.jwt)
                    LOGGER.info("Successfully authenticated as $username")
                    result
                }
            }
            is AuthResult.Failure -> {
                authError = result.reason
                LOGGER.warn("Authentication failed: ${result.reason}")
                result
            }
        }
    }

    private fun saveToken(token: String) {
        try {
            updateConfig { props -> props.setProperty("client_token", token) }
        } catch (e: Exception) {
            LOGGER.warn("Failed to cache auth token: ${e.message}")
        }
    }

    /**
     * Read a boolean flag from config.properties (e.g. `limited_mode_notice_shown`).
     * Missing/malformed values read as false.
     */
    fun getConfigFlag(name: String): Boolean {
        return try {
            val configFile = configDir.resolve("config.properties").toFile()
            if (!configFile.exists()) return false
            val props = Properties()
            configFile.inputStream().use { props.load(it) }
            props.getProperty(name)?.toBoolean() ?: false
        } catch (e: Exception) {
            LOGGER.warn("Failed to read config flag $name: ${e.message}")
            false
        }
    }

    /** Persist a boolean flag to config.properties. */
    fun setConfigFlag(name: String, value: Boolean) {
        try {
            updateConfig { props -> props.setProperty(name, value.toString()) }
        } catch (e: Exception) {
            LOGGER.warn("Failed to persist config flag $name: ${e.message}")
        }
    }

    /** Load config.properties (if present), apply [mutate], and store it back. */
    private fun updateConfig(mutate: (Properties) -> Unit) {
        val configFile = configDir.resolve("config.properties").toFile()
        val props = Properties()
        if (configFile.exists()) {
            configFile.inputStream().use { props.load(it) }
        }
        mutate(props)
        configFile.outputStream().use { props.store(it, "Schematio Connector Configuration") }
    }

    fun shutdown() {
        // No resources to release; the modapi HttpTransport is owned by ClientServices.
    }
}
