package io.schemat.schematioConnector.commands

import com.google.gson.JsonObject
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.schemat.connector.core.validation.InputValidator
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.UIMode
import io.schemat.schematioConnector.utils.UIModeResolver
import io.schemat.schematioConnector.utils.WorldEditUtil
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetObject
import io.schemat.connector.core.json.safeGetString
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import java.util.Base64

/**
 * Command for creating quick share links for schematics.
 *
 * Supports both chat and dialog UI modes:
 * - Chat mode: Creates share immediately with default settings
 * - Dialog mode: Opens a configuration dialog for expiration, password, etc.
 *
 * Usage: /schematio quickshare [-e duration] [-l limit] [-p password] [--chat|--dialog]
 *
 * @property plugin The main plugin instance
 */
class QuickShareSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val QUICKSHARE_ENDPOINT = "/plugin/quick-shares"

    override val name = "quickshare"
    override val permission = "schematio.quickshare"
    override val description = "Create a quick share link"

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

        // Show usage if help requested
        if (args.any { it == "--help" || it == "-h" }) {
            showUsage(player)
            return true
        }

        // Check for API availability
        if (plugin.httpUtil == null) {
            audience.sendMessage(Component.text("Cannot create quick share - not connected to schemat.io").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Configure a community token in config.yml and run /schematio reload").color(NamedTextColor.GRAY))
            return true
        }

        // Get clipboard
        val clipboard = WorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            audience.sendMessage(Component.text("No clipboard found. Copy something with WorldEdit first!").color(NamedTextColor.RED))
            return true
        }

        // Convert clipboard to bytes
        val schematicBytes = WorldEditUtil.clipboardToByteArray(clipboard)
        if (schematicBytes == null) {
            audience.sendMessage(Component.text("Could not convert clipboard to schematic format.").color(NamedTextColor.RED))
            return true
        }

        // Resolve UI mode and clean args
        val (uiMode, cleanedArgs) = resolver.resolveWithArgs(player, args)

        return when (uiMode) {
            UIMode.CHAT -> executeChatMode(player, schematicBytes, cleanedArgs)
            UIMode.DIALOG -> executeDialogMode(player, schematicBytes)
        }
    }

    // ===========================================
    // CHAT MODE - Immediate creation with defaults
    // ===========================================

    private fun executeChatMode(player: Player, schematicBytes: ByteArray, args: Array<String> = emptyArray()): Boolean {
        val audience = player.audience()

        // Parse option flags (set by dialog or command line)
        // Supports: --expires=1h / -e 1h, --limit=10 / -l 10, --password=x / -p x
        var expiresIn = 86400 // default 24 hours
        var maxDownloads: Int? = null
        var password: String? = null

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg.startsWith("--expires=") || arg.startsWith("-e=") ->
                    expiresIn = InputValidator.parseDuration(arg.substringAfter("=")) ?: 86400
                arg == "--expires" || arg == "-e" ->
                    if (i + 1 < args.size) expiresIn = InputValidator.parseDuration(args[++i]) ?: 86400
                arg.startsWith("--limit=") || arg.startsWith("-l=") ->
                    maxDownloads = InputValidator.parseDownloadLimit(arg.substringAfter("="))
                arg == "--limit" || arg == "-l" ->
                    if (i + 1 < args.size) maxDownloads = InputValidator.parseDownloadLimit(args[++i])
                arg.startsWith("--password=") || arg.startsWith("-p=") ->
                    password = arg.substringAfter("=").ifBlank { null }
                arg == "--password" || arg == "-p" ->
                    if (i + 1 < args.size) password = args[++i].ifBlank { null }
            }
            i++
        }
        val hasExplicitFlags = args.isNotEmpty()

        audience.sendMessage(Component.text("Creating quick share...").color(NamedTextColor.YELLOW))

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val httpUtil = plugin.httpUtil ?: run {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        audience.sendMessage(Component.text("API not connected.").color(NamedTextColor.RED))
                    })
                    return@Runnable
                }

                val base64Data = Base64.getEncoder().encodeToString(schematicBytes)
                val requestBody = JsonObject().apply {
                    addProperty("schematic_data", base64Data)
                    addProperty("format", "schem")
                    addProperty("expires_in", expiresIn)
                    addProperty("player_uuid", player.uniqueId.toString())
                    if (maxDownloads != null) {
                        addProperty("max_downloads", maxDownloads)
                    }
                    if (!password.isNullOrBlank()) {
                        addProperty("password", password)
                    }
                }

                val (statusCode, responseBody) = runBlocking {
                    httpUtil.sendPostRequest(QUICKSHARE_ENDPOINT, requestBody.toString())
                }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    handleShareResponse(player, statusCode, responseBody)

                    // Suggest options if using defaults
                    if (!hasExplicitFlags) {
                        if (player.hasPermission(UIModeResolver.PERMISSION_DIALOG)) {
                            audience.sendMessage(
                                Component.text("Tip: Use ").color(NamedTextColor.DARK_GRAY)
                                    .append(Component.text("-d").color(NamedTextColor.AQUA))
                                    .append(Component.text(" for dialog, or flags: ").color(NamedTextColor.DARK_GRAY))
                                    .append(Component.text("-e <duration> -l <limit> -p <password>").color(NamedTextColor.AQUA))
                            )
                        } else {
                            audience.sendMessage(
                                Component.text("Tip: Use flags: ").color(NamedTextColor.DARK_GRAY)
                                    .append(Component.text("-e <duration> -l <limit> -p <password>").color(NamedTextColor.AQUA))
                            )
                        }
                    }
                })
            } catch (e: Exception) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    audience.sendMessage(Component.text("Error creating share: ${e.message}").color(NamedTextColor.RED))
                })
            }
        })

        return true
    }

    // ===========================================
    // DIALOG MODE - Configuration UI
    // ===========================================

    private fun executeDialogMode(player: Player, schematicBytes: ByteArray): Boolean {
        val audience = player.audience()
        showQuickShareDialog(player, schematicBytes)
        return true
    }

    private fun showQuickShareDialog(player: Player, schematicBytes: ByteArray) {
        val sizeKb = schematicBytes.size / 1024

        // Title
        val title = Component.text("Quick Share")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        // Body
        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Share your clipboard (${sizeKb}KB) with a temporary link").color(NamedTextColor.GRAY)
        ))

        // Inputs
        val inputs = mutableListOf<DialogInput>()

        // Expiration dropdown
        val expirationOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create("3600", Component.text("1 hour"), false),
            SingleOptionDialogInput.OptionEntry.create("86400", Component.text("24 hours"), true),
            SingleOptionDialogInput.OptionEntry.create("604800", Component.text("7 days"), false)
        )
        inputs.add(
            DialogInput.singleOption("expiration", Component.text("Expires in").color(NamedTextColor.WHITE), expirationOptions)
                .width(200)
                .build()
        )

        // Download limit dropdown
        val limitOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create("0", Component.text("Unlimited"), true),
            SingleOptionDialogInput.OptionEntry.create("1", Component.text("1 download"), false),
            SingleOptionDialogInput.OptionEntry.create("10", Component.text("10 downloads"), false)
        )
        inputs.add(
            DialogInput.singleOption("limit", Component.text("Download limit").color(NamedTextColor.WHITE), limitOptions)
                .width(200)
                .build()
        )

        // Password (optional)
        inputs.add(
            DialogInput.text("password", Component.text("Password (optional)").color(NamedTextColor.WHITE))
                .width(200)
                .initial("")
                .maxLength(50)
                .build()
        )

        // Action buttons
        val actionButtons = mutableListOf<ActionButton>()

        actionButtons.add(
            ActionButton.builder(Component.text("Create Share").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .width(200)
                .action(DialogAction.commandTemplate("/schematio quickshare --expires=\$(expiration) --limit=\$(limit) --password=\$(password) --chat"))
                .build()
        )

        actionButtons.add(
            ActionButton.builder(Component.text("Cancel").color(NamedTextColor.RED))
                .width(100)
                // No action - clicking closes the dialog (default behavior)
                .build()
        )

        // Build dialog
        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("Quick Share Configuration"))
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
            plugin.logger.warning("Failed to show quickshare dialog: ${e.message}")
            player.audience().sendMessage(
                Component.text("Failed to open dialog. Falling back to chat mode...").color(NamedTextColor.RED)
            )
            executeChatMode(player, schematicBytes)
        }
    }

    // ===========================================
    // SHARED UTILITIES
    // ===========================================

    private fun handleShareResponse(player: Player, statusCode: Int, responseBody: String?) {
        val audience = player.audience()

        if (statusCode == 201 && responseBody != null) {
            try {
                val json = parseJsonSafe(responseBody)
                val quickShare = json.safeGetObject("quick_share")
                val webUrl = quickShare.safeGetString("web_url")

                if (webUrl != null) {
                    audience.sendMessage(Component.text("Quick share created!").color(NamedTextColor.GREEN))

                    val linkComponent = Component.text(webUrl)
                        .color(NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(webUrl))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to open share link")))

                    audience.sendMessage(
                        Component.text("Link: ").color(NamedTextColor.GRAY)
                            .append(linkComponent)
                    )

                    audience.sendMessage(
                        Component.text("[Click to copy]")
                            .color(NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.copyToClipboard(webUrl))
                            .hoverEvent(HoverEvent.showText(Component.text("Copy link to clipboard")))
                    )
                } else {
                    audience.sendMessage(Component.text("Share created but no URL returned.").color(NamedTextColor.YELLOW))
                }
            } catch (e: Exception) {
                audience.sendMessage(Component.text("Error parsing response: ${e.message}").color(NamedTextColor.RED))
            }
        } else {
            val errorMsg = when (statusCode) {
                400 -> "Invalid schematic data"
                403 -> "Permission denied - token may not have create_quick_share permission"
                413 -> "Schematic too large (max 10MB)"
                -1 -> "Connection failed"
                else -> "Error (code: $statusCode)"
            }
            audience.sendMessage(Component.text(errorMsg).color(NamedTextColor.RED))
            if (responseBody != null) {
                plugin.logger.warning("Quick share error response: $responseBody")
            }
        }
    }

    private fun showUsage(player: Player) {
        val audience = player.audience()
        audience.sendMessage(Component.text("Quick Share Usage:").color(NamedTextColor.GOLD))
        audience.sendMessage(Component.text("  /schematio quickshare [options]").color(NamedTextColor.YELLOW))
        audience.sendMessage(Component.text("Options:").color(NamedTextColor.GOLD))
        audience.sendMessage(Component.text("  -e, --expires <duration>  ").color(NamedTextColor.AQUA)
            .append(Component.text("Expiration (1h, 24h, 7d, 1w) [default: 24h]").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  -l, --limit <count>       ").color(NamedTextColor.AQUA)
            .append(Component.text("Download limit (0=unlimited) [default: 0]").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  -p, --password <pass>     ").color(NamedTextColor.AQUA)
            .append(Component.text("Password protect the share").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  -c, --chat                ").color(NamedTextColor.AQUA)
            .append(Component.text("Force chat mode (default)").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  -d, --dialog              ").color(NamedTextColor.AQUA)
            .append(Component.text("Use dialog UI instead of flags").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("Examples:").color(NamedTextColor.GOLD))
        audience.sendMessage(Component.text("  /schematio quickshare -e 7d -l 10").color(NamedTextColor.GRAY))
        audience.sendMessage(Component.text("  /schematio quickshare -e 1h -p secret").color(NamedTextColor.GRAY))
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()

        val partial = args.last().lowercase()

        // Suggest values for flags that take a parameter
        if (args.size >= 2) {
            val prevArg = args[args.size - 2].lowercase()
            if (prevArg == "-e" || prevArg == "--expires") {
                return listOf("1h", "24h", "7d", "1w").filter { it.startsWith(partial, ignoreCase = true) }
            }
            if (prevArg == "-l" || prevArg == "--limit") {
                return listOf("0", "1", "10").filter { it.startsWith(partial, ignoreCase = true) }
            }
        }

        return listOf("-e", "-l", "-p", "-d", "-c", "--help").filter { it.startsWith(partial, ignoreCase = true) }
    }
}
