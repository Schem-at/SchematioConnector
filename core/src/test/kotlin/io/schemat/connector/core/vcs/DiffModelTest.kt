package io.schemat.connector.core.vcs

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * [DiffParser] against the real `nucleation.diff/1` JSON shape (captured from
 * Nucleation 0.2.x `Diff.toJson()`): `added`/`removed` as `{block,pos}`,
 * `changed`/`swapped` as `{from,to,pos}`. Swapped entries are palette-swap detections
 * that Nucleation excludes from its own summary regions, so the parser folds them
 * into CHANGED and regions are clustered here instead.
 */
class DiffParserTest {

    private val sampleJson = """
        {"added":[{"block":"minecraft:glass","pos":[10,0,10]},{"block":"minecraft:oak_planks","pos":[0,1,0]}],
         "changed":[{"from":"minecraft:stone","pos":[1,0,1],"to":"minecraft:diamond_block"}],
         "removed":[{"block":"minecraft:stone","pos":[2,0,2]}],
         "swapped":[{"from":"minecraft:oak_stairs[facing=north]","pos":[0,0,1],"to":"minecraft:oak_stairs[facing=south]"}],
         "schema":"nucleation.diff/1","distance":5,"support":0.5,
         "palette_swaps":[],"transform":{"rotate":{"steps":[]},"translate":[0,0,0]}}
    """.trimIndent()

    @Test
    fun `parses all four change lists into block changes`() {
        val changes = DiffParser.parseBlockChanges(sampleJson)
        assertEquals(5, changes.size)

        val added = changes.filter { it.kind == DiffKind.ADDED }
        assertEquals(setOf(Vec3(10, 0, 10), Vec3(0, 1, 0)), added.map { it.pos }.toSet())
        assertEquals("minecraft:glass", added.first { it.pos == Vec3(10, 0, 10) }.newBlock)
        assertNull(added.first { it.pos == Vec3(10, 0, 10) }.oldBlock)

        val removed = changes.single { it.kind == DiffKind.REMOVED }
        assertEquals(Vec3(2, 0, 2), removed.pos)
        assertEquals("minecraft:stone", removed.oldBlock)
        assertNull(removed.newBlock)

        // changed + swapped both fold into CHANGED with old and new block states
        val changed = changes.filter { it.kind == DiffKind.CHANGED }
        assertEquals(2, changed.size)
        val swappedEntry = changed.single { it.pos == Vec3(0, 0, 1) }
        assertEquals("minecraft:oak_stairs[facing=north]", swappedEntry.oldBlock)
        assertEquals("minecraft:oak_stairs[facing=south]", swappedEntry.newBlock)
    }

    @Test
    fun `clusters nearby changes into one region and distant ones into another`() {
        val regions = DiffParser.parseRegions(sampleJson)
        // (0,1,0), (1,0,1), (2,0,2), (0,0,1) are within merge distance; (10,0,10) is not.
        assertEquals(2, regions.size)
        val near = regions.single { it.blocks.size == 4 }
        val far = regions.single { it.blocks.size == 1 }
        assertEquals(Vec3(0, 0, 0), near.min)
        assertEquals(Vec3(2, 1, 2), near.max)
        assertEquals(Vec3(10, 0, 10), far.min)
        assertEquals(Vec3(10, 0, 10), far.max)
        // deterministic ordering (by min y, then x, then z) and sequential ids
        assertEquals(listOf(0, 1), regions.map { it.id })
        assertTrue(regions[0].min.x <= regions[1].min.x)
    }

    @Test
    fun `region counts and label reflect the kind mix`() {
        val region = DiffParser.parseRegions(sampleJson).single { it.blocks.size == 4 }
        assertEquals(1, region.addedCount)
        assertEquals(1, region.removedCount)
        assertEquals(2, region.changedCount)
        assertEquals("+1 −1 ~2", region.countLabel())
    }

