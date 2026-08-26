package io.schemat.connector.core.vcs

/**
 * Pure budget/LOD math for the vanilla display renderer (spec §4):
 *
 * - at most [MAX_BLOCK_DISPLAYS] display entities per player;
 * - the focused region renders per-block, every other region as bounding box + label
 *   ([BOX_DISPLAY_COST] thin slabs each, reserved off the top);
 * - a focused region that exceeds the remaining budget renders its densest contiguous
 *   Y-slice (window of layers maximizing cell count within budget) and is flagged
 *   [FocusPlan.truncated] so the renderer shows a warning label.
 */
object DisplayBudget {

    /** Spec cap on display entities per viewing player. */
    const val MAX_BLOCK_DISPLAYS = 2000

    /** Display entities consumed by one region bounding box (12 edge slabs). */
    const val BOX_DISPLAY_COST = 12

    /** Cells to spawn for the focused region. */
    data class FocusPlan(
        val regionId: Int,
        val cells: List<BlockChange>,
        val truncated: Boolean,
    )

    /** What to render: per-block focus (possibly truncated) + box-only region ids. */
    data class RenderPlan(
        val focused: FocusPlan?,
        val boxRegionIds: List<Int>,
    )

    /**
     * @param regions all diff regions, ordered
     * @param focusedIndex index into [regions] of the focused region (out-of-range =>
     *   no focus, everything boxes)
     * @param visibleKinds layer toggles; hidden kinds never spawn cells
     * @param budget display-entity cap (tests inject small values)
     */
    fun plan(
        regions: List<DiffRegion>,
        focusedIndex: Int,
        visibleKinds: Set<DiffKind> = DiffKind.entries.toSet(),
        budget: Int = MAX_BLOCK_DISPLAYS,
    ): RenderPlan {
        if (regions.isEmpty()) return RenderPlan(focused = null, boxRegionIds = emptyList())

        val focusedRegion = regions.getOrNull(focusedIndex)
        val boxRegionIds = regions.filter { it !== focusedRegion }.map { it.id }
        if (focusedRegion == null) return RenderPlan(focused = null, boxRegionIds = boxRegionIds)

        val cellBudget = (budget - boxRegionIds.size * BOX_DISPLAY_COST).coerceAtLeast(0)
        val cells = focusedRegion.blocks.filter { it.kind in visibleKinds }

        if (cells.size <= cellBudget) {
            return RenderPlan(FocusPlan(focusedRegion.id, cells, truncated = false), boxRegionIds)
        }

        return RenderPlan(
            FocusPlan(focusedRegion.id, densestSlice(cells, cellBudget), truncated = true),
            boxRegionIds,
        )
    }

    /**
     * The contiguous Y-layer window with the most cells that still fits [cellBudget].
     * When even the densest single layer overflows, that layer's deterministic prefix
     * (cells are already sorted y,x,z) is taken instead.
     */
    private fun densestSlice(cells: List<BlockChange>, cellBudget: Int): List<BlockChange> {
        if (cellBudget == 0) return emptyList()
        val byLayer = cells.groupBy { it.pos.y }.toSortedMap()
        val layers = byLayer.keys.toList()
        val counts = layers.map { byLayer.getValue(it).size }

        var bestStart = -1
        var bestEnd = -1
        var bestCount = 0
        var start = 0
        var windowCount = 0
        var end = 0
        while (end < layers.size) {
            // contiguity in actual Y values: restart the window on gaps
            if (end > start && layers[end] != layers[end - 1] + 1) {
                start = end
                windowCount = 0
            }
            windowCount += counts[end]
            while (windowCount > cellBudget && start < end) {
                windowCount -= counts[start]
                start++
            }
            if (windowCount in (bestCount + 1)..cellBudget) {
                bestCount = windowCount
                bestStart = start
                bestEnd = end
            }
            end++
        }

        if (bestStart == -1) {
            // Densest single layer still overflows the budget: deterministic prefix.
            val densestLayer = layers.maxByOrNull { byLayer.getValue(it).size }!!
            return byLayer.getValue(densestLayer).take(cellBudget)
        }
        return (bestStart..bestEnd).flatMap { byLayer.getValue(layers[it]) }
    }
}
