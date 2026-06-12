package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Displays plugin status and connection information.
 *
 * Shows current configuration state, API connection status,
 * available dependencies (WorldEdit, ProtocolLib, MapEngine),
 * and lists available commands based on permissions.
 *
 * Usage: /schematio info
 *
 * Requires:
 * - schematio.use permission
 *
 * @property plugin The main plugin instance
 */
class InfoSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "info"
    override val permission = "schematio.use"
    override val description = "Show plugin status and connection info"

    override fun execute(sender: Player, args: Array<out String>): Boolean {
        val audience = sender.audience()
        
        // Header
        audience.sendMessage(
            Component.text("═══ SchematioConnector Info ═══")
                .color(NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD)
        )
        
        // Version
        @Suppress("DEPRECATION") // description is deprecated but pluginMeta is Paper-only
        val version = plugin.description.version
        audience.sendMessage(
            Component.text("Version: ", NamedTextColor.GRAY)
                .append(Component.text(version, NamedTextColor.WHITE))
        )
        
        // API Endpoint
        audience.sendMessage(
            Component.text("API Endpoint: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.apiEndpoint.ifEmpty { "Not configured" }, NamedTextColor.WHITE))
        )
        
        // Connection Status
        val statusColor = if (plugin.isApiConnected) NamedTextColor.GREEN else NamedTextColor.RED
        val statusText = if (plugin.isApiConnected) "✔ Connected" else "✘ Not connected"
        audience.sendMessage(
            Component.text("API Status: ", NamedTextColor.GRAY)
                .append(Component.text(statusText, statusColor))
        )
        
        // Token Status
        val tokenStatus = when {
            plugin.communityToken.isEmpty() -> "Not configured" to NamedTextColor.RED
            plugin.communityToken.length < 20 -> "Invalid (too short)" to NamedTextColor.RED
            else -> "Configured" to NamedTextColor.GREEN
        }
        audience.sendMessage(
            Component.text("Token: ", NamedTextColor.GRAY)
                .append(Component.text(tokenStatus.first, tokenStatus.second))
        )
        
        // WorldEdit Status
        val weStatus = if (plugin.hasWorldEdit) "✔ Available" to NamedTextColor.GREEN else "✘ Not found" to NamedTextColor.YELLOW
        audience.sendMessage(
            Component.text("WorldEdit: ", NamedTextColor.GRAY)
                .append(Component.text(weStatus.first, weStatus.second))
        )
        
        // Available Commands
        val availableCommands = mutableListOf("info", "reload")
        if (plugin.isApiConnected && plugin.hasWorldEdit) {
            availableCommands.addAll(listOf("upload", "download", "list"))
        }
        if (plugin.httpUtil?.canManagePasswords() == true) {
            availableCommands.add("setpassword")
        }
        
        audience.sendMessage(
            Component.text("Commands: ", NamedTextColor.GRAY)
                .append(Component.text(availableCommands.joinToString(", "), NamedTextColor.AQUA))
        )
        
        // Help hint if not connected
        if (!plugin.isApiConnected) {
            audience.sendMessage(Component.empty())
            audience.sendMessage(
                Component.text("⚠ ", NamedTextColor.YELLOW)
                    .append(Component.text("Configure your token in ", NamedTextColor.GRAY))
                    .append(Component.text("plugins/SchematioConnector/config.yml", NamedTextColor.WHITE))
            )
            audience.sendMessage(
                Component.text("  Then run ", NamedTextColor.GRAY)
                    .append(Component.text("/schematio reload", NamedTextColor.AQUA))
            )
        }
        
        return true
    }

    override fun tabComplete(sender: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}
