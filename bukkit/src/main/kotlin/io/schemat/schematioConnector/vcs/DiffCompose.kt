package io.schemat.schematioConnector.vcs

import io.schemat.connector.core.vcs.Choice
import io.schemat.connector.core.vcs.DiffRegion

/**
 * Spec §6 compose: start from the new HEAD schematic and, for every region the player
 * chose MINE, copy that region's cells (the full bounding box, so removals carry over
 * as air) from the player's edit. THEIRS regions stay exactly as HEAD has them.
 *
 * Works on `.schem` bytes through [SchematicBridge] so it slots directly between the
 * downloaded HEAD, the player's serialized clipboard, and the re-commit upload.
 * Callers must check [NucleationRuntime.available].
 */
object DiffCompose {

    fun compose(
        headBytes: ByteArray,
        mineBytes: ByteArray,
        regions: List<DiffRegion>,
        choices: Map<Int, Choice>,
    ): ByteArray =
        SchematicBridge.bytesToSchematic(headBytes).use { head ->
            SchematicBridge.bytesToSchematic(mineBytes).use { mine ->
                head.copy().use { result ->
                    regions
                        .filter { choices[it.id] == Choice.MINE }
                        .forEach { region -> copyRegion(mine, result, region) }
                    SchematicBridge.schematicToBytes(result)
                }
            }
        }

    /** Overwrites [region]'s bounding box in [target] with [source]'s cells (absent = air). */
    private fun copyRegion(
        source: com.github.schemat.nucleation.Schematic,
        target: com.github.schemat.nucleation.Schematic,
        region: DiffRegion,
    ) {
        for (y in region.min.y..region.max.y) {
            for (x in region.min.x..region.max.x) {
                for (z in region.min.z..region.max.z) {
                    val block = source.getBlock(x, y, z)
                    if (block.isPresent) {
                        block.get().use { state -> target.setBlock(x, y, z, state) }
                    } else {
                        target.setBlock(x, y, z, "minecraft:air")
                    }
                }
            }
        }
    }
}
