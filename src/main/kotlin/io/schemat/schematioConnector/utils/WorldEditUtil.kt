package io.schemat.schematioConnector.utils

import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.session.ClipboardHolder
import io.schemat.schematioConnector.SchematioConnector
import org.bukkit.entity.Player
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Utility functions for interacting with WorldEdit clipboards.
 *
 * Provides methods to get and set player clipboards, and convert between
 * clipboard objects and byte arrays in various schematic formats.
 *
 * ## Supported Formats
 *
 * When reading schematics, formats are tried in order:
 * 1. SPONGE_V3_SCHEMATIC (.schem) - Modern format, preferred
 * 2. SPONGE_SCHEMATIC - Older sponge format
 * 3. MCEDIT_SCHEMATIC (.schematic) - Legacy MCEdit format
 *
 * When writing schematics, SPONGE_SCHEMATIC format is used by default.
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


    /**
     * Convert a byte array to a Clipboard, trying multiple formats for compatibility.
     * First tries WorldEdit's auto-detection, then falls back to explicit formats.
     */
    @Suppress("DEPRECATION") // SPONGE_SCHEMATIC is deprecated but needed for legacy file support
    fun byteArrayToClipboard(data: ByteArray): Clipboard? {
        // First, try WorldEdit's built-in format detection which handles all sponge versions (1, 2, 3)
        try {
            val detectedFormat = ClipboardFormats.findByInputStream { ByteArrayInputStream(data) }
            if (detectedFormat != null) {
                detectedFormat.getReader(ByteArrayInputStream(data)).use { reader ->
                    val clipboard = reader.read()
                    SchematioConnector.instance.logger.info("Successfully loaded schematic using auto-detected format: ${detectedFormat.name}")
                    return clipboard
                }
            }
        } catch (e: Exception) {
            SchematioConnector.instance.logger.warning("Auto-detection failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Fallback: try formats explicitly in order
        val formatsToTry = listOf(
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC,
            BuiltInClipboardFormat.SPONGE_SCHEMATIC,
            BuiltInClipboardFormat.MCEDIT_SCHEMATIC
        )

        for (format in formatsToTry) {
            val clipboard = byteArrayToClipboard(data, format)
            if (clipboard != null) {
                SchematioConnector.instance.logger.info("Successfully loaded schematic using format: ${format.name}")
                return clipboard
            }
        }

        SchematioConnector.instance.logger.warning("Failed to load schematic - no compatible format found")
        return null
    }

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