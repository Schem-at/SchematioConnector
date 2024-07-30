package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.WorldEditUtil
import io.schemat.schematioConnector.utils.ProgressBarUtil
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.apache.http.util.EntityUtils
import java.io.EOFException

class DownloadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMAT_DOWNLOAD_URL_ENDPOINT = "/schematic/download/"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()
        if (args.isEmpty()) {
            audience.sendMessage(Component.text("Usage: /schematio download <schematic-id>").color(NamedTextColor.RED))
            return false
        }

        val schematicId = args[0]
        val downloadUrl = SCHEMAT_DOWNLOAD_URL_ENDPOINT + schematicId

        audience.sendMessage(Component.text("Downloading schematic...").color(NamedTextColor.YELLOW))
        val progressBar = ProgressBarUtil.createProgressBar(player, "Downloading Schematic")

        runBlocking {
            try {
                val response = plugin.httpUtil.sendGetRequestFullResponse(downloadUrl) { progress ->
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

                val clipboard = WorldEditUtil.byteArrayToClipboard(schematicData)
                if (clipboard == null) {
                    audience.sendMessage(Component.text("Error parsing schematic data").color(NamedTextColor.RED))
                    return@runBlocking
                }

                WorldEditUtil.setClipboard(player, clipboard)
                audience.sendMessage(Component.text("Schematic downloaded and loaded into clipboard").color(NamedTextColor.GREEN))
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