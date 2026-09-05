package io.schemat.connector.fabric.client.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
// RenderType moved into its own package in 1.21.11 (RenderTypes split out the
// static factories); the alias keeps call sites identical on <=1.21.10. This
// mirrors OffscreenSchematicRenderer's own import gating.
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
//?} else {
/*import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.RenderType as RenderTypes
*///?}
// Block tessellation differs by major: 26.x removed ItemBlockRenderTypes and
// BlockRenderDispatcher (blocks go through ModelBlockRenderer.tesselateBlock with
// a per-quad layer; fluids through FluidRenderer), while <26.1 keeps
// ItemBlockRenderTypes + BlockRenderManager.renderBatched/renderLiquid. These
// match OffscreenSchematicRenderer's existing version branches exactly.
//? if >=26.1 {
/*import net.minecraft.client.renderer.block.ModelBlockRenderer
import net.minecraft.client.renderer.block.BlockQuadOutput
import net.minecraft.client.renderer.block.FluidRenderer
*///?} else {
import net.minecraft.client.renderer.ItemBlockRenderTypes
//?}
import net.minecraft.world.level.material.FluidState
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory

/**
 * GPU-geometry cache for the (static) schematic snapshot, the chunk-renderer
 * trick adapted to MC's post-1.21.6 GpuDevice pipeline.
 *
 * ## Why this exists
 * [OffscreenSchematicRenderer] used to RE-TESSELLATE the whole snapshot every
 * frame (every block through [ModelBlockRenderer]/`renderBatched` into the
 * shared immediate [net.minecraft.client.renderer.MultiBufferSource], flushed
 * per call). Tessellation is the expensive part - per-block model lookup, AO,
 * tint and neighbor culling - and the snapshot never changes during an orbit,
 * so doing it 100+ times a second is pure waste. This is the confirmed cause of
 * the live-preview lag.
 *
 * ## What it does instead (vanilla [SectionRenderDispatcher] analogue)
 * MC's chunk renderer tessellates each section ONCE, uploads the mesh to a GPU
 * buffer, and redraws it every frame with just a fresh camera matrix. There is
 * no `VertexBuffer` class on these versions anymore (the 1.21.6 GpuDevice
 * rewrite removed it - javap-confirmed across 1.21.8/.9/.10/.11/26.1), so the
 * equivalent here is:
 *
 * 1. **On source change only** ([buildFrom]): tessellate ALL blocks + fluids
 *    into our OWN per-[RenderType] [com.mojang.blaze3d.vertex.BufferBuilder]s
 *    (reusing the EXISTING tessellation calls, so face culling / AO / tint are
 *    identical), baking ONLY the static `(pos - center)` offset into the
 *    vertices - never the camera. `build()` each layer to a [MeshData], copy its
 *    vertex bytes into a persistent off-heap buffer, and remember the
 *    [MeshData.DrawState] (vertex/index counts, mode, format). Keyed by
 *    [RenderType]; rebuilt when the source identity changes.
 *
 * 2. **Each frame** ([drawAll]): push the camera matrix onto
 *    [RenderSystem.getModelViewStack] (which [RenderType.draw] reads as the
 *    model-view), then for each cached layer write the cached bytes back into a
 *    reusable [ByteBufferBuilder] (a cheap `memcpy`, NOT re-tessellation),
 *    rebuild a transient [MeshData] over them and hand it to
 *    [RenderType.draw] - vanilla's own per-version draw path, which uploads the
 *    vertices to the immediate ring buffer, binds the layer's pipeline /
 *    textures / uniforms and issues the indexed draw. QUADS meshes carry no
 *    index buffer, so `draw` regenerates the shared sequential index buffer for
 *    us, exactly as it does for chunk-layer immediate draws.
 *
 * Delegating the actual draw to [RenderType.draw] is deliberate: it keeps ALL
 * the per-version pipeline / texture-binding / uniform differences (1.21.8
 * `bindSampler` vs 1.21.11 `bindTexture` + RenderSetup, etc.) inside vanilla,
 * so this cache compiles and behaves identically on every version. The win -
 * skipping re-tessellation - is the same as a hand-rolled VertexBuffer would
 * give, since per frame we now do only memcpy + ring-buffer upload + draw.
 *
 * Block entities are NOT cached here (few, and their renderers can't be
 * snapshotted cheaply); [OffscreenSchematicRenderer] still draws them per frame.
 *
 * Render thread only (off-heap buffers + GpuDevice). Not thread-safe.
 */
class CachedSchematicMesh : AutoCloseable {

