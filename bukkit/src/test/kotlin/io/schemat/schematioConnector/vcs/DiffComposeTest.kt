package io.schemat.schematioConnector.vcs

import com.github.schemat.nucleation.Schematic
import io.schemat.connector.core.vcs.Choice
import io.schemat.connector.core.vcs.Vec3
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Compose per spec §6, exercised through the real bridge: start from the new HEAD,
 * apply every MINE region's cells from the player's edit, leave THEIRS regions as the
 * HEAD has them. Small schematics, real Nucleation diff/regions - skipped when the
 * native is unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffComposeTest {

    @BeforeAll
    fun requireNucleation() {
        assumeTrue(NucleationRuntime.available, "Nucleation native not available on this platform")
    }

    /** 3x3 stone floor - the new HEAD (THEIRS side). */
    private fun headBytes(): ByteArray = Schematic("head").use { head ->
        for (x in 0..2) for (z in 0..2) head.setBlock(x, 0, z, "minecraft:stone")
        SchematicBridge.schematicToBytes(head)
    }

    /**
     * The player's edit (MINE side): diamond at (0,0,0), stone at (2,0,2) removed
     * (both cluster into one near region), plus glass at (10,0,10) (a far region).
     */
    private fun mineBytes(): ByteArray = Schematic("mine").use { mine ->
        for (x in 0..2) for (z in 0..2) {
            if (x == 2 && z == 2) continue // removal
            mine.setBlock(x, 0, z, "minecraft:stone")
        }
        mine.setBlock(0, 0, 0, "minecraft:diamond_block")
        mine.setBlock(10, 0, 10, "minecraft:glass")
        SchematicBridge.schematicToBytes(mine)
    }

    private fun blockAt(bytes: ByteArray, x: Int, y: Int, z: Int): String? =
        SchematicBridge.bytesToSchematic(bytes).use { schematic ->
            schematic.getBlockName(x, y, z).orElse(null)
        }

    private fun isEmptyOrAir(name: String?) = name == null || name == "minecraft:air"

    @Test
    fun `diff of the fixture splits into a near and a far region`() {
        val regions = DiffEngine.computeRegions(headBytes(), mineBytes())
        assertEquals(2, regions.size)
        assertEquals(Vec3(0, 0, 0), regions[0].min) // near: change + removal
        assertEquals(Vec3(10, 0, 10), regions[1].min) // far: addition
    }

    @Test
    fun `MINE region applies the player's cells including removals`() {
        val head = headBytes()
        val mine = mineBytes()
        val regions = DiffEngine.computeRegions(head, mine)
        val near = regions[0]
        val far = regions[1]

        val composed = DiffCompose.compose(head, mine, regions, mapOf(near.id to Choice.MINE, far.id to Choice.THEIRS))

        assertEquals("minecraft:diamond_block", blockAt(composed, 0, 0, 0)) // my change kept
        assertTrue(isEmptyOrAir(blockAt(composed, 2, 0, 2))) // my removal kept
        assertEquals("minecraft:stone", blockAt(composed, 1, 0, 1)) // untouched floor intact
        assertTrue(isEmptyOrAir(blockAt(composed, 10, 0, 10))) // THEIRS: head never had it
    }

    @Test
    fun `THEIRS region keeps the head's cells`() {
        val head = headBytes()
        val mine = mineBytes()
        val regions = DiffEngine.computeRegions(head, mine)
        val near = regions[0]
        val far = regions[1]

        val composed = DiffCompose.compose(head, mine, regions, mapOf(near.id to Choice.THEIRS, far.id to Choice.MINE))

        assertEquals("minecraft:stone", blockAt(composed, 0, 0, 0)) // head wins the near region
        assertEquals("minecraft:stone", blockAt(composed, 2, 0, 2)) // head's block restored... never touched
        assertEquals("minecraft:glass", blockAt(composed, 10, 0, 10)) // my far addition applied
    }

    @Test
    fun `composed bytes re-diff cleanly against a same-choice reference`() {
        val head = headBytes()
        val mine = mineBytes()
        val regions = DiffEngine.computeRegions(head, mine)
        val allMine = regions.associate { it.id to Choice.MINE }

        val composed = DiffCompose.compose(head, mine, regions, allMine)

        // choosing MINE everywhere must reproduce the player's edit exactly
        val residual = DiffEngine.computeRegions(mine, composed)
        assertTrue(residual.isEmpty(), "expected no residual diff, got $residual")
    }
}
