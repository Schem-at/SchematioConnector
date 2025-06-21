package io.schemat.schematioConnector.utils

import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.session.ClipboardHolder
import io.schemat.schematioConnector.SchematioConnector
import org.bukkit.entity.Player
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

object WorldEditUtil {

    // This function must now return a nullable WorldEdit?
    fun getWorldEditInstance(): WorldEdit? {
        return SchematioConnector.instance.worldEditInstance
    }

    // Use the ?. safe call operator. If worldEditInstance is null, this will return null.
    fun getSessionManager() = getWorldEditInstance()?.sessionManager

    // This function must also return a nullable LocalSession?
    fun getLocalSession(player: Player): LocalSession? {
        val actor = BukkitAdapter.adapt(player)
        // The safe call propagates here. If getSessionManager() is null, this whole expression is null.
        return getSessionManager()?.get(actor)
    }

    fun getClipboardHolder(player: Player): ClipboardHolder? {
        return try {
            // Safe call needed here too.
            getLocalSession(player)?.clipboard
        } catch (e: EmptyClipboardException) {
            null
        }
    }

    fun getClipboard(player: Player): Clipboard? {
        return getClipboardHolder(player)?.clipboard
    }

    // This function doesn't need changes as it only deals with a Clipboard object
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

    // This function doesn't need changes
    fun clipboardToByteArray(clipboard: Clipboard): ByteArray? {
        return clipboardToStream(clipboard)?.toByteArray()
    }


    // This function doesn't need changes
    fun byteArrayToClipboard(data: ByteArray): Clipboard? {
        ClipboardFormats.getAll()
        val formatsToTry = listOf( BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC, BuiltInClipboardFormat.SPONGE_SCHEMATIC, BuiltInClipboardFormat.MCEDIT_SCHEMATIC)
        for (format in formatsToTry) {
            //log the format being tried
            SchematioConnector.instance.logger.info("Trying to convert byte array to clipboard using format: ${format.name}")

            try {
                return byteArrayToClipboard(data, format)
            } catch (e: Exception) {
                // Log the exception if needed, but continue trying other formats
                e.printStackTrace()
                continue
            }
        }
        return null
    }

    // This function doesn't need changes
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

    // Use a 'let' block to safely operate on the session only if it exists
    fun setClipboard(player: Player, clipboard: Clipboard) {
        getLocalSession(player)?.let { session ->
            session.clipboard = ClipboardHolder(clipboard)
        }
    }
}