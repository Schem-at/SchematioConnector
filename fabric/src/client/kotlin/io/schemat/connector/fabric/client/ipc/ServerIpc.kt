package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.HelloClient
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcFormatException
import io.schemat.connector.core.ipc.IpcOpcode
import io.schemat.connector.core.ipc.IpcProtocol
import io.schemat.connector.core.ipc.LoadClipboard
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object ServerIpc {
    private val LOGGER = LoggerFactory.getLogger("SchematioIpc")
    private const val MOD_ID = "schematioconnector"

    fun init() {
        // Fabric networking API 6.x (>=26.1) renamed playS2C/playC2S to
        // clientboundPlay/serverboundPlay.
        //? if >=26.1 {
        /*PayloadTypeRegistry.clientboundPlay().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        *///?} else {
        PayloadTypeRegistry.playS2C().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        //?}

        ClientPlayNetworking.registerGlobalReceiver(SchematioPayload.TYPE) { payload, _ ->
            // Fabric invokes this on the client thread.
            handle(payload.data)
        }
    }

    private fun handle(data: ByteArray) {
        try {
            when (IpcCodec.peekOpcode(data)) {
                IpcOpcode.HELLO_SERVER -> {
                    val hello = IpcCodec.decodeHelloServer(data)
                    ServerSession.adopt(hello)
                    LOGGER.info("Connected to Schematio server v${hello.pluginVersion} (proto ${hello.protocolVersion})")
                    // 26.1 removed Player.displayClientMessage(Component, boolean);
                    // sendSystemMessage(Component) is the chat-log equivalent (the
                    // false/non-actionbar case used here).
                    //? if >=26.1 {
                    /*Minecraft.getInstance().player?.sendSystemMessage(
                        Component.literal("§aSchematio server detected: v${hello.pluginVersion}"),
                    )
                    *///?} else {
                    Minecraft.getInstance().player?.displayClientMessage(
                        Component.literal("§aSchematio server detected: v${hello.pluginVersion}"),
                        false,
                    )
                    //?}
                    sendClientHello()
                }
                else -> { /* ignore unknown opcodes */ }
            }
        } catch (e: IpcFormatException) {
            LOGGER.warn("Malformed Schematio IPC from server: ${e.message}")
        }
    }

    fun sendClientHello() {
        if (!ClientPlayNetworking.canSend(SchematioPayload.TYPE)) return
        // Idempotent per connection: only the first of the join/reply paths actually sends.
        if (!ServerSession.markHelloSent()) return
        val version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
        val bytes = IpcCodec.encodeHelloClient(HelloClient(IpcProtocol.VERSION, version, 0))
        ClientPlayNetworking.send(SchematioPayload(bytes))
    }

    /**
     * Sends a [LoadClipboard] C2S request asking the connected Schematio server to load
     * [schematicBytes] (a schematic in [format]) into the player's server-side WorldEdit
     * clipboard. No-op (returns false) unless a Schematio server is connected and the
     * channel is sendable. Fire-and-forget: the server does not reply with bytes.
     */
    fun sendLoadClipboard(schematicBytes: ByteArray, format: String): Boolean {
        if (!ServerSession.pluginPresent) return false
        if (!ClientPlayNetworking.canSend(SchematioPayload.TYPE)) return false
        val bytes = IpcCodec.encodeLoadClipboard(
            LoadClipboard(IpcProtocol.VERSION, format, schematicBytes),
        )
        ClientPlayNetworking.send(SchematioPayload(bytes))
        return true
    }
}
