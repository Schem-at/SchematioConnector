package io.schemat.connector.fabric.client.render

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
// 26.x render-pipeline restructure (all javap-confirmed against the deobf 26.1 jar):
// - ItemBlockRenderTypes is GONE; the chunk layer now lives per-quad on
//   BakedQuad.materialInfo().layer(), and FluidRenderer.Output hands the layer
//   to the caller directly.
// - LightTexture is GONE; packed lightmap coords moved to
//   net.minecraft.util.LightCoordsUtil (same 0xF000F0 packing).
// - PerspectiveProjectionMatrixBuffer was renamed ProjectionMatrixBuffer
//   (same ctor(String)/getBuffer(Matrix4f)/close() shape).
// - BlockRenderDispatcher (Minecraft.blockRenderer) is GONE; blocks tessellate
//   through ModelBlockRenderer.tesselateBlock(BlockQuadOutput, ...) and fluids
//   through FluidRenderer.tesselate(...), both against the (relocated)
//   client BlockAndTintGetter.
//? if >=26.1 {
/*import net.minecraft.client.renderer.block.ModelBlockRenderer
import net.minecraft.client.renderer.block.BlockQuadOutput
import net.minecraft.client.renderer.block.FluidRenderer
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.util.LightCoordsUtil
*///?} else {
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer
//?}
// 1.21.11 moved render types into their own package and split the static
// factories into RenderTypes; the alias keeps call sites identical on <=1.21.10.
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
//?} else {
/*import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.RenderType as RenderTypes
*///?}
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
// The render-state + submit-queue block entity flow exists since 1.21.9;
// 1.21.8 renders block entities directly through the dispatcher.
//? if >=1.21.9 {
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
//?}
// 26.x moved CameraRenderState into renderer.state.level.
//? if >=26.1 {
/*import net.minecraft.client.renderer.state.level.CameraRenderState
*///?}
//? if >=1.21.9 && <26.1 {
import net.minecraft.client.renderer.state.CameraRenderState
//?}
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.level.material.FluidState
import net.minecraft.resources.Identifier
import net.minecraft.core.BlockPos
import com.mojang.math.Axis
import net.minecraft.world.phys.Vec3
import net.minecraft.util.RandomSource
import org.joml.Matrix4f
import org.joml.Vector3f
import org.slf4j.LoggerFactory
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Task 6: offscreen schematic renderer - three passes (blocks, fluids, block
 * entities) into an [OffscreenTarget], framed by a [CameraPose].
 *
 * Coordinate scheme (ONE consistent scheme everywhere): the model-view
 * PoseStack is `T(panX·r, panY·r, -camDist) · Rx(pitch) · Ry(yaw)`, and every
 * pass translates per draw by `(worldPos - center)` so the build's bounding-box
 * center sits at the view-space origin, offset by the screen-plane pan. Projection (ortho for ISOMETRIC,
 * perspective for PERSPECTIVE) is applied via PerspectiveProjectionMatrixBuffer at draw time.
 *
 * Verified against the remapped 1.21.11 yarn jar (javap):
 * - `BlockRenderManager.renderBatched(BlockState, BlockPos, BlockRenderView,
 *    PoseStack, VertexConsumer, boolean, List<BlockModelPart>)`
 * - `BlockRenderManager.renderLiquid(BlockPos, BlockRenderView, VertexConsumer,
 *    BlockState, FluidState)` - NOTE: FluidRenderer emits vertices at
 *    SECTION-LOCAL coords (`pos & 15`, bytecode-confirmed), untouched by any
 *    PoseStack, so this pass wraps the buffer in a transforming
 *    VertexConsumer that applies the camera matrix (translated to the
 *    section origin) on the CPU.
 * - `ItemBlockRenderTypes.getBlockLayer(BlockState): ChunkSectionLayer` returns a
 *    chunk-layer ENUM (not a RenderType) in 1.21.11; for immediate-mode
 *    drawing the correct lookup is
 *    `ItemBlockRenderTypes.getMovingBlockRenderType(BlockState): RenderType`.
 *    `ItemBlockRenderTypes.getRenderLayer(FluidState): ChunkSectionLayer` is also
 *    an enum and is mapped to a RenderType here (see [fluidRenderLayer]).
 * - Block entities: `BlockEntityRenderDispatcher` is gone; 1.21.11 uses
 *    `BlockEntityRenderManager` (`Minecraft.getBlockEntityRenderDispatcher()`
 *    still returns it) with a render-state + command-queue flow:
 *    `getRenderState(BE, float, CrumblingOverlayCommand)` then
 *    `render(state, PoseStack, OrderedRenderCommandQueue, CameraRenderState)`,
 *    flushed by `FeatureRenderDispatcher.render()`.
 *
 * Must run on the render thread.
 */
