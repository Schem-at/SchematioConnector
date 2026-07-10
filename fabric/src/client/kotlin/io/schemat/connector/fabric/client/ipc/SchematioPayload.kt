package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.IpcProtocol
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** Wraps the raw IPC body. One payload type per channel; opcode multiplexing happens inside [data]. */
class SchematioPayload(val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SchematioPayload> = TYPE

    companion object {
        private val CHANNEL_ID: Identifier = IpcProtocol.CHANNEL.split(":", limit = 2).let {
            Identifier.fromNamespaceAndPath(it[0], it[1])
        }

        val TYPE: CustomPacketPayload.Type<SchematioPayload> =
            CustomPacketPayload.Type(CHANNEL_ID)

        /** Reads/writes the entire buffer with no length prefix, matching Bukkit's raw byte[] body. */
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SchematioPayload> =
            StreamCodec.of(
                { buf, payload -> buf.writeBytes(payload.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    SchematioPayload(bytes)
                },
            )
    }
}
