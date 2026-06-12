package io.schemat.connector.fabric.client.render

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.level.block.Blocks
import net.minecraft.client.Minecraft
// 26.x: LightTexture is gone (pack moved to LightCoordsUtil, unused here since
// light resolves from the view), the projection buffer was renamed, and
// renderSingleBlock is replaced by ModelBlockRenderer.tesselateBlock against a
// (relocated) client BlockAndTintGetter + per-quad putBakedQuad. See
// OffscreenSchematicRenderer for the javap-confirmed mapping.
//? if >=26.1 {
/*import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.BlockQuadOutput
import net.minecraft.client.renderer.block.ModelBlockRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.CardinalLighting
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.material.FluidState
*///?} else {
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer
//?}
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import org.joml.Matrix4f
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * Task 4 PROTOTYPE SPIKE (throwaway scaffolding): proves the MC 1.21.11 offscreen
 * pipeline end to end - SimpleFramebuffer + output override + PerspectiveProjectionMatrixBuffer
 * ortho projection + BlockRenderManager + ScreenshotRecorder readback → PNG on disk.
 *
 * Trigger: "Debug: capture test" button on the Settings tab. Writes
 * `<gameDir>/schemat-capture-test.png`. Expected: a grass block at ~30°/45° iso
 * angle on a dark blue-grey background. Blank/black image = the output redirect
 * or projection path failed; see SCHEMAT-SPIKE log lines.
 */
object CaptureSpike {

    private val LOGGER = LoggerFactory.getLogger("schematioconnector-client")

    /** Must run on the render thread (e.g. from a GUI button callback). */
    fun run() {
        val client = Minecraft.getInstance()
        val outPath = FabricLoader.getInstance().gameDir.resolve("schemat-capture-test.png")
        var target: OffscreenTarget? = null
        try {
            target = OffscreenTarget()
            target.clear(0.15f, 0.15f, 0.2f, 1f)
            target.bind()

            // Projection: setProjectionMatrix(Matrix4f) is gone in 1.21.11. Verified form:
            // PerspectiveProjectionMatrixBuffer(name).set(joml Matrix4f) -> GpuBufferSlice, then
            // RenderSystem.setProjectionMatrix(slice, ProjectionType.ORTHOGRAPHIC).
            // The PerspectiveProjectionMatrixBuffer owns a GPU buffer - keep it alive until after draw.
            //? if >=26.1 {
            /*val projection = ProjectionMatrixBuffer("schemat-spike")
            *///?} else {
            val projection = PerspectiveProjectionMatrixBuffer("schemat-spike")
            //?}
            val ortho = Matrix4f().setOrtho(-1.5f, 1.5f, -1.5f, 1.5f, -10f, 10f)
            RenderSystem.backupProjectionMatrix()
            RenderSystem.setProjectionMatrix(projection.getBuffer(ortho), ProjectionType.ORTHOGRAPHIC)

            try {
                // Entity-style block render: PoseStack transforms are baked into the
                // vertices CPU-side, so only the projection matters at draw time.
                val matrices = PoseStack()
                matrices.mulPose(Axis.XP.rotationDegrees(30f))
                matrices.mulPose(Axis.YP.rotationDegrees(45f))
                matrices.translate(-0.5f, -0.5f, -0.5f)

                val immediate = client.renderBuffers().bufferSource()
                //? if >=26.1 {
                /*// 26.x single-block render: tessellate against an all-air
                // full-bright view (all faces visible, no neighbor culling) and
                // route each quad by its own material layer - vanilla's
                // moving-block path (BlockFeatureRenderer bytecode).
                val state = Blocks.GRASS_BLOCK.defaultBlockState()
                val output = BlockQuadOutput { x, y, z, quad, quadInstance ->
                    matrices.pushPose()
                    matrices.translate(x, y, z)
                    val layer = when (quad.materialInfo().layer()) {
                        ChunkSectionLayer.TRANSLUCENT -> RenderTypes.translucentMovingBlock()
                        ChunkSectionLayer.CUTOUT -> RenderTypes.cutoutMovingBlock()
                        ChunkSectionLayer.SOLID -> RenderTypes.solidMovingBlock()
                    }
                    immediate.getBuffer(layer).putBakedQuad(matrices.last(), quad, quadInstance)
                    matrices.popPose()
                }
                ModelBlockRenderer(true, true, client.blockColors).tesselateBlock(
                    output, 0f, 0f, 0f,
                    SpikeView, BlockPos.ZERO, state,
                    client.modelManager.blockStateModelSet.get(state),
                    42L,
                )
                *///?} else {
                client.blockRenderer.renderSingleBlock(
                    Blocks.GRASS_BLOCK.defaultBlockState(),
                    matrices,
                    immediate,
                    LightTexture.pack(15, 15),
                    OverlayTexture.NO_OVERLAY,
                )
                //?}
                // Flush all buffered layers into the (overridden) output target.
                immediate.endBatch()
            } finally {
                RenderSystem.restoreProjectionMatrix()
                target.unbind()
                projection.close()
            }

            // Async readback; the framebuffer must outlive the callback.
            val capturedTarget = target
            target = null // ownership moves to the callback
            capturedTarget.readPng { bytes ->
                try {
                    if (bytes != null) {
                        Files.write(outPath, bytes)
                        LOGGER.info(
                            "SCHEMAT-SPIKE: capture OK ({} bytes) -> {}",
                            bytes.size, outPath.toAbsolutePath()
                        )
                    } else {
                        LOGGER.error("SCHEMAT-SPIKE: readback returned null - capture FAILED")
                    }
                } catch (e: Exception) {
                    LOGGER.error("SCHEMAT-SPIKE: writing $outPath failed", e)
                } finally {
                    // GPU deletion belongs on the render thread.
                    client.execute { capturedTarget.close() }
                }
            }
        } catch (e: Throwable) {
            LOGGER.error("SCHEMAT-SPIKE: capture test crashed", e)
            try {
                target?.unbind()
                target?.close()
            } catch (cleanup: Throwable) {
                LOGGER.error("SCHEMAT-SPIKE: cleanup also failed", cleanup)
            }
        }
    }

    //? if >=26.1 {
    /*/**
     * Minimal all-air, full-bright 26.x BlockAndTintGetter for the one-block
     * spike: air neighbors keep every face visible, brightness 15 keeps the
     * block lit, DEFAULT cardinal lighting keeps the vanilla face shading.
     */
    private object SpikeView : BlockAndTintGetter {
        override fun getBlockState(pos: BlockPos): BlockState = Blocks.AIR.defaultBlockState()
        override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState
        override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
        override fun getLightEngine(): LevelLightEngine = LevelLightEngine.EMPTY
        override fun getBrightness(type: LightLayer, pos: BlockPos): Int = 15
        override fun getRawBrightness(pos: BlockPos, ambientDarkness: Int): Int = 15
        override fun cardinalLighting(): CardinalLighting = CardinalLighting.DEFAULT
        override fun getBlockTint(pos: BlockPos, colorResolver: ColorResolver): Int = -1
        override fun getHeight(): Int = 384
        override fun getMinY(): Int = -64
    }
    *///?}
}
