package io.schemat.connector.fabric.client.render.data

import io.schemat.connector.fabric.client.render.SchematicRenderSource
import io.schemat.connector.fabric.client.render.SchematicSnapshot
import io.schemat.connector.fabric.client.render.SnapshotBlockRenderView
import net.minecraft.core.BlockPos

/**
 * Builds a Litematica-free [SchematicRenderSource] straight from schematic bytes:
 * iterate the blocks with Nucleation ([SchematicData]), resolve each to a real
 * [net.minecraft.world.level.block.state.BlockState] with [BlockStateMapper] (modded
 * blocks included; unresolved ones skipped + counted), and freeze them into a
 * [SchematicSnapshot] — the exact same render-source type the Litematica bridge
 * produces, so the renderer/composer don't care where the data came from.
 *
 * Mirrors `LitematicaBridgeImpl.renderSourceFromSchematic`: capture is clamped to the
 * centered [SchematicRenderSource.clampedRenderRegion] (VOLUME_CAP per axis) so a huge
 * schematic doesn't materialize tens of millions of states, while the source still
 * receives the build's TRUE bounds (its `downsampled` flag + camera framing depend on
 * them). Block-entity NBT isn't exposed by Nucleation, so block entities render as
 * defaults (see [SchematicData]).
 *
 * Decode plain data on an IO worker, then resolve states on the client thread.
 */
object NucleationSnapshotSource {
    private data class Block(val x: Int, val y: Int, val z: Int, val state: String)

    /** Owns plain data only; native handles are closed before returning from decode. */
    class Prepared internal constructor(
        private val minPos: BlockPos,
        private val maxPos: BlockPos,
        private val resolve: (SchematicSnapshot.Builder) -> Iterator<Unit>,
    ) {
        private val builder = SchematicSnapshot.Builder(minPos, maxPos)
        private val remaining by lazy { resolve(builder) }

        /** Registry and block-entity access stays on the client thread. */
        fun advance(budgetNanos: Long = 2_000_000L): SchematicRenderSource? {
            val start = System.nanoTime()
            do {
                if (!remaining.hasNext()) {
                    return SchematicRenderSource(SnapshotBlockRenderView(builder.build()), minPos, maxPos)
                }
                remaining.next()
            } while (System.nanoTime() - start < budgetNanos)
            return null
        }
    }

    /** Decode on an IO worker; cancellation releases the native schematic via use. */
    fun decode(bytes: ByteArray, checkCancelled: () -> Unit = {}): Prepared =
        SchematicData.fromBytes(bytes).use { data ->
            checkCancelled()
            require(data.sizeX > 0 && data.sizeY > 0 && data.sizeZ > 0) { "Schematic has empty bounds" }
            val minPos = BlockPos.ZERO
            val maxPos = BlockPos(data.sizeX - 1, data.sizeY - 1, data.sizeZ - 1)
            val (captureMin, captureMax) = SchematicRenderSource.clampedRenderRegion(minPos, maxPos)
            val blocks = ArrayList<Block>()
            val palette = HashMap<String, String>()
            data.forEachBlock { x, y, z, stateString ->
                checkCancelled()
                if (x in captureMin.x..captureMax.x &&
                    y in captureMin.y..captureMax.y &&
                    z in captureMin.z..captureMax.z
                ) blocks.add(Block(x, y, z, palette.getOrPut(stateString) { stateString }))
            }
            Prepared(minPos, maxPos) { builder ->
                sequence {
                    for (block in blocks) {
                        BlockStateMapper.parse(block.state)?.let { state ->
                            val pos = BlockPos(block.x, block.y, block.z)
                            builder.setBlockState(pos, state)
                            if (state.hasBlockEntity()) builder.setDefaultBlockEntity(pos)
                        }
                        yield(Unit)
                    }
                }.iterator()
            }
        }

    /** Synchronous entry point for callers already owning the client thread. */
    fun snapshotFromBytes(bytes: ByteArray): SchematicRenderSource = decode(bytes).advance(Long.MAX_VALUE)!!
}
