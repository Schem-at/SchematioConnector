package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.page.ListGuiContent
import io.schemat.schematioConnector.ui.page.PageBounds
import io.schemat.schematioConnector.ui.page.PageManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * Floating 3D UI for browsing schematics on schemat.io.
 *
 * This command opens an immersive floating display entity interface
 * that allows browsing, searching, and downloading schematics.
 *
 * Usage: /schematio list-gui
 *
 * Requires:
 * - schematio.list permission (base permission for listing)
 * - schematio.tier.floating permission (tier permission for floating UI)
 * - WorldEdit plugin for clipboard operations
 *
 * @property plugin The main plugin instance
 */
class ListGuiSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "list-gui"
    override val permission = "schematio.list"
    override val description = "Browse schematics in an immersive 3D UI"

    /** The tier permission required for this UI variant */
    val tierPermission = "schematio.tier.floating"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Check tier permission
        if (!player.hasPermission(tierPermission)) {
            audience.sendMessage(
                Component.text("You don't have permission for floating UI. Try ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("/schematio list").color(NamedTextColor.YELLOW))
                    .append(Component.text(" or ").color(NamedTextColor.RED))
                    .append(Component.text("/schematio list-inv").color(NamedTextColor.YELLOW))
            )
            return true
        }

        // Check for API availability
        if (plugin.httpUtil == null) {
            audience.sendMessage(Component.text("Cannot browse schematics - not connected to schemat.io").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Configure a community token in config.yml and run /schematio reload").color(NamedTextColor.GRAY))
            return true
        }

        // Calculate UI center position (in front of the player)
        val eyeLocation = player.eyeLocation
        val direction = eyeLocation.direction.normalize()
        val center = eyeLocation.clone().add(direction.multiply(3.0))

        // Create the floating UI (this also closes any existing UI for this player)
        val ui = FloatingUI.create(plugin, player, center)

        // Define page bounds (centered, reasonable size)
        val bounds = PageBounds(
            x = -1.5f,  // Left edge at -1.5 (centered around 0)
            y = 1.0f,   // Top edge at 1.0
            width = 3.0f,
            height = 2.0f
        )

        // Create page manager and show the list content
        val manager = PageManager(plugin, ui, player, bounds)
        val content = ListGuiContent(plugin, player)
        manager.showPage(content)

        audience.sendMessage(Component.text("Opening schematic browser...").color(NamedTextColor.GRAY))
        audience.sendMessage(Component.text("Look away to close the UI.").color(NamedTextColor.GRAY))

        return true
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}
