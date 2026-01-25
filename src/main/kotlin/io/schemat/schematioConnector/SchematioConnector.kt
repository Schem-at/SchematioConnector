package io.schemat.schematioConnector

import io.schemat.schematioConnector.commands.*
import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.utils.HttpUtil
import io.schemat.schematioConnector.utils.MapEngineHandler
import io.schemat.schematioConnector.utils.MapImageCache
import io.schemat.schematioConnector.utils.OfflineMode
import io.schemat.schematioConnector.utils.RateLimiter
import io.schemat.schematioConnector.utils.SchematicCache
import io.schemat.schematioConnector.utils.SchematicPreviewUI
import io.schemat.schematioConnector.utils.SchematicsApiService
import io.schemat.schematioConnector.utils.UIModeResolver
import io.schemat.schematioConnector.utils.UserPreferences
import io.schemat.schematioConnector.utils.ValidationConstants
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Main plugin class for SchematioConnector.
 *
 * SchematioConnector is a Bukkit/Paper plugin that integrates with schemat.io
 * to allow players to upload, download, browse, and share WorldEdit schematics
 * directly from in-game.
 *
 * ## Features
 *
 * - Upload WorldEdit clipboard contents to schemat.io
 * - Download schematics by ID into clipboard
 * - Browse schematics with multiple UI tiers (chat, inventory, floating 3D GUI)
 * - QuickShare for instant temporary share links
 * - Schematic previews with MapEngine integration (optional)
 *
 * ## Dependencies
 *
 * - **Required**: WorldEdit (for clipboard operations)
 * - **Optional**: ProtocolLib (for enhanced authentication)
 * - **Optional**: MapEngine (for schematic preview rendering)
 *
 * ## Configuration
 *
 * The plugin requires a community token from schemat.io to function.
 * Configure in `plugins/SchematioConnector/config.yml`:
 * ```yaml
 * community-token: "your_jwt_token_here"
 * api-endpoint: "https://schemat.io/api/v1"
 * ```
 *
 * @see HttpUtil for API communication
 * @see FloatingUI for 3D UI system
 */
class SchematioConnector : JavaPlugin(), Listener {

    var httpUtil: HttpUtil? = null
        private set
    private var _hasWorldEdit = false

    // ProtocolLib handler (loaded dynamically)
    private var protocolLibHandler: Any? = null

    // MapEngine handler (optional - for schematic previews)
    var mapEngineHandler: MapEngineHandler? = null
        private set

    // Schematic preview UI (combines MapEngine + Display entities)
    var previewUI: SchematicPreviewUI? = null
        private set

    // Rate limiter for API requests
    lateinit var rateLimiter: RateLimiter
        private set

    // Rate limit cleanup task
    private var rateLimitCleanupTask: Int = -1

    // Schematic cache for API responses
    lateinit var schematicCache: SchematicCache
        private set

    // Cache cleanup task
    private var cacheCleanupTask: Int = -1

    // Offline mode manager
    lateinit var offlineMode: OfflineMode
        private set

    // Centralized API service for fetching schematics
    lateinit var schematicsApiService: SchematicsApiService
        private set

    // User preferences (stored in PersistentDataContainer)
    lateinit var userPreferences: UserPreferences
        private set

    // UI mode resolver (handles config → user pref → flag → permissions hierarchy)
    lateinit var uiModeResolver: UIModeResolver
        private set

    // Track plugin state
    var hasProtocolLib = false
        private set
    var hasMapEngine = false
        private set
    var isApiConnected = false
        private set
    var apiEndpoint: String = ""
        private set
    var communityToken: String = ""
        private set

    val hasWorldEdit: Boolean
        get() = _hasWorldEdit

    /**
     * Returns the base URL for the schemat.io website derived from the API endpoint.
     * For example, if apiEndpoint is "https://schemat.io/api/v1", this returns "https://schemat.io".
     * If apiEndpoint is "http://localhost/api/v1", this returns "http://localhost".
     */
    val baseUrl: String
        get() {
            // Remove /api/v1 suffix to get the base URL
            return apiEndpoint.replace(Regex("/api/v\\d+$"), "")
        }

