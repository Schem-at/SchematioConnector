package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardResolveOutcome

/**
 * Pure decision logic for LOAD_REQUEST handling (spec: guard order, token bucket,
 * outcome -> STATUS mapping). Kept Bukkit-free so it runs under plain JUnit.
 */
object LoadRequestGuards {

    /** plugin.yml permission node, default true. */
    const val LOAD_PERMISSION: String = "schematio.clipboard.load"

    /** Per-player LOAD_REQUEST budget (spec: 5/min token bucket). */
    const val REQUESTS_PER_MINUTE: Int = 5
    const val WINDOW_MS: Long = 60_000L

    enum class Failure(val state: StatusState, val detail: String) {
        WORLDEDIT_MISSING(StatusState.UNAVAILABLE, "WorldEdit is not installed on this server"),
        NOT_ATTESTED(StatusState.DENIED, "Session is not attested; rejoin the server"),
        NO_PERMISSION(StatusState.DENIED, "Missing permission schematio.clipboard.load"),
    }

    /**
     * First failing guard, in spec order: WorldEdit present -> session attested ->
     * bukkit permission. The rate limit is checked by the caller AFTER these pass,
     * so denied/unattested requests never consume bucket slots.
     */
    fun firstFailure(worldEditAvailable: Boolean, attested: Boolean, hasPermission: Boolean): Failure? =
        when {
            !worldEditAvailable -> Failure.WORLDEDIT_MISSING
            !attested -> Failure.NOT_ATTESTED
            !hasPermission -> Failure.NO_PERMISSION
            else -> null
        }

    /** Terminal STATUS for a backend outcome; null for Bytes (the caller loads + sends OK). */
    fun statusFor(outcome: ClipboardResolveOutcome): Pair<StatusState, String>? = when (outcome) {
        is ClipboardResolveOutcome.Bytes -> null
        ClipboardResolveOutcome.Denied -> StatusState.DENIED to "The site denied access to that schematic"
        ClipboardResolveOutcome.NotFound -> StatusState.NOT_FOUND to "Schematic not found"
        ClipboardResolveOutcome.TooLarge -> StatusState.TOO_LARGE to "Schematic exceeds the 8 MiB limit"
        ClipboardResolveOutcome.RateLimited -> StatusState.RATE_LIMITED to "The site rate-limited this server; try again shortly"
        ClipboardResolveOutcome.Unavailable -> StatusState.UNAVAILABLE to "The schemat.io backend is unreachable"
        ClipboardResolveOutcome.Error -> StatusState.ERROR to "Unexpected backend response"
    }
}
