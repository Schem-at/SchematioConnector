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
 * Floating 3D UI for creating quick share links with configuration options.
 *
 * This command opens an immersive floating display entity interface
 * that allows configuring share options (expiration, password, etc.)
 * before creating the share link.
 *
 * Usage: /schematio quickshare-gui
 *
 * Requires:
 * - schematio.quickshare permission (base permission)
 * - schematio.tier.floating permission (tier permission for floating UI)
 * - WorldEdit plugin for clipboard operations
 *
 * @property plugin The main plugin instance
 */
class QuickShareGuiSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "quickshare-gui"
    override val permission = "schematio.quickshare"
    override val description = "Create a quick share link with GUI options"

    /** The tier permission required for this UI variant */
    val tierPermission = "schematio.tier.floating"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Check tier permission
        if (!player.hasPermission(tierPermission)) {
            audience.sendMessage(
                Component.text("You don't have permission for floating UI. Try ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("/schematio quickshare").color(NamedTextColor.YELLOW))
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

        // Open configuration UI
        openQuickShareUI(player, schematicBytes)

        return true
    }

    private fun openQuickShareUI(player: Player, schematicBytes: ByteArray) {
        val audience = player.audience()

        // Calculate UI center position (in front of the player)
        val eyeLocation = player.eyeLocation
        val direction = eyeLocation.direction.normalize()
        val center = eyeLocation.clone().add(direction.multiply(3.0))

        // Create the floating UI
        val ui = FloatingUI.create(plugin, player, center)

        // Define page bounds
        val bounds = PageBounds(
            x = -1.5f,
            y = 1.0f,
            width = 3.0f,
            height = 2.0f
        )

        // Create page manager and show the quick share content
        val manager = PageManager(plugin, ui, player, bounds)
        val content = QuickShareContent(plugin, player, schematicBytes)
        manager.showPage(content)

        audience.sendMessage(Component.text("Opening quick share configuration...").color(NamedTextColor.GRAY))
        audience.sendMessage(Component.text("Look away to close the UI.").color(NamedTextColor.GRAY))
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}
