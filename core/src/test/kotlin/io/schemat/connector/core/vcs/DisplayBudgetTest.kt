package io.schemat.connector.core.vcs

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * [DisplayBudget] - the pure budget/LOD math behind VanillaDisplayRenderer (spec §4):
 * <= 2000 block displays, focused region per-block, unfocused regions box+label only,
 * an oversized focused region renders its densest contiguous Y-slice + warning.
 */
class DisplayBudgetTest {

    private fun region(id: Int, cells: List<Vec3>, kind: DiffKind = DiffKind.ADDED): DiffRegion {
        val blocks = cells.map { BlockChange(it, kind, "minecraft:stone", "minecraft:stone") }
            .sortedWith(compareBy({ it.pos.y }, { it.pos.x }, { it.pos.z }))
        return DiffRegion(
            id = id,
            min = Vec3(cells.minOf { it.x }, cells.minOf { it.y }, cells.minOf { it.z }),
            max = Vec3(cells.maxOf { it.x }, cells.maxOf { it.y }, cells.maxOf { it.z }),
            blocks = blocks,
        )
    }

    private fun line(y: Int, count: Int): List<Vec3> = (0 until count).map { Vec3(it, y, 0) }

    @Test
    fun `small focused region renders fully, others as boxes`() {
        val regions = listOf(region(0, line(0, 5)), region(1, line(10, 5)), region(2, line(20, 5)))
        val plan = DisplayBudget.plan(regions, focusedIndex = 1, budget = 2000)

        val focus = plan.focused!!
        assertEquals(1, focus.regionId)
        assertEquals(5, focus.cells.size)
        assertFalse(focus.truncated)
        assertEquals(listOf(0, 2), plan.boxRegionIds)
    }

    @Test
    fun `oversized focused region keeps its densest contiguous y-slice`() {
        // y=0: 10 cells, y=1: 50 cells, y=2: 40 cells, y=3: 5 cells; budget for ~90 cells
        val cells = line(0, 10) + line(1, 50) + line(2, 40) + line(3, 5)
        val plan = DisplayBudget.plan(listOf(region(0, cells)), focusedIndex = 0, budget = 90)

        val focus = plan.focused!!
        assertTrue(focus.truncated)
        // densest window under 90 is y=1..2 (90 cells) - not y=0..1 (60) or y=2..3 (45)
        assertEquals(90, focus.cells.size)
        assertEquals(setOf(1, 2), focus.cells.map { it.pos.y }.toSet())
    }

    @Test
    fun `single overfull layer truncates deterministically to the budget`() {
        val plan = DisplayBudget.plan(listOf(region(0, line(0, 100))), focusedIndex = 0, budget = 30)
        val focus = plan.focused!!
        assertTrue(focus.truncated)
        assertEquals(30, focus.cells.size)
        // deterministic prefix of the region's sorted cells
        assertEquals((0 until 30).map { Vec3(it, 0, 0) }, focus.cells.map { it.pos })
    }

    @Test
    fun `box cost of unfocused regions is reserved before focus cells`() {
        // 3 unfocused regions reserve 3 * BOX_DISPLAY_COST; focused region only gets the rest
        val regions = (0..2).map { region(it, line(it * 10, 2)) } + region(3, line(50, 100))
        val budget = 3 * DisplayBudget.BOX_DISPLAY_COST + 40
        val plan = DisplayBudget.plan(regions, focusedIndex = 3, budget = budget)
        val focus = plan.focused!!
        assertTrue(focus.truncated)
        assertEquals(40, focus.cells.size)
    }

    @Test
    fun `hidden layers are excluded from the focused cells`() {
        val cells = line(0, 4)
        val mixed = region(0, cells, DiffKind.ADDED).let { r ->
            r.copy(blocks = r.blocks.mapIndexed { i, b -> if (i % 2 == 0) b.copy(kind = DiffKind.REMOVED) else b })
        }
        val plan = DisplayBudget.plan(listOf(mixed), 0, visibleKinds = setOf(DiffKind.REMOVED), budget = 2000)
        assertEquals(2, plan.focused!!.cells.size)
        assertTrue(plan.focused!!.cells.all { it.kind == DiffKind.REMOVED })
    }

    @Test
    fun `empty regions yield an empty plan`() {
        val plan = DisplayBudget.plan(emptyList(), 0, budget = 2000)
        assertNull(plan.focused)
        assertTrue(plan.boxRegionIds.isEmpty())
    }

    @Test
    fun `default budget is the spec cap`() {
        assertEquals(2000, DisplayBudget.MAX_BLOCK_DISPLAYS)
    }
}