    companion object {
        lateinit var instance: SchematioConnector
            private set
    }

    override fun onEnable() {
        instance = this
        saveDefaultConfig()

        // Initialize rate limiter with config values
        initializeRateLimiter()

        // Initialize cache and offline mode
        initializeCacheAndOfflineMode()

        // Initialize API service
        schematicsApiService = SchematicsApiService(this)

        // Initialize user preferences and UI mode resolver
        userPreferences = UserPreferences(this)
        uiModeResolver = UIModeResolver(this)

        // Load configuration
        loadConfiguration()
        
        // Check for WorldEdit
        _hasWorldEdit = server.pluginManager.isPluginEnabled("WorldEdit")
        if (_hasWorldEdit) {
            logger.info("WorldEdit integration enabled")
        } else {
            logger.warning("WorldEdit not found - upload/download commands disabled")
        }
        
        // Register event listeners (for cleanup on player quit, etc.)
        server.pluginManager.registerEvents(this, this)
        
        // Check for ProtocolLib (optional) - load handler dynamically
        hasProtocolLib = server.pluginManager.isPluginEnabled("ProtocolLib")
        if (hasProtocolLib) {
            try {
                // Load the handler class only if ProtocolLib is available
                val handlerClass = Class.forName("io.schemat.schematioConnector.ProtocolLibHandler")
                val constructor = handlerClass.getConstructor(JavaPlugin::class.java, java.util.logging.Logger::class.java)
                protocolLibHandler = constructor.newInstance(this, logger)
                
                // Call initialize method
                val initMethod = handlerClass.getMethod("initialize")
                initMethod.invoke(protocolLibHandler)
                
                logger.info("ProtocolLib found - advanced auth features enabled")
            } catch (e: Exception) {
                logger.warning("Failed to initialize ProtocolLib handler: ${e.message}")
                protocolLibHandler = null
            }
        }
        
        // Check for MapEngine (optional) - for schematic preview rendering
        hasMapEngine = server.pluginManager.isPluginEnabled("MapEngine")
        if (hasMapEngine) {
            try {
                mapEngineHandler = MapEngineHandler(this)
                logger.info("MapEngine found - schematic previews enabled")
            } catch (e: Exception) {
                logger.warning("Failed to initialize MapEngine handler: ${e.message}")
                mapEngineHandler = null
                hasMapEngine = false
            }
        }
        
        // Initialize preview UI (works with or without MapEngine)
        try {
            previewUI = SchematicPreviewUI(this)
            logger.info("Preview UI initialized")
        } catch (e: Exception) {
            logger.warning("Failed to initialize preview UI: ${e.message}")
        }
        
        // Set up commands (always, even if API isn't connected)
        setupCommands()
        
        logger.info("SchematioConnector enabled!")
    }
    
    /**
     * Initialize the rate limiter with config values.
     */
    private fun initializeRateLimiter() {
        reloadConfig()
        val maxRequests = config.getInt("rate-limit-requests", ValidationConstants.DEFAULT_RATE_LIMIT_REQUESTS)
        val windowSeconds = config.getInt("rate-limit-window-seconds", ValidationConstants.DEFAULT_RATE_LIMIT_WINDOW_SECONDS)

        // Create rate limiter (or recreate if reloading)
        if (::rateLimiter.isInitialized) {
            rateLimiter.clear()
        }
        rateLimiter = RateLimiter(
            maxRequests = if (maxRequests > 0) maxRequests else ValidationConstants.DEFAULT_RATE_LIMIT_REQUESTS,
            windowMs = windowSeconds * 1000L
        )

        // Cancel existing cleanup task if any
        if (rateLimitCleanupTask != -1) {
            server.scheduler.cancelTask(rateLimitCleanupTask)
        }

        // Schedule cleanup every 5 minutes (6000 ticks)
        rateLimitCleanupTask = server.scheduler.runTaskTimer(this, Runnable {
            rateLimiter.cleanup()
        }, 6000L, 6000L).taskId

        if (maxRequests > 0) {
            logger.info("Rate limiting enabled: $maxRequests requests per ${windowSeconds}s")
        } else {
            logger.info("Rate limiting disabled")
        }
    }

