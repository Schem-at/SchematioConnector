package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class SchematioCommand(
    private val plugin: SchematioConnector,
    private val subcommands: Map<String, Subcommand> // Now receives the map of available commands
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.audience().sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sendHelpMessage(sender)
            return true
        }

        val subcommand = subcommands[args[0].lowercase()]
        if (subcommand == null) {
            // If WorldEdit is missing, provide a more helpful message for those specific commands
            val missingCommand = args[0].lowercase()
            if (missingCommand in listOf("upload", "download", "list") && !plugin.hasWorldEdit) {
                sender.audience().sendMessage(
                    Component.text("This command requires WorldEdit, which was not found on the server.")
                        .color(NamedTextColor.RED)
                )
            } else {
                sender.audience().sendMessage(
                    Component.text("Unknown subcommand. Use /schematio for help.").color(NamedTextColor.RED)
                )
            }
            return true
        }

        // Check permissions
        if (!sender.hasPermission(subcommand.permission)) {
            sender.audience().sendMessage(Component.text("You don't have permission to use this command.").color(NamedTextColor.RED))
            return true
        }

        return subcommand.execute(sender, args.drop(1).toTypedArray())
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) {
            return emptyList()
        }

        if (args.size <= 1) {
            // Suggest only the subcommands the player has permission for
            return subcommands.keys
                .filter { sender.hasPermission(subcommands[it]!!.permission) }
                .filter { it.startsWith(args.getOrNull(0) ?: "", ignoreCase = true) }
        }

        val subcommand = subcommands[args[0].lowercase()]
        if (subcommand != null && sender.hasPermission(subcommand.permission)) {
            return subcommand.tabComplete(sender, args.drop(1).toTypedArray())
        }

        return emptyList()
    }

    private fun sendHelpMessage(player: Player) {
        val audience = player.audience()
        audience.sendMessage(Component.text("Schematio Commands:").color(NamedTextColor.GOLD))

        // Dynamically generate help message from available commands
        subcommands.values
            .filter { player.hasPermission(it.permission) }
            .sortedBy { it.name }
            .forEach { subcommand ->
                val helpLine = Component.text("/schematio ${subcommand.name}", NamedTextColor.YELLOW)
                    .append(Component.text(" - ${subcommand.description}", NamedTextColor.GRAY))
                audience.sendMessage(helpLine)
            }
    }
}

// Extension function to get the audience for a CommandSender
fun CommandSender.audience(): Audience = this as? Audience ?: throw IllegalStateException("CommandSender is not an Audience")