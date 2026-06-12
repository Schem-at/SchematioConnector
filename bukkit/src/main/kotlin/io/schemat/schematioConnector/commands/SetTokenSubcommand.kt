package io.schemat.schematioConnector.commands

import com.auth0.jwt.JWT
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.schemat.schematioConnector.SchematioConnector
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import java.io.File

/**
 * Securely sets the community JWT token from in-game.
 *
 * Supports both chat and dialog modes:
 * - Chat mode: /schematio settoken <jwt_token> (may hit chat length limits)
 * - Dialog mode: /schematio settoken (opens secure input dialog)
 *
 * Security measures:
 * - Only available to server operators
 * - Token value is NEVER logged (even on errors)
 * - No tab completion to prevent token exposure
 * - Token is validated before saving
 * - Immediate config reload after setting
 *
 * Usage:
 * - /schematio settoken - Opens dialog for token input (recommended)
 * - /schematio settoken <jwt_token> - Direct input (may be truncated)
 *
 * @property plugin The main plugin instance
 */
class SetTokenSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "settoken"
    override val permission = "schematio.admin"
    override val description = "Set the community JWT token (OP only)"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Extra security: require OP status even with permission
        if (!player.isOp) {
            audience.sendMessage(Component.text("This command requires operator status.").color(NamedTextColor.RED))
            return true
        }

        // If no args, show dialog for input (recommended for long tokens)
        if (args.isEmpty()) {
            showTokenInputDialog(player)
            return true
        }

        // If args provided, try to use them (may be truncated due to chat limits)
        val token = args.joinToString("")
        processToken(player, token)

        return true
    }

    /**
     * Shows a dialog for secure token input.
     * This is the recommended method as it avoids chat character limits.
     */
    private fun showTokenInputDialog(player: Player) {
        val title = Component.text("Set Community Token")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Paste your JWT token from schemat.io").color(NamedTextColor.GRAY)
        ))
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Get it from: Community Settings -> Plugin Tokens").color(NamedTextColor.DARK_GRAY)
        ))

        val inputs = mutableListOf<DialogInput>()
        inputs.add(
            DialogInput.text("token", Component.text("JWT Token").color(NamedTextColor.WHITE))
                .width(400)
                .initial("")
                .maxLength(2000) // JWT tokens can be quite long
                .build()
        )

        val actionButtons = mutableListOf<ActionButton>()

        // Save button - uses command template to pass the token
        actionButtons.add(
            ActionButton.builder(Component.text("Save Token").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .width(150)
                .action(DialogAction.commandTemplate("/schematio settoken \$(token)"))
                .build()
        )

        actionButtons.add(
            ActionButton.builder(Component.text("Cancel").color(NamedTextColor.GRAY))
                .width(100)
                // No action - clicking closes the dialog
                .build()
        )

        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("Set Community Token"))
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
            plugin.logger.warning("Failed to show token dialog: ${e.message}")
            player.audience().sendMessage(
                Component.text("Failed to open dialog. Use: /schematio settoken <token>").color(NamedTextColor.RED)
            )
            player.audience().sendMessage(
                Component.text("Note: Chat may truncate long tokens.").color(NamedTextColor.GRAY)
            )
        }
    }

    /**
     * Processes and saves a token.
     */
    private fun processToken(player: Player, token: String) {
        val audience = player.audience()

        // Validate token format WITHOUT logging the token
        val validationResult = validateToken(token)
        if (validationResult != null) {
            audience.sendMessage(Component.text(validationResult).color(NamedTextColor.RED))
            return
        }

        // Save token to config
        try {
            saveTokenToConfig(token)
            audience.sendMessage(Component.text("Token saved to config.yml").color(NamedTextColor.GREEN))

            // Reload the plugin to apply the new token
            audience.sendMessage(Component.text("Reloading plugin...").color(NamedTextColor.YELLOW))
            plugin.loadConfiguration()
            plugin.refreshCommands()

            // Report status
            if (plugin.isApiConnected) {
                audience.sendMessage(Component.text("API connection established!").color(NamedTextColor.GREEN))

                // Show token info (without the actual token)
                showTokenInfo(player, token)
            } else {
                audience.sendMessage(Component.text("Token saved but API connection failed.").color(NamedTextColor.YELLOW))
                audience.sendMessage(Component.text("Check server console for details.").color(NamedTextColor.GRAY))
            }
        } catch (e: Exception) {
            // NEVER log the token, even in errors
            audience.sendMessage(Component.text("Failed to save token: ${e.message}").color(NamedTextColor.RED))
        }
    }

    /**
     * Validates token format without logging sensitive data.
     * Returns error message if invalid, null if valid.
     */
    private fun validateToken(token: String): String? {
        if (token.isBlank()) {
            return "Token cannot be empty."
        }

        // Check basic format (JWT has 3 parts)
        if (token.count { it == '.' } != 2) {
            return "Invalid token format. JWT tokens have 3 parts separated by dots."
        }

        // Try to decode (without verifying signature - we just want to check structure)
        return try {
            val decoded = JWT.decode(token)

            // Check required claims
            val type = decoded.claims["type"]?.asString()
            if (type != "community") {
                return "Invalid token type. Expected 'community' token."
            }

            val communityId = decoded.claims["community_id"]?.asString()
            if (communityId.isNullOrEmpty()) {
                return "Token missing community_id claim."
            }

            null // Valid
        } catch (e: Exception) {
            // Don't include exception details that might leak token info
            "Failed to parse token. Ensure you copied the entire token."
        }
    }

    /**
     * Saves token to config.yml without logging it.
     */
    private fun saveTokenToConfig(token: String) {
        val configFile = File(plugin.dataFolder, "config.yml")

        // Read current config
        val lines = if (configFile.exists()) {
            configFile.readLines().toMutableList()
        } else {
            mutableListOf()
        }

        // Find and replace community-token line, or add it
        var found = false
        for (i in lines.indices) {
            val line = lines[i]
            // Match both commented and uncommented versions
            if (line.trimStart().startsWith("community-token:") && !line.trimStart().startsWith("#")) {
                lines[i] = "community-token: \"$token\""
                found = true
                break
            }
        }

        if (!found) {
            // Add after api-endpoint if exists, otherwise at end
            val apiEndpointIndex = lines.indexOfFirst { it.trimStart().startsWith("api-endpoint:") }
            if (apiEndpointIndex >= 0) {
                lines.add(apiEndpointIndex + 1, "")
                lines.add(apiEndpointIndex + 2, "community-token: \"$token\"")
            } else {
                lines.add("")
                lines.add("community-token: \"$token\"")
            }
        }

        // Write back without logging
        configFile.writeText(lines.joinToString("\n"))
    }

    /**
     * Shows decoded token info without exposing the actual token.
     */
    private fun showTokenInfo(player: Player, token: String) {
        val audience = player.audience()

        try {
            val decoded = JWT.decode(token)

            audience.sendMessage(Component.empty())
            audience.sendMessage(Component.text("Token Info:").color(NamedTextColor.GOLD))

            // Show scope
            val scope = decoded.claims["scope"]?.asString() ?: "unknown"
            audience.sendMessage(
                Component.text("  Scope: ").color(NamedTextColor.GRAY)
                    .append(Component.text(scope).color(NamedTextColor.WHITE))
            )

            // Show permissions count
            val permissions = decoded.claims["permissions"]?.asList(String::class.java) ?: emptyList()
            audience.sendMessage(
                Component.text("  Permissions: ").color(NamedTextColor.GRAY)
                    .append(Component.text("${permissions.size} granted").color(NamedTextColor.WHITE))
            )

            // Show special capabilities
            val canManagePasswords = decoded.claims["can_manage_passwords"]?.asBoolean() == true ||
                    permissions.any { it == "can_manage_password" || it == "canManagePasswords" }
            val canAssignTags = decoded.claims["can_assign_tags"]?.asBoolean() == true

            if (canManagePasswords) {
                audience.sendMessage(
                    Component.text("  ").color(NamedTextColor.GRAY)
                        .append(Component.text("\u2714 ").color(NamedTextColor.GREEN))
                        .append(Component.text("Password management enabled").color(NamedTextColor.WHITE))
                )
            }
            if (canAssignTags) {
                audience.sendMessage(
                    Component.text("  ").color(NamedTextColor.GRAY)
                        .append(Component.text("\u2714 ").color(NamedTextColor.GREEN))
                        .append(Component.text("Tag assignment enabled").color(NamedTextColor.WHITE))
                )
            }

            audience.sendMessage(Component.empty())
        } catch (e: Exception) {
            // Silently fail - token info is just nice to have
        }
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        // NEVER provide tab completion for tokens - security risk
        return emptyList()
    }
}
