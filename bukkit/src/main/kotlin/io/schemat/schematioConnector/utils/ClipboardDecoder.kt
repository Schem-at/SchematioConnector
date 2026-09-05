package io.schemat.schematioConnector.utils

import com.github.schemat.nucleation.Schematic
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import io.schemat.schematioConnector.vcs.NucleationRuntime
import java.io.ByteArrayInputStream
import java.util.logging.Logger

/** Decodes original API/bridge files, which may still be in Litematica format. */
internal object ClipboardDecoder {
    @Suppress("DEPRECATION") // Older Sponge files still need the v1/v2 reader.
    fun decode(data: ByteArray, logger: Logger): Clipboard? {
        val failures = mutableListOf<String>()
        val formats = linkedSetOf<ClipboardFormat>()
        // getAll/isFormat are shared by WorldEdit and FAWE. FAWE does not provide
        // ClipboardFormats.findByInputStream(Supplier), even on current releases.
        for (format in ClipboardFormats.getAll()) {
            try {
                if (ByteArrayInputStream(data).use { format.isFormat(it) }) formats.add(format)
            } catch (e: Exception) {
                failures.add("${format.name} detection: ${e.javaClass.simpleName}")
            }
        }
        formats.addAll(listOf(
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC,
            BuiltInClipboardFormat.SPONGE_SCHEMATIC,
            BuiltInClipboardFormat.MCEDIT_SCHEMATIC,
        ))
        for (format in formats) {
            try {
                format.getReader(ByteArrayInputStream(data)).use { return it.read() }
            } catch (e: Exception) {
                failures.add("${format.name}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // The clipboard bridge deliberately serves original files. Older API
        // deployments can also return the original after a failed conversion.
        // Preserve WorldEdit's direct read path; only convert unsupported input.
        if (NucleationRuntime.available) {
            try {
                val sponge = Schematic.fromBytes(data).use { it.toSchematic() }
                BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(ByteArrayInputStream(sponge)).use {
                    return it.read()
                }
            } catch (e: Exception) {
                failures.add("Nucleation conversion: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            failures.add("Nucleation is unavailable; install a supported native platform or supply a WorldEdit .schem file")
        }

        logger.warning("Failed to load schematic: ${failures.joinToString("; ")}")
        return null
    }
}
