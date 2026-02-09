package io.schemat.connector.core.cache

import io.schemat.connector.core.validation.ValidationConstants
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player rate limiter for API requests using a sliding window algorithm.
 *
 * This prevents players from spamming API requests and causing DoS conditions.
 * Each player has their own request counter that resets after the time window expires.
 *
 * ## Thread Safety
 * This class is thread-safe and can be accessed from multiple threads concurrently.
 *
 * ## Usage Example
 * ```kotlin
 * val rateLimiter = RateLimiter(maxRequests = 10, windowMs = 60_000)
 *
 * // In command handler:
 * val remaining = rateLimiter.tryAcquire(player.uniqueId)
 * if (remaining == null) {
 *     val waitTime = rateLimiter.getWaitTimeMs(player.uniqueId) / 1000
 *     player.sendMessage("Rate limited. Please wait ${waitTime}s")
 *     return
 * }
 * // Proceed with API call...
 * ```
 *
 * @property maxRequests Maximum number of requests allowed per window
 * @property windowMs Time window in milliseconds
 */
class RateLimiter(
    private val maxRequests: Int = ValidationConstants.DEFAULT_RATE_LIMIT_REQUESTS,
    private val windowMs: Long = ValidationConstants.DEFAULT_RATE_LIMIT_WINDOW_SECONDS * 1000L
) {
    /**
     * Map of player UUID to their request timestamps within the current window.
     */
    private val requestLog = ConcurrentHashMap<UUID, MutableList<Long>>()

    /**
     * Attempt to acquire a request slot for the given player.
     *
     * If the player is within their rate limit, this records the request and returns
     * the number of remaining requests. If the player has exceeded their limit,
     * returns null.
     *
     * @param playerId The UUID of the player making the request
     * @return The number of remaining requests, or null if rate limited
     */
    fun tryAcquire(playerId: UUID): Int? {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs

        val requests = requestLog.compute(playerId) { _, existing ->
            val list = existing ?: mutableListOf()
            // Remove expired entries
            list.removeIf { it < cutoff }
            list
        }!!

        synchronized(requests) {
            if (requests.size >= maxRequests) {
                return null  // Rate limited
            }
            requests.add(now)
            return maxRequests - requests.size
        }
    }

    /**
     * Check if a request would be allowed without consuming a slot.
     *
     * @param playerId The UUID of the player
     * @return true if the player can make a request, false if rate limited
     */
    fun canAcquire(playerId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs

        val requests = requestLog[playerId] ?: return true
        synchronized(requests) {
            val validRequests = requests.count { it >= cutoff }
            return validRequests < maxRequests
        }
    }

    /**
     * Get the number of remaining requests for a player.
     *
     * @param playerId The UUID of the player
     * @return The number of remaining requests in the current window
     */
    fun getRemainingRequests(playerId: UUID): Int {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs

        val requests = requestLog[playerId] ?: return maxRequests
        synchronized(requests) {
            val validRequests = requests.count { it >= cutoff }
            return (maxRequests - validRequests).coerceAtLeast(0)
        }
    }

    /**
     * Get the time in milliseconds until the next request slot becomes available.
     *
     * @param playerId The UUID of the player
     * @return Milliseconds until a request is allowed, or 0 if a request is currently allowed
     */
    fun getWaitTimeMs(playerId: UUID): Long {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs

        val requests = requestLog[playerId] ?: return 0
        synchronized(requests) {
            val validRequests = requests.filter { it >= cutoff }
            if (validRequests.size < maxRequests) return 0

            // Find the oldest request that's still in the window
            val oldest = validRequests.minOrNull() ?: return 0

            // Time until that request expires
            val waitTime = (oldest + windowMs) - now
            return waitTime.coerceAtLeast(0)
        }
    }

    /**
     * Get the time in seconds until the next request slot becomes available.
     *
     * @param playerId The UUID of the player
     * @return Seconds until a request is allowed (rounded up), or 0 if allowed now
     */
    fun getWaitTimeSeconds(playerId: UUID): Int {
        val waitMs = getWaitTimeMs(playerId)
        return if (waitMs == 0L) 0 else ((waitMs + 999) / 1000).toInt()
    }

    /**
     * Clean up expired entries from all players.
     *
     * Call this periodically (e.g., every 5 minutes) to prevent memory buildup
     * from players who have left the server.
     */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - windowMs

        requestLog.forEach { (_, requests) ->
            synchronized(requests) {
                requests.removeIf { it < cutoff }
            }
        }

        // Remove empty entries
        requestLog.entries.removeIf { it.value.isEmpty() }
    }

    /**
     * Remove a specific player's rate limit data.
     *
     * Useful when a player disconnects.
     *
     * @param playerId The UUID of the player to remove
     */
    fun removePlayer(playerId: UUID) {
        requestLog.remove(playerId)
    }

    /**
     * Clear all rate limit data.
     *
     * Useful when reloading the plugin configuration.
     */
    fun clear() {
        requestLog.clear()
    }

    /**
     * Get the current number of tracked players.
     *
     * Useful for debugging and monitoring.
     *
     * @return The number of players with active rate limit tracking
     */
    fun getTrackedPlayerCount(): Int {
        return requestLog.size
    }

    /**
     * Get statistics about rate limiting.
     *
     * @return A map of statistics
     */
    fun getStats(): Map<String, Any> {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs

        var totalActiveRequests = 0
        var playersAtLimit = 0

        requestLog.forEach { (_, requests) ->
            synchronized(requests) {
                val validCount = requests.count { it >= cutoff }
                totalActiveRequests += validCount
                if (validCount >= maxRequests) {
                    playersAtLimit++
                }
            }
        }

        return mapOf(
            "trackedPlayers" to requestLog.size,
            "totalActiveRequests" to totalActiveRequests,
            "playersAtLimit" to playersAtLimit,
            "maxRequests" to maxRequests,
            "windowSeconds" to (windowMs / 1000)
        )
    }
}
