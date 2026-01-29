package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.ValidationResult
import io.schemat.schematioConnector.utils.WorldEditUtil
import io.schemat.schematioConnector.utils.parseJsonSafe
import io.schemat.schematioConnector.utils.safeGetObject
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
                            val errorCode = jsonResponse.safeGetString("error")

                            // Handle specific error cases with user-friendly messages
                            when (errorCode) {
                                "user_not_in_community" -> {
                                    showCommunityMembershipError(player, jsonResponse)
                                }
                                "author_not_found" -> {
                                    audience.sendMessage(Component.text("Your account was not found on schemat.io").color(NamedTextColor.RED))
                                    audience.sendMessage(Component.text("Please link your Minecraft account at schemat.io first").color(NamedTextColor.GRAY))
                                }
                                else -> {
                                    val errorMessage = jsonResponse.safeGetString("message") ?: errorCode
                                    if (errorMessage != null) {
                                        audience.sendMessage(Component.text("Error: $errorMessage").color(NamedTextColor.RED))
                                    } else {
                                        audience.sendMessage(Component.text("Error: Upload succeeded but no link was returned").color(NamedTextColor.RED))
                                    }
                                }
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

    private fun showCommunityMembershipError(player: Player, jsonResponse: com.google.gson.JsonObject) {
        val audience = player.audience()
        val communityInfo = jsonResponse.safeGetObject("community")
        val communityName = communityInfo.safeGetString("name") ?: "this community"
        val communitySlug = communityInfo.safeGetString("slug")

        // Build the community URL
        val communityUrl = if (communitySlug != null) "${plugin.baseUrl}/communities/$communitySlug" else plugin.baseUrl

        // Header
        audience.sendMessage(Component.empty())
        audience.sendMessage(
            Component.text("✖ ").color(NamedTextColor.RED)
                .append(Component.text("Community Membership Required").color(NamedTextColor.RED))
        )
        audience.sendMessage(Component.empty())

        // Explanation
        audience.sendMessage(
            Component.text("This server uses a community token from ").color(NamedTextColor.GRAY)
                .append(Component.text(communityName).color(NamedTextColor.GOLD))
                .append(Component.text(".").color(NamedTextColor.GRAY))
        )
        audience.sendMessage(
            Component.text("To upload schematics, you must be a member of this community.").color(NamedTextColor.GRAY)
        )
        audience.sendMessage(Component.empty())

        // How to join
        audience.sendMessage(
            Component.text("How to join:").color(NamedTextColor.YELLOW)
        )
        audience.sendMessage(
            Component.text("1. ").color(NamedTextColor.GRAY)
                .append(Component.text("Visit the community page on schemat.io").color(NamedTextColor.WHITE))
        )
        audience.sendMessage(
            Component.text("2. ").color(NamedTextColor.GRAY)
                .append(Component.text("Click \"Join Community\" (you may need to log in first)").color(NamedTextColor.WHITE))
        )
        audience.sendMessage(
            Component.text("3. ").color(NamedTextColor.GRAY)
                .append(Component.text("Return here and try uploading again!").color(NamedTextColor.WHITE))
        )
        audience.sendMessage(Component.empty())

        // Clickable link button
        val linkComponent = Component.text("  ➜ ")
            .color(NamedTextColor.DARK_GRAY)
            .append(
                Component.text("[Open Community Page]")
                    .color(NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.openUrl(communityUrl))
                    .hoverEvent(HoverEvent.showText(
                        Component.text("Click to open ").color(NamedTextColor.GRAY)
                            .append(Component.text(communityName).color(NamedTextColor.GOLD))
                            .append(Component.text(" in your browser").color(NamedTextColor.GRAY))
                    ))
            )

        audience.sendMessage(linkComponent)
        audience.sendMessage(Component.empty())
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