object OffscreenSchematicRenderer {

    private val LOGGER = LoggerFactory.getLogger("schematioconnector-client")

    /**
     * Background matches the website renderer: solid light sky blue #7ea8ff
     * for ISOMETRIC; for PERSPECTIVE the same solid is the clear color and an
     * HDRI equirect skybox quad is drawn over it (see [renderHdriBackground]).
     */
    private const val BACKDROP_R = 0x7E / 255f
    private const val BACKDROP_G = 0xA8 / 255f
    private const val BACKDROP_B = 0xFF / 255f

    /**
     * Equirect day-sky background for PERSPECTIVE mode (tonemapped PNG derived
     * from the website's minecraft_day.hdr; .mcmeta sets blur+repeat).
     */
    private val HDRI_TEXTURE: Identifier =
        Identifier.fromNamespaceAndPath("schematioconnector", "textures/hdr/minecraft_day.png")

    /**
     * Block-entity pass master switch. The 1.21.11 command-queue BE flow is
     * the newest, least-proven part of this renderer; if it misbehaves in a
     * dev run, flipping this to false keeps blocks+fluids rendering while the
     * BE pass becomes a warn-once no-op.
     */
    private const val RENDER_BLOCK_ENTITIES = true

    /** BE types (and pass stages) we already warned about - log once, not per frame. */
    private val warnedBeKeys = mutableSetOf<String>()

    /**
     * Packed full-bright lightmap coords (sky 15, block 15). 26.x removed
     * LightTexture; the packing helpers moved to LightCoordsUtil unchanged.
     */
    //? if >=26.1 {
    /*private val FULL_BRIGHT_LIGHT: Int = LightCoordsUtil.pack(15, 15)
    *///?} else {
    private val FULL_BRIGHT_LIGHT: Int = LightTexture.pack(15, 15)
    //?}

    // PERSPECTIVE_FOV_DEG / ORTHO_EXTENT_FACTOR are shared top-level consts in
    // CaptureModel.kt so CameraPose.fitDistance stays consistent with this math.

    /**
     * Lazily created, reused BE command-queue + dispatcher. The dispatcher is
     * AutoCloseable but is built on the client's SHARED vertex consumers, so we
     * deliberately never close it (closing could tear down shared buffers).
     */
    //? if >=1.21.9 {
    private var beQueue: SubmitNodeStorage? = null
    private var beDispatcher: FeatureRenderDispatcher? = null
    //?}

    /**
     * GPU-geometry cache for the static snapshot (see [CachedSchematicMesh]).
     * Blocks + fluids are tessellated ONCE per source and redrawn every frame
     * with just the camera matrix, exactly like vanilla's chunk renderer - this
     * is what makes the live preview smooth during an orbit instead of
     * re-tessellating the whole build every frame. Lazily created, reused across
     * frames and sources (it re-tessellates internally only when the source
     * identity changes), and freed by [releaseCache].
     */
    private var meshCache: CachedSchematicMesh? = null

