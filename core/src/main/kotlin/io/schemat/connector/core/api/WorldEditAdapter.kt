package io.schemat.connector.core.api

import java.util.UUID

/**
 * Abstraction for WorldEdit/schematic operations.
 * Handles clipboard access and schematic format conversion.
 *
 * Bukkit: Uses WorldEdit-Bukkit API
 * Fabric: Uses WorldEdit-Fabric or alternative schematic libraries
 */
interface WorldEditAdapter {
    /**
     * Check if WorldEdit (or equivalent) is available on this platform.
     */
    val isAvailable: Boolean

    /**
     * Get the player's clipboard contents as a byte array in .schem format.
     * @param playerUuid The player's UUID
     * @return Clipboard data as bytes, or null if no clipboard
     */
    fun getClipboardBytes(playerUuid: UUID): ByteArray?

    /**
     * Set a player's clipboard from a byte array.
     * Supports multiple formats: .schem, .schematic, .mcedit
     * @param playerUuid The player's UUID
     * @param data The schematic data bytes
     * @param format Optional format hint (e.g., "schem", "schematic")
     * @return true if clipboard was set successfully
     */
    fun setClipboardFromBytes(playerUuid: UUID, data: ByteArray, format: String? = null): Boolean

    /**
     * Check if a player has a clipboard.
     * @param playerUuid The player's UUID
     * @return true if player has a non-empty clipboard
     */
    fun hasClipboard(playerUuid: UUID): Boolean

    /**
     * Get the clipboard dimensions for a player.
     * @param playerUuid The player's UUID
     * @return Dimensions as a triple (width, height, length), or null if no clipboard
     */
    fun getClipboardDimensions(playerUuid: UUID): Triple<Int, Int, Int>?

    /**
     * Get the clipboard block count for a player.
     * @param playerUuid The player's UUID
     * @return Number of blocks in clipboard, or null if no clipboard
     */
    fun getClipboardBlockCount(playerUuid: UUID): Int?
}
