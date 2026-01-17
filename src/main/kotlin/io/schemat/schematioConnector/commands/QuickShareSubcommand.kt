package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.page.PageBounds
import io.schemat.schematioConnector.ui.page.PageManager
import io.schemat.schematioConnector.ui.page.QuickShareContent
import io.schemat.schematioConnector.utils.WorldEditUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * Chat-based quick share command for creating share links.
 *
 * This is the default quickshare command that creates a share link
 * immediately with default settings (no configuration UI).
 *
 * Usage: /schematio quickshare
 *
 * Requires:
 * - schematio.quickshare permission (base permission)
 * - schematio.tier.chat permission (tier permission for chat UI)
 * - WorldEdit plugin for clipboard operations
 *
 * @property plugin The main plugin instance
 */
class QuickShareSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "quickshare"
    override val permission = "schematio.quickshare"
    override val description = "Create a quick share link (instant)"

    /** The tier permission required for this UI variant */
    val tierPermission = "schematio.tier.chat"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Check tier permission
        if (!player.hasPermission(tierPermission)) {
            audience.sendMessage(
                Component.text("You don't have permission for chat UI.").color(NamedTextColor.RED)
            )
            return true
        }

        // Check for API availability
        if (plugin.httpUtil == null) {
            audience.sendMessage(Component.text("Cannot create quick share - not connected to schemat.io").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Configure a community token in config.yml and run /schematio reload").color(NamedTextColor.GRAY))
            return true
        }

        // Get clipboard
        val clipboard = WorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            audience.sendMessage(Component.text("No clipboard found. Copy something with WorldEdit first!").color(NamedTextColor.RED))
            return true
        }

        // Convert clipboard to bytes
        val schematicBytes = WorldEditUtil.clipboardToByteArray(clipboard)
        if (schematicBytes == null) {
            audience.sendMessage(Component.text("Could not convert clipboard to schematic format.").color(NamedTextColor.RED))
            return true
        }

        // Direct mode: create share immediately with defaults
        QuickShareContent.createQuickShareDirect(plugin, player, schematicBytes)

        // Suggest GUI alternative if player has permission
        if (player.hasPermission("schematio.tier.floating")) {
            audience.sendMessage(
                Component.text("Tip: Use ").color(NamedTextColor.DARK_GRAY)
                    .append(Component.text("/schematio quickshare-gui").color(NamedTextColor.AQUA))
                    .append(Component.text(" for more options").color(NamedTextColor.DARK_GRAY))
            )
        }

        return true
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}