    private companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-client")
    }

    /** A tessellated, off-heap snapshot of one render layer's geometry. */
    private class CachedLayer(
        /** Off-heap copy of the layer's vertex bytes (address from [MemoryUtil.nmemAlloc]). */
        val vertexAddr: Long,
        val vertexByteCount: Int,
        /** Draw state to rebuild a [MeshData] from the cached bytes each frame. */
        val drawState: MeshData.DrawState,
        /** Reusable scratch builder the bytes are re-injected into per frame. */
        val scratch: ByteBufferBuilder,
    ) {
        var gpuVertices: GpuBuffer? = null
        fun free() {
            gpuVertices?.close()
            MemoryUtil.nmemFree(vertexAddr)
            scratch.close()
        }
    }

    /** Cached layers in insertion order (SOLID before CUTOUT before TRANSLUCENT, as tessellated). */
    private val layers = LinkedHashMap<RenderType, CachedLayer>()

    /** The source whose geometry [layers] currently holds; identity-compared. */
    private var builtFor: SchematicRenderSource? = null

    /** True once [buildFrom] has produced a (possibly empty) cache for [builtFor]. */
    private var built = false

    /** True when [buildFrom] succeeded for [source] and nothing needs re-tessellating. */
    fun isBuiltFor(source: SchematicRenderSource): Boolean = built && builtFor === source

    /**
     * (Re)tessellate [source] into the per-layer cache if not already built for
     * it. No-op when [isBuiltFor] is already true. Render thread only.
     *
     * Vertices bake ONLY the static `(pos - center)` offset; the camera is
     * applied per frame in [drawAll], so an orbit never re-tessellates.
     */
    fun buildFrom(source: SchematicRenderSource) {
        if (isBuiltFor(source)) return
        releaseLayers()
        builtFor = source
        built = false

        val center = source.center()
        // One BufferBuilder per layer, started lazily on first quad for that
        // layer (same lazy getBuffer semantics the immediate source had).
        val recorder = LayerRecorder()
        try {
            tessellateBlocks(source, center, recorder)
            tessellateFluids(source, center, recorder)
            // Build every started layer and snapshot its vertex bytes off-heap.
            for ((renderType, builder) in recorder.builders) {
                val mesh = builder.build() ?: continue
                try {
                    val vb = mesh.vertexBuffer()
                    val count = vb.remaining()
                    if (count <= 0) continue
                    val addr = MemoryUtil.nmemAlloc(count.toLong())
                    if (addr == 0L) {
                        LOGGER.warn("SCHEMAT-CACHE: nmemAlloc({}) failed for layer {}; skipping", count, renderType)
                        continue
                    }
                    MemoryUtil.memCopy(MemoryUtil.memAddress(vb), addr, count.toLong())
                    layers[renderType] = CachedLayer(
                        vertexAddr = addr,
                        vertexByteCount = count,
                        drawState = mesh.drawState(),
                        scratch = ByteBufferBuilder(count.coerceAtLeast(2048)),
                    )
                } finally {
                    mesh.close()
                }
            }
            built = true
        } finally {
            recorder.close()
        }
    }

    /**
     * Draw every cached layer with [cameraMatrix] as the model-view. The caller
     * must have already set the projection matrix (via
     * [RenderSystem.setProjectionMatrix]) and bound the offscreen target. Render
     * thread only.
     */
    fun drawAll(cameraMatrix: Matrix4f) {
        if (layers.isEmpty()) return
        val mvStack = RenderSystem.getModelViewStack()
        mvStack.pushMatrix()
        try {
            // RenderType.draw reads RenderSystem.getModelViewMatrix() (the top of
            // this stack) as the model-view; replace it wholesale with the camera.
            mvStack.identity().mul(cameraMatrix)
            for ((renderType, layer) in layers) {
                drawLayer(renderType, layer)
            }
        } finally {
            mvStack.popMatrix()
        }
    }

    /** Rebuild a transient MeshData over the cached bytes and hand it to vanilla's draw. */
    private fun drawLayer(renderType: RenderType, layer: CachedLayer) {
        //? if >=26.2 {
        /*val vertices = layer.gpuVertices ?: RenderSystem.getDevice().createBuffer(
            { "schemat-cached-vertices" }, GpuBuffer.USAGE_VERTEX,
            MemoryUtil.memByteBuffer(layer.vertexAddr, layer.vertexByteCount),
        ).also { layer.gpuVertices = it }
        val indices = RenderSystem.getSequentialBuffer(layer.drawState.primitiveTopology())
        val indexBuffer = indices.getBuffer(layer.drawState.indexCount())
        renderType.prepare().drawFromBuffer(vertices, indexBuffer, indices.type(), 0, 0, layer.drawState.indexCount())
        *///?} else {
        val builder = layer.scratch
        // reserve() advances the write head and returns the absolute write
        // address; copy the cached vertex bytes in, then build() yields a Result
        // covering exactly those bytes (bytecode-verified ByteBufferBuilder shape).
        val dst = builder.reserve(layer.vertexByteCount)
        MemoryUtil.memCopy(layer.vertexAddr, dst, layer.vertexByteCount.toLong())
        val result = builder.build() ?: return
        // MeshData takes ownership of `result`; RenderType.draw closes the MeshData
        // (and thus the Result), freeing the scratch region for next frame.
        val mesh = MeshData(result, layer.drawState)
        renderType.draw(mesh)
        //?}
    }

    override fun close() {
        releaseLayers()
        builtFor = null
        built = false
    }

    private fun releaseLayers() {
        for (layer in layers.values) layer.free()
        layers.clear()
    }

    // ---- tessellation (mirrors OffscreenSchematicRenderer, baking pos-center) ----

    /**
     * Per-[RenderType] [com.mojang.blaze3d.vertex.BufferBuilder] recorder. Plays
     * the role the shared immediate [net.minecraft.client.renderer.MultiBufferSource]
     * did during tessellation, but keeps each layer's builder so we can [build]
     * and snapshot it instead of flushing it to the screen.
     */
    private class LayerRecorder : AutoCloseable {
        val builders = LinkedHashMap<RenderType, com.mojang.blaze3d.vertex.BufferBuilder>()
        private val byteBuffers = ArrayList<ByteBufferBuilder>()

        fun getBuffer(renderType: RenderType): VertexConsumer =
            builders.getOrPut(renderType) {
                //? if <26.2 {
                val bb = ByteBufferBuilder(renderType.bufferSize())
                byteBuffers += bb
                com.mojang.blaze3d.vertex.BufferBuilder(bb, renderType.mode(), renderType.format())
                //?} else {
                /*val bb = ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE)
                byteBuffers += bb
                com.mojang.blaze3d.vertex.BufferBuilder(bb, renderType.primitiveTopology(), renderType.format())
                *///?}
            }

        override fun close() {
            // BufferBuilders are drained by build(); free the backing ByteBufferBuilders.
            for (bb in byteBuffers) runCatching { bb.close() }
            builders.clear()
            byteBuffers.clear()
        }
    }

    private fun tessellateBlocks(source: SchematicRenderSource, center: Vec3, recorder: LayerRecorder) {
        // Static-only PoseStack: per-block translate(pos - center). The camera is
        // applied per frame via the model-view stack, never baked here.
        val matrices = PoseStack()
        //? if >=26.1 {
        /*val client = Minecraft.getInstance()
        val tessellator = ModelBlockRenderer(true, true, client.blockColors)
        val models = client.modelManager.blockStateModelSet
        val output = BlockQuadOutput { x, y, z, quad, quadInstance ->
            matrices.pushPose()
            matrices.translate(x, y, z)
            recorder.getBuffer(movingBlockLayer(quad.materialInfo().layer()))
                .putBakedQuad(matrices.last(), quad, quadInstance)
            matrices.popPose()
        }
        for (pos in source.blockPositions()) {
            val state = source.view.getBlockState(pos)
            tessellator.tesselateBlock(
                output,
                (pos.x - center.x).toFloat(),
                (pos.y - center.y).toFloat(),
                (pos.z - center.z).toFloat(),
                source.view, pos, state,
                models.get(state),
                state.getSeed(pos),
            )
        }
        *///?} else {
        val blockRenderManager = Minecraft.getInstance().blockRenderer
        val random = RandomSource.create(42L)
        for (pos in source.blockPositions()) {
            val state = source.view.getBlockState(pos)
            val parts = blockRenderManager.getBlockModel(state).collectParts(random)
            if (parts.isEmpty()) continue
            val layer = ItemBlockRenderTypes.getMovingBlockRenderType(state)
            matrices.pushPose()
            matrices.translate(
                (pos.x - center.x).toFloat(),
                (pos.y - center.y).toFloat(),
                (pos.z - center.z).toFloat(),
            )
            blockRenderManager.renderBatched(
                state, pos, source.view, matrices,
                recorder.getBuffer(layer),
                /* cull = */ true,
                parts,
            )
            matrices.popPose()
        }
        //?}
    }

    private fun tessellateFluids(source: SchematicRenderSource, center: Vec3, recorder: LayerRecorder) {
        //? if >=26.1 {
        /*val fluidRenderer = FluidRenderer(Minecraft.getInstance().modelManager.fluidStateModelSet)
        *///?} else {
        val blockRenderManager = Minecraft.getInstance().blockRenderer
        //?}
        val matrices = PoseStack()
        for (pos in BlockPos.betweenClosed(source.renderMinPos, source.renderMaxPos)) {
            val state = source.view.getBlockState(pos)
            val fluidState = state.fluidState
            if (fluidState.isEmpty) continue
            // FluidRenderer emits section-local coords (pos & 15); bake the static
            // (sectionOrigin - center) offset only.
            val ox = (pos.x shr 4) shl 4
            val oy = (pos.y shr 4) shl 4
            val oz = (pos.z shr 4) shl 4
            matrices.pushPose()
            matrices.translate(
                (ox - center.x).toFloat(),
                (oy - center.y).toFloat(),
                (oz - center.z).toFloat(),
            )
            //? if >=26.1 {
            /*val entry = matrices.last()
            fluidRenderer.tesselate(
                source.view, pos,
                FluidRenderer.Output { layer ->
                    StaticTransformingVertexConsumer(recorder.getBuffer(movingBlockLayer(layer)), entry)
                },
                state, fluidState,
            )
            *///?} else {
            blockRenderManager.renderLiquid(
                pos, source.view,
                StaticTransformingVertexConsumer(recorder.getBuffer(fluidRenderLayer(fluidState)), matrices.last()),
                state, fluidState,
            )
            //?}
            matrices.popPose()
        }
    }

    //? if >=26.1 {
    /*/** 26.x: ChunkSectionLayer -> immediate-mode moving-block RenderType. */
    private fun movingBlockLayer(layer: ChunkSectionLayer): RenderType = when (layer) {
        ChunkSectionLayer.TRANSLUCENT -> RenderTypes.translucentMovingBlock()
        ChunkSectionLayer.CUTOUT -> RenderTypes.cutoutMovingBlock()
        ChunkSectionLayer.SOLID -> RenderTypes.solidMovingBlock()
    }
    *///?}

    // Map ChunkSectionLayer (fluid layer) -> immediate-mode RenderType, mirroring
    // OffscreenSchematicRenderer.fluidRenderLayer exactly (per-version split).
    //? if >=1.21.11 && <26.1 {
    private fun fluidRenderLayer(fluidState: FluidState): RenderType =
        when (ItemBlockRenderTypes.getRenderLayer(fluidState)) {
            ChunkSectionLayer.TRANSLUCENT -> RenderTypes.translucentMovingBlock()
            ChunkSectionLayer.TRIPWIRE -> RenderTypes.tripwireMovingBlock()
            ChunkSectionLayer.CUTOUT -> RenderTypes.cutoutMovingBlock()
            ChunkSectionLayer.SOLID -> RenderTypes.solidMovingBlock()
        }
    //?}
    //? if <1.21.11 {
    /*private fun fluidRenderLayer(fluidState: FluidState): RenderType =
        when (ItemBlockRenderTypes.getRenderLayer(fluidState)) {
            ChunkSectionLayer.TRANSLUCENT -> RenderTypes.translucentMovingBlock()
            ChunkSectionLayer.TRIPWIRE -> RenderTypes.tripwire()
            ChunkSectionLayer.CUTOUT -> RenderTypes.cutout()
            ChunkSectionLayer.CUTOUT_MIPPED -> RenderTypes.cutoutMipped()
            ChunkSectionLayer.SOLID -> RenderTypes.solid()
        }
    *///?}

    /**
     * Applies a static PoseStack entry on the CPU before delegating - needed
     * because FluidRenderer takes no PoseStack and emits section-local coords.
     * Same idea as OffscreenSchematicRenderer.TransformingVertexConsumer, but the
     * baked matrix is the STATIC (sectionOrigin - center) only (no camera), since
     * the camera is applied per frame at draw time.
     */
    private class StaticTransformingVertexConsumer(
        private val delegate: VertexConsumer,
        entry: PoseStack.Pose,
    ) : VertexConsumer {
        private val positionMatrix = Matrix4f(entry.pose())
        private val normalMatrix = org.joml.Matrix3f(entry.normal())
        private val posVec = Vector3f()
        private val normVec = Vector3f()

        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
            positionMatrix.transformPosition(x, y, z, posVec)
            delegate.addVertex(posVec.x, posVec.y, posVec.z)
            return this
        }

        override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer {
            normalMatrix.transform(x, y, z, normVec)
            delegate.setNormal(normVec.x, normVec.y, normVec.z)
            return this
        }

        override fun setColor(r: Int, g: Int, b: Int, a: Int): VertexConsumer {
            delegate.setColor(r, g, b, a); return this
        }

        override fun setColor(argb: Int): VertexConsumer {
            delegate.setColor(argb); return this
        }

        override fun setUv(u: Float, v: Float): VertexConsumer {
            delegate.setUv(u, v); return this
        }

        override fun setUv1(u: Int, v: Int): VertexConsumer {
            delegate.setUv1(u, v); return this
        }

        override fun setUv2(u: Int, v: Int): VertexConsumer {
            delegate.setUv2(u, v); return this
        }

        // VertexConsumer.setLineWidth was added in 1.21.11.
        //? if >=1.21.11 {
        override fun setLineWidth(width: Float): VertexConsumer {
            delegate.setLineWidth(width); return this
        }
        //?}
    }
}
