package io.schemat.connector.fabric.worldedit

import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.session.ClipboardHolder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Utility functions for interacting with WorldEdit clipboards on Fabric.
 *
 * Uses worldedit-core API directly and reaches FabricAdapter through
 * [WorldEditPlayerAdapter] to avoid Loom version mismatch issues with the
 * worldedit-fabric artifact.
 *
 * Every failure is logged to the server console here; callers turn the null/false
 * results into a message for the player.
 */
object FabricWorldEditUtil {

    private val LOGGER = LoggerFactory.getLogger("schematioconnector-worldedit")

    /**
     * Check if WorldEdit is loaded as a Fabric mod.
     */
    fun isAvailable(): Boolean {
        return FabricLoader.getInstance().isModLoaded("worldedit")
    }

    fun getLocalSession(player: ServerPlayer): LocalSession? {
        return try {
            val wePlayer = WorldEditPlayerAdapter.adapt(player) ?: return null
            WorldEdit.getInstance().sessionManager.get(wePlayer)
        } catch (t: Throwable) {
            LOGGER.warn("Could not open the WorldEdit session of {}", player.scoreboardName, t)
            null
        }
    }

    /**
     * The player's clipboard, or null when it is empty or the session is unreachable.
     * Use [getLocalSession] first to tell the two apart.
     */
    fun getClipboard(player: ServerPlayer): Clipboard? {
        return try {
            getLocalSession(player)?.clipboard?.clipboard
        } catch (e: EmptyClipboardException) {
            null
        }
    }

    /** Returns false when the player's WorldEdit session could not be reached. */
    fun setClipboard(player: ServerPlayer, clipboard: Clipboard): Boolean {
        val session = getLocalSession(player) ?: return false
        session.clipboard = ClipboardHolder(clipboard)
        return true
    }

    fun clipboardToByteArray(clipboard: Clipboard): ByteArray? {
        val outputStream = ByteArrayOutputStream()
        return try {
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(outputStream).use { writer ->
                writer.write(clipboard)
            }
            outputStream.toByteArray()
        } catch (t: Throwable) {
            LOGGER.warn("Could not write the WorldEdit clipboard as a Sponge v3 schematic", t)
            null
        }
    }

    /**
     * Convert a byte array to a Clipboard, trying multiple formats for compatibility.
     * First tries WorldEdit's auto-detection, then falls back to explicit formats.
     */
    @Suppress("DEPRECATION")
    fun byteArrayToClipboard(data: ByteArray): Clipboard? {
        // Try auto-detection first
        try {
            val detectedFormat = ClipboardFormats.findByInputStream { ByteArrayInputStream(data) }
            if (detectedFormat != null) {
                detectedFormat.getReader(ByteArrayInputStream(data)).use { reader ->
                    return reader.read()
                }
            }
        } catch (_: Exception) {
        } catch (_: LinkageError) {
            // WorldEdit changed the detection method's binary signature in 7.4.
            // Its explicit format readers below remain compatible.
        }

        // Fallback: try formats explicitly in order
        val formatsToTry = listOf(
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC,
            BuiltInClipboardFormat.SPONGE_SCHEMATIC,
            BuiltInClipboardFormat.MCEDIT_SCHEMATIC
        )

        var lastFailure: Throwable? = null
        for (format in formatsToTry) {
            try {
                format.getReader(ByteArrayInputStream(data)).use { reader ->
                    return reader.read()
                }
            } catch (t: Throwable) {
                lastFailure = t
            }
        }

        LOGGER.warn("Could not read a {}-byte schematic in any WorldEdit format", data.size, lastFailure)
        return null
    }
}
