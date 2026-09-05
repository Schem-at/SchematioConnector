//? if >=26.2 {
/*package io.schemat.connector.fabric.client.render

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.rendertype.RenderType

// Small ad-hoc meshes such as the preview's sky quad. Static schematic blocks
// use persistent GPU buffers in CachedSchematicMesh instead.
internal class PreviewBuffers26 : AutoCloseable {
    private val builders = linkedMapOf<RenderType, Pair<ByteBufferBuilder, BufferBuilder>>()

    fun getBuffer(type: RenderType): VertexConsumer = builders.getOrPut(type) {
        val bytes = ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE)
        bytes to BufferBuilder(bytes, type.primitiveTopology(), type.format())
    }.second

    fun endBatch() {
        try {
            for ((type, pair) in builders) {
                val mesh = pair.second.build() ?: continue
                mesh.use {
                    RenderSystem.getDevice().createBuffer(
                        { "schemat-preview-quad" }, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer(),
                    ).use { vertices ->
                        val indices = RenderSystem.getSequentialBuffer(mesh.drawState().primitiveTopology())
                        val indexBuffer = indices.getBuffer(mesh.drawState().indexCount())
                        type.prepare().drawFromBuffer(vertices, indexBuffer, indices.type(), 0, 0, mesh.drawState().indexCount())
                    }
                }
            }
        } finally {
            close()
        }
    }

    override fun close() {
        builders.values.forEach { it.first.close() }
        builders.clear()
    }
}
*///?}
