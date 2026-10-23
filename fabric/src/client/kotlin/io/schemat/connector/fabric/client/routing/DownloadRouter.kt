package io.schemat.connector.fabric.client.routing

import io.schemat.connector.core.TransferEpoch
import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.routing.Destination
import io.schemat.connector.core.routing.DestinationResolver
import io.schemat.connector.core.routing.DownloadKind
import io.schemat.connector.core.routing.ParsedDownload
import io.schemat.connector.core.routing.Resolution
import io.schemat.connector.core.routing.ServerDownloadCommand
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.SchematicFiles
import io.schemat.connector.fabric.client.ipc.ServerIpc
import io.schemat.connector.fabric.client.ipc.ServerSession
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Routes a download to the first available destination of the player's priority order.
 *
 * `reply(text, isError)` is always invoked on the client thread, once per terminal outcome.
 * [route] and [load] must be called from the client thread.
 */
object DownloadRouter {

    private val LOGGER = LoggerFactory.getLogger("SchematioDownloadRouter")

    private val services get() = SchematioClientMod.instance.services

    /** Supersedes older local loads so a stale download cannot overwrite a newer one. */
    private val transfers = TransferEpoch()

    fun route(kind: DownloadKind, parsed: ParsedDownload.Ok, reply: (String, Boolean) -> Unit) {
        val order = ClientDestinations.order()
        val unavailable = ClientDestinations.unavailable()
        when (val resolution = DestinationResolver.resolve(order, parsed.filter, unavailable)) {
            is Resolution.Unavailable -> reply(resolution.reason, true)
            is Resolution.Chosen -> {
                val from = resolution.fellBackFrom
                if (from != null) {
                    reply(
                        "${from.label} unavailable: ${unavailable[from] ?: "not available"}. " +
                            "Loading into ${resolution.destination.label} instead.",
                        false,
                    )
                }
                attempt(resolution.destination, kind, parsed, order, unavailable, reply)
            }
        }
    }

    private fun attempt(
        destination: Destination,
        kind: DownloadKind,
        parsed: ParsedDownload.Ok,
        order: List<Destination>,
        unavailable: Map<Destination, String>,
        reply: (String, Boolean) -> Unit,
    ) {
        val name = displayName(kind, parsed.id)
        load(destination, kind, parsed.id, parsed.password, name, reply) { detail ->
            // Hop to the client thread: the failure may be reported from a network callback.
            services.onMainThread {
                if (parsed.filter != null) {
                    reply("${destination.label} failed: $detail", true)
                    return@onMainThread
                }
                // The failed destination joins the unavailable set, so the chain strictly
                // shrinks and can never revisit it (fall back at most once per destination).
                val nowUnavailable = unavailable + (destination to detail)
                when (val next = DestinationResolver.resolve(order, null, nowUnavailable)) {
                    is Resolution.Unavailable -> reply("${destination.label} failed: $detail. ${next.reason}", true)
                    is Resolution.Chosen -> {
                        reply(
                            "${destination.label} unavailable: $detail. Loading into ${next.destination.label} instead.",
                            false,
                        )
                        attempt(next.destination, kind, parsed, order, nowUnavailable, reply)
                    }
                }
            }
        }
    }

    private fun displayName(kind: DownloadKind, id: String): String =
        if (kind == DownloadKind.QUICK_SHARE) "quick share" else id

    /**
     * Executes a single [destination]. Only the server WorldEdit path reports failures through
     * [onFailure] (so the caller can fall back); every other failure goes to [reply] as an error.
     */
    fun load(
        destination: Destination,
        kind: DownloadKind,
        id: String,
        password: String?,
        name: String,
        reply: (String, Boolean) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        when (destination) {
            Destination.SERVER_WORLDEDIT -> loadOnServer(kind, id, password, name, reply, onFailure)
            Destination.LOCAL_WORLDEDIT -> loadLocalWorldEdit(kind, id, password, name, reply)
            Destination.LOCAL_LITEMATICA -> loadLitematica(kind, id, password, name, reply)
            Destination.LOCAL_FILE -> saveToFile(kind, id, password, name, reply)
        }
    }

