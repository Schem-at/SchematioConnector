package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.ProgressBarUtil
import io.schemat.schematioConnector.utils.ValidationResult
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.apache.http.util.EntityUtils
import org.bukkit.entity.Player
import java.io.EOFException

/**
 * Downloads a schematic from schemat.io and loads it into the player's WorldEdit clipboard.
 *
 * This command fetches a schematic by its ID from the schemat.io API and automatically
 * loads it into the player's WorldEdit clipboard, ready for pasting.
 *
 * Usage: /schematio download <schematic-id> [format]
 *
 * Arguments:
 * - schematic-id: The short ID of the schematic (e.g., "abc123")
 * - format: Optional download format (schem, schematic, mcedit). Defaults to "schem"
 *
 * Requires:
 * - schematio.download permission
 * - WorldEdit plugin for clipboard operations
 * - Active API connection
 *
 * @property plugin The main plugin instance
 */
class DownloadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMAT_DOWNLOAD_URL_ENDPOINT = "/schematics/"

    override val name = "download"
    override val permission = "schematio.download"
    override val description = "Download a schematic from schemat.io to your clipboard"

    override fun execute(player: Player, args: Array<out String>): Boolean {
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

        // Check rate limit
        val rateLimitResult = plugin.rateLimiter.tryAcquire(player.uniqueId)
        if (rateLimitResult == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return true
        }

        val downloadUrl = "$SCHEMAT_DOWNLOAD_URL_ENDPOINT$schematicId/download"
        val requestBody = """{"format":"$downloadFormat"}"""

        audience.sendMessage(Component.text("Downloading schematic in $downloadFormat format...").color(NamedTextColor.YELLOW))
        val progressBar = ProgressBarUtil.createProgressBar(player, "Downloading Schematic")

        // Check API connection before starting async task
        val httpUtil = plugin.httpUtil
        if (httpUtil == null) {
            ProgressBarUtil.removeProgressBar(player, progressBar)
            audience.sendMessage(Component.text("API not connected. Run /schematio reload after configuring token.").color(NamedTextColor.RED))
            return true
        }

        // Run the download asynchronously to avoid blocking the main thread
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val response = runBlocking {
                    httpUtil.sendGetRequestWithBodyFullResponse(downloadUrl, requestBody) { progress ->
                        // Update progress bar (safe to call from any thread)
                        ProgressBarUtil.updateProgressBar(progressBar, progress)
                    }
                }

                // Switch back to main thread for Bukkit API calls
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
                        audience.sendMessage(Component.text("Schematic downloaded and loaded into clipboard ($downloadFormat format)").color(NamedTextColor.GREEN))
                    } catch (e: EOFException) {
                        plugin.logger.warning("EOFException occurred while parsing schematic data: ${e.message}")
                        audience.sendMessage(Component.text("Error: The downloaded schematic data appears to be incomplete or corrupted.").color(NamedTextColor.RED))
                    } catch (e: Exception) {
                        plugin.logger.warning("Error processing schematic: ${e.message}")
                        audience.sendMessage(Component.text("Error processing schematic data.").color(NamedTextColor.RED))
                    }
                })
            } catch (e: Exception) {
                // Handle network errors on async thread, then notify on main thread
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

        return true
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}