package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.attest.bytesToHexLower
import io.schemat.connector.core.cache.RateLimiter
import io.schemat.connector.core.ipc.Attest
import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.DraftCreated
import io.schemat.connector.core.ipc.HelloClient
import io.schemat.connector.core.ipc.HelloServer
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcFormatException
import io.schemat.connector.core.ipc.IpcOpcode
import io.schemat.connector.core.ipc.IpcPayloadTooLargeException
import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.ipc.IpcProtocol
import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.ipc.LoadRequest
import io.schemat.connector.core.ipc.OpenUi
import io.schemat.connector.core.ipc.OpenUiSurface
import io.schemat.connector.core.ipc.Status
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.ipc.UploadClipboard
import io.schemat.connector.core.modapi.ClipboardResolveOutcome
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the Schematio IPC handshake over the plugin-messaging channel and serves
 * reference-pull clipboard loads (LOAD_REQUEST -> STATUS). The listener itself runs
 * on the main thread; the backend fetch is dispatched async and hops back for WorldEdit.
 */
class PluginIpcService(private val plugin: SchematioConnector) : PluginMessageListener, Listener {

    /** Players we have already greeted this session, to dedupe register-event vs client-hello triggers. */
    private val greeted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * Players whose connection we successfully attested (ATTEST relayed) this session.
     * This is the server-side "session is ATTESTED" gate for LOAD_REQUEST: the server
     * cannot observe the client's verification result, but a relayed ATTEST proves the
     * community token was live and the client sent a v2 nonce.
     */
    private val attested: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * clientFlags from each player's HELLO_CLIENT this session (bitset of Capabilities.*).
     * Absent entry = vanilla client (or no hello yet) -> flags 0 -> never hands off.
     */
    private val clientFlags = ConcurrentHashMap<UUID, Int>()

    /** Per-player LOAD_REQUEST budget (spec: 5/min token bucket). */
    private val loadLimiter = RateLimiter(
        maxRequests = LoadRequestGuards.REQUESTS_PER_MINUTE,
        windowMs = LoadRequestGuards.WINDOW_MS,
    )

    /** Last unknown/legacy-opcode log, for the spec's "one rate-limited log line". */
    @Volatile
    private var lastOpcodeNoiseLogMs: Long = 0L

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
     * Capabilities advertised in HELLO_SERVER, computed per-greet: UPLOAD depends on
     * the backend connection, which races plugin startup (spec: "advertised only when
     * WorldEdit + backend configured").
     */
    private fun currentCapabilities(): Int =
        capabilitiesFor(worldEditAvailable, plugin.clipboardUploadClient != null)

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
        attested.remove(event.player.uniqueId)
        clientFlags.remove(event.player.uniqueId)
        loadLimiter.removePlayer(event.player.uniqueId)
        plugin.clipboardUploadService.removePlayer(event.player.uniqueId)
    }

    // ---- command ownership handoff (sub-project D) ----

    /**
     * Server-side ownership gate: attested this session AND the client advertised
     * WANTS_COMMAND_OWNERSHIP in its HELLO_CLIENT flags. Pure matrix in
     * [CommandOwnershipRouting.shouldHandOff]; this only supplies the per-player state.
     */
    fun canOpenUi(player: Player): Boolean =
        CommandOwnershipRouting.shouldHandOff(
            attested = attested.contains(player.uniqueId),
            clientFlags = clientFlags[player.uniqueId] ?: 0,
        )

