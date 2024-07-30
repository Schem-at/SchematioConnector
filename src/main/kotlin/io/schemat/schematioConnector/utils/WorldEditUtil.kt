package io.schemat.schematioConnector.utils

import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.session.ClipboardHolder
import io.schemat.schematioConnector.SchematioConnector
import org.bukkit.entity.Player
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

object WorldEditUtil {

    fun getWorldEditInstance(): WorldEdit {
        return SchematioConnector.instance.worldEditInstance
    }

    fun getSessionManager() = getWorldEditInstance().sessionManager

    fun getLocalSession(player: Player): LocalSession {
        val actor = BukkitAdapter.adapt(player)
        return getSessionManager().get(actor)
    }

    fun getClipboardHolder(player: Player): ClipboardHolder? {
        return try {
            getLocalSession(player).clipboard
        } catch (e: EmptyClipboardException) {
            null
        }
    }

    fun getClipboard(player: Player): Clipboard? {
        return getClipboardHolder(player)?.clipboard
    }

    fun clipboardToStream(clipboard: Clipboard): ByteArrayOutputStream? {
        val outputStream = ByteArrayOutputStream()
        try {
            BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(outputStream).use { writer ->
                writer.write(clipboard)
            }
        } catch (e: Exception) {
            return null
        }
        return outputStream
    }

    fun clipboardToByteArray(clipboard: Clipboard): ByteArray? {
        return clipboardToStream(clipboard)?.toByteArray()
    }

    fun byteArrayToClipboard(data: ByteArray): Clipboard? {
        return byteArrayToClipboard(data, BuiltInClipboardFormat.SPONGE_SCHEMATIC)
    }

    fun byteArrayToClipboard(data: ByteArray, format: BuiltInClipboardFormat): Clipboard? {
        val inputStream = ByteArrayInputStream(data)
        return try {
            format.getReader(inputStream).use { reader ->
                reader.read()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun setClipboard(player: Player, clipboard: Clipboard) {
        val actor = BukkitAdapter.adapt(player)
        val session = getSessionManager().get(actor)
        session.clipboard = ClipboardHolder(clipboard)
    }
}