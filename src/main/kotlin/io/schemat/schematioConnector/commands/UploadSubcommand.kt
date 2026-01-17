package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.ValidationConstants
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
 * This command takes the player's current WorldEdit clipboard, converts it
 * to schematic format, and uploads it to the schemat.io API. On success,
 * a clickable link is provided to view the uploaded schematic.
 *
 * Usage: /schematio upload
 *
 * Requires:
 * - schematio.upload permission
 * - WorldEdit plugin with something copied to clipboard
 * - Active API connection
 *
 * @property plugin The main plugin instance
 */
class UploadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMAT_UPLOAD_URL_ENDPOINT = "/schematics/upload"

    override val name = "upload"
    override val permission = "schematio.upload"
    override val description = "Upload your current WorldEdit clipboard to schemat.io"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Get clipboard synchronously on main thread (WorldEdit requirement)
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

        // Check API connection before starting async task
        val httpUtil = plugin.httpUtil
        if (httpUtil == null) {
            audience.sendMessage(Component.text("API not connected. Run /schematio reload after configuring token.").color(NamedTextColor.RED))
            return true
        }

        audience.sendMessage(Component.text("Uploading schematic...").color(NamedTextColor.YELLOW))

        val authorUUID = player.uniqueId.toString()

        // Run the upload asynchronously to avoid blocking the main thread
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val builder = MultipartEntityBuilder.create()
                    .addTextBody("author", authorUUID)
                    .addBinaryBody("schematic", schematicBytes, ContentType.DEFAULT_BINARY, "schematic")

                val response = runBlocking {
                    httpUtil.sendMultiPartRequest(SCHEMAT_UPLOAD_URL_ENDPOINT, builder.build())
                }

                // Switch back to main thread for Bukkit API calls
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
                            // Check for error message in response
                            val error = jsonResponse.safeGetString("message") ?: jsonResponse.safeGetString("error")
                            if (error != null) {
                                audience.sendMessage(Component.text("Error: $error").color(NamedTextColor.RED))
                            } else {
                                audience.sendMessage(Component.text("Error: Upload succeeded but no link was returned").color(NamedTextColor.RED))
                            }
                            return@Runnable
                        }

                        val linkComponent = Component.text("Click here to view the schematic")
                            .color(NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.openUrl(link))
                            .hoverEvent(HoverEvent.showText(Component.text("Open the schematic link")))

                        audience.sendMessage(Component.text("Schematic uploaded successfully!").color(NamedTextColor.GREEN))
                        audience.sendMessage(linkComponent)
                    } catch (e: Exception) {
                        plugin.logger.warning("Error parsing upload response: ${e.message}")
                        audience.sendMessage(Component.text("Error processing server response").color(NamedTextColor.RED))
                    }
                })
            } catch (e: Exception) {
                // Handle network errors on async thread, then notify on main thread
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

        return true
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}