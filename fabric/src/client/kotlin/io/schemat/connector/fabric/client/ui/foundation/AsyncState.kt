package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.core.modapi.ApiError

/**
 * UI-side lifecycle of an async API value: [Loading] → [Ready] (possibly stale,
 * mirroring `StaleAware`) or [Failed] (carrying the [ApiError] plus a pre-rendered
 * user-facing message).
 */
sealed class AsyncState<out T> {

    data object Loading : AsyncState<Nothing>()

    data class Ready<T>(
        val value: T,
        val isStale: Boolean = false,
        val ageMs: Long = 0,
    ) : AsyncState<T>()

    data class Failed(val error: ApiError, val message: String) : AsyncState<Nothing>()

    companion object {
        fun failed(error: ApiError): Failed = Failed(error, error.toUserMessage())
    }
}

/** Map an [ApiError] to a short, user-facing message suitable for a notice banner. */
fun ApiError.toUserMessage(): String = when (this) {
    is ApiError.Offline -> "Can't reach schemat.io - showing cached data where available"
    is ApiError.Unauthorized -> "Not signed in - try re-authenticating from Settings"
    is ApiError.Forbidden -> message
    is ApiError.NotFound -> message
    is ApiError.Validation -> message
    is ApiError.Conflict -> message
    is ApiError.RateLimited ->
        if (retryAfterSeconds != null) "Slow down - try again in ${retryAfterSeconds}s"
        else "Slow down - too many requests"
    is ApiError.Server -> "Server error (HTTP $status) - try again later"
    is ApiError.Unexpected -> "Unexpected response (HTTP $status)"
}
