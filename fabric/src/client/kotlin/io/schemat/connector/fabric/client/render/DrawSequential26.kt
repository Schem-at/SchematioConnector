//? if >=26.3 {
/*package io.schemat.connector.fabric.client.render

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.RenderType

// Draw [indexCount] sequential indices of [vertices] with [renderType]. 26.3's
// PreparedRenderType.drawFromBuffer no longer opens its own pass (and the global
// output overrides are gone), so the pass targets whatever OffscreenTarget is bound.
internal fun drawSequential(renderType: RenderType, vertices: GpuBuffer, indexCount: Int) {
    val topology = renderType.primitiveTopology()
    val indices = RenderSystem.getSequentialBuffer(topology)
    val info = StagedVertexBuffer.ExecuteInfo(
        vertices, indices.getBuffer(indexCount), indices.type(), 0, 0, indexCount, topology,
    )
    // prepare() may upload a texture on first use, which 26.3 rejects while a pass is open.
    val prepared = renderType.prepare()
    OffscreenTarget.withRenderPass("schemat-preview") { pass -> prepared.drawFromBuffer(info, pass) }
}
*///?} else if >=26.2 {
/*package io.schemat.connector.fabric.client.render

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.rendertype.RenderType

// Draw [indexCount] sequential indices of [vertices] with [renderType]. On 26.2
// drawFromBuffer opens its own pass on RenderSystem.output*TextureOverride.
internal fun drawSequential(renderType: RenderType, vertices: GpuBuffer, indexCount: Int) {
    val indices = RenderSystem.getSequentialBuffer(renderType.primitiveTopology())
    val indexBuffer = indices.getBuffer(indexCount)
    renderType.prepare().drawFromBuffer(vertices, indexBuffer, indices.type(), 0, 0, indexCount)
}
*///?}
