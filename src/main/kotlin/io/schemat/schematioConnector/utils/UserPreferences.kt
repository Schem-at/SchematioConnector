package io.schemat.schematioConnector.utils

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * Available UI modes for displaying command output.
 */
enum class UIMode {
    /** Chat-based UI with clickable text components */
    CHAT,
    /** Native Minecraft dialog UI (1.21.7+) */
    DIALOG;

    companion object {
        fun fromString(value: String?): UIMode? {
            return when (value?.lowercase()) {
                "chat" -> CHAT
                "dialog" -> DIALOG
                else -> null
            }
        }
    }
}

/**
 * Manages user preferences stored in PersistentDataContainer.
 *
 * Preferences are stored on the player entity and persist across sessions.
 * The preference hierarchy is:
 * 1. Command flag (--chat, --dialog) - highest priority, temporary
 * 2. User preference (stored in PDC)
 * 3. Server config default
 * 4. Permissions have final say - if user doesn't have permission for a mode, falls back
 *
 * @property plugin The plugin instance for creating namespaced keys
 */
class UserPreferences(private val plugin: Plugin) {

    private val uiModeKey = NamespacedKey(plugin, "ui_mode")

    /**
     * Gets the user's preferred UI mode from PersistentDataContainer.
     *
     * @param player The player to get preference for
     * @return The stored UIMode preference, or null if not set
     */
    fun getUIMode(player: Player): UIMode? {
        val pdc = player.persistentDataContainer
        val stored = pdc.get(uiModeKey, PersistentDataType.STRING)
        return UIMode.fromString(stored)
    }

    /**
     * Sets the user's preferred UI mode in PersistentDataContainer.
     *
     * @param player The player to set preference for
     * @param mode The UIMode to store
     */
    fun setUIMode(player: Player, mode: UIMode) {
        val pdc = player.persistentDataContainer
        pdc.set(uiModeKey, PersistentDataType.STRING, mode.name.lowercase())
    }

    /**
     * Clears the user's UI mode preference, reverting to server default.
     *
     * @param player The player to clear preference for
     */
    fun clearUIMode(player: Player) {
        val pdc = player.persistentDataContainer
        pdc.remove(uiModeKey)
    }

    /**
     * Checks if the player has a UI mode preference set.
     *
     * @param player The player to check
     * @return true if a preference is set, false otherwise
     */
    fun hasUIMode(player: Player): Boolean {
        return player.persistentDataContainer.has(uiModeKey, PersistentDataType.STRING)
    }
}