    @Test
    fun `empty or malformed json yields no regions`() {
        assertTrue(DiffParser.parseRegions("{}").isEmpty())
        assertTrue(DiffParser.parseRegions("not json").isEmpty())
    }
}

class DiffSessionTest {

    private fun regionAt(x: Int, id: Int = 0): DiffRegion {
        val block = BlockChange(Vec3(x, 0, 0), DiffKind.ADDED, null, "minecraft:stone")
        return DiffRegion(id = id, min = Vec3(x, 0, 0), max = Vec3(x, 0, 0), blocks = listOf(block))
    }

    private fun session(
        regionCount: Int = 3,
        mode: SessionMode = SessionMode.VIEW,
        clock: () -> Long = { 0L },
    ) = DiffSession(
        schematicId = "s-1",
        labels = "v1" to "v2",
        anchor = Anchor(0, 64, 0),
        regions = (0 until regionCount).map { regionAt(it * 10, it) },
        mode = mode,
        clock = clock,
    )

    @Test
    fun `cursor starts at first region and wraps in both directions`() {
        val s = session(3)
        assertEquals(0, s.cursor)
        s.next(); assertEquals(1, s.cursor)
        s.next(); s.next(); assertEquals(0, s.cursor)
        s.prev(); assertEquals(2, s.cursor)
    }

    @Test
    fun `goto accepts valid indices and rejects out-of-range`() {
        val s = session(3)
        assertTrue(s.goto(2))
        assertEquals(2, s.cursor)
        assertFalse(s.goto(3))
        assertFalse(s.goto(-1))
        assertEquals(2, s.cursor)
    }

    @Test
    fun `layers default visible and toggle independently`() {
        val s = session()
        assertTrue(DiffKind.entries.all { s.isLayerVisible(it) })
        s.setLayerVisible(DiffKind.REMOVED, false)
        assertFalse(s.isLayerVisible(DiffKind.REMOVED))
        assertTrue(s.isLayerVisible(DiffKind.ADDED))
    }

    @Test
    fun `choosing is rejected in VIEW mode`() {
        assertFailsWith<IllegalStateException> { session(mode = SessionMode.VIEW).choose(Choice.MINE) }
    }

    @Test
    fun `resolve mode tracks per-region choices and allDecided`() {
        val s = session(3, mode = SessionMode.RESOLVE)
        assertFalse(s.allDecided)
        assertEquals(listOf(0, 1, 2), s.undecidedRegionIds)

        s.choose(Choice.MINE)          // region 0
        s.goto(1); s.choose(Choice.THEIRS)
        assertEquals(Choice.MINE, s.choices[0])
        assertEquals(Choice.THEIRS, s.choices[1])
        assertFalse(s.allDecided)
        assertEquals(listOf(2), s.undecidedRegionIds)

        s.goto(2); s.choose(Choice.MINE)
        assertTrue(s.allDecided)

        // re-choosing overwrites
        s.goto(0); s.choose(Choice.THEIRS)
        assertEquals(Choice.THEIRS, s.choices[0])
    }

    @Test
    fun `idle time derives from the injected clock and interactions touch it`() {
        var now = 1_000L
        val s = session(3, clock = { now })
        now = 5_000L
        assertEquals(4_000L, s.idleMs())
        s.next() // interaction resets idle
        now = 6_000L
        assertEquals(1_000L, s.idleMs())
    }

    @Test
    fun `dispose is idempotent and marks the session unusable`() {
        val s = session()
        assertFalse(s.disposed)
        s.dispose()
        s.dispose()
        assertTrue(s.disposed)
        assertFailsWith<IllegalStateException> { s.next() }
    }

    @Test
    fun `session with no regions is trivially decided`() {
        val s = session(0, mode = SessionMode.RESOLVE)
        assertTrue(s.allDecided)
        assertTrue(s.undecidedRegionIds.isEmpty())
    }
}
