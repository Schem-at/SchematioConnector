package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.cache.RateLimiter
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardUploadClient
import io.schemat.connector.core.modapi.ClipboardUploadOutcome
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Shared "upload my server-side WE clipboard as a draft" flow for BOTH entry points
 * (IPC UPLOAD_CLIPBOARD and /schematio upload). Spec discipline:
 * guards -> rate limit -> MAIN-THREAD clipboard snapshot -> ASYNC serialize + POST ->
 * main-thread callback (exactly once). User tokens NEVER appear here — the HTTP layer
 * is the community-token ClipboardUploadClient (spec invariant 1).
 */
class ClipboardUploadService(private val plugin: SchematioConnector) {

    sealed class Result {
        class Created(val draftId: String, val webUrl: String) : Result()
        class Failed(val state: StatusState, val detail: String, val notLinked: Boolean = false) : Result()
    }

    /** One bucket for both entry points — a player can't dodge the limit by mixing paths. */
    private val limiter = RateLimiter(
        maxRequests = ClipboardUploadGuards.REQUESTS_PER_MINUTE,
        windowMs = ClipboardUploadGuards.WINDOW_MS,
    )

    /** WorldEdit is a soft dependency — never touch its classes unless present. */
    private val worldEditAvailable: Boolean =
        runCatching { Class.forName("com.sk89q.worldedit.WorldEdit") }.isSuccess

    fun removePlayer(playerId: UUID) = limiter.removePlayer(playerId)

    /** Main thread only. [onResult] is invoked exactly once, back on the main thread. */
    fun uploadCurrentClipboard(
        player: Player,
        requireAttested: Boolean,
        attested: Boolean,
        onResult: (Result) -> Unit,
    ) {
        // MAIN-THREAD SNAPSHOT: the WE session API is main-thread; the Clipboard
        // reference captured here is the snapshot — only serialization goes async (spec).
        val clipboard = if (worldEditAvailable) WorldEditUtil.getClipboard(player) else null

        val guard = ClipboardUploadGuards.firstFailure(
            worldEditAvailable = worldEditAvailable,
            hasClipboard = clipboard != null,
            hasPermission = player.hasPermission(ClipboardUploadGuards.UPLOAD_PERMISSION),
            requireAttested = requireAttested,
            attested = attested,
        )
        if (guard != null) {
            onResult(Result.Failed(guard.state, guard.detail))
            return
        }
        if (limiter.tryAcquire(player.uniqueId) == null) {
            val wait = limiter.getWaitTimeSeconds(player.uniqueId)
            onResult(Result.Failed(StatusState.RATE_LIMITED, "Too many clipboard uploads; retry in ${wait}s"))
            return
        }
        val client = plugin.clipboardUploadClient
        if (client == null) {
            onResult(Result.Failed(StatusState.UNAVAILABLE, "The plugin is not connected to schemat.io"))
            return
        }

        val playerUuid = player.uniqueId.toString()
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val bytes = WorldEditUtil.clipboardToByteArray(clipboard!!)
            val result: Result = when {
                bytes == null ->
                    Result.Failed(StatusState.ERROR, "Could not serialize your clipboard")
                bytes.size > ClipboardUploadClient.MAX_UPLOAD_BYTES ->
                    Result.Failed(StatusState.TOO_LARGE, "Clipboard exceeds the 8 MiB limit")
                else -> when (val outcome = runBlocking { client.upload(playerUuid, bytes) }) {
                    is ClipboardUploadOutcome.Created -> Result.Created(outcome.draftId, outcome.webUrl)
                    else -> {
                        val (state, detail) = ClipboardUploadGuards.statusFor(outcome)!!
                        Result.Failed(state, detail, notLinked = outcome == ClipboardUploadOutcome.NotLinked)
                    }
                }
            }
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (player.isOnline) onResult(result)
            })
        })
    }
}
