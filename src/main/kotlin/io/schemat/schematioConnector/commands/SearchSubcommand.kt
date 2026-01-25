package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * Shorthand command for searching schematics on schemat.io.
 *
 * This is essentially an alias for `/schematio list <search>` that requires
 * a search term. It delegates all functionality to ListSubcommand.
 *
 * Usage: /schematio search <term> [page] [--chat|--dialog] [options]
 *
 * Examples:
 * - /schematio search castle - Search for "castle"
 * - /schematio search medieval house 2 - Page 2 of "medieval house" results
 * - /schematio search tower --dialog - Search with dialog UI
 *
 * @property plugin The main plugin instance
 */
class SearchSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val listSubcommand = ListSubcommand(plugin)

    override val name = "search"
    override val permission = "schematio.list"
    override val description = "Search for schematics"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Search requires at least one argument (the search term)
        if (args.isEmpty()) {
            audience.sendMessage(
                Component.text("Usage: /schematio search <term> [page] [--chat|--dialog]").color(NamedTextColor.RED)
            )
            audience.sendMessage(
                Component.text("Example: /schematio search castle").color(NamedTextColor.GRAY)
            )
            return true
        }

        // Delegate to the list subcommand with all args
        return listSubcommand.execute(player, args)
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        // Delegate tab completion to list subcommand
        return listSubcommand.tabComplete(player, args)
    }
}
