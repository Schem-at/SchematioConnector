package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.HelloClient
import io.schemat.connector.core.ipc.HelloServer
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcFormatException
import io.schemat.connector.core.ipc.IpcOpcode
import io.schemat.connector.core.ipc.IpcProtocol
import io.schemat.connector.core.ipc.LoadClipboard
import io.schemat.schematioConnector.utils.WorldEditUtil
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the Schematio IPC handshake over the plugin-messaging channel.
 * Runs entirely on the main thread; does no blocking work in the listener.
 */
class PluginIpcService(private val plugin: JavaPlugin) : PluginMessageListener, Listener {

    /** Players we have already greeted this session, to dedupe register-event vs client-hello triggers. */
    private val greeted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * Whether the WorldEdit API is on the classpath. WorldEdit is a soft dependency
     * (compileOnly + plugin.yml softdepend), so we must not touch its classes unless present —
     * otherwise the plugin would fail to load on servers without WorldEdit.
     */
    private val worldEditAvailable: Boolean = run {
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit")
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Capabilities this build supports. WANTS_COMMAND_OWNERSHIP advertises intent; client logic
     * is POC-gated. LOAD_CLIPBOARD is only advertised when WorldEdit is actually present.
     */
    private val capabilities: Int =
        Capabilities.DOWNLOAD_CMD or
            Capabilities.WANTS_COMMAND_OWNERSHIP or
            (if (worldEditAvailable) Capabilities.LOAD_CLIPBOARD else 0)

    fun register() {
        val messenger = plugin.server.messenger
        messenger.registerOutgoingPluginChannel(plugin, IpcProtocol.CHANNEL)
        messenger.registerIncomingPluginChannel(plugin, IpcProtocol.CHANNEL, this)
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("Schematio IPC registered on channel ${IpcProtocol.CHANNEL}")
    }

    /** Client advertised our channel via minecraft:register — greet it proactively. */
    @EventHandler
    fun onRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (event.channel == IpcProtocol.CHANNEL) greet(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        greeted.remove(event.player.uniqueId)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != IpcProtocol.CHANNEL) return
        try {
            when (IpcCodec.peekOpcode(message)) {
                IpcOpcode.HELLO_CLIENT -> {
                    val hello: HelloClient = IpcCodec.decodeHelloClient(message)
                    plugin.logger.info("Schematio mod present for ${player.name}: v${hello.modVersion} (proto ${hello.protocolVersion})")
                    greet(player) // fallback path; deduped
                }
                IpcOpcode.LOAD_CLIPBOARD -> {
                    val msg: LoadClipboard = IpcCodec.decodeLoadClipboard(message)
                    handleLoadClipboard(player, msg)
                }
                else -> { /* unknown/opcode we don't handle as a request; ignore */ }
            }
        } catch (e: IpcFormatException) {
            plugin.logger.warning("Malformed Schematio IPC from ${player.name}: ${e.message}")
        }
    }

    /**
     * Loads schematic bytes from a [LoadClipboard] request into [player]'s server-side WorldEdit
     * clipboard. Runs on the main thread (plugin messages are delivered there), where WorldEdit's
     * session API is safe to touch. If WorldEdit is absent, logs and ignores (the client should
     * not even offer this, since LOAD_CLIPBOARD is gated by the advertised capability).
     */
    private fun handleLoadClipboard(player: Player, msg: LoadClipboard) {
        if (!worldEditAvailable) {
            plugin.logger.warning(
                "Ignoring LOAD_CLIPBOARD from ${player.name}: WorldEdit not available on this server.",
            )
            return
        }
        try {
            val clipboard = WorldEditUtil.byteArrayToClipboard(msg.bytes)
            if (clipboard == null) {
                plugin.logger.warning("Failed to parse LOAD_CLIPBOARD schematic from ${player.name} (format hint='${msg.format}', ${msg.bytes.size} bytes)")
                player.sendMessage("§cSchematio: could not read that schematic into your clipboard.")
                return
            }
            WorldEditUtil.setClipboard(player, clipboard)
            plugin.logger.info("Loaded ${msg.bytes.size}-byte schematic into ${player.name}'s WorldEdit clipboard (format hint='${msg.format}')")
            player.sendMessage("§aSchematio: schematic loaded into your WorldEdit clipboard. Use //paste to place it.")
        } catch (e: Throwable) {
            plugin.logger.warning("Error loading clipboard for ${player.name}: ${e.javaClass.simpleName}: ${e.message}")
            player.sendMessage("§cSchematio: an error occurred loading the schematic into your clipboard.")
        }
    }

    private fun greet(player: Player) {
        if (!greeted.add(player.uniqueId)) return
        if (!player.listeningPluginChannels.contains(IpcProtocol.CHANNEL)) {
            greeted.remove(player.uniqueId) // not ready yet; allow a later trigger to retry
            return
        }
        val hello = HelloServer(
            protocolVersion = IpcProtocol.VERSION,
            pluginVersion = plugin.description.version,
            capabilities = capabilities,
        )
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeHelloServer(hello))
    }
}
