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

/**
 * Utility functions for interacting with WorldEdit clipboards.
 *
 * Provides methods to get and set player clipboards, and convert between
 * clipboard objects and byte arrays in various schematic formats.
 *
 * Reads WorldEdit formats directly and converts other supported formats, including
 * Litematica, with the bundled Nucleation library. Writes Sponge v3 `.schem` files.
 *
 * ## Usage
 *
 * ```kotlin
 * // Get player's current clipboard
 * val clipboard = WorldEditUtil.getClipboard(player) ?: return
 *
 * // Convert to bytes for upload
 * val bytes = WorldEditUtil.clipboardToByteArray(clipboard)
 *
 * // Load bytes into clipboard
 * val newClipboard = WorldEditUtil.byteArrayToClipboard(downloadedBytes)
 * WorldEditUtil.setClipboard(player, newClipboard)
 * ```
 */
object WorldEditUtil {

    // Get WorldEdit instance directly - this util only works when WorldEdit is available
    fun getWorldEditInstance(): WorldEdit? {
        return try {
            WorldEdit.getInstance()
        } catch (e: Exception) {
            null
        }
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
            // Use SPONGE_V3_SCHEMATIC (modern format, .schem extension)
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(outputStream).use { writer ->
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


    /** Read original download or bridge bytes into a WorldEdit clipboard. */
    fun byteArrayToClipboard(data: ByteArray): Clipboard? =
        ClipboardDecoder.decode(data, SchematioConnector.instance.logger)

    /**
     * Attempt to read clipboard data using a specific format.
     * Catches all exceptions since different formats throw different error types.
     */
    fun byteArrayToClipboard(data: ByteArray, format: BuiltInClipboardFormat): Clipboard? {
        val inputStream = ByteArrayInputStream(data)
        return try {
            format.getReader(inputStream).use { reader ->
                reader.read()
            }
        } catch (e: Exception) {
            // Different formats throw different exceptions (IOException, NoSuchElementException, etc.)
            // Log the actual error to help debug format compatibility issues
            SchematioConnector.instance.logger.warning("Format ${format.name} failed: ${e.javaClass.simpleName}: ${e.message}")
            // Return null to allow fallback to other formats
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