    /**
     * Render the schematic into [target]. Render thread only.
     *
     * [background] selects the backdrop: STUDIO keeps the original behavior
     * (solid #7ea8ff clear; HDRI sky quad over it in PERSPECTIVE), TRANSPARENT
     * clears to alpha 0 and skips the backdrop entirely so only the build's
     * own pixels carry alpha (block/fluid/BE passes are unchanged).
     */
    fun render(
        source: SchematicRenderSource,
        pose: CameraPose,
        target: OffscreenTarget,
        background: BackgroundMode = BackgroundMode.STUDIO,
    ) {
        val client = Minecraft.getInstance()
        val center = source.center()
        val radius = maxOf(source.radius(), 0.5f)
        val camDist = maxOf(pose.distance * radius, 0.5f)

        // Tessellate the (static) snapshot into the GPU-geometry cache once per
        // source; an orbit/zoom/pan only changes the camera, never the geometry,
        // so this no-ops on every frame after the first for a given source.
        val cache = meshCache ?: CachedSchematicMesh().also { meshCache = it }
        cache.buildFrom(source)

        when (background) {
            BackgroundMode.STUDIO -> target.clear(BACKDROP_R, BACKDROP_G, BACKDROP_B, 1f)
            BackgroundMode.TRANSPARENT -> target.clear(0f, 0f, 0f, 0f)
        }
        target.bind()

        // 26.x renamed PerspectiveProjectionMatrixBuffer → ProjectionMatrixBuffer;
        // ctor(String)/getBuffer(Matrix4f)/close() are shape-identical.
        //? if >=26.1 {
        /*val projection = ProjectionMatrixBuffer("schemat-thumbnail")
        *///?} else {
        val projection = PerspectiveProjectionMatrixBuffer("schemat-thumbnail")
        //?}
        RenderSystem.backupProjectionMatrix()
        try {
            val (matrix, type) = buildProjection(pose, radius, camDist)
            RenderSystem.setProjectionMatrix(projection.getBuffer(matrix), type)

            // Camera model-view: pan (view-space), push back, pitch, yaw. Per-pass
            // draws add (pos - center). The pan translation is LEFTMOST in the
            // stack, i.e. applied after the rotations in view space - it slides
            // the build along the camera's right/up axes (the screen plane) at
            // any yaw/pitch, never along world axes. panX/panY are stored in
            // radius-multiples (CameraPose.pan), so `pan * radius` scales the
            // offset with build size exactly like camDist = distance * radius.
            val matrices = PoseStack()
            matrices.translate(pose.panX * radius, pose.panY * radius, -camDist)
            matrices.mulPose(Axis.XP.rotationDegrees(pose.pitch))
            matrices.mulPose(Axis.YP.rotationDegrees(pose.yaw))

            val immediate = client.renderBuffers().bufferSource()
            if (pose.projection == Projection.PERSPECTIVE && background == BackgroundMode.STUDIO) {
                // Draw + flush the skybox FIRST so blocks overwrite it by depth
                // and translucent fluids blend over it. TRANSPARENT mode skips
                // the backdrop entirely (alpha-0 clear above).
                renderHdriBackground(pose, immediate, radius, camDist)
            }
            // Blocks + fluids come from the GPU-geometry cache: tessellated once,
            // redrawn each frame with only the camera matrix as the model-view.
            // The cached vertices already bake the static (pos - center) offset,
            // so the camera matrix is exactly the PoseStack camera transform
            // (pan/push-back/pitch/yaw) with no per-block translate.
            cache.drawAll(Matrix4f(matrices.last().pose()))
            // Block entities can't be cached cheaply; render them per frame as
            // before (the PoseStack still carries the same camera transform, and
            // each BE adds its own (pos - center) translate inside the pass).
            renderBlockEntities(source, pose, matrices, immediate, center, camDist)
            immediate.endBatch()
        } finally {
            RenderSystem.restoreProjectionMatrix()
            projection.close()
            target.unbind()
        }
    }

