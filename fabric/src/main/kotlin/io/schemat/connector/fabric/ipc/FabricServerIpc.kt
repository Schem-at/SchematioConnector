package io.schemat.connector.fabric.ipc

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.HelloServer
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcOpcode
import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.ipc.IpcProtocol
import io.schemat.connector.core.ipc.ServerReadiness
import io.schemat.connector.fabric.SchematioConnectorMod
import io.schemat.connector.fabric.worldedit.FabricWorldEditUtil
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Announces the Fabric *server* Connector to the client mod on `schematio:c`, so the client
 * routes `/schematio download` to this server's WorldEdit instead of local Litematica.
 * Only dedicated servers greet; an integrated server (singleplayer/LAN) stays silent.
 */
object FabricServerIpc {
    private val LOGGER = LoggerFactory.getLogger("SchematioServerIpc")

    private val greeted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    @Volatile private var mod: SchematioConnectorMod? = null
    private var registered = false

    /** Null means "do not greet". */
    internal fun capabilitiesFor(
        dedicated: Boolean,
        worldEdit: Boolean,
        tokenConfigured: Boolean,
        downloadEnabled: Boolean,
    ): Int? {
        if (!dedicated) return null
        val ready = ServerReadiness.serverWorldEdit(worldEdit, tokenConfigured, downloadEnabled, hasPermission = true)
        return Capabilities.DOWNLOAD_CMD or (if (ready) Capabilities.SERVER_WORLDEDIT else 0)
    }

    /** Registers the payload type in both directions, once. Runs in the main initializer (client too). */
    @Synchronized
    fun register(mod: SchematioConnectorMod) {
        this.mod = mod
        if (registered) return
        registered = true

        // Fabric networking API 6.x (>=26.1) renamed playS2C/playC2S to
        // clientboundPlay/serverboundPlay.
        //? if >=26.1 {
        /*PayloadTypeRegistry.clientboundPlay().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        *///?} else {
        PayloadTypeRegistry.playS2C().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        //?}

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            greet(handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            greeted.remove(handler.player.uuid)
        }
        ServerPlayNetworking.registerGlobalReceiver(SchematioPayload.TYPE) { payload, context ->
            try {
                when (IpcCodec.peekOpcode(payload.data)) {
                    IpcOpcode.HELLO_CLIENT -> greet(context.player())
                    else -> LOGGER.debug("Ignoring schematio:c opcode from {}", context.player().scoreboardName)
                }
            } catch (t: Exception) {
                LOGGER.debug("Ignoring malformed schematio:c payload", t)
            }
        }
    }

    /** Re-announces to every online player (config reload / token change), on the server thread. */
    fun greetAll() {
        val server = mod?.server ?: return
        server.execute {
            greeted.clear()
            server.playerList.players.forEach { greet(it) }
        }
    }

    private fun greet(player: ServerPlayer) {
        val m = mod ?: return
        val server = m.server ?: return
        if (!ServerPlayNetworking.canSend(player, SchematioPayload.TYPE)) return
        val caps = capabilitiesFor(
            dedicated = server.isDedicatedServer,
            worldEdit = FabricWorldEditUtil.isAvailable(),
            tokenConfigured = m.apiTokenConfigured,
            downloadEnabled = "download" !in m.disabledCommands,
        ) ?: return
        if (!greeted.add(player.uuid)) return

        val version = FabricLoader.getInstance().getModContainer(SchematioConnectorMod.MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
        val bytes = IpcCodec.encodeHelloServer(
            HelloServer(
                protocolVersion = IpcProtocol.VERSION,
                pluginVersion = version,
                capabilities = caps,
                platform = IpcPlatform.FABRIC_SERVER,
                serverSoftware = "Fabric",
                mcVersion = mcVersion(),
                backendHost = m.baseUrl,
                communityId = "",
                communitySlug = "",
            ),
        )
        ServerPlayNetworking.send(player, SchematioPayload(bytes))
    }

    private fun mcVersion(): String = try {
        SharedConstants.getCurrentVersion().name()
    } catch (t: Throwable) {
        ""
    }
}
