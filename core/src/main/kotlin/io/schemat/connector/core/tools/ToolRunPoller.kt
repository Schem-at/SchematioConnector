package io.schemat.connector.core.tools

import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import kotlinx.coroutines.delay

/** How waiting on a run ended. */
sealed class ToolRunOutcome {
    /** The run reached a terminal status (done, failed, timeout or cancelled). */
    data class Finished(val run: ToolRun) : ToolRunOutcome()

    /** Polling hit a non-transient API error (permission, not found, validation...). */
    data class Failed(val error: ApiError) : ToolRunOutcome()

    /** The client stopped waiting; the run may still finish on the server. Never cancelled. */
    data class StillRunning(val run: ToolRun?) : ToolRunOutcome()
}

/**
 * Polls `GET /tools/runs/{id}` until the run is terminal. Waits [DEFAULT_INTERVAL_MS]
 * between polls unless the server's `Retry-After` says otherwise (clamped to 1-10 s),
 * rides out up to [MAX_TRANSIENT] consecutive network/5xx/429 blips, and stops waiting
 * after the tool's timeout plus [GRACE_SECONDS] (queue time included) without cancelling.
 */
class ToolRunPoller(
    private val api: ToolsApi,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_INTERVAL_MS = 1_500L
        const val GRACE_SECONDS = 60
        const val DEFAULT_TIMEOUT_SECONDS = 300
        const val MAX_TRANSIENT = 5
    }

    /**
     * @param isCancelled checked before every poll; when true the run is DELETEd and
     *        the cancel response (or the last seen run) is returned.
     * @param onUpdate called with every polled run, including the terminal one.
     */
    suspend fun await(
        runId: String,
        timeoutSeconds: Int?,
        actorUuid: String? = null,
        isCancelled: () -> Boolean = { false },
        onUpdate: (ToolRun) -> Unit,
    ): ToolRunOutcome {
        val deadline = now() + ((timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS) + GRACE_SECONDS) * 1000L
        var last: ToolRun? = null
        var transient = 0
        while (true) {
            if (isCancelled()) {
                return when (val r = api.cancel(runId, actorUuid)) {
                    is ApiResult.Success -> ToolRunOutcome.Finished(r.value).also { onUpdate(r.value) }
                    is ApiResult.Failure -> last?.let { ToolRunOutcome.StillRunning(it) } ?: ToolRunOutcome.Failed(r.error)
                }
            }
            var waitMs = DEFAULT_INTERVAL_MS
            when (val r = api.run(runId, actorUuid)) {
                is ApiResult.Success -> {
                    transient = 0
                    last = r.value.run
                    onUpdate(r.value.run)
                    if (r.value.run.status.terminal) return ToolRunOutcome.Finished(r.value.run)
                    r.value.retryAfterSeconds?.let { waitMs = it.coerceIn(1, 10) * 1000L }
                }
                is ApiResult.Failure -> {
                    val error = r.error
                    val isTransient = error is ApiError.Offline || error is ApiError.Server || error is ApiError.RateLimited
                    if (!isTransient || ++transient > MAX_TRANSIENT) return ToolRunOutcome.Failed(error)
                    if (error is ApiError.RateLimited) error.retryAfterSeconds?.let { waitMs = it.coerceIn(1, 10) * 1000L }
                }
            }
            if (now() >= deadline) return ToolRunOutcome.StillRunning(last)
            sleep(waitMs)
        }
    }
}