    private fun buildProjection(pose: CameraPose, radius: Float, camDist: Float): Pair<Matrix4f, ProjectionType> =
        when (pose.projection) {
            Projection.ISOMETRIC -> {
                // Frame half-extents scale with distance so scroll-zoom works in
                // ortho too. The VERTICAL half-extent is sized from the build
                // radius (same framing as the old square capture, so tall builds
                // still fit top-to-bottom); the horizontal half-extent is
                // vertical × CAPTURE_ASPECT, which widens the frame to 16:9
                // without stretching - since aspect > 1, anything that fits
                // vertically also fits horizontally.
                val s = maxOf(radius * pose.distance * ORTHO_EXTENT_FACTOR, 0.1f)
                val sX = s * CAPTURE_ASPECT
                Matrix4f().setOrtho(-sX, sX, -s, s, 0.05f, camDist + 4f * radius) to
                    ProjectionType.ORTHOGRAPHIC
            }
            Projection.PERSPECTIVE -> {
                val near = maxOf(0.05f, camDist - 2f * radius)
                val far = camDist + 2f * radius + 8f
                // Vertical FOV comes from the pose (default 55°); the 16:9
                // aspect widens the horizontal FOV so the frame matches the
                // capture target with no stretch.
                Matrix4f().setPerspective(
                    Math.toRadians(pose.fovDeg.toDouble()).toFloat(), CAPTURE_ASPECT, near, far,
                ) to
                    ProjectionType.PERSPECTIVE
            }
        }

    /**
     * PERSPECTIVE background: a fullscreen quad at ~far depth whose corner UVs
     * sample the equirect day-sky texture by the corner view rays (yaw → u,
     * pitch → v). Linear UV interpolation across the quad is a close
     * approximation at our 55° FOV. Drawn through `RenderTypes.text` (format
     * POSITION_COLOR_TEXTURE_LIGHT, depth-tested) and flushed immediately so
     * the later block/fluid passes draw over it. Any failure here is warn-once
     * and non-fatal - the #7ea8ff clear color remains as the fallback.
     */
    private fun renderHdriBackground(
        pose: CameraPose,
        immediate: MultiBufferSource.BufferSource,
        radius: Float,
        camDist: Float,
    ) {
        try {
            // Just inside the far plane used by buildProjection's perspective path.
            val far = camDist + 2f * radius + 8f
            val dist = far * 0.95f
            // Frustum half-extents at `dist`: vertical from the (vertical) FOV,
            // horizontal widened by the 16:9 capture aspect - the quad corners
            // must be the corners of the ACTUAL frustum so the equirect sky maps
            // correctly across the wider frame.
            val halfY = dist * tan(Math.toRadians(pose.fovDeg / 2.0)).toFloat()
            val halfX = halfY * CAPTURE_ASPECT

            // World→view rotation is Rx(pitch)·Ry(yaw); transpose → view→world.
            val viewToWorld = org.joml.Matrix3f()
                .rotationX(Math.toRadians(pose.pitch.toDouble()).toFloat())
                .rotateY(Math.toRadians(pose.yaw.toDouble()).toFloat())
                .transpose()

            // Camera forward (view -Z) in world space → center yaw for seam-free
            // unwrapping of per-corner yaws.
            val fwd = viewToWorld.transform(Vector3f(0f, 0f, -1f))
            val centerYaw = atan2(fwd.x.toDouble(), -fwd.z.toDouble())

            // Corners CCW from bottom-left (front-facing toward the camera).
            val corners = arrayOf(
                floatArrayOf(-halfX, -halfY),
                floatArrayOf(halfX, -halfY),
                floatArrayOf(halfX, halfY),
                floatArrayOf(-halfX, halfY),
            )
            val buffer = immediate.getBuffer(RenderTypes.text(HDRI_TEXTURE))
            val fullBright = FULL_BRIGHT_LIGHT
            val tmp = Vector3f()
            for ((cx, cy) in corners.map { it[0] to it[1] }) {
                tmp.set(cx, cy, -dist)
                viewToWorld.transform(tmp)
                val len = sqrt((tmp.x * tmp.x + tmp.y * tmp.y + tmp.z * tmp.z).toDouble())
                val yaw = atan2(tmp.x.toDouble(), -tmp.z.toDouble())
                // Wrap the corner yaw to within ±π of the center yaw so the
                // four u values interpolate continuously (texture repeats in u).
                var dYaw = yaw - centerYaw
                while (dYaw > PI) dYaw -= 2 * PI
                while (dYaw < -PI) dYaw += 2 * PI
                val u = (0.5 + (centerYaw + dYaw) / (2 * PI)).toFloat()
                val pitch = asin((tmp.y / len).coerceIn(-1.0, 1.0))
                val v = (0.5 - pitch / PI).coerceIn(0.002, 0.998).toFloat()
                buffer.addVertex(cx, cy, -dist).setColor(-1).setUv(u, v).setLight(fullBright)
            }
            immediate.endBatch()
        } catch (e: Exception) {
            if (warnedBeKeys.add("hdri-background")) {
                LOGGER.warn("SCHEMAT-CAPTURE: HDRI background failed; keeping solid #7ea8ff", e)
            }
        }
    }

