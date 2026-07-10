package io.schemat.connector.fabric.client.render.data

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gated to macOS: only the macOS-arm64 Nucleation native ships in the test
 * classpath today (see docs/nucleation-build.md). On other OSes these tests are
 * skipped rather than failing on UnsatisfiedLinkError.
 */
@EnabledOnOs(OS.MAC)
class SchematicDataTest {
    @Test
    fun parsesDimensionsAndBlocks() {
        val bytes = javaClass.getResourceAsStream("/schematic/single_stone.schem")!!.readBytes()
        SchematicData.fromBytes(bytes).use { d ->
            assertEquals(1, d.sizeX)
            assertEquals(1, d.sizeY)
            assertEquals(1, d.sizeZ)
            val states = mutableListOf<String>()
            d.forEachBlock { _, _, _, s -> states += s }
            assertEquals(1, states.size)
            assertTrue(states[0].startsWith("minecraft:stone"), "got: ${states[0]}")
        }
    }

    @Test
    fun canonicalStateSortsAndBracketsProperties() {
        assertEquals("minecraft:stone", SchematicData.canonicalState("minecraft:stone", emptyMap()))
        assertEquals(
            "minecraft:oak_log[axis=x]",
            SchematicData.canonicalState("minecraft:oak_log", mapOf("axis" to "x")),
        )
        // Properties are sorted by key so the string is deterministic regardless of map order.
        assertEquals(
            "minecraft:furnace[facing=north,lit=true]",
            SchematicData.canonicalState("minecraft:furnace", linkedMapOf("lit" to "true", "facing" to "north")),
        )
    }
}
