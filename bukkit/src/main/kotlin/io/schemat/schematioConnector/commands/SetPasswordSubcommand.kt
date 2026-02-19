package io.schemat.schematioConnector.commands

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.connector.core.validation.InputValidator
import io.schemat.schematioConnector.utils.UIMode
import io.schemat.schematioConnector.utils.ValidationConstants
import io.schemat.connector.core.validation.ValidationResult
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Sets the player's API password for schemat.io.
 *
 * Supports both chat and dialog UI modes:
 * - Chat mode: /schematio setpassword <new_password>
 * - Dialog mode: /schematio setpassword (opens secure input dialog)
 *
 * Security measures:
 * - No tab completion for password input
 * - Password is validated before sending to API
 * - Uses secure dialog input when available
 *
 * Usage:
 * - /schematio setpassword - Opens dialog for password input (recommended)
 * - /schematio setpassword <password> - Direct input
 * - /schematio setpassword --dialog - Force dialog mode
 * - /schematio setpassword <password> --chat - Force chat mode
 *
 * @property plugin The main plugin instance
 */
class SetPasswordSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "setpassword"
    override val permission = "schematio.admin"
    override val description = "Set your API password for schemat.io"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()
        val resolver = plugin.uiModeResolver

        // Check if player has any UI permission
        if (!resolver.hasAnyUIPermission(player)) {
            audience.sendMessage(
                Component.text("You don't have permission to use any UI mode.").color(NamedTextColor.RED)
            )
            return true
        }

        // Check API connection
        val httpUtil = plugin.httpUtil
        if (httpUtil == null) {
            audience.sendMessage(Component.text("API not connected. Run /schematio reload after configuring token.").color(NamedTextColor.RED))
            return true
        }

        // Check if token has password management permission
        if (!httpUtil.canManagePasswords()) {
            audience.sendMessage(Component.text("This server's token doesn't have permission to manage passwords.").color(NamedTextColor.RED))
            return true
        }

        // Resolve UI mode and clean args
        val (uiMode, cleanedArgs) = resolver.resolveWithArgs(player, args)

        return when (uiMode) {
            UIMode.CHAT -> executeChatMode(player, cleanedArgs)
            UIMode.DIALOG -> executeDialogMode(player, cleanedArgs)
        }
    }

    // ===========================================
    // CHAT MODE
    // ===========================================

    private fun executeChatMode(player: Player, args: Array<String>): Boolean {
        val audience = player.audience()

        if (args.isEmpty()) {
            audience.sendMessage(Component.text("Usage: /schematio setpassword <new_password>").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Or use -d for a secure input dialog").color(NamedTextColor.GRAY))
            return true
        }

        val newPassword = args[0]
        processPassword(player, newPassword)
        return true
    }

    // ===========================================
    // DIALOG MODE
    // ===========================================

    private fun executeDialogMode(player: Player, args: Array<String>): Boolean {
        val audience = player.audience()

        // If args provided from dialog (password + confirm)
        if (args.size >= 2) {
            val password = args[0]
            val confirm = args[1]

            // Validate passwords match without logging either value
            if (password != confirm) {
                audience.sendMessage(Component.text("Passwords do not match").color(NamedTextColor.RED))
                return true
            }

            processPassword(player, password)
            return true
        }

        // Single arg (legacy or direct input) - process directly
        if (args.size == 1) {
            processPassword(player, args[0])
            return true
        }

        // Show dialog for password input
        showPasswordDialog(player)
        return true
    }

    private fun showPasswordDialog(player: Player) {
        val title = Component.text("Set Password")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Set your schemat.io API password").color(NamedTextColor.GRAY)
        ))
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Password must be ${ValidationConstants.MIN_PASSWORD_LENGTH}-${ValidationConstants.MAX_PASSWORD_LENGTH} characters").color(NamedTextColor.DARK_GRAY)
        ))

        val inputs = mutableListOf<DialogInput>()
        inputs.add(
            DialogInput.text("password", Component.text("New Password").color(NamedTextColor.WHITE))
                .width(300)
                .initial("")
                .maxLength(128)
                .build()
        )
        inputs.add(
            DialogInput.text("confirm", Component.text("Confirm Password").color(NamedTextColor.WHITE))
                .width(300)
                .initial("")
                .maxLength(128)
                .build()
        )

        val actionButtons = mutableListOf<ActionButton>()

        // Set Password button - passes both password and confirm for validation
        actionButtons.add(
            ActionButton.builder(Component.text("Set Password").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .width(150)
                .action(DialogAction.commandTemplate("/schematio setpassword \$(password) \$(confirm) --dialog"))
                .build()
        )

        actionButtons.add(
            ActionButton.builder(Component.text("Cancel").color(NamedTextColor.GRAY))
                .width(100)
                // No action - clicking closes the dialog
                .build()
        )

        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("Set API Password"))
            .body(bodyElements)
            .inputs(inputs)
            .canCloseWithEscape(true)
            .build()

        try {
            val dialog = Dialog.create { builder ->
                builder.empty()
                    .base(dialogBase)
                    .type(DialogType.multiAction(actionButtons, null, 1))
            }
            player.showDialog(dialog)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to show password dialog: ${e.message}")
            player.audience().sendMessage(
                Component.text("Failed to open dialog. Use: /schematio setpassword <password>").color(NamedTextColor.RED)
            )
        }
    }

    // ===========================================
    // SHARED LOGIC
    // ===========================================

    private fun processPassword(player: Player, newPassword: String) {
        val audience = player.audience()

        // Validate password
        val passwordResult = InputValidator.validatePassword(newPassword)
        if (passwordResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(passwordResult.message).color(NamedTextColor.RED))
            return
        }

        // Check rate limit
        val rateLimitResult = plugin.rateLimiter.tryAcquire(player.uniqueId)
        if (rateLimitResult == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return
        }

        val httpUtil = plugin.httpUtil ?: return
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
                            audience.sendMessage(Component.text("Password change failed:").color(NamedTextColor.RED))
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
                            // Don't log the password, just log that an error occurred
                            plugin.logger.warning("Password set error occurred")
                            audience.sendMessage(Component.text("Error setting password. Please try again.").color(NamedTextColor.RED))
                        }
                    }
                })
            }
        })
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        // Only suggest flags, never passwords
        if (args.isEmpty()) return emptyList()

        val partial = args.last().lowercase()
        return listOf("-c", "-d", "--chat", "--dialog").filter { it.startsWith(partial, ignoreCase = true) }
    }
}
