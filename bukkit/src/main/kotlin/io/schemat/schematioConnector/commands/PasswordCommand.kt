package io.schemat.schematioConnector.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class PasswordCommand(
    private val setPassword: SetPasswordSubcommand
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.audience().sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission(setPassword.permission)) {
            sender.audience().sendMessage(Component.text("You don't have permission to use this command.").color(NamedTextColor.RED))
            return true
        }
        return setPassword.execute(sender, args)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()
        if (!sender.hasPermission(setPassword.permission)) return emptyList()
        return setPassword.tabComplete(sender, args)
    }
}
