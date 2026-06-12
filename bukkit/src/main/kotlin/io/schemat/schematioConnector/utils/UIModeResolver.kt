package io.schemat.schematioConnector.utils

import io.schemat.schematioConnector.SchematioConnector
import org.bukkit.entity.Player

/**
 * Resolves the effective UI mode for a player based on the preference hierarchy.
 *
 * Resolution order (highest to lowest priority):
 * 1. Command-line flag (--chat, --dialog) - temporary override
 * 2. User preference (stored in PDC via UserPreferences)
 * 3. Server config default (default-ui-mode in config.yml)
 * 4. Hardcoded fallback (CHAT)
 *
 * Permissions act as a final filter - if a player doesn't have permission
 * for their preferred mode, the resolver falls back to an allowed mode.
 *
 * Permissions:
 * - schematio.tier.chat - allows chat mode
 * - schematio.tier.dialog - allows dialog mode
 *
 * @property plugin The plugin instance
 */
class UIModeResolver(private val plugin: SchematioConnector) {

    companion object {
        const val PERMISSION_CHAT = "schematio.tier.chat"
        const val PERMISSION_DIALOG = "schematio.tier.dialog"

        const val FLAG_CHAT = "--chat"
        const val FLAG_DIALOG = "--dialog"
    }

    /**
     * Gets the server's default UI mode from config.
     *
     * @return The configured default UIMode, or CHAT if not set/invalid
     */
    fun getConfigDefault(): UIMode {
        val configValue = plugin.config.getString("default-ui-mode")
        return UIMode.fromString(configValue) ?: UIMode.CHAT
    }

    /**
     * Checks if the player has permission to use the specified UI mode.
     *
     * @param player The player to check
     * @param mode The UI mode to check permission for
     * @return true if the player can use this mode
     */
    fun hasPermission(player: Player, mode: UIMode): Boolean {
        return when (mode) {
            UIMode.CHAT -> player.hasPermission(PERMISSION_CHAT)
            UIMode.DIALOG -> player.hasPermission(PERMISSION_DIALOG)
        }
    }

    /**
     * Parses command arguments for UI mode flags.
     *
     * @param args The command arguments
     * @return A pair of (flagMode, remainingArgs) where flagMode is the parsed flag or null
     */
    fun parseFlags(args: Array<out String>): Pair<UIMode?, List<String>> {
        var flagMode: UIMode? = null
        val remainingArgs = mutableListOf<String>()

        for (arg in args) {
            when (arg.lowercase()) {
                FLAG_CHAT, "-c" -> flagMode = UIMode.CHAT
                FLAG_DIALOG, "-d" -> flagMode = UIMode.DIALOG
                else -> remainingArgs.add(arg)
            }
        }

        return Pair(flagMode, remainingArgs)
    }

    /**
     * Resolves the effective UI mode for a player, considering all factors.
     *
     * @param player The player to resolve mode for
     * @param flagOverride Optional flag override from command arguments
     * @return The resolved UIMode that the player is allowed to use
     */
    fun resolve(player: Player, flagOverride: UIMode? = null): UIMode {
        // Determine the preferred mode based on priority
        val preferredMode = flagOverride
            ?: plugin.userPreferences.getUIMode(player)
            ?: getConfigDefault()

        // Check if player has permission for their preferred mode
        if (hasPermission(player, preferredMode)) {
            return preferredMode
        }

        // Fall back to the other mode if they have permission
        val fallbackMode = if (preferredMode == UIMode.CHAT) UIMode.DIALOG else UIMode.CHAT
        if (hasPermission(player, fallbackMode)) {
            return fallbackMode
        }

        // Last resort: return CHAT (they'll get a permission error)
        return UIMode.CHAT
    }

    /**
     * Resolves UI mode from command arguments and returns both the mode and cleaned args.
     *
     * This is a convenience method that combines parseFlags() and resolve().
     *
     * @param player The player executing the command
     * @param args The command arguments
     * @return A pair of (resolvedMode, cleanedArgs)
     */
    fun resolveWithArgs(player: Player, args: Array<out String>): Pair<UIMode, Array<String>> {
        val (flagMode, remainingArgs) = parseFlags(args)
        val resolvedMode = resolve(player, flagMode)
        return Pair(resolvedMode, remainingArgs.toTypedArray())
    }

    /**
     * Gets a list of available UI modes for a player based on permissions.
     *
     * @param player The player to check
     * @return List of UIMode values the player can use
     */
    fun getAvailableModes(player: Player): List<UIMode> {
        return UIMode.entries.filter { hasPermission(player, it) }
    }

    /**
     * Checks if the player can use any UI mode at all.
     *
     * @param player The player to check
     * @return true if at least one mode is available
     */
    fun hasAnyUIPermission(player: Player): Boolean {
        return getAvailableModes(player).isNotEmpty()
    }
}
