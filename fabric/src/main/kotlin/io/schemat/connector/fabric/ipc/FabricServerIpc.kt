package io.schemat.connector.fabric.ipc

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.HelloClient
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
 *
 * The server never speaks first: it greets only in reply to a HELLO_CLIENT whose flags carry
 * [Capabilities.ROUTES_DOWNLOADS]. Released 1.5.x clients send HELLO_CLIENT without that bit
 * (and would mistake any HELLO_SERVER for a Paper plugin), so they never see this server.
 */
object FabricServerIpc {
    private val LOGGER = LoggerFactory.getLogger("SchematioServerIpc")

    private val greeted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Players whose latest HELLO_CLIENT carried ROUTES_DOWNLOADS; [greetAll] re-greets only these. */
    private val routingClients: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Whether a HELLO_CLIENT with [clientFlags] earns a HELLO_SERVER reply. */
    internal fun shouldGreet(dedicated: Boolean, clientFlags: Int): Boolean =
        dedicated && Capabilities.has(clientFlags, Capabilities.ROUTES_DOWNLOADS)
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

        // No JOIN greet: the client's HELLO_CLIENT (sent on its own join) starts the handshake.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            greeted.remove(handler.player.uuid)
            routingClients.remove(handler.player.uuid)
        }
        ServerPlayNetworking.registerGlobalReceiver(SchematioPayload.TYPE) { payload, context ->
            try {
                when (IpcCodec.peekOpcode(payload.data)) {
                    IpcOpcode.HELLO_CLIENT -> onHelloClient(context.player(), IpcCodec.decodeHelloClient(payload.data))
                    else -> LOGGER.debug("Ignoring schematio:c opcode from {}", context.player().scoreboardName)
                }
            } catch (t: Exception) {
                LOGGER.debug("Ignoring malformed schematio:c payload", t)
            }
        }
    }

    private fun onHelloClient(player: ServerPlayer, hello: HelloClient) {
        val server = mod?.server ?: return
        if (!shouldGreet(server.isDedicatedServer, hello.clientFlags)) {
            routingClients.remove(player.uuid)
            LOGGER.debug("Not greeting {}: client does not route downloads (flags={})", player.scoreboardName, hello.clientFlags)
            return
        }
        routingClients.add(player.uuid)
        // Deduped by [greeted]: the client's re-hello after a [greetAll] gets no second reply.
        greet(player)
    }

    /**
     * Re-announces to every online routing-capable player (config reload / token change), on
     * the server thread. Same reset semantics as Paper's regreetAll: the greet is forgotten and
     * re-sent, the client treats it as a re-hello and sends a fresh HELLO_CLIENT, which
     * refreshes [routingClients] (no attestation state exists on this server to clear).
     */
    fun greetAll() {
        val server = mod?.server ?: return
        server.execute {
            greeted.clear()
            server.playerList.players.filter { it.uuid in routingClients }.forEach { greet(it) }
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
