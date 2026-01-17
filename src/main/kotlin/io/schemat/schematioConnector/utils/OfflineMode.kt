package io.schemat.schematioConnector.utils

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages offline mode detection and graceful degradation.
 *
 * Tracks API connectivity status and provides mechanisms for:
 * - Detecting when API becomes unreachable
 * - Automatic recovery when connectivity is restored
 * - Exponential backoff for connection retries
 * - Graceful degradation with cached data
 *
 * ## States
 * - **Online**: API is reachable, normal operation
 * - **Offline**: API is unreachable, using cached data if available
 * - **Degraded**: API is slow/unreliable, prefer cached data
 *
 * ## Usage
 * ```kotlin
 * val offlineMode = OfflineMode()
 *
 * // Before API call
 * if (offlineMode.shouldSkipApiCall()) {
 *     return cache.getStaleData()
 * }
 *
 * // After API call
 * when (result) {
 *     is Success -> offlineMode.recordSuccess()
 *     is Failure -> offlineMode.recordFailure()
 * }
 * ```
 */
class OfflineMode {

    companion object {
        /** Minimum wait time between retry attempts */
        const val MIN_RETRY_DELAY_MS = 5_000L // 5 seconds

        /** Maximum wait time between retry attempts */
        const val MAX_RETRY_DELAY_MS = 300_000L // 5 minutes

        /** Number of consecutive failures before entering offline mode */
        const val FAILURE_THRESHOLD = 3

        /** Time window for counting failures (reset after this period of no failures) */
        const val FAILURE_WINDOW_MS = 60_000L // 1 minute
    }

    /**
     * Current connectivity state.
     */
    enum class State {
        /** API is reachable and responding normally */
        ONLINE,

        /** API is unreachable or not responding */
        OFFLINE,

        /** API is responding but with errors/slowness */
        DEGRADED
    }

    private val isOffline = AtomicBoolean(false)
    private val consecutiveFailures = AtomicLong(0)
    private val lastFailureTime = AtomicLong(0)
    private val lastSuccessTime = AtomicLong(System.currentTimeMillis())
    private val lastRetryAttempt = AtomicLong(0)
    private val currentRetryDelay = AtomicLong(MIN_RETRY_DELAY_MS)

    /**
     * Get the current connectivity state.
     */
    fun getState(): State {
        return when {
            isOffline.get() -> State.OFFLINE
            consecutiveFailures.get() > 0 -> State.DEGRADED
            else -> State.ONLINE
        }
    }

    /**
     * Check if currently in offline mode.
     */
    fun isOffline(): Boolean = isOffline.get()

    /**
     * Check if we should skip making an API call.
     *
     * Returns true if:
     * - We're in offline mode AND haven't waited long enough for retry
     *
     * This implements exponential backoff for retries.
     */
    fun shouldSkipApiCall(): Boolean {
        if (!isOffline.get()) {
            return false
        }

        val now = System.currentTimeMillis()
        val timeSinceLastRetry = now - lastRetryAttempt.get()

        // If enough time has passed, allow a retry attempt
        return timeSinceLastRetry < currentRetryDelay.get()
    }

    /**
     * Mark that we're about to attempt an API call.
     * Call this before making the request.
     */
    fun recordAttempt() {
        lastRetryAttempt.set(System.currentTimeMillis())
    }

    /**
     * Record a successful API call.
     * Resets failure counters and exits offline mode.
     */
    fun recordSuccess() {
        val now = System.currentTimeMillis()
        lastSuccessTime.set(now)
        consecutiveFailures.set(0)
        currentRetryDelay.set(MIN_RETRY_DELAY_MS)

        if (isOffline.compareAndSet(true, false)) {
            // Just recovered from offline mode
        }
    }

    /**
     * Record a failed API call.
     * May trigger offline mode if threshold is reached.
     *
     * @return true if this failure triggered offline mode
     */
    fun recordFailure(): Boolean {
        val now = System.currentTimeMillis()
        val lastFailure = lastFailureTime.get()

        // Reset counter if failures are spread apart
        if (now - lastFailure > FAILURE_WINDOW_MS) {
            consecutiveFailures.set(0)
        }

        lastFailureTime.set(now)
        val failures = consecutiveFailures.incrementAndGet()

        // Check if we should enter offline mode
        if (failures >= FAILURE_THRESHOLD && !isOffline.get()) {
            isOffline.set(true)
            return true
        }

        // If already offline, increase backoff
        if (isOffline.get()) {
            val newDelay = (currentRetryDelay.get() * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            currentRetryDelay.set(newDelay)
        }

        return false
    }

    /**
     * Force enter offline mode (e.g., after config change or on startup).
     */
    fun forceOffline() {
        isOffline.set(true)
        currentRetryDelay.set(MIN_RETRY_DELAY_MS)
    }

    /**
     * Force exit offline mode (e.g., after successful /reload).
     */
    fun forceOnline() {
        isOffline.set(false)
        consecutiveFailures.set(0)
        currentRetryDelay.set(MIN_RETRY_DELAY_MS)
    }

    /**
     * Get time until next retry is allowed (0 if retry is allowed now).
     */
    fun getTimeUntilRetryMs(): Long {
        if (!isOffline.get()) return 0

        val timeSinceLastRetry = System.currentTimeMillis() - lastRetryAttempt.get()
        val remaining = currentRetryDelay.get() - timeSinceLastRetry
        return remaining.coerceAtLeast(0)
    }

    /**
     * Get time until next retry in seconds (for display).
     */
    fun getTimeUntilRetrySeconds(): Int {
        return ((getTimeUntilRetryMs() + 999) / 1000).toInt()
    }

    /**
     * Get statistics about offline mode.
     */
    fun getStats(): Map<String, Any> {
        val now = System.currentTimeMillis()
        return mapOf(
            "state" to getState().name,
            "isOffline" to isOffline.get(),
            "consecutiveFailures" to consecutiveFailures.get(),
            "currentRetryDelaySeconds" to (currentRetryDelay.get() / 1000),
            "timeUntilRetrySeconds" to getTimeUntilRetrySeconds(),
            "timeSinceLastSuccessSeconds" to ((now - lastSuccessTime.get()) / 1000)
        )
    }

    /**
     * Reset all state (for testing or reload).
     */
    fun reset() {
        isOffline.set(false)
        consecutiveFailures.set(0)
        lastFailureTime.set(0)
        lastSuccessTime.set(System.currentTimeMillis())
        lastRetryAttempt.set(0)
        currentRetryDelay.set(MIN_RETRY_DELAY_MS)
    }
}
