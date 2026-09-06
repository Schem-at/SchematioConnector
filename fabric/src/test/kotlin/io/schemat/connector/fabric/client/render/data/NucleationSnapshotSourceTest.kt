package io.schemat.connector.fabric.client.render.data

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.github.schemat.nucleation.Schematic
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * Integration test: schematic bytes -> Nucleation iterate -> BlockStateMapper ->
 * frozen snapshot render source. Needs the bundled Nucleation native and MC
 * registries (bootstrapped) for the block parse.
 */
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

    @Test
    fun includesDefaultBlockEntitiesForFilePreviews() {
        val bytes = Schematic("chest-preview").use {
            it.setBlock(0, 0, 0, "minecraft:chest[facing=north,type=single,waterlogged=false]")
            it.toSchematic()
        }
        val source = NucleationSnapshotSource.snapshotFromBytes(bytes)
        val chest = assertIs<ChestBlockEntity>(source.view.getBlockEntity(BlockPos.ZERO))
        assertEquals(source.view.getBlockState(BlockPos.ZERO), chest.blockState)
        assertEquals(listOf(chest), source.blockEntities())
    }

    @Test
    fun resolvesAcrossBudgetsWithoutLosingBlocks() {
        val bytes = Schematic("incremental-preview").use {
            repeat(12) { x -> it.setBlock(x, 0, 0, "minecraft:stone") }
            it.toSchematic()
        }
        val pending = NucleationSnapshotSource.decode(bytes)
        repeat(12) { kotlin.test.assertNull(pending.advance(0L)) }
        val source = kotlin.test.assertNotNull(pending.advance(0L))
        repeat(12) { x -> assertEquals(Blocks.STONE.defaultBlockState(), source.view.getBlockState(BlockPos(x, 0, 0))) }
    }

    @Test
    fun cancelledDecodeDoesNotPoisonNextSnapshot() {
        val bytes = javaClass.getResourceAsStream("/schematic/single_stone.schem")!!.readBytes()
        var visited = 0
        kotlin.test.assertFailsWith<java.util.concurrent.CancellationException> {
            NucleationSnapshotSource.decode(bytes) {
                if (++visited == 2) throw java.util.concurrent.CancellationException()
            }
        }
        assertEquals(Blocks.STONE.defaultBlockState(), NucleationSnapshotSource.snapshotFromBytes(bytes).view.getBlockState(BlockPos.ZERO))
    }
}
