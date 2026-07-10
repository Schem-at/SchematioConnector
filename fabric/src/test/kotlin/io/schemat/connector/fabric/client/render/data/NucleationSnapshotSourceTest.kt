package io.schemat.connector.fabric.client.render.data

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertEquals

/**
 * Integration test: schematic bytes -> Nucleation iterate -> BlockStateMapper ->
 * frozen snapshot render source. Needs the Nucleation native (macOS-gated) AND MC
 * registries (bootstrapped) for the block parse.
 */
@EnabledOnOs(OS.MAC)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NucleationSnapshotSourceTest {
    @BeforeAll
    fun bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion()
        net.minecraft.server.Bootstrap.bootStrap()
        BlockStateMapper.clearCache()
    }

    @Test
    fun buildsSingleStoneSource() {
        val bytes = javaClass.getResourceAsStream("/schematic/single_stone.schem")!!.readBytes()
        val source = NucleationSnapshotSource.snapshotFromBytes(bytes)

        assertEquals(1, source.sizeX)
        assertEquals(1, source.sizeY)
        assertEquals(1, source.sizeZ)
        assertEquals(
            Blocks.STONE.defaultBlockState(),
            source.view.getBlockState(BlockPos(0, 0, 0)),
        )
    }
}