    /**
     * Initialize the schematic cache and offline mode manager.
     */
    private fun initializeCacheAndOfflineMode() {
        reloadConfig()

        // Get cache TTL from config (default 5 minutes)
        val cacheTtlSeconds = config.getInt("cache-ttl-seconds", 300)
        val cacheTtlMs = (cacheTtlSeconds * 1000L).coerceIn(
            SchematicCache.MIN_TTL_MS,
            SchematicCache.MAX_TTL_MS
        )

        // Get cache enabled setting
        val cacheEnabled = config.getBoolean("cache-enabled", true)

        // Create or recreate cache
        if (::schematicCache.isInitialized) {
            schematicCache.clear()
        }
        schematicCache = SchematicCache(ttlMs = if (cacheEnabled) cacheTtlMs else 0)

        // Cancel existing cleanup task if any
        if (cacheCleanupTask != -1) {
            server.scheduler.cancelTask(cacheCleanupTask)
        }

        // Schedule cache cleanup every 5 minutes (6000 ticks)
        if (cacheEnabled) {
            cacheCleanupTask = server.scheduler.runTaskTimer(this, Runnable {
                val removed = schematicCache.cleanup()
                if (removed > 0) {
                    logger.fine("Cache cleanup: removed $removed expired entries")
                }
            }, 6000L, 6000L).taskId

            logger.info("Caching enabled: ${cacheTtlSeconds}s TTL")
        } else {
            logger.info("Caching disabled")
        }

        // Initialize offline mode manager
        if (!::offlineMode.isInitialized) {
            offlineMode = OfflineMode()
        } else {
            offlineMode.reset()
        }
    }

