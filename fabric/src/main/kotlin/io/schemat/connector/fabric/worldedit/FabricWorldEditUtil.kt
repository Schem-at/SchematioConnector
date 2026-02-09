package io.schemat.connector.fabric.worldedit

import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.entity.Player
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.session.ClipboardHolder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.network.ServerPlayerEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Utility functions for interacting with WorldEdit clipboards on Fabric.
 *
 * Uses worldedit-core API directly and calls FabricAdapter via reflection
 * to avoid Loom version mismatch issues with the worldedit-fabric artifact.
 */
object FabricWorldEditUtil {

    /**
     * Check if WorldEdit is loaded as a Fabric mod.
     */
    fun isAvailable(): Boolean {
        return FabricLoader.getInstance().isModLoaded("worldedit")
    }

    /**
     * Adapt a Fabric ServerPlayerEntity to a WorldEdit Player via reflection.
     * Calls com.sk89q.worldedit.fabric.FabricAdapter.adaptPlayer(ServerPlayerEntity)
     */
    private fun adaptPlayer(player: ServerPlayerEntity): Player? {
        return try {
            val adapterClass = Class.forName("com.sk89q.worldedit.fabric.FabricAdapter")
            val method = adapterClass.methods.first { m ->
                m.name == "adaptPlayer" && m.parameterCount == 1
            }
            method.invoke(null, player) as Player
        } catch (e: Exception) {
            null
        }
    }

    fun getLocalSession(player: ServerPlayerEntity): LocalSession? {
        return try {
            val wePlayer = adaptPlayer(player) ?: return null
            WorldEdit.getInstance().sessionManager.get(wePlayer)
        } catch (e: Exception) {
            null
        }
    }

    fun getClipboard(player: ServerPlayerEntity): Clipboard? {
        return try {
            getLocalSession(player)?.clipboard?.clipboard
        } catch (e: EmptyClipboardException) {
            null
        }
    }

    fun setClipboard(player: ServerPlayerEntity, clipboard: Clipboard) {
        getLocalSession(player)?.let { session ->
            session.clipboard = ClipboardHolder(clipboard)
        }
    }

    fun clipboardToByteArray(clipboard: Clipboard): ByteArray? {
        val outputStream = ByteArrayOutputStream()
        return try {
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(outputStream).use { writer ->
                writer.write(clipboard)
            }
            outputStream.toByteArray()
        } catch (e: Exception) {
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
        } catch (_: Exception) {}

        // Fallback: try formats explicitly in order
        val formatsToTry = listOf(
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC,
            BuiltInClipboardFormat.SPONGE_SCHEMATIC,
            BuiltInClipboardFormat.MCEDIT_SCHEMATIC
        )

        for (format in formatsToTry) {
            try {
                format.getReader(ByteArrayInputStream(data)).use { reader ->
                    return reader.read()
                }
            } catch (_: Exception) {
                continue
            }
        }

        return null
    }
}
