package io.schemat.schematioConnector.vcs

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The byte-level halves of [SchematicBridge]: sponge `.schem` bytes <-> Nucleation
 * schematic. The WorldEdit-clipboard halves reuse the existing (already exercised)
 * WorldEditUtil read/write paths and need a live server, so they are covered by the
 * run-paper checklist instead.
 *
 * Requires the Nucleation native (bundled for macOS-arm64); skips elsewhere.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchematicBridgeTest {

    @BeforeAll
    fun requireNucleation() {
        assumeTrue(NucleationRuntime.available, "Nucleation native not available on this platform")
    }

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/schematic/single_stone.schem")!!.readBytes()

    @Test
    fun `parses sponge schem bytes into a Nucleation schematic`() {
        SchematicBridge.bytesToSchematic(fixtureBytes()).use { schematic ->
            val dims = schematic.dimensions()
            assertEquals(1, dims.width())
            assertEquals(1, dims.height())
            assertEquals(1, dims.length())
            assertEquals("minecraft:stone", schematic.getBlockName(0, 0, 0).get())
        }
    }

    @Test
    fun `serializes a Nucleation schematic back to parseable schem bytes`() {
        SchematicBridge.bytesToSchematic(fixtureBytes()).use { original ->
            val bytes = SchematicBridge.schematicToBytes(original)
            assertTrue(bytes.isNotEmpty())
            SchematicBridge.bytesToSchematic(bytes).use { reparsed ->
                assertEquals(1, reparsed.dimensions().volume())
                assertEquals("minecraft:stone", reparsed.getBlockName(0, 0, 0).get())
            }
        }
    }

    @Test
    fun `roundtrip preserves block states with properties`() {
        com.github.schemat.nucleation.Schematic("props").use { schematic ->
            schematic.setBlock(0, 0, 0, "minecraft:oak_stairs", mapOf("facing" to "south"))
            schematic.setBlock(1, 0, 0, "minecraft:stone")
            val bytes = SchematicBridge.schematicToBytes(schematic)
            SchematicBridge.bytesToSchematic(bytes).use { reparsed ->
                val stairs = reparsed.getBlock(0, 0, 0).get()
                assertEquals("minecraft:oak_stairs", stairs.name())
                assertEquals("south", stairs.properties()["facing"])
                assertEquals("minecraft:stone", reparsed.getBlockName(1, 0, 0).get())
            }
        }
    }
}
