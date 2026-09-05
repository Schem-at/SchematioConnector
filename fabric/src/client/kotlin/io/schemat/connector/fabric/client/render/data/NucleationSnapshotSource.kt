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
 * Call on the client thread (registry access + the immutable snapshot is then safe to
 * read from the render thread).
 */
object NucleationSnapshotSource {
    fun snapshotFromBytes(bytes: ByteArray): SchematicRenderSource =
        SchematicData.fromBytes(bytes).use { data ->
            val minPos = BlockPos.ZERO
            val maxPos = BlockPos(data.sizeX - 1, data.sizeY - 1, data.sizeZ - 1)
            val (captureMin, captureMax) = SchematicRenderSource.clampedRenderRegion(minPos, maxPos)

            val builder = SchematicSnapshot.Builder(minPos, maxPos)
            data.forEachBlock { x, y, z, stateString ->
                if (x in captureMin.x..captureMax.x &&
                    y in captureMin.y..captureMax.y &&
                    z in captureMin.z..captureMax.z
                ) {
                    // setBlockState skips air; BlockStateMapper returns null for unknown blocks.
                    BlockStateMapper.parse(stateString)?.let { state ->
                        val pos = BlockPos(x, y, z)
                        builder.setBlockState(pos, state)
                        if (state.hasBlockEntity()) builder.setDefaultBlockEntity(pos)
                    }
                }
            }
            SchematicRenderSource(SnapshotBlockRenderView(builder.build()), minPos, maxPos)
        }
}
