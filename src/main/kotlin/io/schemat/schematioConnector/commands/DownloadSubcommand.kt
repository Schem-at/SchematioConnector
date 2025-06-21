package io.schemat.schematioConnector.commands

import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.ProgressBarUtil
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.apache.http.util.EntityUtils
import org.bukkit.entity.Player
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Paths

class DownloadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMAT_DOWNLOAD_URL_ENDPOINT = "/schematics/"

    // Add the required properties from the interface
    override val name = "download"
    override val permission = "schematioconnector.download"
    override val description = "Download a schematic to your clipboard"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()
        if (args.isEmpty()) {
            audience.sendMessage(Component.text("Usage: /schematio download <schematic-id> [format]").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Available formats: schem, schematic, mcedit").color(NamedTextColor.GRAY))
            return false
        }

        val schematicId = args[0]
        val downloadFormat = if (args.size > 1) args[1] else "schem"
        val downloadUrl = "$SCHEMAT_DOWNLOAD_URL_ENDPOINT$schematicId/download"

        // Validate format
        val validFormats = listOf("schem", "schematic", "mcedit")
        if (downloadFormat !in validFormats) {
            audience.sendMessage(Component.text("Invalid format '$downloadFormat'. Available formats: ${validFormats.joinToString(", ")}").color(NamedTextColor.RED))
            return false
        }

        // Create the request body with the format
        val requestBody = """{"format":"$downloadFormat"}"""

        audience.sendMessage(Component.text("Downloading schematic in $downloadFormat format...").color(NamedTextColor.YELLOW))
        val progressBar = ProgressBarUtil.createProgressBar(player, "Downloading Schematic")

        runBlocking {
            try {
                val response = plugin.httpUtil.sendGetRequestWithBodyFullResponse(downloadUrl, requestBody) { progress ->
                    ProgressBarUtil.updateProgressBar(progressBar, progress)
                }

                ProgressBarUtil.removeProgressBar(player, progressBar)

                if (response == null) {
                    audience.sendMessage(Component.text("Error downloading schematic. Please check the server logs for more details.").color(NamedTextColor.RED))
                    return@runBlocking
                }

                val statusCode = response.statusLine.statusCode
                if (statusCode != 200) {
                    audience.sendMessage(Component.text("Error downloading schematic. Status code: $statusCode").color(NamedTextColor.RED))
                    return@runBlocking
                }

                val entity = response.entity
                if (entity == null) {
                    audience.sendMessage(Component.text("Received empty response from server.").color(NamedTextColor.RED))
                    return@runBlocking
                }

                val schematicData = EntityUtils.toByteArray(entity)
                plugin.logger.info("Downloaded schematic data size: ${schematicData.size} bytes")


                Files.write(Paths.get("schematic_debug.schem"), schematicData)



                val clipboard = WorldEditUtil.byteArrayToClipboard(schematicData)
                if (clipboard == null) {
                    audience.sendMessage(Component.text("Error parsing schematic data").color(NamedTextColor.RED))
                    return@runBlocking
                }

                WorldEditUtil.setClipboard(player, clipboard)
                audience.sendMessage(Component.text("Schematic downloaded and loaded into clipboard ($downloadFormat format)").color(NamedTextColor.GREEN))
            } catch (e: EOFException) {
                plugin.logger.warning("EOFException occurred while parsing schematic data: ${e.message}")
                audience.sendMessage(Component.text("Error: The downloaded schematic data appears to be incomplete or corrupted.").color(NamedTextColor.RED))
            } catch (e: Exception) {
                plugin.logger.severe("An unexpected error occurred: ${e.message}")
                e.printStackTrace()
                audience.sendMessage(Component.text("An unexpected error occurred. Please check the server logs.").color(NamedTextColor.RED))
            }
        }

        return true
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}