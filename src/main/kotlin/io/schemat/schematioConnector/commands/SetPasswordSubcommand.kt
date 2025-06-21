package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

class SetPasswordSubcommand(private val plugin: SchematioConnector) : Subcommand {

    // Add the required properties from the interface
    override val name = "setpassword"
    override val permission = "schematioconnector.setpassword"
    override val description = "Set your API password for schemat.io"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        // ... (rest of your code is correct)
        val audience = player.audience()

        if (args.size != 1) {
            audience.sendMessage(Component.text("Usage: /schematio setpassword <new_password>").color(NamedTextColor.RED))
            return false
        }

        val newPassword = args[0]

        audience.sendMessage(Component.text("Setting your new password...").color(NamedTextColor.YELLOW))

        runBlocking {
            val (statusCode, messages) = plugin.httpUtil.setPassword(player.uniqueId.toString(), newPassword)
            when (statusCode) {
                200 -> audience.sendMessage(Component.text(messages.first()).color(NamedTextColor.GREEN))
                403 -> audience.sendMessage(Component.text(messages.first()).color(NamedTextColor.RED))
                422 -> {
                    audience.sendMessage(Component.text("Password change failed due to the following errors:").color(NamedTextColor.RED))
                    messages.forEach { errorMessage ->
                        audience.sendMessage(Component.text("- $errorMessage").color(NamedTextColor.RED))
                    }
                }
                else -> audience.sendMessage(Component.text(messages.first()).color(NamedTextColor.RED))
            }
        }

        return true
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList() // No tab completion for password input
    }
}