    // ------------------------------------------------------------------ server WorldEdit

    private fun loadOnServer(
        kind: DownloadKind,
        id: String,
        password: String?,
        name: String,
        reply: (String, Boolean) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        // LOAD_REQUEST carries no password, so a protected share goes through the command path.
        if (ServerIpc.canLoadOnServer() && password.isNullOrEmpty()) {
            val refType = if (kind == DownloadKind.QUICK_SHARE) LoadRefType.SHARE_TOKEN else LoadRefType.SCHEMATIC
            val requestId = ServerIpc.sendLoadRequest(refType, id) { state, detail ->
                if (!state.isTerminal) return@sendLoadRequest
                services.onMainThread {
                    if (state == StatusState.OK) {
                        reply("Loaded \"$name\" into the server's WorldEdit clipboard", false)
                    } else {
                        onFailure(detail.ifBlank { state.name.lowercase().replace('_', ' ') })
                    }
                }
            }
            if (requestId == null) {
                LOGGER.warn("Server load request for {} could not be sent", id)
                onFailure("No verified Schematio server connection")
            }
            return
        }

        val command = try {
            ServerDownloadCommand.build(ServerSession.platform, kind, id, password)
        } catch (e: IllegalArgumentException) {
            // Unreachable: DownloadArgs.parse already rejected invalid codes. Never silent regardless.
            LOGGER.error("Could not build the server download command", e)
            reply("Could not build the server command: ${e.message ?: "invalid download"}", true)
            return
        }
        if (forwardToServer(command)) {
            reply("Sent to the server's WorldEdit. The server will confirm in chat.", false)
        } else {
            LOGGER.warn("Could not forward the download command: no server connection")
            onFailure("Not connected to a server")
        }
    }

    /**
     * Sends a `/`-less command line straight to the server as an unsigned command packet.
     *
     * MUST NOT use `ClientPacketListener.sendCommand(...)`: Fabric's client-command API
     * mixins into the HEAD of `sendCommand` and re-dispatches any *client*-registered
     * command through its own dispatcher, cancelling the vanilla server send. Because
     * `/schematio` is registered client-side, forwarding `schematio …` there re-enters this
     * command's executor → `forwardToServer` again → infinite recursion (StackOverflowError).
     * We instead send the exact packet vanilla `sendCommand` sends for a no-signable-argument
     * command (`ServerboundChatCommandPacket`) via `connection.send(...)`, which the mixin
     * does not touch. All `/schematio` forwards have only plain string args, so unsigned is
     * the correct vanilla behaviour.
     *
     * Returns false when there is no server connection to send on.
     */
    internal fun forwardToServer(command: String): Boolean {
        val connection = net.minecraft.client.Minecraft.getInstance().player?.connection ?: return false
        connection.send(net.minecraft.network.protocol.game.ServerboundChatCommandPacket(command))
        return true
    }

    // ------------------------------------------------------------------ local destinations

    private fun loadLocalWorldEdit(
        kind: DownloadKind,
        id: String,
        password: String?,
        name: String,
        reply: (String, Boolean) -> Unit,
    ) {
        val ticket = transfers.begin()
        val check: () -> String? = {
            if (!transfers.isCurrent(ticket)) "A newer download replaced this request." else null
        }
        download(kind, id, password, "schem", name, reply) { bytes ->
            services.onMainThread {
                try {
                    Bridges.worldEdit.bytesToClipboard(bytes, "schem", check) { ok, error ->
                        if (ok) {
                            reply("Loaded \"$name\" into your WorldEdit clipboard", false)
                        } else {
                            LOGGER.warn("Local WorldEdit load of {} failed: {}", id, error)
                            reply(error ?: "Failed to load \"$name\" into your WorldEdit clipboard", true)
                        }
                    }
                } catch (e: Exception) {
                    LOGGER.error("Local WorldEdit load of {} threw", id, e)
                    reply("Failed to load \"$name\" into your WorldEdit clipboard: ${e.message ?: "unexpected error"}", true)
                }
            }
        }
    }

