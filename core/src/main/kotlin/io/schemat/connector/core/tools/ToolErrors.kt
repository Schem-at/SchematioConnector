package io.schemat.connector.core.tools

import io.schemat.connector.core.modapi.ApiError

/** Player-facing wording for remote-tools API errors (contract sections 4 and 5). */
object ToolErrors {

    private const val NOT_PERMITTED = "remote_tools_not_permitted"

    /** The 403 `{"error":"remote_tools_not_permitted"}`: the caller holds no remote-tools grant. */
    fun isNotPermitted(error: ApiError): Boolean =
        error is ApiError.Forbidden && (error.code == NOT_PERMITTED || error.message.contains(NOT_PERMITTED))

    fun userMessage(error: ApiError): String = when {
        isNotPermitted(error) ->
            "You don't have permission to run remote tools. Ask a schemat.io admin for the remote tools " +
                "permission, or run the tool through a community whose role allows remote tools."
        error is ApiError.Forbidden -> "Not allowed: ${error.message}"
        error is ApiError.Unauthorized -> "Sign in to schemat.io first (your session may have expired)."
        error is ApiError.NotFound -> "Tool or run not found on schemat.io."
        error is ApiError.Validation -> {
            val fields = error.fieldErrors.entries.flatMap { (field, messages) ->
                messages.map { if (field == "_") it else "$field: $it" }
            }
            fields.joinToString("; ").ifEmpty { error.message }
        }
        error is ApiError.RateLimited ->
            "Too many tool runs right now. Try again in " +
                (error.retryAfterSeconds?.let { "$it s." } ?: "a moment.")
        error is ApiError.Server && error.status == 503 -> "Remote tools are disabled on schemat.io right now."
        error is ApiError.Server -> "schemat.io had a problem (HTTP ${error.status}). Try again later."
        error is ApiError.Offline -> "Couldn't reach schemat.io. Check your connection and try again."
        error is ApiError.Conflict -> error.message
        error is ApiError.Unexpected -> error.message
        else -> "Request failed."
    }

    /** Wording for a run that ended FAILED/TIMEOUT, from the contract's `error.code`. */
    fun runFailureMessage(run: ToolRun): String {
        val base = when (run.errorCode) {
            "validation" -> "The tool rejected its inputs"
            "timeout" -> "The tool ran out of time"
            "cancelled" -> "The run was cancelled"
            "output_too_large" -> "The result was too large to return"
            "backend_unavailable" -> "The tool server is unavailable"
            else -> if (run.status == RunStatus.TIMEOUT) "The tool ran out of time" else "The tool failed"
        }
        return run.error?.takeIf { it.isNotBlank() }?.let { "$base: $it" } ?: "$base."
    }
}