    /**
     * Release the GPU-geometry cache (frees the off-heap vertex buffers + scratch
     * builders). Call when the composer closes so nothing is held across
     * sessions. Render thread only. Safe to call repeatedly.
     */
    fun releaseCache() {
        runCatching { meshCache?.close() }
            .onFailure { LOGGER.debug("Error closing mesh cache: {}", it.message) }
        meshCache = null
    }

    /**
     * Pass 3: block entities through the 1.21.11 render-state + command-queue
     * flow. Forces full-bright lightmap on each state; flushes via a reused
     * FeatureRenderDispatcher built on the client's shared consumers (never closed).
     *
     * Exact 1.21.11 signatures (javap, remapped yarn jar):
     * - `<E extends BlockEntity, S extends BlockEntityRenderState> S
     *    BlockEntityRenderManager.getRenderState(E, float,
     *    ModelCommandRenderer$CrumblingOverlayCommand)` - BOTH type params are
     *    free (S is not derived from E), so Kotlin MUST be given explicit type
     *    arguments. Letting inference run inside a `try { ... ?: continue }`
     *    expression previously inferred S = Nothing, emitting a CHECKCAST to
     *    java.lang.Void that crashed every frame
     *    ("SignBlockEntityRenderState cannot be cast to java.lang.Void").
     * - `<S extends BlockEntityRenderState> void render(S, PoseStack,
     *    OrderedRenderCommandQueue, CameraRenderState)` - queues commands.
     * - `FeatureRenderDispatcher.render()` - drains the queue into the Immediate.
     *
     * Failure containment: every per-BE step AND the final dispatcher flush
     * are individually guarded; nothing thrown in this pass can escape (one
     * broken BE renderer must never kill the frame). Warnings are logged once
     * per BE type, not per frame.
     */
    private fun renderBlockEntities(
        source: SchematicRenderSource,
        pose: CameraPose,
        matrices: PoseStack,
        immediate: MultiBufferSource.BufferSource,
        center: Vec3,
        camDist: Float,
    ) {
        if (!RENDER_BLOCK_ENTITIES) {
            if (warnedBeKeys.add("pass-disabled")) {
                LOGGER.warn("SCHEMAT-CAPTURE: block-entity pass disabled (RENDER_BLOCK_ENTITIES=false); skipping signs/chests/etc.")
            }
            return
        }
        try {
            val blockEntities = source.blockEntities()
            if (blockEntities.isEmpty()) return
            val client = Minecraft.getInstance()
            val manager = client.blockEntityRenderDispatcher

            // 1.21.9+ renders block entities through the render-state extraction +
            // submit-queue flow; 1.21.8 still has the classic direct
            // BlockEntityRenderDispatcher.render(be, partialTick, poseStack, buffers).
            //? if <1.21.9 {
            /*for (blockEntity in blockEntities) {
                val pos = blockEntity.blockPos
                matrices.pushPose()
                try {
                    matrices.translate(
                        (pos.x - center.x).toFloat(),
                        (pos.y - center.y).toFloat(),
                        (pos.z - center.z).toFloat(),
                    )
                    manager.render(blockEntity, 0f, matrices, immediate)
                } catch (e: Exception) {
                    if (warnedBeKeys.add("render:${blockEntity.type}")) {
                        LOGGER.warn("SCHEMAT-CAPTURE: BE render failed for {} - skipping this BE type", blockEntity.type, e)
                    }
                } finally {
                    matrices.popPose()
                }
            }
            *///?}
            //? if >=1.21.9 {
            val queue = beQueue ?: SubmitNodeStorage().also { beQueue = it }
            // 26.x FeatureRenderDispatcher ctor (javap): arg 2 became the
            // ModelManager (BlockRenderDispatcher is gone) and a trailing
            // GameRenderState was added (GameRenderer.getGameRenderState()).
            //? if >=26.1 {
            /*val dispatcher = beDispatcher ?: FeatureRenderDispatcher(
                queue,
                client.modelManager,
                immediate,
                client.atlasManager,
                client.renderBuffers().outlineBufferSource(),
                client.renderBuffers().crumblingBufferSource(),
                client.font,
                client.gameRenderer.gameRenderState,
            ).also { beDispatcher = it }
            *///?} else {
            val dispatcher = beDispatcher ?: FeatureRenderDispatcher(
                queue,
                client.blockRenderer,
                immediate,
                client.atlasManager,
                client.renderBuffers().outlineBufferSource(),
                client.renderBuffers().crumblingBufferSource(),
                client.font,
            ).also { beDispatcher = it }
            //?}

            // Approximate the virtual camera in world space so distance/orientation
            // dependent BE renderers (signs, beacons) behave sanely.
            val yawRad = Math.toRadians(pose.yaw.toDouble())
            val pitchRad = Math.toRadians(pose.pitch.toDouble())
            val horiz = camDist * cos(pitchRad)
            val eye = center.add(-sin(yawRad) * horiz, camDist * sin(pitchRad), -cos(yawRad) * horiz)
            val cameraState = CameraRenderState().apply {
                initialized = true
                pos = eye
                // 26.x dropped CameraRenderState.entityPos (the camera entity is
                // now a nested CameraEntityRenderState with no position field).
                //? if <26.1 {
                entityPos = eye
                //?}
                blockPos = BlockPos.containing(eye.x, eye.y, eye.z)
            }

            val fullBright = FULL_BRIGHT_LIGHT
            for (blockEntity in blockEntities) {
                // Explicit type arguments - see method doc. S = the base render
                // state type is a valid instantiation (every concrete state
                // extends it), and render() below is generic in S the same way.
                val state: BlockEntityRenderState = try {
                    manager.tryExtractRenderState<BlockEntity, BlockEntityRenderState>(blockEntity, 0f, null)
                        ?: continue
                } catch (e: Exception) {
                    if (warnedBeKeys.add("state:${blockEntity.type}")) {
                        LOGGER.warn("SCHEMAT-CAPTURE: getRenderState failed for {} - skipping this BE type", blockEntity.type, e)
                    }
                    continue
                }
                state.lightCoords = fullBright
                val pos = blockEntity.blockPos
                matrices.pushPose()
                try {
                    matrices.translate(
                        (pos.x - center.x).toFloat(),
                        (pos.y - center.y).toFloat(),
                        (pos.z - center.z).toFloat(),
                    )
                    manager.submit(state, matrices, queue, cameraState)
                } catch (e: Exception) {
                    if (warnedBeKeys.add("render:${blockEntity.type}")) {
                        LOGGER.warn("SCHEMAT-CAPTURE: BE render failed for {} - skipping this BE type", blockEntity.type, e)
                    }
                } finally {
                    matrices.popPose()
                }
            }
            try {
                dispatcher.renderAllFeatures()
            } catch (e: Exception) {
                if (warnedBeKeys.add("dispatcher-flush")) {
                    LOGGER.warn("SCHEMAT-CAPTURE: BE dispatcher flush failed - block entities omitted from thumbnail", e)
                }
            } finally {
                queue.clear()
            }
            //?}
        } catch (e: Exception) {
            // Belt-and-braces: NOTHING from the BE pass may crash the frame.
            if (warnedBeKeys.add("pass-failure")) {
                LOGGER.warn("SCHEMAT-CAPTURE: block-entity pass failed - block entities omitted from thumbnail", e)
            }
        }
    }
}
