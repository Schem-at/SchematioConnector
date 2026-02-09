package io.schemat.connector.fabric

import io.schemat.connector.core.cache.RateLimiter
import io.schemat.connector.core.cache.SchematicCache
import io.schemat.connector.core.http.HttpUtil
import io.schemat.connector.core.offline.OfflineMode
import io.schemat.connector.core.service.SchematicsApiService
import io.schemat.connector.fabric.adapter.FabricPlatformAdapter
import io.schemat.connector.fabric.command.SchematioCommand
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Main entry point for the Schematio Connector Fabric mod.
 *
 * This is a server-side mod that provides schematic upload/download functionality
 * through schemat.io API integration.
 */
class SchematioConnectorMod : ModInitializer {

    companion object {
        const val MOD_ID = "schematioconnector"

        private val LOGGER = LoggerFactory.getLogger(MOD_ID)

        // Singleton instance
        lateinit var instance: SchematioConnectorMod
            private set
    }

    // Platform adapter
    lateinit var platformAdapter: FabricPlatformAdapter
        private set

    // Core services
    var httpUtil: HttpUtil? = null
        private set
    lateinit var schematicCache: SchematicCache
        private set
    lateinit var rateLimiter: RateLimiter
        private set
    lateinit var offlineMode: OfflineMode
        private set
    lateinit var schematicsApiService: SchematicsApiService
        private set

    // Configuration
    var apiEndpoint: String = "https://schemat.io/api/v1"
        private set

    // Base URL without /api/v1 (for links to schematics in chat)
    val baseUrl: String
        get() = apiEndpoint.replace(Regex("/api/v\\d+$"), "")
    private var apiToken: String? = null

    // Server reference
    var server: MinecraftServer? = null
        private set

    // Config directory
    val configDir: Path
        get() = FabricLoader.getInstance().configDir.resolve(MOD_ID)

    // Java logger wrapper for core module compatibility
    val logger: Logger = Logger.getLogger(MOD_ID)

    override fun onInitialize() {
        instance = this
        LOGGER.info("Initializing Schematio Connector...")

        // Create config directory
        configDir.toFile().mkdirs()

        // Initialize core services
        initializeCoreServices()

        // Register commands
        registerCommands()

        // Register server lifecycle events
        registerServerEvents()

        LOGGER.info("Schematio Connector initialized!")
    }

    private fun initializeCoreServices() {
        // Initialize rate limiter (default: 10 requests per 60 seconds)
        rateLimiter = RateLimiter(
            maxRequests = 10,
            windowMs = 60_000L
        )

        // Initialize cache (1 hour TTL)
        schematicCache = SchematicCache(ttlMs = 3600000L)

        // Initialize offline mode
        offlineMode = OfflineMode()

        // Load configuration and create HTTP util
        loadConfiguration()
    }

    private fun loadConfiguration() {
        val configFile = configDir.resolve("config.properties").toFile()

        if (configFile.exists()) {
            val props = java.util.Properties()
            configFile.inputStream().use { props.load(it) }

            // Support both old 'base_url' and new 'api_endpoint' keys for backwards compatibility
            val configuredEndpoint = props.getProperty("api_endpoint")
                ?: props.getProperty("base_url")?.let { url ->
                    // If old base_url doesn't have /api/v1, append it
                    if (url.endsWith("/api/v1")) url else "$url/api/v1"
                }
            apiEndpoint = configuredEndpoint ?: "https://schemat.io/api/v1"
            apiToken = props.getProperty("api_token")
        } else {
            // Create default config
            saveDefaultConfig()
        }

        // Create HTTP util if we have a token
        if (!apiToken.isNullOrBlank()) {
            httpUtil = HttpUtil(apiToken!!, apiEndpoint, logger)
            LOGGER.info("API connection configured for: $apiEndpoint")
        } else {
            LOGGER.warn("No API token configured. Run /schematio settoken <token> to set one.")
        }
    }

    private fun saveDefaultConfig() {
        val configFile = configDir.resolve("config.properties").toFile()
        configFile.writeText("""
            # Schematio Connector Configuration
            # API endpoint URL (don't change unless using a custom server)
            api_endpoint=https://schemat.io/api/v1

            # API token - set this to your schemat.io API token
            # Get your token from: https://schemat.io/settings/api
            api_token=
        """.trimIndent())
    }

    private fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            SchematioCommand.register(dispatcher, this)
        }
    }

    private fun registerServerEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            this.server = server
            platformAdapter = FabricPlatformAdapter(server, logger)

            // Initialize API service with platform adapter
            schematicsApiService = SchematicsApiService(
                httpUtil = httpUtil,
                platform = platformAdapter,
                cache = schematicCache,
                offlineMode = offlineMode,
                rateLimiter = rateLimiter
            )
        }

        ServerLifecycleEvents.SERVER_STOPPED.register {
            httpUtil?.close()
            this.server = null
        }
    }

    /**
     * Reload configuration and reconnect to API.
     */
    fun reload() {
        httpUtil?.close()
        httpUtil = null
        loadConfiguration()

        // Reinitialize API service
        schematicsApiService = SchematicsApiService(
            httpUtil = httpUtil,
            platform = platformAdapter,
            cache = schematicCache,
            offlineMode = offlineMode,
            rateLimiter = rateLimiter
        )
    }

    /**
     * Set the API token and save to config.
     */
    fun setApiToken(token: String) {
        apiToken = token

        // Update config file
        val configFile = configDir.resolve("config.properties").toFile()
        val props = java.util.Properties()
        if (configFile.exists()) {
            configFile.inputStream().use { props.load(it) }
        }
        props.setProperty("api_token", token)
        props.setProperty("api_endpoint", apiEndpoint)
        configFile.outputStream().use { props.store(it, "Schematio Connector Configuration") }

        // Reconnect with new token
        httpUtil?.close()
        httpUtil = HttpUtil(token, apiEndpoint, logger)

        // Reinitialize API service
        schematicsApiService = SchematicsApiService(
            httpUtil = httpUtil,
            platform = platformAdapter,
            cache = schematicCache,
            offlineMode = offlineMode,
            rateLimiter = rateLimiter
        )
    }
}