    private fun loadLitematica(
        kind: DownloadKind,
        id: String,
        password: String?,
        name: String,
        reply: (String, Boolean) -> Unit,
    ) {
        val ticket = transfers.begin()
        val destinationCheck = Bridges.litematica.captureImportCheck()
        val check: () -> String? = {
            if (!transfers.isCurrent(ticket)) "A newer download replaced this request." else destinationCheck()
        }
        download(kind, id, password, "litematic", name, reply) { bytes ->
            val file = try {
                val dir = downloadDirectory()
                Files.createDirectories(dir)
                SchematicFiles.save(dir, name, "litematic", bytes)
            } catch (e: Exception) {
                LOGGER.error("Could not save {}", id, e)
                post(reply, "Could not save \"$name\": ${e.message ?: "unexpected error"}", true)
                return@download
            }
            services.onMainThread {
                try {
                    if (Bridges.litematica.isAvailable) {
                        Bridges.litematica.loadSchematic(file.toFile(), name, check) { ok, error ->
                            if (ok) {
                                reply("Loaded \"$name\" into Litematica", false)
                            } else {
                                LOGGER.warn("Litematica load of {} failed: {}", id, error)
                                reply(error ?: "Failed to load \"$name\" into Litematica", true)
                            }
                        }
                    } else {
                        reply("Litematica is not installed - saved to $file", false)
                    }
                } catch (e: Exception) {
                    LOGGER.error("Litematica load of {} threw", id, e)
                    reply("Failed to load \"$name\" into Litematica: ${e.message ?: "unexpected error"}", true)
                }
            }
        }
    }

    private fun saveToFile(
        kind: DownloadKind,
        id: String,
        password: String?,
        name: String,
        reply: (String, Boolean) -> Unit,
    ) {
        download(kind, id, password, "litematic", name, reply) { bytes ->
            try {
                val dir = downloadDirectory()
                Files.createDirectories(dir)
                val file = SchematicFiles.save(dir, name, "litematic", bytes)
                post(reply, "Saved to $file", false)
            } catch (e: Exception) {
                LOGGER.error("Could not save {}", id, e)
                post(reply, "Could not save \"$name\": ${e.message ?: "unexpected error"}", true)
            }
        }
    }

    /**
     * Downloads off-thread and hands the bytes to [onBytes] (IO thread). Any failure is logged
     * and replied (client thread) as an error, so [onBytes] is the only other terminal path.
     */
    private fun download(
        kind: DownloadKind,
        id: String,
        password: String?,
        format: String,
        name: String,
        reply: (String, Boolean) -> Unit,
        onBytes: (ByteArray) -> Unit,
    ) {
        val label = if (kind == DownloadKind.QUICK_SHARE) "quick share" else "schematic"
        services.scope.launch {
            try {
                when (val result = services.cached.download(id, format, password)) {
                    is ApiResult.Success -> onBytes(result.value)
                    is ApiResult.Failure -> {
                        LOGGER.warn("Download of {} failed: {}", id, result.error)
                        post(reply, "Could not download $label \"$name\": ${result.error.toUserMessage()}", true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOGGER.error("Download of {} threw", id, e)
                val message = ApiError.Unexpected(0, e.message ?: "Unexpected client error").toUserMessage()
                post(reply, "Could not download $label \"$name\": $message", true)
            }
        }
    }

    private fun post(reply: (String, Boolean) -> Unit, text: String, isError: Boolean) {
        services.onMainThread { reply(text, isError) }
    }

    /** Downloads land in Litematica's schematics dir when known, else `<game>/schematics/schemat.io`. */
    private fun downloadDirectory(): Path =
        Bridges.litematica.schematicsDirectory()
            ?: FabricLoader.getInstance().gameDir.resolve("schematics").resolve("schemat.io")
}
