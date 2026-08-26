package io.schemat.connector.core.vcs

/** Change classification rendered by the diff viewer (spec §4 vocabulary). */
enum class DiffKind { ADDED, REMOVED, CHANGED }

/** Block position in schematic-local coordinates. */
data class Vec3(val x: Int, val y: Int, val z: Int)

/**
 * One differing cell. [oldBlock]/[newBlock] are block-state strings in the
 * `minecraft:name[prop=value,...]` form Nucleation emits (parseable by
 * `Bukkit.createBlockData`): ADDED has only [newBlock], REMOVED only [oldBlock],
 * CHANGED both.
 */
data class BlockChange(
    val pos: Vec3,
    val kind: DiffKind,
    val oldBlock: String?,
    val newBlock: String?,
)

/**
 * A spatial cluster of [BlockChange]s with its bounding box. [id] is the region's
 * stable index within its diff (0-based; cursor/choices key on it).
 */
data class DiffRegion(
    val id: Int,
    val min: Vec3,
    val max: Vec3,
    val blocks: List<BlockChange>,
) {
    val addedCount: Int get() = blocks.count { it.kind == DiffKind.ADDED }
    val removedCount: Int get() = blocks.count { it.kind == DiffKind.REMOVED }
    val changedCount: Int get() = blocks.count { it.kind == DiffKind.CHANGED }

    /** Kind-mix label fragment, e.g. `+45 −12 ~7` (spec §4 region label). */
    fun countLabel(): String = "+$addedCount −$removedCount ~$changedCount"
}
