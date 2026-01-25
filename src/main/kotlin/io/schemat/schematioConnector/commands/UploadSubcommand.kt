package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.ValidationResult
import io.schemat.schematioConnector.utils.WorldEditUtil
import io.schemat.schematioConnector.utils.parseJsonSafe
import io.schemat.schematioConnector.utils.safeGetString
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.util.EntityUtils
import org.bukkit.entity.Player

/**
 * Uploads the player's current WorldEdit clipboard to schemat.io.
 *
 * Results are always shown in chat - no dialog needed for simple confirmations.
 *
 * Usage: /schematio upload
 *
 * @property plugin The main plugin instance
 */
class UploadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMAT_UPLOAD_URL_ENDPOINT = "/schematics/upload"

    override val name = "upload"
    override val permission = "schematio.upload"
    override val description = "Upload your clipboard to schemat.io"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Get clipboard synchronously on main thread
        val clipboard = WorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            audience.sendMessage(Component.text("No clipboard found").color(NamedTextColor.RED))
            return false
        }

        val schematicBytes = WorldEditUtil.clipboardToByteArray(clipboard)
        if (schematicBytes == null) {
            audience.sendMessage(Component.text("Could not convert clipboard to schematic format.").color(NamedTextColor.RED))
            return false
        }

        // Validate schematic size
        val sizeResult = InputValidator.validateSchematicSize(schematicBytes.size)
        if (sizeResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(sizeResult.message).color(NamedTextColor.RED))
            return false
        }

        // Check rate limit
        val rateLimitResult = plugin.rateLimiter.tryAcquire(player.uniqueId)
        if (rateLimitResult == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return true
        }

        // Check API connection
        val httpUtil = plugin.httpUtil
        if (httpUtil == null) {
            audience.sendMessage(Component.text("API not connected. Run /schematio reload after configuring token.").color(NamedTextColor.RED))
            return true
        }

        // Upload always shows results in chat - no dialog needed for simple confirmation
        audience.sendMessage(Component.text("Uploading schematic...").color(NamedTextColor.YELLOW))
        uploadSchematic(player, schematicBytes)

        return true
    }

    private fun uploadSchematic(player: Player, schematicBytes: ByteArray) {
        val audience = player.audience()
        val httpUtil = plugin.httpUtil!!
        val authorUUID = player.uniqueId.toString()

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val builder = MultipartEntityBuilder.create()
                    .addTextBody("author", authorUUID)
                    .addBinaryBody("schematic", schematicBytes, ContentType.DEFAULT_BINARY, "schematic")

                val response = runBlocking {
                    httpUtil.sendMultiPartRequest(SCHEMAT_UPLOAD_URL_ENDPOINT, builder.build())
                }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (response == null) {
                        audience.sendMessage(Component.text("Could not connect to schemat.io API").color(NamedTextColor.RED))
                        audience.sendMessage(Component.text("The service may be temporarily unavailable").color(NamedTextColor.GRAY))
                        return@Runnable
                    }

                    try {
                        val responseString = EntityUtils.toString(response)
                        val jsonResponse = parseJsonSafe(responseString)

                        if (jsonResponse == null) {
                            audience.sendMessage(Component.text("Error: Invalid response from server").color(NamedTextColor.RED))
                            return@Runnable
                        }

                        val link = jsonResponse.safeGetString("link")
                        if (link == null) {
                            val error = jsonResponse.safeGetString("message") ?: jsonResponse.safeGetString("error")
                            if (error != null) {
                                audience.sendMessage(Component.text("Error: $error").color(NamedTextColor.RED))
                            } else {
                                audience.sendMessage(Component.text("Error: Upload succeeded but no link was returned").color(NamedTextColor.RED))
                            }
                            return@Runnable
                        }

                        // Always show result in chat - no dialog needed for simple confirmation
                        showChatResult(player, link)
                    } catch (e: Exception) {
                        plugin.logger.warning("Error parsing upload response: ${e.message}")
                        audience.sendMessage(Component.text("Error processing server response").color(NamedTextColor.RED))
                    }
                })
            } catch (e: Exception) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    val msg = e.message ?: "Unknown error"
                    when {
                        msg.contains("Connection refused") || msg.contains("timed out") -> {
                            audience.sendMessage(Component.text("schemat.io API is currently unavailable").color(NamedTextColor.RED))
                            audience.sendMessage(Component.text("Please try again later").color(NamedTextColor.GRAY))
                        }
                        else -> {
                            plugin.logger.warning("Upload error: $msg")
                            audience.sendMessage(Component.text("Error uploading schematic. Please try again.").color(NamedTextColor.RED))
                        }
                    }
                })
            }
        })
    }

    private fun showChatResult(player: Player, link: String) {
        val audience = player.audience()

        val linkComponent = Component.text("Click here to view the schematic")
            .color(NamedTextColor.AQUA)
            .clickEvent(ClickEvent.openUrl(link))
            .hoverEvent(HoverEvent.showText(Component.text("Open the schematic link")))

        audience.sendMessage(Component.text("Schematic uploaded successfully!").color(NamedTextColor.GREEN))
        audience.sendMessage(linkComponent)

        audience.sendMessage(
            Component.text("[Click to copy link]")
                .color(NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.copyToClipboard(link))
                .hoverEvent(HoverEvent.showText(Component.text("Copy link to clipboard")))
        )
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}
