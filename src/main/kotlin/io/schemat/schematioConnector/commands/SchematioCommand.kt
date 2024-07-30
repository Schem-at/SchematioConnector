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

class SchematioCommand(private val plugin: SchematioConnector) : CommandExecutor, TabCompleter {

    private val subcommands = mapOf(
        "upload" to UploadSubcommand(plugin),
        "download" to DownloadSubcommand(plugin),
        "setpassword" to SetPasswordSubcommand(plugin),
        "list" to ListSubcommand(plugin)
    )

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
            sender.audience().sendMessage(Component.text("Unknown subcommand. Use /schematio for help.").color(NamedTextColor.RED))
            return true
        }

        // Check permissions
        val permission = when (args[0].lowercase()) {
            "upload" -> "schematioconnector.upload"
            "download" -> "schematioconnector.download"
            "setpassword" -> "schematioconnector.setpassword"
            "list" -> "schematioconnector.list"
            else -> null
        }

        if (permission != null && !sender.hasPermission(permission)) {
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
            return subcommands.keys
                .filter { it.startsWith(args.getOrNull(0) ?: "", ignoreCase = true) }
                .filter { sender.hasPermission("schematioconnector.$it") }
        }

        val subcommand = subcommands[args[0].lowercase()]
        return subcommand?.tabComplete(sender, args.drop(1).toTypedArray()) ?: emptyList()
    }

    private fun sendHelpMessage(player: Player) {
        val audience = player.audience()
        audience.sendMessage(Component.text("Schematio Commands:").color(NamedTextColor.GOLD))
        if (player.hasPermission("schematioconnector.upload")) {
            audience.sendMessage(Component.text("/schematio upload - Upload your current clipboard").color(NamedTextColor.YELLOW))
        }
        if (player.hasPermission("schematioconnector.download")) {
            audience.sendMessage(Component.text("/schematio download <id> - Download a schematic to your clipboard").color(NamedTextColor.YELLOW))
        }
        if (player.hasPermission("schematioconnector.setpassword")) {
            audience.sendMessage(Component.text("/schematio setpassword <new_password> - Set your password").color(NamedTextColor.YELLOW))
        }
        if (player.hasPermission("schematioconnector.list")) {
            audience.sendMessage(Component.text("/schematio list [page] [search] - List schematics").color(NamedTextColor.YELLOW))
        }
    }
}

// Extension function to get the audience for a CommandSender
fun CommandSender.audience(): Audience = this as? Audience ?: throw IllegalStateException("CommandSender is not an Audience")