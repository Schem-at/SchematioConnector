package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardUploadOutcome

/**
 * Pure decision logic for clipboard-draft uploads (spec: guard order, 2/min token
 * bucket, outcome -> STATUS mapping). Kept Bukkit-free so it runs under plain JUnit.
 */
object ClipboardUploadGuards {

    /** plugin.yml permission node, default true (spec). */
    const val UPLOAD_PERMISSION: String = "schematio.clipboard.upload"

    /** Per-player upload budget, shared by the IPC and chat paths (spec: 2/min). */
    const val REQUESTS_PER_MINUTE: Int = 2
    const val WINDOW_MS: Long = 60_000L

    enum class Failure(val state: StatusState, val detail: String) {
        WORLDEDIT_MISSING(StatusState.UNAVAILABLE, "WorldEdit is not installed on this server"),
        EMPTY_CLIPBOARD(StatusState.UNAVAILABLE, "Your WorldEdit clipboard is empty — //copy something first"),
        NO_PERMISSION(StatusState.DENIED, "Missing permission schematio.clipboard.upload"),
        NOT_ATTESTED(StatusState.DENIED, "Session is not attested; rejoin the server"),
    }

    /**
     * First failing guard, in spec order: WE present -> non-empty clipboard ->
     * permission -> attested (IPC path only; the chat command has no session to
     * attest). The rate limit is checked by the caller AFTER these pass, so denied
     * requests never consume bucket slots.
     */
    fun firstFailure(
        worldEditAvailable: Boolean,
        hasClipboard: Boolean,
        hasPermission: Boolean,
        requireAttested: Boolean,
        attested: Boolean,
    ): Failure? = when {
        !worldEditAvailable -> Failure.WORLDEDIT_MISSING
        !hasClipboard -> Failure.EMPTY_CLIPBOARD
        !hasPermission -> Failure.NO_PERMISSION
        requireAttested && !attested -> Failure.NOT_ATTESTED
        else -> null
    }

    /** Terminal STATUS for a backend outcome; null for Created (the caller answers DRAFT_CREATED). */
    fun statusFor(outcome: ClipboardUploadOutcome): Pair<StatusState, String>? = when (outcome) {
        is ClipboardUploadOutcome.Created -> null
        ClipboardUploadOutcome.NotLinked -> StatusState.DENIED to "No schemat.io account is linked to your Minecraft account"
        ClipboardUploadOutcome.Denied -> StatusState.DENIED to "The site denied the draft upload"
        ClipboardUploadOutcome.QuotaExceeded -> StatusState.DENIED to "Draft limit reached (10) — publish or delete drafts on schemat.io first"
        ClipboardUploadOutcome.TooLarge -> StatusState.TOO_LARGE to "Clipboard exceeds the 8 MiB limit"
        ClipboardUploadOutcome.RateLimited -> StatusState.RATE_LIMITED to "The site rate-limited this server; try again shortly"
        ClipboardUploadOutcome.Unavailable -> StatusState.UNAVAILABLE to "The schemat.io backend is unreachable"
        ClipboardUploadOutcome.Error -> StatusState.ERROR to "Unexpected backend response"
    }
}
