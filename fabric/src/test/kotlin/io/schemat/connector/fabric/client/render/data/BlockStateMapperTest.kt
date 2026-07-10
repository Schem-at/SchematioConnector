package io.schemat.connector.fabric.client.render.data

import net.minecraft.core.Direction
import net.minecraft.world.level.block.RotatedPillarBlock
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Exercises the real vanilla blockstate parser, so it needs MC's registries
 * bootstrapped (no client/world required — just the static registry data).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockStateMapperTest {
    @BeforeAll
    fun bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion()
        net.minecraft.server.Bootstrap.bootStrap()
        BlockStateMapper.clearCache()
    }

    @Test
    fun parsesVanillaStateWithProperties() {
        val s = BlockStateMapper.parse("minecraft:oak_log[axis=x]")
        assertNotNull(s)
        assertEquals(Direction.Axis.X, s.getValue(RotatedPillarBlock.AXIS))
    }

    @Test
    fun unknownBlockReturnsNullAndCounts() {
        assertNull(BlockStateMapper.parse("nonexistmod:gizmo[foo=bar]"))
        assert(BlockStateMapper.unresolvedCount >= 1)
    }

    @Test
    fun cachesByString() {
        val a = BlockStateMapper.parse("minecraft:stone")
        val b = BlockStateMapper.parse("minecraft:stone")
        assertSame(a, b)
    }
}
