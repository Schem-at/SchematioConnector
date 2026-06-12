package io.schemat.connector.bukkit.adapter

import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.session.ClipboardHolder
import io.schemat.connector.core.api.WorldEditAdapter
import org.bukkit.Bukkit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.logging.Logger

/**
 * Bukkit/Paper implementation of WorldEditAdapter.
 * Provides clipboard operations using WorldEdit-Bukkit API.
 */
class BukkitWorldEditAdapter(private val logger: Logger) : WorldEditAdapter {

    override val isAvailable: Boolean
        get() = try {
            Bukkit.getPluginManager().isPluginEnabled("WorldEdit") &&
                WorldEdit.getInstance() != null
        } catch (e: Exception) {
            false
        }

    override fun getClipboardBytes(playerUuid: UUID): ByteArray? {
        val player = Bukkit.getPlayer(playerUuid) ?: return null
        val clipboard = getClipboard(player) ?: return null
        return clipboardToByteArray(clipboard)
    }

    override fun setClipboardFromBytes(playerUuid: UUID, data: ByteArray, format: String?): Boolean {
        val player = Bukkit.getPlayer(playerUuid) ?: return false
        val clipboard = byteArrayToClipboard(data) ?: return false
        setClipboard(player, clipboard)
        return true
    }

    override fun hasClipboard(playerUuid: UUID): Boolean {
        val player = Bukkit.getPlayer(playerUuid) ?: return false
        return getClipboard(player) != null
    }

    override fun getClipboardDimensions(playerUuid: UUID): Triple<Int, Int, Int>? {
        val player = Bukkit.getPlayer(playerUuid) ?: return null
        val clipboard = getClipboard(player) ?: return null
        val region = clipboard.region
        return Triple(
            region.width,
            region.height,
            region.length
        )
    }

    override fun getClipboardBlockCount(playerUuid: UUID): Int? {
        val player = Bukkit.getPlayer(playerUuid) ?: return null
        val clipboard = getClipboard(player) ?: return null
        return clipboard.region.volume.toInt()
    }

    // ========== Internal WorldEdit Operations ==========

    private fun getWorldEditInstance(): WorldEdit? {
        return try {
            WorldEdit.getInstance()
        } catch (e: Exception) {
            null
        }
    }

    private fun getSessionManager() = getWorldEditInstance()?.sessionManager

    private fun getLocalSession(player: org.bukkit.entity.Player) =
        getSessionManager()?.get(BukkitAdapter.adapt(player))

    private fun getClipboardHolder(player: org.bukkit.entity.Player): ClipboardHolder? {
        return try {
            getLocalSession(player)?.clipboard
        } catch (e: EmptyClipboardException) {
            null
        }
    }

    private fun getClipboard(player: org.bukkit.entity.Player): Clipboard? {
        return getClipboardHolder(player)?.clipboard
    }

    private fun clipboardToByteArray(clipboard: Clipboard): ByteArray? {
        val outputStream = ByteArrayOutputStream()
        try {
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(outputStream).use { writer ->
                writer.write(clipboard)
            }
        } catch (e: Exception) {
            logger.warning("Failed to convert clipboard to bytes: ${e.message}")
            return null
        }
        return outputStream.toByteArray()
    }

    @Suppress("DEPRECATION") // SPONGE_SCHEMATIC is deprecated but needed for legacy file support
    private fun byteArrayToClipboard(data: ByteArray): Clipboard? {
        // First, try WorldEdit's built-in format detection
        try {
            val detectedFormat = ClipboardFormats.findByInputStream { ByteArrayInputStream(data) }
            if (detectedFormat != null) {
                detectedFormat.getReader(ByteArrayInputStream(data)).use { reader ->
                    val clipboard = reader.read()
                    logger.info("Successfully loaded schematic using auto-detected format: ${detectedFormat.name}")
                    return clipboard
                }
            }
        } catch (e: Exception) {
            logger.warning("Auto-detection failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Fallback: try formats explicitly in order
        val formatsToTry = listOf(
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC,
            BuiltInClipboardFormat.SPONGE_SCHEMATIC,
            BuiltInClipboardFormat.MCEDIT_SCHEMATIC
        )

        for (format in formatsToTry) {
            try {
                format.getReader(ByteArrayInputStream(data)).use { reader ->
                    val clipboard = reader.read()
                    logger.info("Successfully loaded schematic using format: ${format.name}")
                    return clipboard
                }
            } catch (e: Exception) {
                logger.warning("Format ${format.name} failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        logger.warning("Failed to load schematic - no compatible format found")
        return null
    }

    private fun setClipboard(player: org.bukkit.entity.Player, clipboard: Clipboard) {
        getLocalSession(player)?.let { session ->
            session.clipboard = ClipboardHolder(clipboard)
        }
    }
}
