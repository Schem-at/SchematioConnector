package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.ValidationResult
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

class SetPasswordSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "setpassword"
    override val permission = "schematio.admin"
    override val description = "Set your API password for schemat.io (legacy)"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        if (args.size != 1) {
            audience.sendMessage(Component.text("Usage: /schematio setpassword <new_password>").color(NamedTextColor.RED))
            return false
        }

        val newPassword = args[0]

        // Validate password
        val passwordResult = InputValidator.validatePassword(newPassword)
        if (passwordResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(passwordResult.message).color(NamedTextColor.RED))
            return false
        }

        // Check rate limit
        val rateLimitResult = plugin.rateLimiter.tryAcquire(player.uniqueId)
        if (rateLimitResult == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return true
        }

        // Check API connection before starting async task
        val httpUtil = plugin.httpUtil
        if (httpUtil == null) {
            audience.sendMessage(Component.text("API not connected. Run /schematio reload after configuring token.").color(NamedTextColor.RED))
            return true
        }

        audience.sendMessage(Component.text("Setting your new password...").color(NamedTextColor.YELLOW))

        val playerUuid = player.uniqueId.toString()

        // Run the password set asynchronously to avoid blocking the main thread
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val (statusCode, messages) = runBlocking {
                    httpUtil.setPassword(playerUuid, newPassword)
                }

                // Switch back to main thread for Bukkit API calls
                plugin.server.scheduler.runTask(plugin, Runnable {
                    when (statusCode) {
                        200 -> audience.sendMessage(Component.text(messages.firstOrNull() ?: "Password updated successfully!").color(NamedTextColor.GREEN))
                        403 -> audience.sendMessage(Component.text(messages.firstOrNull() ?: "Permission denied").color(NamedTextColor.RED))
                        422 -> {
                            audience.sendMessage(Component.text("Password change failed due to the following errors:").color(NamedTextColor.RED))
                            messages.forEach { errorMessage ->
                                audience.sendMessage(Component.text("- $errorMessage").color(NamedTextColor.RED))
                            }
                        }
                        else -> audience.sendMessage(Component.text(messages.firstOrNull() ?: "An error occurred").color(NamedTextColor.RED))
                    }
                })
            } catch (e: Exception) {
                // Handle errors on async thread, then notify on main thread
                plugin.server.scheduler.runTask(plugin, Runnable {
                    val msg = e.message ?: "Unknown error"
                    when {
                        msg.contains("Connection refused") || msg.contains("timed out") -> {
                            audience.sendMessage(Component.text("schemat.io API is currently unavailable").color(NamedTextColor.RED))
                            audience.sendMessage(Component.text("Please try again later").color(NamedTextColor.GRAY))
                        }
                        else -> {
                            plugin.logger.warning("Password set error: $msg")
                            audience.sendMessage(Component.text("Error setting password. Please try again.").color(NamedTextColor.RED))
                        }
                    }
                })
            }
        })

        return true
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList() // No tab completion for password input
    }
}