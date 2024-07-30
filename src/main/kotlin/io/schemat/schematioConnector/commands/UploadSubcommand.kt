package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.HttpUtil
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.entity.ContentType
import com.google.gson.Gson
import org.apache.http.util.EntityUtils
import org.bukkit.command.CommandSender

class UploadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMAT_UPLOAD_URL_ENDPOINT = "/schematics/upload"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()
        val clipboard = WorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            audience.sendMessage(Component.text("No clipboard found").color(NamedTextColor.RED))
            return false
        }

        audience.sendMessage(Component.text("Uploading schematic...").color(NamedTextColor.YELLOW))

        runBlocking {
            val authorUUID = player.uniqueId.toString()
            val builder = MultipartEntityBuilder.create()
                .addTextBody("author", authorUUID)
                .addBinaryBody("schematic", WorldEditUtil.clipboardToByteArray(clipboard), ContentType.DEFAULT_BINARY, "schematic")

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