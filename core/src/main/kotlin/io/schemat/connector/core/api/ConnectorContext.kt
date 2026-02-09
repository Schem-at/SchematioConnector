package io.schemat.connector.core.api

import java.util.UUID

/**
 * Central context that provides access to all platform services.
 * This is the main entry point for core services to access platform functionality.
 *
 * Implemented by the main plugin/mod class on each platform.
 */
interface ConnectorContext {
    /**
     * Platform adapter for scheduling and logging.
     */
    val platform: PlatformAdapter

    /**
     * Configuration adapter for accessing plugin settings.
     */
    val config: ConfigAdapter

    /**
     * WorldEdit adapter for clipboard operations.
     * May be unavailable if WorldEdit is not installed.
     */
    val worldEdit: WorldEditAdapter?

    /**
     * Check if the connector is enabled and ready.
     */
    val isEnabled: Boolean

    /**
     * Get a player adapter by UUID.
     * @param uuid The player's UUID
     * @return Player adapter, or null if player is not online
     */
    fun getPlayer(uuid: UUID): PlayerAdapter?

    /**
     * Get a player adapter by name.
     * @param name The player's name
     * @return Player adapter, or null if player is not online
     */
    fun getPlayerByName(name: String): PlayerAdapter?

    /**
     * Get all online players.
     * @return List of player adapters for all online players
     */
    fun getOnlinePlayers(): List<PlayerAdapter>

    /**
     * Get player-specific persistent storage.
     * @param uuid The player's UUID
     * @return Storage adapter for the player
     */
    fun getPlayerStorage(uuid: UUID): PlayerStorage

    /**
     * Get the configured API endpoint URL.
     * @return API base URL (e.g., "https://schemat.io/api/v1")
     */
    fun getApiEndpoint(): String

    /**
     * Get the configured community token.
     * @return JWT token for API authentication, or null if not configured
     */
    fun getCommunityToken(): String?
}
