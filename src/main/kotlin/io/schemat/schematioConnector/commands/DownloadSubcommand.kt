package io.schemat.schematioConnector.commands

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.ProgressBarUtil
import io.schemat.schematioConnector.utils.UIMode
import io.schemat.schematioConnector.utils.ValidationResult
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.apache.http.util.EntityUtils
import org.bukkit.entity.Player
import java.io.EOFException

/**
 * Downloads a schematic from schemat.io and loads it into the player's WorldEdit clipboard.
 *
 * Supports both chat and dialog UI modes:
 * - Chat mode: Standard download with progress bar and chat output
 * - Dialog mode: Shows download input dialog and success dialog
 *
 * Usage: /schematio download <schematic-id> [format] [--chat|--dialog]
 *
 * @property plugin The main plugin instance
 */
class DownloadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMAT_DOWNLOAD_URL_ENDPOINT = "/schematics/"

    override val name = "download"
    override val permission = "schematio.download"
    override val description = "Download a schematic to your clipboard"

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
            audience.sendMessage(Component.text("Usage: /schematio download <schematic-id> [format]").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Available formats: schem, schematic, mcedit").color(NamedTextColor.GRAY))
            return false
        }

        // Validate schematic ID
        val schematicIdResult = InputValidator.validateSchematicId(args[0])
        if (schematicIdResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(schematicIdResult.message).color(NamedTextColor.RED))
            return false
        }
        val schematicId = (schematicIdResult as ValidationResult.Valid).value

        // Validate format
        val validFormats = listOf("schem", "schematic", "mcedit")
        val formatResult = InputValidator.validateDownloadFormat(args.getOrNull(1), validFormats)
        if (formatResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(formatResult.message).color(NamedTextColor.RED))
            return false
        }
        val downloadFormat = (formatResult as ValidationResult.Valid).value

        downloadSchematic(player, schematicId, downloadFormat, UIMode.CHAT)
        return true
    }

    // ===========================================
    // DIALOG MODE
    // ===========================================

    private fun executeDialogMode(player: Player, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            // No args - show input dialog to get schematic ID
            showDownloadDialog(player)
        } else {
            // Args provided - just download and show result in chat (no dialog needed for confirmation)
            val schematicIdResult = InputValidator.validateSchematicId(args[0])
            if (schematicIdResult is ValidationResult.Invalid) {
                player.audience().sendMessage(Component.text(schematicIdResult.message).color(NamedTextColor.RED))
                return false
            }
            val schematicId = (schematicIdResult as ValidationResult.Valid).value

            val validFormats = listOf("schem", "schematic", "mcedit")
            val formatResult = InputValidator.validateDownloadFormat(args.getOrNull(1), validFormats)
            if (formatResult is ValidationResult.Invalid) {
                player.audience().sendMessage(Component.text(formatResult.message).color(NamedTextColor.RED))
                return false
            }
            val downloadFormat = (formatResult as ValidationResult.Valid).value

            // Use CHAT mode for result display - dialogs are only for input, not confirmation
            downloadSchematic(player, schematicId, downloadFormat, UIMode.CHAT)
        }
        return true
    }

    private fun showDownloadDialog(player: Player) {
        val title = Component.text("Download Schematic")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Enter the schematic ID or URL to download").color(NamedTextColor.GRAY)
        ))

        val inputs = mutableListOf<DialogInput>()
        inputs.add(
            DialogInput.text("schematic_id", Component.text("Schematic ID or URL").color(NamedTextColor.WHITE))
                .width(300)
                .initial("")
                .maxLength(100)
                .build()
        )

        val actionButtons = mutableListOf<ActionButton>()
        // Download button uses command template to get the input value
        actionButtons.add(
            ActionButton.builder(Component.text("Download").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .width(150)
                .action(DialogAction.commandTemplate("/schematio download \$(schematic_id) --dialog"))
                .build()
        )

        actionButtons.add(
            ActionButton.builder(Component.text("Cancel").color(NamedTextColor.RED))
                .width(100)
                // No action - clicking closes the dialog (default behavior)
                .build()
        )

        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("Download Schematic"))
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
            plugin.logger.warning("Failed to show download dialog: ${e.message}")
            player.audience().sendMessage(
                Component.text("Failed to open dialog. Use: /schematio download <schematic-id>").color(NamedTextColor.RED)
            )
        }
    }

    // ===========================================
    // SHARED LOGIC
    // ===========================================

    private fun downloadSchematic(player: Player, schematicId: String, format: String, uiMode: UIMode) {
        val audience = player.audience()

        // Check rate limit
        val rateLimitResult = plugin.rateLimiter.tryAcquire(player.uniqueId)
        if (rateLimitResult == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return
        }

        val downloadUrl = "$SCHEMAT_DOWNLOAD_URL_ENDPOINT$schematicId/download"
        val requestBody = """{"format":"$format"}"""

        audience.sendMessage(Component.text("Downloading schematic in $format format...").color(NamedTextColor.YELLOW))
        val progressBar = ProgressBarUtil.createProgressBar(player, "Downloading Schematic")

        val httpUtil = plugin.httpUtil
        if (httpUtil == null) {
            ProgressBarUtil.removeProgressBar(player, progressBar)
            audience.sendMessage(Component.text("API not connected. Run /schematio reload after configuring token.").color(NamedTextColor.RED))
            return
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val response = runBlocking {
                    httpUtil.sendGetRequestWithBodyFullResponse(downloadUrl, requestBody) { progress ->
                        ProgressBarUtil.updateProgressBar(progressBar, progress)
                    }
                }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    ProgressBarUtil.removeProgressBar(player, progressBar)

                    if (response == null) {
                        audience.sendMessage(Component.text("Could not connect to schemat.io API").color(NamedTextColor.RED))
                        audience.sendMessage(Component.text("The service may be temporarily unavailable").color(NamedTextColor.GRAY))
                        return@Runnable
                    }

                    val statusCode = response.statusLine.statusCode
                    if (statusCode != 200) {
                        audience.sendMessage(Component.text("Error downloading schematic. Status code: $statusCode").color(NamedTextColor.RED))
                        return@Runnable
                    }

                    val entity = response.entity
                    if (entity == null) {
                        audience.sendMessage(Component.text("Received empty response from server.").color(NamedTextColor.RED))
                        return@Runnable
                    }

                    try {
                        val schematicData = EntityUtils.toByteArray(entity)
                        plugin.logger.info("Downloaded schematic data size: ${schematicData.size} bytes")

                        val clipboard = WorldEditUtil.byteArrayToClipboard(schematicData)
                        if (clipboard == null) {
                            audience.sendMessage(Component.text("Error parsing schematic data").color(NamedTextColor.RED))
                            return@Runnable
                        }

                        WorldEditUtil.setClipboard(player, clipboard)

                        // Show result based on UI mode
                        when (uiMode) {
                            UIMode.CHAT -> showChatSuccess(player, schematicId, format)
                            UIMode.DIALOG -> showDialogSuccess(player, schematicId, format)
                        }
                    } catch (e: EOFException) {
                        plugin.logger.warning("EOFException occurred while parsing schematic data: ${e.message}")
                        audience.sendMessage(Component.text("Error: The downloaded schematic data appears to be incomplete or corrupted.").color(NamedTextColor.RED))
                    } catch (e: Exception) {
                        plugin.logger.warning("Error processing schematic: ${e.message}")
                        audience.sendMessage(Component.text("Error processing schematic data.").color(NamedTextColor.RED))
                    }
                })
            } catch (e: Exception) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    ProgressBarUtil.removeProgressBar(player, progressBar)
                    val msg = e.message ?: "Unknown error"
                    when {
                        msg.contains("Connection refused") || msg.contains("timed out") -> {
                            audience.sendMessage(Component.text("schemat.io API is currently unavailable").color(NamedTextColor.RED))
                            audience.sendMessage(Component.text("Please try again later").color(NamedTextColor.GRAY))
                        }
                        else -> {
                            plugin.logger.warning("Download error: $msg")
                            audience.sendMessage(Component.text("Error downloading schematic. Please try again.").color(NamedTextColor.RED))
                        }
                    }
                })
            }
        })
    }

    private fun showChatSuccess(player: Player, schematicId: String, format: String) {
        val audience = player.audience()
        audience.sendMessage(Component.text("Schematic downloaded and loaded into clipboard ($format format)").color(NamedTextColor.GREEN))
        audience.sendMessage(Component.text("Use //paste to place it.").color(NamedTextColor.GRAY))
    }

    private fun showDialogSuccess(player: Player, schematicId: String, format: String) {
        val title = Component.text("Download Complete!")
            .color(NamedTextColor.GREEN)
            .decorate(TextDecoration.BOLD)

        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Schematic loaded into your clipboard ($format)").color(NamedTextColor.WHITE)
        ))
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Use //paste to place it in the world").color(NamedTextColor.GRAY)
        ))

        val actionButtons = mutableListOf<ActionButton>()

        val schematicUrl = "${plugin.baseUrl}/schematics/$schematicId"
        actionButtons.add(
            ActionButton.builder(Component.text("View on Web").color(NamedTextColor.AQUA))
                .width(120)
                .action(DialogAction.staticAction(ClickEvent.openUrl(schematicUrl)))
                .build()
        )

        actionButtons.add(
            ActionButton.builder(Component.text("Close").color(NamedTextColor.GRAY))
                .width(80)
                // No action - clicking closes the dialog (default behavior)
                .build()
        )

        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("Download Complete"))
            .body(bodyElements)
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
            showChatSuccess(player, schematicId, format)
        }
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()

        val partial = args.last().lowercase()
        val suggestions = mutableListOf<String>()

        if (args.size == 1) {
            if (partial.isEmpty()) suggestions.add("<schematic-id>")
            if ("--chat".startsWith(partial)) suggestions.add("--chat")
            if ("--dialog".startsWith(partial)) suggestions.add("--dialog")
        } else if (args.size == 2) {
            val formats = listOf("schem", "schematic", "mcedit")
            suggestions.addAll(formats.filter { it.startsWith(partial, ignoreCase = true) })
            if ("--chat".startsWith(partial)) suggestions.add("--chat")
            if ("--dialog".startsWith(partial)) suggestions.add("--dialog")
        } else {
            if ("--chat".startsWith(partial)) suggestions.add("--chat")
            if ("--dialog".startsWith(partial)) suggestions.add("--dialog")
        }

        return suggestions.filter { it.startsWith(partial, ignoreCase = true) }
    }
}
