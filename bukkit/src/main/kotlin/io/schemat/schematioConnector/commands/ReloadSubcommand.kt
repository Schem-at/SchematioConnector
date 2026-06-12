package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * Reloads the plugin configuration from disk.
 *
 * This command re-reads the config.yml file, re-tests the API connection,
 * and refreshes the available commands based on the new configuration.
 * Useful after editing the configuration file without restarting the server.
 *
 * Usage: /schematio reload
 *
 * Requires:
 * - schematio.admin permission
 *
 * @property plugin The main plugin instance
 */
class ReloadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "reload"
    override val permission = "schematio.admin"
    override val description = "Reload plugin configuration"

    override fun execute(sender: Player, args: Array<out String>): Boolean {
        val audience = sender.audience()
        
        audience.sendMessage(
            Component.text("Reloading SchematioConnector configuration...", NamedTextColor.YELLOW)
        )
        
        val wasConnected = plugin.isApiConnected
        
        // Reload configuration
        val success = plugin.loadConfiguration()
        
        // Refresh commands based on new config
        plugin.refreshCommands()
        
        if (success) {
            audience.sendMessage(
                Component.text("✔ Configuration reloaded successfully!", NamedTextColor.GREEN)
            )
            audience.sendMessage(
                Component.text("  API Status: ", NamedTextColor.GRAY)
                    .append(Component.text("Connected", NamedTextColor.GREEN))
            )
        } else {
            if (plugin.communityToken.isEmpty()) {
                audience.sendMessage(
                    Component.text("⚠ No community token configured", NamedTextColor.YELLOW)
                )
                audience.sendMessage(
                    Component.text("  Set 'community-token' in config.yml", NamedTextColor.GRAY)
                )
            } else {
                audience.sendMessage(
                    Component.text("✘ Could not connect to API", NamedTextColor.RED)
                )
                audience.sendMessage(
                    Component.text("  Check your token and endpoint in config.yml", NamedTextColor.GRAY)
                )
            }
        }
        
        // Notify if connection status changed
        if (wasConnected && !plugin.isApiConnected) {
            audience.sendMessage(
                Component.text("⚠ Lost API connection - some commands disabled", NamedTextColor.YELLOW)
            )
        } else if (!wasConnected && plugin.isApiConnected) {
            audience.sendMessage(
                Component.text("✔ API connected - all commands now available!", NamedTextColor.GREEN)
            )
        }
        
        return true
    }

    override fun tabComplete(sender: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}
