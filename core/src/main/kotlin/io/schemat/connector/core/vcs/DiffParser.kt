package io.schemat.connector.core.vcs

import com.google.gson.JsonObject
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetString

/**
 * Parses Nucleation `Diff.toJson()` output (`schema: nucleation.diff/1`) into
 * [BlockChange]s and clusters them into [DiffRegion]s.
 *
 * Why not Nucleation's own `summaryJson().regions`: those clusters are single-kind
 * (one region can never produce the spec's mixed `+a −r ~c` label) and blocks that
 * Nucleation attributes to a detected palette swap (`swapped`) are excluded from them
 * entirely - a conflict viewer must show every differing cell. So the full per-block
 * lists are parsed (`added`/`removed` as `{block,pos}`, `changed`/`swapped` as
 * `{from,to,pos}`, swapped folded into CHANGED) and clustered here, deterministically.
 */
object DiffParser {

    /** Chebyshev distance at which two changed cells still belong to the same region. */
    const val MERGE_DISTANCE = 2

    /** All differing cells of the diff; empty on malformed input. */
    fun parseBlockChanges(diffJson: String): List<BlockChange> {
        val json = parseJsonSafe(diffJson) ?: return emptyList()
        val changes = mutableListOf<BlockChange>()
        json.entries("added").forEach { entry ->
            entry.posOrNull()?.let { changes.add(BlockChange(it, DiffKind.ADDED, null, entry.safeGetString("block"))) }
        }
        json.entries("removed").forEach { entry ->
            entry.posOrNull()?.let { changes.add(BlockChange(it, DiffKind.REMOVED, entry.safeGetString("block"), null)) }
        }
        for (key in listOf("changed", "swapped")) {
            json.entries(key).forEach { entry ->
                entry.posOrNull()?.let {
                    changes.add(BlockChange(it, DiffKind.CHANGED, entry.safeGetString("from"), entry.safeGetString("to")))
                }
            }
        }
        return changes
    }

    /** [parseBlockChanges] + [clusterRegions]. */
    fun parseRegions(diffJson: String): List<DiffRegion> = clusterRegions(parseBlockChanges(diffJson))

    /**
     * Union-find clustering: cells within [MERGE_DISTANCE] (Chebyshev) merge into one
     * region. Regions are ordered by (min.y, min.x, min.z) and ids assigned in that
     * order, so the same diff always yields the same region numbering.
     */
    fun clusterRegions(changes: List<BlockChange>): List<DiffRegion> {
        if (changes.isEmpty()) return emptyList()

        val parent = IntArray(changes.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var cur = i
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // Bucket by position; each cell unions with occupied cells in its
        // (2*MERGE_DISTANCE+1)^3 neighborhood.
        val byPos = HashMap<Vec3, Int>(changes.size * 2)
        changes.forEachIndexed { index, change -> byPos[change.pos] = index }
        changes.forEachIndexed { index, change ->
            for (dx in -MERGE_DISTANCE..MERGE_DISTANCE) {
                for (dy in -MERGE_DISTANCE..MERGE_DISTANCE) {
                    for (dz in -MERGE_DISTANCE..MERGE_DISTANCE) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        val neighbor = byPos[Vec3(change.pos.x + dx, change.pos.y + dy, change.pos.z + dz)]
                        if (neighbor != null) union(index, neighbor)
                    }
                }
            }
        }

        val groups = changes.indices.groupBy { find(it) }.values
        val regions = groups.map { indices ->
            val blocks = indices.map { changes[it] }.sortedWith(compareBy({ it.pos.y }, { it.pos.x }, { it.pos.z }))
            DiffRegion(
                id = -1,
                min = Vec3(blocks.minOf { it.pos.x }, blocks.minOf { it.pos.y }, blocks.minOf { it.pos.z }),
                max = Vec3(blocks.maxOf { it.pos.x }, blocks.maxOf { it.pos.y }, blocks.maxOf { it.pos.z }),
                blocks = blocks,
            )
        }
        return regions
            .sortedWith(compareBy({ it.min.y }, { it.min.x }, { it.min.z }))
            .mapIndexed { id, region -> region.copy(id = id) }
    }

    private fun JsonObject.entries(key: String): List<JsonObject> =
        safeGetArray(key).mapNotNull { if (it.isJsonObject) it.asJsonObject else null }

    private fun JsonObject.posOrNull(): Vec3? {
        val pos = get("pos")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (pos.size() < 3) return null
        return try {
            Vec3(pos[0].asInt, pos[1].asInt, pos[2].asInt)
        } catch (e: Exception) {
            null
        }
    }
}
