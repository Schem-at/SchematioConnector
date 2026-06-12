package io.schemat.connector.fabric.client.render

import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
// 26.x moved BlockAndTintGetter to net.minecraft.client.renderer.block.
//? if >=26.1 {
/*import net.minecraft.client.renderer.block.BlockAndTintGetter
*///?} else {
import net.minecraft.world.level.BlockAndTintGetter
//?}
import kotlin.math.sqrt

/**
 * "What to draw + where the camera frames it" for the offscreen thumbnail renderer.
 *
 * Holds a [BlockAndTintGetter] (a [SnapshotBlockRenderView] over a frozen
 * [SchematicSnapshot]) plus the bounding box of the build inside that view.
 *
 * The view is a SNAPSHOT, not a live world: the Litematica bridge captures the
 * schematic's blocks/BEs once into plain data, so the source has no lifecycle -
 * nothing to release, nothing Litematica can unload out from under the preview.
 *
 * Deliberately MC-types only - NO Litematica imports - so non-Litematica code (the
 * composer screen, the renderer, the wizard) can hold one. The Litematica bridge is
 * the only thing that constructs it from Litematica data
 * (`LitematicaBridge.loadRenderSource`). The snapshot is immutable, so reads are
 * safe from the render thread.
 */
class SchematicRenderSource(
    /** Frozen render view to read states/BEs/tint/light from. */
    val view: BlockAndTintGetter,
    min: BlockPos,
    max: BlockPos,
) {
    /** Inclusive minimum corner of the build, in [view] coordinates. */
    val minPos: BlockPos = BlockPos(
        minOf(min.x, max.x),
        minOf(min.y, max.y),
        minOf(min.z, max.z),
    )

    /** Inclusive maximum corner of the build, in [view] coordinates. */
    val maxPos: BlockPos = BlockPos(
        maxOf(min.x, max.x),
        maxOf(min.y, max.y),
        maxOf(min.z, max.z),
    )

    val sizeX: Int = maxPos.x - minPos.x + 1
    val sizeY: Int = maxPos.y - minPos.y + 1
    val sizeZ: Int = maxPos.z - minPos.z + 1

    /** World-space AABB enclosing every block cell (max corner is exclusive, +1). */
    val bounds: AABB = AABB(
        minPos.x.toDouble(), minPos.y.toDouble(), minPos.z.toDouble(),
        maxPos.x + 1.0, maxPos.y + 1.0, maxPos.z + 1.0,
    )

    /** True when the build exceeds [VOLUME_CAP] in any dimension - composer shows a "large build" note. */
    val downsampled: Boolean = maxOf(sizeX, sizeY, sizeZ) > VOLUME_CAP

    // ---- effective render region (VOLUME_CAP enforcement) ------------------
    //
    // Iterating the FULL bounds of a huge build every frame freezes the client
    // (a 300³ placement is ~27M cells/frame). When any dimension exceeds
    // VOLUME_CAP, the renderer iterates a CENTERED sub-region clamped to
    // VOLUME_CAP per axis instead (worst case VOLUME_CAP³ ≈ 884k cells), and
    // the camera frames that sub-region - not the full, mostly-unrendered
    // build. `downsampled` stays true so the composer shows the
    // "large build - preview may omit detail" note.

    /** Inclusive minimum corner of the rendered (clamped, centered) sub-region. */
    val renderMinPos: BlockPos

    /** Inclusive maximum corner of the rendered (clamped, centered) sub-region. */
    val renderMaxPos: BlockPos

    init {
        val (clampedMin, clampedMax) = clampedRenderRegion(minPos, maxPos)
        renderMinPos = clampedMin
        renderMaxPos = clampedMax
    }

    /** Per-axis size of the rendered sub-region (≤ [VOLUME_CAP]). */
    private val renderSizeX: Int = renderMaxPos.x - renderMinPos.x + 1
    private val renderSizeY: Int = renderMaxPos.y - renderMinPos.y + 1
    private val renderSizeZ: Int = renderMaxPos.z - renderMinPos.z + 1

    /** World-space AABB of the rendered sub-region (max corner exclusive, +1). */
    val renderBounds: AABB = AABB(
        renderMinPos.x.toDouble(), renderMinPos.y.toDouble(), renderMinPos.z.toDouble(),
        renderMaxPos.x + 1.0, renderMaxPos.y + 1.0, renderMaxPos.z + 1.0,
    )

    /** Geometric center of the RENDERED region - the camera orbit target. */
    fun center(): Vec3 = renderBounds.center

    /** Half the rendered region's bounding-box diagonal, in blocks - drives camera distance. */
    fun radius(): Float {
        val dx = renderSizeX.toDouble()
        val dy = renderSizeY.toDouble()
        val dz = renderSizeZ.toDouble()
        return (0.5 * sqrt(dx * dx + dy * dy + dz * dz)).toFloat()
    }

    /**
     * Positions of all non-air states within the RENDERED (clamped) region, lazily.
     * Positions are immutable copies (safe to retain across iterations).
     */
    fun blockPositions(): Sequence<BlockPos> =
        BlockPos.betweenClosed(renderMinPos, renderMaxPos).asSequence()
            .filter { pos -> !view.getBlockState(pos).isAir }
            .map { it.immutable() }

    /** Block entities present in the view within the RENDERED (clamped) region (chests, signs, …). */
    fun blockEntities(): List<BlockEntity> {
        val result = mutableListOf<BlockEntity>()
        for (pos in BlockPos.betweenClosed(renderMinPos, renderMaxPos)) {
            val state = view.getBlockState(pos)
            if (!state.isAir && state.hasBlockEntity()) {
                view.getBlockEntity(pos)?.let { result += it }
            }
        }
        return result
    }

    companion object {
        /**
         * The CENTERED sub-region of `[min]..[max]` (both inclusive, must be ordered)
         * clamped to [VOLUME_CAP] per axis - the exact region the renderer iterates.
         * The Litematica bridge uses the same function to bound snapshot CAPTURE, so
         * the captured volume and the rendered volume always coincide.
         */
        fun clampedRenderRegion(min: BlockPos, max: BlockPos): Pair<BlockPos, BlockPos> {
            val sizeX = max.x - min.x + 1
            val sizeY = max.y - min.y + 1
            val sizeZ = max.z - min.z + 1
            val clampedX = minOf(sizeX, VOLUME_CAP)
            val clampedY = minOf(sizeY, VOLUME_CAP)
            val clampedZ = minOf(sizeZ, VOLUME_CAP)
            val clampedMin = BlockPos(
                min.x + (sizeX - clampedX) / 2,
                min.y + (sizeY - clampedY) / 2,
                min.z + (sizeZ - clampedZ) / 2,
            )
            val clampedMax = BlockPos(
                clampedMin.x + clampedX - 1,
                clampedMin.y + clampedY - 1,
                clampedMin.z + clampedZ - 1,
            )
            return clampedMin to clampedMax
        }
    }
}
