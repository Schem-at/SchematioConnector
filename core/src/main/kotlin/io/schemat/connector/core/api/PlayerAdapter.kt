package io.schemat.connector.core.api

import java.util.UUID

/**
 * Platform-agnostic player representation.
 * Wraps the platform-specific player implementation to provide
 * a common interface for player interactions.
 */
interface PlayerAdapter {
    /**
     * Unique player identifier (same across all platforms).
     */
    val uuid: UUID

    /**
     * Player display name.
     */
    val name: String

    /**
     * Send a plain text message to the player.
     * @param message The message text
     */
    fun sendMessage(message: String)

    /**
     * Send a formatted rich message to the player.
     * Supports colors, click actions, and hover text.
     * @param message The rich message to send
     */
    fun sendRichMessage(message: RichMessage)

    /**
     * Check if the player has a specific permission.
     * @param permission The permission node to check
     * @return true if the player has the permission
     */
    fun hasPermission(permission: String): Boolean

    /**
     * Check if the player is currently online.
     * @return true if online
     */
    fun isOnline(): Boolean

    /**
     * Check if the player is a server operator.
     * @return true if operator
     */
    fun isOp(): Boolean
}
