package io.schemat.schematioConnector.commands

import com.google.gson.Gson
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.util.EntityUtils
import org.bukkit.entity.Player

class UploadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMAT_UPLOAD_URL_ENDPOINT = "/schematics/upload"

    // Add the required properties from the interface
    override val name = "upload"
    override val permission = "schematioconnector.upload"
    override val description = "Upload your current WorldEdit clipboard"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        // ... (rest of your code is correct)
        val audience = player.audience()
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

        audience.sendMessage(Component.text("Uploading schematic...").color(NamedTextColor.YELLOW))

        runBlocking {
            val authorUUID = player.uniqueId.toString()
            val builder = MultipartEntityBuilder.create()
                .addTextBody("author", authorUUID)
                .addBinaryBody("schematic", schematicBytes, ContentType.DEFAULT_BINARY, "schematic")

            val response = plugin.httpUtil.sendMultiPartRequest(SCHEMAT_UPLOAD_URL_ENDPOINT, builder.build())

            if (response == null) {
                audience.sendMessage(Component.text("Error uploading schematic").color(NamedTextColor.RED))
                return@runBlocking
            }

            val responseString = EntityUtils.toString(response)
            val jsonResponse = Gson().fromJson(responseString, Map::class.java)
            val link = jsonResponse["link"] as String

            val linkComponent = Component.text("Click here to view the schematic")
                .color(NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(Component.text("Open the schematic link")))

            audience.sendMessage(linkComponent)
        }

        return true
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}