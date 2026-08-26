package io.schemat.schematioConnector.vcs

import com.github.schemat.nucleation.Schematic
import com.sk89q.worldedit.extent.clipboard.Clipboard
import io.schemat.schematioConnector.utils.WorldEditUtil

/**
 * Bridges WorldEdit clipboards and Nucleation schematics via sponge `.schem` bytes.
 *
 * No per-block mapping: WorldEdit's own writer/reader (the exact paths upload/download
 * already use) produces/consumes the bytes and Nucleation parses/serializes them, so
 * block entities and every state property ride along for free.
 *
 * Callers must check [NucleationRuntime.available] before invoking anything here.
 * Returned [Schematic]s are AutoCloseable native handles - `use { }` or close them.
 */
object SchematicBridge {

    /** Sponge `.schem` bytes -> Nucleation schematic. Throws on unparseable bytes. */
    fun bytesToSchematic(bytes: ByteArray): Schematic = Schematic.fromBytes(bytes)

    /** Nucleation schematic -> sponge `.schem` bytes. */
    fun schematicToBytes(schematic: Schematic): ByteArray = schematic.toSchematic()

    /**
     * WorldEdit clipboard -> Nucleation schematic (via the existing upload write path).
     * Null when the clipboard could not be serialized.
     */
    fun clipboardToSchematic(clipboard: Clipboard): Schematic? =
        WorldEditUtil.clipboardToByteArray(clipboard)?.let { bytesToSchematic(it) }

    /**
     * Nucleation schematic -> WorldEdit clipboard (via the existing download read path).
     * Null when WorldEdit rejects the serialized bytes.
     */
    fun schematicToClipboard(schematic: Schematic): Clipboard? =
        WorldEditUtil.byteArrayToClipboard(schematicToBytes(schematic))
}