    /**
     * Load configuration and attempt API connection
     */
    fun loadConfiguration(): Boolean {
        reloadConfig()
        val config = config

        // Get token (try new name first, then legacy)
        communityToken = config.getString("community-token")
            ?: config.getString("api-key")
            ?: ""

        apiEndpoint = config.getString("api-endpoint") ?: "https://schemat.io/api/v1"

        // Validate token format (JWT has 3 parts separated by dots)
        if (communityToken.isNotEmpty() && communityToken.count { it == '.' } != 2) {
            logger.warning("Invalid token format - JWT tokens should have 3 parts separated by dots")
            logger.warning("   Get a valid token from: Community Settings -> Plugin Tokens")
            isApiConnected = false
            httpUtil = null
            return false
        }

        // Validate endpoint URL format
        if (apiEndpoint.isNotEmpty() && !apiEndpoint.startsWith("http://") && !apiEndpoint.startsWith("https://")) {
            logger.warning("Invalid API endpoint - must start with http:// or https://")
            isApiConnected = false
            httpUtil = null
            return false
        }

        // Normalize endpoint (remove trailing slash)
        apiEndpoint = apiEndpoint.trimEnd('/')

        if (communityToken.isEmpty()) {
            logger.warning("No community token configured!")
            logger.warning("   Set 'community-token' in config.yml to enable API features")
            logger.warning("   Get a token from: Community Settings -> Plugin Tokens")
            isApiConnected = false
            httpUtil = null
            return false
        }

        if (apiEndpoint.isEmpty()) {
            logger.warning("No API endpoint configured!")
            isApiConnected = false
            httpUtil = null
            return false
        }

        // Close old client if reloading
        httpUtil?.close()
        httpUtil = HttpUtil(communityToken, apiEndpoint, logger)

        // Re-initialize rate limiter with any updated config values
        initializeRateLimiter()

        // Test API connection asynchronously to avoid blocking startup
        server.scheduler.runTaskAsynchronously(this, Runnable {
            offlineMode.recordAttempt()
            val connected = runBlocking { httpUtil!!.checkConnection() }

            server.scheduler.runTask(this, Runnable {
                isApiConnected = connected
                if (isApiConnected) {
                    offlineMode.recordSuccess()
                    logger.info("Connected to schemat.io API at $apiEndpoint")
                } else {
                    val enteredOffline = offlineMode.recordFailure()
                    if (enteredOffline) {
                        logger.warning("Entered offline mode - API unreachable")
                    }
                    logger.warning("Could not connect to schemat.io API")
                    logger.warning("   Check your token and endpoint in config.yml")
                    if (schematicCache.hasData()) {
                        logger.info("Cached data available for offline browsing")
                    }
                }
            })
        })

        // Return true optimistically - actual connection status set async
        return true
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        // Only run if ProtocolLib handler is available
        if (protocolLibHandler == null) return
        
        try {
            val handlerClass = protocolLibHandler!!.javaClass
            val getHashMethod = handlerClass.getMethod("getRecentAuthHash")
            val hash = getHashMethod.invoke(protocolLibHandler) as? String
            
            if (hash != null) {
                logger.info("🔗 https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${event.player.name}&serverId=$hash")
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
    
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        // Clean up any floating UIs for this player
        FloatingUI.closeForPlayer(event.player)
        mapEngineHandler?.closePreview(event.player)
        // Clean up rate limiter data for this player
        rateLimiter.removePlayer(event.player.uniqueId)
    }

    private fun setupCommands() {
        val subcommands = mutableListOf<Subcommand>()

        // Always available commands
        subcommands.add(InfoSubcommand(this))
        subcommands.add(ReloadSubcommand(this))
        subcommands.add(SettingsSubcommand(this))  // User UI preferences

        // Only add SetPassword if the token has permission
        if (httpUtil?.canManagePasswords() == true) {
            subcommands.add(SetPasswordSubcommand(this))
            logger.info("Password management enabled (token has canManagePasswords)")
        }

        // WorldEdit-dependent commands - always register but they check API at runtime
        if (hasWorldEdit) {
            // Core commands with unified chat/dialog support
            subcommands.add(UploadSubcommand(this))
            subcommands.add(DownloadSubcommand(this))
            subcommands.add(ListSubcommand(this))            // Unified list with chat/dialog support
            subcommands.add(SearchSubcommand(this))          // Shorthand for list with search
            subcommands.add(QuickShareSubcommand(this))      // Unified quickshare with chat/dialog
            subcommands.add(QuickShareGetSubcommand(this))   // Unified quickshareget with chat/dialog

            // DEPRECATED: Legacy UI-specific commands (kept for backwards compatibility)
            // These commands still work but are hidden from tab completion
            // Users should use --chat or --dialog flags or /schematio settings instead
            subcommands.add(ListInvSubcommand(this))         // DEPRECATED: Use 'list' with settings
            subcommands.add(ListGuiSubcommand(this))         // DEPRECATED: Use 'list' with settings
            subcommands.add(ListDialogSubcommand(this))      // DEPRECATED: Use 'list --dialog'
            subcommands.add(QuickShareGuiSubcommand(this))   // DEPRECATED: Use 'quickshare' with settings
        }

        val schematioCommand = SchematioCommand(this, subcommands.associateBy { it.name })
        getCommand("schematio")?.let {
            it.setExecutor(schematioCommand)
            it.tabCompleter = schematioCommand
        }
    }
    
    /**
     * Re-initialize commands after config reload
     */
    fun refreshCommands() {
        setupCommands()
    }

    override fun onDisable() {
        // Clean up ProtocolLib handler
        if (protocolLibHandler != null) {
            try {
                val clearMethod = protocolLibHandler!!.javaClass.getMethod("clear")
                clearMethod.invoke(protocolLibHandler)
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Cancel rate limiter cleanup task
        if (rateLimitCleanupTask != -1) {
            server.scheduler.cancelTask(rateLimitCleanupTask)
        }

        // Cancel cache cleanup task
        if (cacheCleanupTask != -1) {
            server.scheduler.cancelTask(cacheCleanupTask)
        }

        // Clear rate limiter
        if (::rateLimiter.isInitialized) {
            rateLimiter.clear()
        }

        // Clear schematic cache
        if (::schematicCache.isInitialized) {
            schematicCache.clear()
        }

        // Close all floating UIs first
        FloatingUI.closeAll()

        // Clean up preview UI
        previewUI?.shutdown()

        // Clean up MapEngine handler
        mapEngineHandler?.shutdown()

        // Shutdown map cache executor
        MapImageCache.shutdown()

        // Close HTTP client
        httpUtil?.close()
    }
}