    /**
     * Sends an OPEN_UI handoff if the gate passes and the channel is deliverable.
     * Returns whether it was sent — false lets the caller run the classic chat flow,
     * so vanilla clients (and races before HELLO/ATTEST complete) degrade gracefully.
     * Main thread only (sendPluginMessage).
     */
    fun sendOpenUi(player: Player, surface: OpenUiSurface, contextId: String = ""): Boolean {
        if (!canOpenUi(player)) return false
        if (!player.listeningPluginChannels.contains(IpcProtocol.CHANNEL)) return false
        val msg = OpenUi(IpcProtocol.VERSION, surface.wire, contextId)
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeOpenUi(msg))
        return true
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != IpcProtocol.CHANNEL) return
        try {
            when (IpcCodec.peekOpcode(message)) {
                IpcOpcode.HELLO_CLIENT -> {
                    val hello: HelloClient = IpcCodec.decodeHelloClient(message)
                    clientFlags[player.uniqueId] = hello.clientFlags
                    plugin.logger.info("Schematio mod present for ${player.name}: v${hello.modVersion} (proto ${hello.protocolVersion})")
                    greet(player) // fallback path; deduped
                    if (wantsAttestation(hello)) {
                        requestAndSendAttest(player, hello.nonce)
                    }
                }
                IpcOpcode.LOAD_REQUEST -> handleLoadRequest(player, IpcCodec.decodeLoadRequest(message))
                IpcOpcode.UPLOAD_CLIPBOARD -> handleUploadClipboard(player, IpcCodec.decodeUploadClipboard(message))
                IpcOpcode.LOAD_CLIPBOARD -> logOpcodeNoise("legacy LOAD_CLIPBOARD (removed in protocol v2)", player)
                else -> logOpcodeNoise("unknown opcode", player)
            }
        } catch (_: IpcPayloadTooLargeException) {
            // Spec: over-cap payloads are dropped quietly — no parse, no log spam.
        } catch (e: IpcFormatException) {
            plugin.logger.warning("Malformed Schematio IPC from ${player.name}: ${e.message}")
        }
    }

    /** Spec: unknown/dead opcodes are ignored with at most one log line per minute. */
    private fun logOpcodeNoise(what: String, player: Player) {
        val now = System.currentTimeMillis()
        if (now - lastOpcodeNoiseLogMs >= 60_000L) {
            lastOpcodeNoiseLogMs = now
            plugin.logger.info("Ignoring $what on ${IpcProtocol.CHANNEL} from ${player.name}")
        }
    }

    // ---- LOAD_REQUEST (reference-pull clipboard load) ----

    /**
     * Guards in spec order (each failure -> exactly one terminal STATUS), then:
     * STATUS RESOLVING (main) -> async backend fetch (DOWNLOADING emitted just before
     * the blocking call) -> main-thread hop -> WorldEdit parse + setClipboard -> STATUS OK.
     * No schematic bytes are ever echoed back over the channel.
     */
    private fun handleLoadRequest(player: Player, msg: LoadRequest) {
        val guard = LoadRequestGuards.firstFailure(
            worldEditAvailable = worldEditAvailable,
            attested = attested.contains(player.uniqueId),
            hasPermission = player.hasPermission(LoadRequestGuards.LOAD_PERMISSION),
        )
        if (guard != null) {
            sendStatus(player, msg.requestId, guard.state, guard.detail)
            return
        }
        if (loadLimiter.tryAcquire(player.uniqueId) == null) {
            val waitSeconds = loadLimiter.getWaitTimeSeconds(player.uniqueId)
            sendStatus(player, msg.requestId, StatusState.RATE_LIMITED, "Too many clipboard loads; retry in ${waitSeconds}s")
            return
        }
        val client = plugin.clipboardResolveClient
        if (client == null) {
            sendStatus(player, msg.requestId, StatusState.UNAVAILABLE, "The plugin is not connected to schemat.io")
            return
        }
        val refType = LoadRefType.fromWire(msg.refType)
        if (refType == null) { // unreachable: decode validates; belt-and-braces
            sendStatus(player, msg.requestId, StatusState.ERROR, "Unknown reference type")
            return
        }

        sendStatus(player, msg.requestId, StatusState.RESOLVING, "")
        val playerUuid = player.uniqueId.toString()
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            // The transport reads the body in one blocking call, so "while streaming"
            // collapses to: announce DOWNLOADING just before the fetch (main-thread send).
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (player.isOnline) sendStatus(player, msg.requestId, StatusState.DOWNLOADING, "")
            })
            val outcome = runBlocking { client.resolve(playerUuid, refType, msg.refId, msg.versionId) }
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                finishLoadRequest(player, msg.requestId, outcome)
            })
        })
    }

    /** Main thread only: WorldEdit session APIs + sendPluginMessage. */
    private fun finishLoadRequest(player: Player, requestId: Int, outcome: ClipboardResolveOutcome) {
        LoadRequestGuards.statusFor(outcome)?.let { (state, detail) ->
            sendStatus(player, requestId, state, detail)
            return
        }
        val bytes = (outcome as ClipboardResolveOutcome.Bytes).bytes
        try {
            val clipboard = WorldEditUtil.byteArrayToClipboard(bytes)
            if (clipboard == null) {
                sendStatus(player, requestId, StatusState.ERROR, "Could not parse the schematic (format '${outcome.format}')")
                return
            }
            WorldEditUtil.setClipboard(player, clipboard)
            plugin.logger.info("Loaded ${bytes.size}-byte schematic into ${player.name}'s WorldEdit clipboard (format '${outcome.format}')")
            sendStatus(player, requestId, StatusState.OK, "")
            // Chat confirmation kept on purpose: the flow stays observable without the UI.
            player.sendMessage("§aSchematio: schematic loaded into your WorldEdit clipboard. Use //paste to place it.")
        } catch (e: Throwable) {
            plugin.logger.warning("Error loading clipboard for ${player.name}: ${e.javaClass.simpleName}: ${e.message}")
            sendStatus(player, requestId, StatusState.ERROR, "An error occurred loading the schematic")
        }
    }

    // ---- UPLOAD_CLIPBOARD (server clipboard -> backend draft, sub-project C) ----

    /**
     * The IPC path REQUIRES an attested session (spec). Every request gets exactly one
     * reply: DRAFT_CREATED on success, or one terminal STATUS. The draft id is the ONLY
     * thing the client trusts from this server — it re-fetches the draft with the
     * USER's own auth and checks ownership before opening any UI.
     */
    private fun handleUploadClipboard(player: Player, msg: UploadClipboard) {
        plugin.clipboardUploadService.uploadCurrentClipboard(
            player,
            requireAttested = true,
            attested = attested.contains(player.uniqueId),
        ) { result ->
            when (result) {
                is ClipboardUploadService.Result.Created ->
                    sendDraftCreated(player, msg.requestId, result.draftId)
                is ClipboardUploadService.Result.Failed ->
                    sendStatus(player, msg.requestId, result.state, result.detail)
            }
        }
    }

    /** Main thread only. */
    private fun sendDraftCreated(player: Player, requestId: Int, draftId: String) {
        val msg = DraftCreated(IpcProtocol.VERSION, requestId, draftId)
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeDraftCreated(msg))
    }

    /** Main thread only. */
    private fun sendStatus(player: Player, requestId: Int, state: StatusState, detail: String) {
        val status = Status(IpcProtocol.VERSION, requestId, state.wire, detail)
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeStatus(status))
    }

    // ---- handshake (sub-project A) ----

    private fun greet(player: Player) {
        if (!greeted.add(player.uniqueId)) return
        if (!player.listeningPluginChannels.contains(IpcProtocol.CHANNEL)) {
            greeted.remove(player.uniqueId) // not ready yet; allow a later trigger to retry
            return
        }
        val hello = HelloServer(
            protocolVersion = IpcProtocol.VERSION,
            pluginVersion = plugin.description.version,
            capabilities = currentCapabilities(),
            platform = IpcPlatform.PAPER_PLUGIN,
            serverSoftware = "${plugin.server.name} ${plugin.server.minecraftVersion}",
            mcVersion = plugin.server.minecraftVersion,
            backendHost = plugin.apiEndpoint.replace(Regex("/api/v\\d+$"), ""),
            communityId = plugin.communityId,
            communitySlug = plugin.communitySlug,
        )
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeHelloServer(hello))
    }

    /**
     * Fetches a backend attestation for [nonce] off-thread and relays it verbatim as an
     * ATTEST message on the main thread. On success the player's connection is marked
     * attested, unlocking LOAD_REQUEST. Failure sends nothing — the client settles at
     * UNVERIFIED and load stays gated off.
     */
    private fun requestAndSendAttest(player: Player, nonce: ByteArray) {
        val client = plugin.attestationClient ?: run {
            plugin.logger.info("No attestation client (API unconfigured); ${player.name} will stay UNVERIFIED")
            return
        }
        val nonceHex = bytesToHexLower(nonce)
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val attestation = runBlocking { client.requestAttestation(nonceHex, IpcPlatform.PAPER_PLUGIN) }
            if (attestation == null) {
                plugin.logger.info("Attestation unavailable for ${player.name}; client will settle at UNVERIFIED")
                return@Runnable
            }
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                val msg = Attest(IpcProtocol.VERSION, attestation.payloadJson, attestation.signature, attestation.keyId)
                player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeAttest(msg))
                attested.add(player.uniqueId)
            })
        })
    }

    companion object {
        /** Pure gate: only v2 hellos carrying a well-formed 16-byte nonce get attested. */
        fun wantsAttestation(hello: HelloClient): Boolean =
            hello.protocolVersion >= 2 && hello.nonce.size == 16

        /** Pure gate for tests: which capability bits a build/config combination advertises. */
        fun capabilitiesFor(worldEditAvailable: Boolean, uploadConfigured: Boolean): Int =
            Capabilities.DOWNLOAD_CMD or
                Capabilities.WANTS_COMMAND_OWNERSHIP or
                (if (worldEditAvailable) Capabilities.LOAD_CLIPBOARD else 0) or
                (if (worldEditAvailable && uploadConfigured) Capabilities.UPLOAD else 0)
    }
}
