package io.schemat.connector.fabric.client.render

import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.material.FluidState
import net.minecraft.core.BlockPos
// 26.x moved BlockAndTintGetter into the client render package
// (net.minecraft.client.renderer.block) and replaced the per-face getShade hook
// with a CardinalLighting record (DEFAULT = vanilla overworld face shading).
//? if >=26.1 {
/*import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.world.level.CardinalLighting
*///?} else {
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockAndTintGetter
//?}
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.lighting.LevelLightEngine

/**
 * Self-contained [BlockAndTintGetter] over a [SchematicSnapshot] - no world, no
 * chunks, no Litematica. The offscreen thumbnail renderer reads states, fluid
 * states, block entities, brightness, light and tint exclusively through this
 * view, so the preview can never "disappear" when Litematica unloads schematic
 * chunks.
 *
 * Interface coverage (verified with javap against the 1.21.11 yarn jar):
 * - `BlockAndTintGetter` abstracts: [getBrightness], [getLightingProvider], [getColor];
 *   defaults [getLightLevel]/[getBaseLightLevel] overridden for full-bright.
 * - `BlockView` abstracts: [getBlockState], [getFluidState], [getBlockEntity].
 * - `HeightLimitView` abstracts: [getHeight], [getBottomY] (sized to cover the
 *   snapshot bounds plus padding so neighbor lookups at the edges stay in-limit).
 *
 * Lighting is full-bright: light levels are a constant 15 and [getBrightness]
 * applies vanilla's directional face shading (up 1.0, down 0.5, N/S 0.8, E/W 0.6)
 * so blocks keep their depth cues. [getLightingProvider] returns the vanilla
 * `LevelLightEngine.EMPTY` stub purely to satisfy the interface - the renderer
 * resolves light through the overridden level getters, never the provider.
 *
 * Tint resolves against the snapshot's captured plains biome (grass/leaves/water
 * get a sensible neutral color); white when no biome was captured.
 */
class SnapshotBlockRenderView(private val snapshot: SchematicSnapshot) : BlockAndTintGetter {

    private val bottomY: Int = snapshot.minPos.y - HEIGHT_PADDING
    private val topYInclusive: Int = snapshot.maxPos.y + HEIGHT_PADDING

    // ---- BlockView -----------------------------------------------------------

    override fun getBlockState(pos: BlockPos): BlockState = snapshot.getBlockState(pos)

    override fun getFluidState(pos: BlockPos): FluidState = snapshot.getBlockState(pos).fluidState

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = snapshot.getBlockEntity(pos)

    // ---- BlockAndTintGetter -----------------------------------------------------

    // 26.x: getShade(Direction, Boolean) is gone; face shading comes from a
    // CardinalLighting record. DEFAULT is bytecode-confirmed to be the vanilla
    // overworld shading (down 0.5, up 1.0, N/S 0.8, E/W 0.6) - identical to the
    // getShade table below. The "model opts out of shading" case is handled by
    // the lighter via the quad's material shade flag on 26.x.
    //? if >=26.1 {
    /*override fun cardinalLighting(): CardinalLighting = CardinalLighting.DEFAULT
    *///?} else {
    /** Vanilla overworld face shading; 1.0 when the model opts out of shading. */
    override fun getShade(direction: Direction, shaded: Boolean): Float {
        if (!shaded) return 1.0f
        return when (direction) {
            Direction.DOWN -> 0.5f
            Direction.UP -> 1.0f
            Direction.NORTH, Direction.SOUTH -> 0.8f
            Direction.WEST, Direction.EAST -> 0.6f
        }
    }
    //?}

    /** Satisfies the interface only - light is answered by the level getters below. */
    override fun getLightEngine(): LevelLightEngine = LevelLightEngine.EMPTY

    override fun getBlockTint(pos: BlockPos, colorResolver: ColorResolver): Int {
        val biome = snapshot.biome?.value() ?: return -1 // white: no world joined at capture
        return colorResolver.getColor(biome, pos.x.toDouble(), pos.z.toDouble())
    }

    override fun getBrightness(type: LightLayer, pos: BlockPos): Int = 15

    override fun getRawBrightness(pos: BlockPos, ambientDarkness: Int): Int = 15

    // ---- HeightLimitView -----------------------------------------------------

    override fun getHeight(): Int = topYInclusive - bottomY + 1

    override fun getMinY(): Int = bottomY

    companion object {
        /** Vertical slack so edge-of-build neighbor lookups never trip the height limit. */
        private const val HEIGHT_PADDING = 16
    }
}
