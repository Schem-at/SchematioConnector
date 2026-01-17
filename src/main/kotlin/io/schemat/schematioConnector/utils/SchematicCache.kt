package io.schemat.schematioConnector.utils

import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for schematic listings and API responses.
 *
 * Provides time-based expiration and automatic cleanup to reduce API calls
 * and enable offline mode functionality.
 *
 * ## Features
 * - Time-based cache expiration (configurable TTL)
 * - Per-query caching for search results
 * - Thread-safe concurrent access
 * - Automatic stale entry cleanup
 * - Cache statistics for monitoring
 *
 * ## Usage
 * ```kotlin
 * val cache = SchematicCache(ttlMs = 60_000) // 1 minute TTL
 *
 * // Try cache first
 * val cached = cache.getListings("search=castle&page=1")
 * if (cached != null) {
 *     return cached
 * }
 *
 * // Fetch from API and cache
 * val result = api.fetchListings(...)
 * cache.putListings("search=castle&page=1", result)
 * ```
 */
class SchematicCache(
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    companion object {
        /** Default cache TTL: 5 minutes */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        /** Minimum allowed TTL: 30 seconds */
        const val MIN_TTL_MS = 30 * 1000L

        /** Maximum allowed TTL: 1 hour */
        const val MAX_TTL_MS = 60 * 60 * 1000L
    }

    /**
     * Cached entry with timestamp for expiration checking.
     */
    private data class CacheEntry<T>(
        val data: T,
        val cachedAt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(ttlMs: Long): Boolean {
            return System.currentTimeMillis() - cachedAt > ttlMs
        }

        fun ageMs(): Long = System.currentTimeMillis() - cachedAt
    }

    // Cache for schematic listings (keyed by query string)
    private val listingsCache = ConcurrentHashMap<String, CacheEntry<CachedListingResult>>()

    // Cache for individual schematic details (keyed by schematic ID)
    private val detailsCache = ConcurrentHashMap<String, CacheEntry<JsonObject>>()

    // Statistics
    private var hits = 0L
    private var misses = 0L

    /**
     * Cached listing result containing schematics and pagination metadata.
     */
    data class CachedListingResult(
        val schematics: List<JsonObject>,
        val meta: JsonObject,
        val totalCount: Int = schematics.size
    )

    // ========== Listings Cache ==========

    /**
     * Get cached listings for a query, or null if not cached or expired.
     *
     * @param queryKey The cache key (usually the full query string)
     * @return Cached result or null
     */
    fun getListings(queryKey: String): CachedListingResult? {
        val entry = listingsCache[queryKey]
        return if (entry != null && !entry.isExpired(ttlMs)) {
            hits++
            entry.data
        } else {
            if (entry != null) {
                listingsCache.remove(queryKey)
            }
            misses++
            null
        }
    }

    /**
     * Get cached listings even if expired (for offline mode).
     *
     * @param queryKey The cache key
     * @return Cached result with age info, or null if never cached
     */
    fun getListingsStale(queryKey: String): Pair<CachedListingResult, Long>? {
        val entry = listingsCache[queryKey] ?: return null
        return entry.data to entry.ageMs()
    }

    /**
     * Store listings in cache.
     *
     * @param queryKey The cache key
     * @param result The listing result to cache
     */
    fun putListings(queryKey: String, result: CachedListingResult) {
        listingsCache[queryKey] = CacheEntry(result)
    }

    /**
     * Store listings from raw data.
     */
    fun putListings(queryKey: String, schematics: List<JsonObject>, meta: JsonObject) {
        putListings(queryKey, CachedListingResult(schematics, meta))
    }

    // ========== Details Cache ==========

    /**
     * Get cached schematic details by ID.
     */
    fun getDetails(schematicId: String): JsonObject? {
        val entry = detailsCache[schematicId]
        return if (entry != null && !entry.isExpired(ttlMs)) {
            hits++
            entry.data
        } else {
            if (entry != null) {
                detailsCache.remove(schematicId)
            }
            misses++
            null
        }
    }

    /**
     * Get cached details even if expired (for offline mode).
     */
    fun getDetailsStale(schematicId: String): Pair<JsonObject, Long>? {
        val entry = detailsCache[schematicId] ?: return null
        return entry.data to entry.ageMs()
    }

    /**
     * Store schematic details in cache.
     */
    fun putDetails(schematicId: String, details: JsonObject) {
        detailsCache[schematicId] = CacheEntry(details)
    }

    // ========== Cache Management ==========

    /**
     * Remove all expired entries from the cache.
     *
     * @return Number of entries removed
     */
    fun cleanup(): Int {
        var removed = 0

        val listingsIterator = listingsCache.entries.iterator()
        while (listingsIterator.hasNext()) {
            val entry = listingsIterator.next()
            if (entry.value.isExpired(ttlMs)) {
                listingsIterator.remove()
                removed++
            }
        }

        val detailsIterator = detailsCache.entries.iterator()
        while (detailsIterator.hasNext()) {
            val entry = detailsIterator.next()
            if (entry.value.isExpired(ttlMs)) {
                detailsIterator.remove()
                removed++
            }
        }

        return removed
    }

    /**
     * Clear all cached data.
     */
    fun clear() {
        listingsCache.clear()
        detailsCache.clear()
        hits = 0
        misses = 0
    }

    /**
     * Invalidate listings cache (e.g., after upload).
     */
    fun invalidateListings() {
        listingsCache.clear()
    }

    /**
     * Invalidate a specific schematic's cached details.
     */
    fun invalidateDetails(schematicId: String) {
        detailsCache.remove(schematicId)
    }

    // ========== Statistics ==========

    /**
     * Get cache statistics.
     */
    fun getStats(): Map<String, Any> {
        val totalRequests = hits + misses
        val hitRate = if (totalRequests > 0) (hits.toDouble() / totalRequests * 100) else 0.0

        return mapOf(
            "listingsCached" to listingsCache.size,
            "detailsCached" to detailsCache.size,
            "hits" to hits,
            "misses" to misses,
            "hitRate" to "%.1f%%".format(hitRate),
            "ttlSeconds" to (ttlMs / 1000)
        )
    }

    /**
     * Check if cache has any data (for offline mode detection).
     */
    fun hasData(): Boolean {
        return listingsCache.isNotEmpty() || detailsCache.isNotEmpty()
    }

    /**
     * Get the number of cached listing queries.
     */
    fun getListingsCacheSize(): Int = listingsCache.size

    /**
     * Get the number of cached schematic details.
     */
    fun getDetailsCacheSize(): Int = detailsCache.size
}
