package io.schemat.connector.fabric.client.render

import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.core.registries.Registries
import net.minecraft.core.Holder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import org.slf4j.LoggerFactory

/**
 * An immutable, frozen capture of a schematic volume: every non-air [BlockState],
 * every instantiated [BlockEntity], the bounds, and a default biome handle for tint.
 *
 * This is the fix for the "build disappears in the preview" bug: the thumbnail
 * preview used to read from Litematica's live `WorldSchematic`, whose chunks get
 * unloaded for render-disabled placements - the block data vanished mid-preview.
 * A snapshot is plain data, fully decoupled from Litematica's chunk/render
 * lifecycle (deliberately NO Litematica imports here - MC types only; the
 * Litematica bridge populates it via [Builder]). Once built it is never mutated,
 * so it is safe to read from the render thread regardless of which thread built it
 * (the bridge builds it on the client thread).
 *
 * Storage is a hash map of non-air states keyed by immutable [BlockPos] - bounds
 * are already volume-capped upstream (see `SchematicRenderSource.clampedRenderRegion`),
 * so worst case is ~VOLUME_CAP³ entries for a solid build.
 */
class SchematicSnapshot private constructor(
    private val states: Map<BlockPos, BlockState>,
    private val blockEntities: Map<BlockPos, BlockEntity>,
    /** Inclusive minimum corner of the captured build (snapshot coordinates). */
    val minPos: BlockPos,
    /** Inclusive maximum corner of the captured build (snapshot coordinates). */
    val maxPos: BlockPos,
    /**
     * Biome used to resolve grass/foliage/water tint (plains, captured from the
     * client world's registry at build time). Null when no world was joined -
     * tint then falls back to white in [SnapshotBlockRenderView.getColor].
     */
    val biome: Holder<Biome>?,
) {
    /** Captured state at [pos]; air for positions never set (including outside bounds). */
    fun getBlockState(pos: BlockPos): BlockState = states[pos] ?: AIR

    /** Captured block entity at [pos], or null. */
    fun getBlockEntity(pos: BlockPos): BlockEntity? = blockEntities[pos]

    /**
     * Mutable accumulator for a snapshot. Populate on the client thread (BE
     * instantiation touches the client world's registries), then [build] once.
     */
    class Builder(
        private val minPos: BlockPos,
        private val maxPos: BlockPos,
    ) {
        private val states = HashMap<BlockPos, BlockState>()
        private val blockEntities = HashMap<BlockPos, BlockEntity>()

        /** Record [state] at [pos]. Air states are skipped (the map stores non-air only). */
        fun setBlockState(pos: BlockPos, state: BlockState) {
            if (!state.isAir) states[pos.immutable()] = state
        }

        /** Record an already-instantiated block entity (area-selection capture from the live world). */
        fun setBlockEntity(pos: BlockPos, blockEntity: BlockEntity) {
            blockEntities[pos.immutable()] = blockEntity
        }

        /**
         * Instantiate a block entity from schematic NBT at [pos] (whose state must
         * already be set) via `BlockEntity.loadStatic(BlockPos, BlockState,
         * CompoundTag, RegistryWrapper.WrapperLookup)`. The client world's
         * `DynamicRegistryManager` is the wrapper lookup; the BE's world ref is set
         * to the client world so renderers that consult it don't NPE. Failures are
         * logged and skipped - the BE render pass tolerates missing entries.
         */
        fun setBlockEntityNbt(pos: BlockPos, nbt: CompoundTag) {
            val immutable = pos.immutable()
            val state = states[immutable] ?: return
            if (!state.hasBlockEntity()) return
            val client = Minecraft.getInstance()
            val world = client.level ?: return
            try {
                val blockEntity = BlockEntity.loadStatic(immutable, state, nbt, world.registryAccess()) ?: return
                runCatching { blockEntity.setLevel(world) }
                blockEntities[immutable] = blockEntity
            } catch (t: Throwable) {
                LOGGER.debug("Skipping block entity at {} in snapshot: {}", immutable, t.message)
            }
        }

        fun build(): SchematicSnapshot =
            SchematicSnapshot(states, blockEntities, minPos, maxPos, captureDefaultBiome())
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-snapshot")
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()

        /**
         * Plains biome entry from the client world's dynamic registry - gives
         * grass/leaves/water a sensible neutral tint in previews. Null outside a world.
         */
        private fun captureDefaultBiome(): Holder<Biome>? = try {
            // ResourceKey.location() was renamed to identifier() in 1.21.11.
            //? if >=1.21.11
            val plainsId = Biomes.PLAINS.identifier()
            //? if <1.21.11
            //val plainsId = Biomes.PLAINS.location()
            Minecraft.getInstance().level?.registryAccess()
                ?.lookup(Registries.BIOME)?.orElse(null)
                ?.get(plainsId)?.orElse(null)
        } catch (t: Throwable) {
            LOGGER.debug("Could not capture a default biome for tint: {}", t.message)
            null
        }
    }
}
