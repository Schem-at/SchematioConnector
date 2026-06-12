package io.schemat.schematioConnector.commands

import com.google.gson.Gson
import com.google.gson.JsonObject
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
import io.schemat.schematioConnector.utils.UIModeResolver
import io.schemat.connector.core.validation.ValidationResult
import io.schemat.schematioConnector.utils.WorldEditUtil
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetString
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Downloads a schematic from a quick share link into the player's clipboard.
 *
 * Supports both chat and dialog UI modes:
 * - Chat mode: Prompts for access code/password in chat
 * - Dialog mode: Shows input dialog for access code and optional password
 *
 * Usage: /schematio quickshareget <accessCode|url> [password] [--chat|--dialog]
 *
 * @property plugin The main plugin instance
 */
class QuickShareGetSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val gson = Gson()
    private val QUICKSHARE_ENDPOINT = "/plugin/quick-shares"

    override val name = "quickshareget"
    override val permission = "schematio.quickshare"
    override val description = "Download a schematic from a quick share link"

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

        // Check for API availability
        if (plugin.httpUtil == null) {
            audience.sendMessage(Component.text("Cannot download quick share - not connected to schemat.io").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Configure a community token in config.yml and run /schematio reload").color(NamedTextColor.GRAY))
            return true
        }

        // Check for WorldEdit
        if (!plugin.hasWorldEdit) {
            audience.sendMessage(Component.text("WorldEdit is required for this command.").color(NamedTextColor.RED))
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

        // Parse arguments
        if (args.isEmpty()) {
            audience.sendMessage(Component.text("Usage: /schematio quickshareget <accessCode|url> [password]").color(NamedTextColor.RED))
            return true
        }

        // Validate and extract access code
        val codeValidation = InputValidator.validateQuickShareCode(args[0])
        if (codeValidation is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(codeValidation.message).color(NamedTextColor.RED))
            return true
        }
        val accessCode = codeValidation.getOrNull()!!

        val password = if (args.size > 1) args[1] else null

        // Check rate limit
        val remaining = plugin.rateLimiter.tryAcquire(player.uniqueId)
        if (remaining == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return true
        }

        audience.sendMessage(Component.text("Downloading quick share...").color(NamedTextColor.YELLOW))
        downloadQuickShare(player, accessCode, password)

        return true
    }

    // ===========================================
    // DIALOG MODE
    // ===========================================

    private fun executeDialogMode(player: Player, args: Array<String>): Boolean {
        // If no code provided, show input dialog
        if (args.isEmpty()) {
            showInputDialog(player)
            return true
        }

        val codeValidation = InputValidator.validateQuickShareCode(args[0])
        if (codeValidation is ValidationResult.Invalid) {
            player.audience().sendMessage(Component.text(codeValidation.message).color(NamedTextColor.RED))
            return true
        }
        val accessCode = codeValidation.getOrNull()!!
        val password = if (args.size > 1) args[1] else null

        // Always try to download - if password is required, API will return 401
        // and we'll show the password dialog then
        downloadQuickShare(player, accessCode, password, showPasswordDialogOn401 = true)
        return true
    }

    private fun showInputDialog(player: Player) {
        val title = Component.text("Download Quick Share")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Enter the access code or URL to download").color(NamedTextColor.GRAY)
        ))

        val inputs = mutableListOf<DialogInput>()
        inputs.add(
            DialogInput.text("code", Component.text("Access Code or URL").color(NamedTextColor.WHITE))
                .width(300)
                .initial("")
                .maxLength(200)
                .build()
        )
        inputs.add(
            DialogInput.text("password", Component.text("Password (if required)").color(NamedTextColor.WHITE))
                .width(200)
                .initial("")
                .maxLength(50)
                .build()
        )

        val actionButtons = mutableListOf<ActionButton>()
        // Download button uses command template to get input values
        actionButtons.add(
            ActionButton.builder(Component.text("Download").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .width(150)
                .action(DialogAction.commandTemplate("/schematio quickshareget \$(code) \$(password) --dialog"))
                .build()
        )

        actionButtons.add(
            ActionButton.builder(Component.text("Cancel").color(NamedTextColor.RED))
                .width(100)
                // No action - clicking closes the dialog (default behavior)
                .build()
        )

        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("Quick Share Download"))
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
            plugin.logger.warning("Failed to show quickshareget dialog: ${e.message}")
            player.audience().sendMessage(
                Component.text("Failed to open dialog. Use: /schematio quickshareget <code> [password]").color(NamedTextColor.RED)
            )
        }
    }

    private fun showPasswordDialog(player: Player, accessCode: String) {
        val title = Component.text("Password Required?")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Enter password if the share is protected").color(NamedTextColor.GRAY)
        ))

        val inputs = mutableListOf<DialogInput>()
        inputs.add(
            DialogInput.text("password", Component.text("Password (leave blank if none)").color(NamedTextColor.WHITE))
                .width(200)
                .initial("")
                .maxLength(50)
                .build()
        )

        val actionButtons = mutableListOf<ActionButton>()
        // Download button uses command template
        actionButtons.add(
            ActionButton.builder(Component.text("Download").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .width(150)
                .action(DialogAction.commandTemplate("/schematio quickshareget $accessCode \$(password) --dialog"))
                .build()
        )

        actionButtons.add(
            ActionButton.builder(Component.text("Cancel").color(NamedTextColor.RED))
                .width(100)
                // No action - clicking closes the dialog (default behavior)
                .build()
        )

        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("Quick Share Password"))
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
            // Fall back to attempting download without password
            downloadQuickShare(player, accessCode, null)
        }
    }

    // ===========================================
    // SHARED LOGIC
    // ===========================================

    private fun downloadQuickShare(
        player: Player,
        accessCode: String,
        password: String?,
        showPasswordDialogOn401: Boolean = false
    ) {
        val audience = player.audience()

        // Check rate limit
        val remaining = plugin.rateLimiter.tryAcquire(player.uniqueId)
        if (remaining == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return
        }

        val httpUtil = plugin.httpUtil
        if (httpUtil == null) {
            audience.sendMessage(Component.text("Not connected to API. Set token first.").color(NamedTextColor.RED))
            return
        }

        audience.sendMessage(Component.text("Downloading quick share...").color(NamedTextColor.YELLOW))

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {

                val requestBody = JsonObject().apply {
                    addProperty("player_uuid", player.uniqueId.toString())
                    if (!password.isNullOrBlank()) {
                        addProperty("password", password)
                    }
                }

                val (statusCode, bytes, errorBody) = runBlocking {
                    httpUtil.sendPostRequestForBinary(
                        "$QUICKSHARE_ENDPOINT/$accessCode/download",
                        requestBody.toString()
                    )
                }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    when (statusCode) {
                        200 -> {
                            if (bytes != null) {
                                val clipboard = WorldEditUtil.byteArrayToClipboard(bytes)
                                if (clipboard != null) {
                                    WorldEditUtil.setClipboard(player, clipboard)
                                    audience.sendMessage(Component.text("Quick share downloaded to clipboard!").color(NamedTextColor.GREEN))
                                    audience.sendMessage(Component.text("Use //paste to place it.").color(NamedTextColor.GRAY))
                                } else {
                                    audience.sendMessage(Component.text("Failed to parse schematic data.").color(NamedTextColor.RED))
                                }
                            } else {
                                audience.sendMessage(Component.text("No data received.").color(NamedTextColor.RED))
                            }
                        }
                        401 -> {
                            // Password required - show dialog if in dialog mode, otherwise show chat message
                            if (showPasswordDialogOn401) {
                                showPasswordDialog(player, accessCode)
                            } else {
                                audience.sendMessage(Component.text("This share requires a password.").color(NamedTextColor.RED))
                                audience.sendMessage(Component.text("Usage: /schematio quickshareget $accessCode <password>").color(NamedTextColor.GRAY))
                            }
                        }
                        403 -> {
                            val msg = parseErrorMessage(errorBody) ?: "Access denied"
                            audience.sendMessage(Component.text(msg).color(NamedTextColor.RED))
                        }
                        404 -> {
                            audience.sendMessage(Component.text("Quick share not found.").color(NamedTextColor.RED))
                        }
                        410 -> {
                            val msg = parseErrorMessage(errorBody) ?: "This share has expired or been revoked"
                            audience.sendMessage(Component.text(msg).color(NamedTextColor.RED))
                        }
                        429 -> {
                            val msg = parseErrorMessage(errorBody) ?: "Download limit reached or rate limited"
                            audience.sendMessage(Component.text(msg).color(NamedTextColor.RED))
                        }
                        -1 -> {
                            audience.sendMessage(Component.text("Connection failed.").color(NamedTextColor.RED))
                        }
                        else -> {
                            audience.sendMessage(Component.text("Error downloading share (code: $statusCode)").color(NamedTextColor.RED))
                        }
                    }
                })
            } catch (e: Exception) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    audience.sendMessage(Component.text("Error: ${e.message}").color(NamedTextColor.RED))
                })
            }
        })
    }

    private fun parseErrorMessage(errorBody: String?): String? {
        val json = parseJsonSafe(errorBody) ?: return null
        return json.safeGetString("message") ?: json.safeGetString("error")
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()

        val partial = args.last().lowercase()
        val suggestions = mutableListOf<String>()

        if (args.size == 1) {
            if (partial.isEmpty()) {
                suggestions.add("<access_code>")
            }
            if ("--chat".startsWith(partial)) suggestions.add("--chat")
            if ("--dialog".startsWith(partial)) suggestions.add("--dialog")
        } else {
            if ("--chat".startsWith(partial)) suggestions.add("--chat")
            if ("--dialog".startsWith(partial)) suggestions.add("--dialog")
        }

        return suggestions.filter { it.startsWith(partial, ignoreCase = true) }
    }
}
