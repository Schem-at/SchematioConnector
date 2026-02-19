package io.schemat.schematioConnector.commands

import com.google.gson.JsonObject
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetString
import io.schemat.connector.core.validation.InputValidator
import io.schemat.connector.core.validation.ValidationResult
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.ProgressBarUtil
import io.schemat.schematioConnector.utils.UIMode
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
 * Downloads a schematic from schemat.io or a quick share link and loads it
 * into the player's WorldEdit clipboard.
 *
 * Supports both schematic IDs and quick share codes/URLs:
 * - Schematic ID: alphanumeric identifier for a posted schematic
 * - Quick share: access code (with optional qs_ prefix) or full URL
 *
 * Usage: /schematio download <id|code|url> [format|password] [--chat|--dialog]
 * Alias: /schematio get
 *
 * @property plugin The main plugin instance
 */
class DownloadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMAT_DOWNLOAD_URL_ENDPOINT = "/schematics/"
    private val QUICKSHARE_ENDPOINT = "/plugin/quick-shares"

    override val name = "download"
    override val permission = "schematio.download"
    override val description = "Download a schematic to your clipboard"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Show usage if help requested
        if (args.any { it == "--help" || it == "-h" }) {
            showUsage(player)
            return true
        }

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
    // INPUT TYPE DETECTION
    // ===========================================

    /**
     * Detects whether the input looks like a quick share code/URL.
     * URLs and qs_ prefixed codes are always treated as quick shares.
     */
    private fun isQuickShareInput(input: String): Boolean {
        if (input.startsWith("http://") || input.startsWith("https://")) return true
        if (input.startsWith("qs_")) return true
        return false
    }

    // ===========================================
    // CHAT MODE
    // ===========================================

    private fun executeChatMode(player: Player, args: Array<String>): Boolean {
        val audience = player.audience()

        if (args.isEmpty()) {
            audience.sendMessage(Component.text("Usage: /schematio download <id|code|url> [format|password]").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Accepts schematic IDs, quick share codes, or URLs").color(NamedTextColor.GRAY))
            return false
        }

        val input = args[0]

        // Route based on input type
        if (isQuickShareInput(input)) {
            val codeValidation = InputValidator.validateQuickShareCode(input)
            if (codeValidation is ValidationResult.Invalid) {
                audience.sendMessage(Component.text(codeValidation.message).color(NamedTextColor.RED))
                return false
            }
            val accessCode = codeValidation.getOrNull()!!
            val password = args.getOrNull(1)?.takeIf { it.isNotBlank() }
            downloadQuickShare(player, accessCode, password)
            return true
        }

        // Treat as schematic ID
        val schematicIdResult = InputValidator.validateSchematicId(input)
        if (schematicIdResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(schematicIdResult.message).color(NamedTextColor.RED))
            return false
        }
        val schematicId = (schematicIdResult as ValidationResult.Valid).value

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
            showDownloadDialog(player)
            return true
        }

        val input = args[0]

        // Route based on input type
        if (isQuickShareInput(input)) {
            val codeValidation = InputValidator.validateQuickShareCode(input)
            if (codeValidation is ValidationResult.Invalid) {
                player.audience().sendMessage(Component.text(codeValidation.message).color(NamedTextColor.RED))
                return false
            }
            val accessCode = codeValidation.getOrNull()!!
            val password = args.getOrNull(1)?.takeIf { it.isNotBlank() }
            downloadQuickShare(player, accessCode, password, showPasswordDialogOn401 = true)
            return true
        }

        // Treat as schematic ID
        val schematicIdResult = InputValidator.validateSchematicId(input)
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
        return true
    }

    private fun showDownloadDialog(player: Player) {
        val title = Component.text("Download Schematic")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Enter a schematic ID, quick share code, or URL").color(NamedTextColor.GRAY)
        ))

        val inputs = mutableListOf<DialogInput>()
        inputs.add(
            DialogInput.text("id", Component.text("Schematic ID, Code, or URL").color(NamedTextColor.WHITE))
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
        actionButtons.add(
            ActionButton.builder(Component.text("Download").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .width(150)
                .action(DialogAction.commandTemplate("/schematio download \$(id) \$(password) --dialog"))
                .build()
        )

        actionButtons.add(
            ActionButton.builder(Component.text("Cancel").color(NamedTextColor.RED))
                .width(100)
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
                Component.text("Failed to open dialog. Use: /schematio download <id|code|url>").color(NamedTextColor.RED)
            )
        }
    }

    // ===========================================
    // SCHEMATIC DOWNLOAD (by ID)
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

    // ===========================================
    // QUICK SHARE DOWNLOAD (by code/URL)
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
                            if (showPasswordDialogOn401) {
                                showPasswordDialog(player, accessCode)
                            } else {
                                audience.sendMessage(Component.text("This share requires a password.").color(NamedTextColor.RED))
                                audience.sendMessage(Component.text("Usage: /schematio download $accessCode <password>").color(NamedTextColor.GRAY))
                            }
                        }
                        403 -> {
                            val msg = parseQuickShareError(errorBody) ?: "Access denied"
                            audience.sendMessage(Component.text(msg).color(NamedTextColor.RED))
                        }
                        404 -> {
                            audience.sendMessage(Component.text("Quick share not found.").color(NamedTextColor.RED))
                        }
                        410 -> {
                            val msg = parseQuickShareError(errorBody) ?: "This share has expired or been revoked"
                            audience.sendMessage(Component.text(msg).color(NamedTextColor.RED))
                        }
                        429 -> {
                            val msg = parseQuickShareError(errorBody) ?: "Download limit reached or rate limited"
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

    private fun showPasswordDialog(player: Player, accessCode: String) {
        val title = Component.text("Password Required")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("This share is password-protected").color(NamedTextColor.GRAY)
        ))

        val inputs = mutableListOf<DialogInput>()
        inputs.add(
            DialogInput.text("password", Component.text("Password").color(NamedTextColor.WHITE))
                .width(200)
                .initial("")
                .maxLength(50)
                .build()
        )

        val actionButtons = mutableListOf<ActionButton>()
        actionButtons.add(
            ActionButton.builder(Component.text("Download").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .width(150)
                .action(DialogAction.commandTemplate("/schematio download $accessCode \$(password) --dialog"))
                .build()
        )

        actionButtons.add(
            ActionButton.builder(Component.text("Cancel").color(NamedTextColor.RED))
                .width(100)
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
            player.audience().sendMessage(Component.text("This share requires a password.").color(NamedTextColor.RED))
            player.audience().sendMessage(Component.text("Usage: /schematio download $accessCode <password>").color(NamedTextColor.GRAY))
        }
    }

    private fun parseQuickShareError(errorBody: String?): String? {
        val json = parseJsonSafe(errorBody) ?: return null
        return json.safeGetString("message") ?: json.safeGetString("error")
    }

    // ===========================================
    // SUCCESS DISPLAY
    // ===========================================

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

    private fun showUsage(player: Player) {
        val audience = player.audience()
        audience.sendMessage(Component.text("Download Usage:").color(NamedTextColor.GOLD))
        audience.sendMessage(Component.text("  /schematio download <id|code|url> [format|password]").color(NamedTextColor.YELLOW))
        audience.sendMessage(Component.text("  /schematio get <id|code|url> [format|password]").color(NamedTextColor.YELLOW))
        audience.sendMessage(Component.text("Input types:").color(NamedTextColor.GOLD))
        audience.sendMessage(Component.text("  Schematic ID  ").color(NamedTextColor.AQUA)
            .append(Component.text("Alphanumeric ID, second arg is format (schem/schematic/mcedit)").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  Quick share   ").color(NamedTextColor.AQUA)
            .append(Component.text("URL or qs_-prefixed code, second arg is password").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("Options:").color(NamedTextColor.GOLD))
        audience.sendMessage(Component.text("  -c, --chat    ").color(NamedTextColor.AQUA)
            .append(Component.text("Force chat mode (default)").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  -d, --dialog  ").color(NamedTextColor.AQUA)
            .append(Component.text("Use dialog UI").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("Examples:").color(NamedTextColor.GOLD))
        audience.sendMessage(Component.text("  /sio download abc123").color(NamedTextColor.GRAY))
        audience.sendMessage(Component.text("  /sio get https://schemat.io/share/xyz").color(NamedTextColor.GRAY))
        audience.sendMessage(Component.text("  /sio get qs_abc123 mypassword").color(NamedTextColor.GRAY))
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()

        val partial = args.last().lowercase()
        val flags = listOf("-d", "-c", "--help")

        if (args.size == 1) {
            return flags.filter { it.startsWith(partial, ignoreCase = true) }
        } else if (args.size == 2) {
            val formats = listOf("schem", "schematic", "mcedit")
            return (formats + flags).filter { it.startsWith(partial, ignoreCase = true) }
        }

        return flags.filter { it.startsWith(partial, ignoreCase = true) }
    }
}
