package io.schemat.schematioConnector.utils

import com.google.gson.JsonObject
import io.schemat.schematioConnector.SchematioConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.UUID

/**
 * Centralized service for fetching schematics from the schemat.io API.
 *
 * Handles:
 * - API endpoint management
 * - Request building with query parameters
 * - Caching (fresh and stale)
 * - Offline mode with backoff
 * - Rate limiting
 * - Error handling
 *
 * @property plugin The main plugin instance
 */
class SchematicsApiService(private val plugin: SchematioConnector) {

    companion object {
        const val SCHEMATICS_ENDPOINT = "/schematics"
        const val DEFAULT_ITEMS_PER_PAGE = 10
        /** Filter to only show schematics convertible to .schem format (WorldEdit compatible) */
        const val CONVERTIBLE_TO_FORMAT = "schem"
    }

    /**
     * Result of a schematics fetch operation.
     */
    sealed class FetchResult {
        /**
         * Successful fetch with schematic data.
         * @property schematics List of schematic JSON objects
         * @property meta Pagination metadata
         * @property fromCache Whether data came from cache
         * @property staleMinutes If using stale cache, how old the data is
         */
        data class Success(
            val schematics: List<JsonObject>,
            val meta: JsonObject,
            val fromCache: Boolean,
            val staleMinutes: Int = 0
        ) : FetchResult()

        /**
         * Request was rate limited.
         * @property waitSeconds Seconds until rate limit resets
         */
        data class RateLimited(val waitSeconds: Int) : FetchResult()

        /**
         * API is offline and no cached data available.
         * @property retrySeconds Seconds until next retry attempt
         */
        data class OfflineNoCache(val retrySeconds: Int) : FetchResult()

        /**
         * An error occurred during fetch.
         * @property message Error description
         * @property enteredOffline Whether this error triggered offline mode
         */
        data class Error(val message: String, val enteredOffline: Boolean) : FetchResult()
    }

    /**
     * Fetches schematics with full caching, offline mode, and rate limiting support.
     *
     * This is the main entry point for list commands. It handles:
     * 1. Cache lookup (returns immediately if fresh cache hit)
     * 2. Offline mode check (returns stale cache or offline error)
     * 3. Rate limiting check
     * 4. Async API fetch with caching of results
     * 5. Fallback to stale cache on errors
     *
     * @param playerId Player UUID for rate limiting
     * @param search Optional search query
     * @param page Page number (1-indexed)
     * @param perPage Items per page
     * @param cacheKeyPrefix Prefix for cache key to differentiate UI variants
     * @param onResult Callback with the fetch result, called on the main thread
     */
    fun fetchSchematicsWithCache(
        playerId: UUID,
        search: String?,
        page: Int,
        perPage: Int = DEFAULT_ITEMS_PER_PAGE,
        cacheKeyPrefix: String = "list",
        onResult: (FetchResult) -> Unit
    ) {
        val cache = plugin.schematicCache
        val offlineMode = plugin.offlineMode

        // Build cache key
        val cacheKey = buildCacheKey(cacheKeyPrefix, search, page, perPage)

        // Check cache first - cache hits bypass rate limiting for fast UX
        val cachedResult = cache.getListings(cacheKey)
        if (cachedResult != null) {
            onResult(FetchResult.Success(
                schematics = cachedResult.schematics,
                meta = cachedResult.meta,
                fromCache = true
            ))
            return
        }

        // Check if we should skip API call (offline mode with backoff)
        if (offlineMode.shouldSkipApiCall()) {
            val staleResult = cache.getListingsStale(cacheKey)
            if (staleResult != null) {
                val (data, ageMs) = staleResult
                val ageMinutes = (ageMs / 60000).toInt()
                onResult(FetchResult.Success(
                    schematics = data.schematics,
                    meta = data.meta,
                    fromCache = true,
                    staleMinutes = ageMinutes
                ))
            } else {
                onResult(FetchResult.OfflineNoCache(offlineMode.getTimeUntilRetrySeconds()))
            }
            return
        }

        // Rate limit only applies to actual API calls
        val rateLimitResult = plugin.rateLimiter.tryAcquire(playerId)
        if (rateLimitResult == null) {
            onResult(FetchResult.RateLimited(plugin.rateLimiter.getWaitTimeSeconds(playerId)))
            return
        }

        // Run async fetch
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            offlineMode.recordAttempt()

            try {
                val (schematics, meta) = runBlocking { fetchSchematics(search, page, perPage) }

                // Cache the successful result
                cache.putListings(cacheKey, schematics, meta)
                offlineMode.recordSuccess()

                // Return on main thread
                plugin.server.scheduler.runTask(plugin, Runnable {
                    onResult(FetchResult.Success(
                        schematics = schematics,
                        meta = meta,
                        fromCache = false
                    ))
                })
            } catch (e: Exception) {
                val enteredOffline = offlineMode.recordFailure()
                val staleResult = cache.getListingsStale(cacheKey)

                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (staleResult != null) {
                        val (data, ageMs) = staleResult
                        val ageMinutes = (ageMs / 60000).toInt()
                        onResult(FetchResult.Success(
                            schematics = data.schematics,
                            meta = data.meta,
                            fromCache = true,
                            staleMinutes = ageMinutes
                        ))
                    } else {
                        onResult(FetchResult.Error(
                            message = e.message ?: "Unknown error",
                            enteredOffline = enteredOffline
                        ))
                    }
                })
            }
        })
    }

    /**
     * Query options for filtering and sorting schematics.
     */
    data class QueryOptions(
        val search: String? = null,
        val visibility: Visibility = Visibility.ALL,
        val sort: SortField = SortField.CREATED_AT,
        val order: SortOrder = SortOrder.DESC,
        val page: Int = 1,
        val perPage: Int = DEFAULT_ITEMS_PER_PAGE
    )

    enum class Visibility(val apiValue: String?) {
        ALL(null),
        PUBLIC("public"),
        PRIVATE("private")
    }

    enum class SortField(val apiValue: String) {
        CREATED_AT("created_at"),
        UPDATED_AT("updated_at"),
        NAME("name"),
        DOWNLOADS("downloads")
    }

    enum class SortOrder(val apiValue: String) {
        ASC("asc"),
        DESC("desc")
    }

    /**
     * Fetches schematics directly from the API without caching or rate limiting.
     *
     * Use this for simple cases where you handle caching yourself, or for
     * UI components that don't need the full caching flow.
     *
     * @param search Optional search query
     * @param page Page number (1-indexed)
     * @param perPage Items per page
     * @return Pair of schematic list and pagination metadata
     * @throws Exception if API is unavailable or returns invalid data
     */
    suspend fun fetchSchematics(
        search: String? = null,
        page: Int = 1,
        perPage: Int = DEFAULT_ITEMS_PER_PAGE
    ): Pair<List<JsonObject>, JsonObject> {
        return fetchSchematicsWithOptions(QueryOptions(
            search = search,
            page = page,
            perPage = perPage
        ))
    }

    /**
     * Fetches schematics with full query options including visibility, sort, and order.
     *
     * @param options Query options for filtering and sorting
     * @return Pair of schematic list and pagination metadata
     * @throws Exception if API is unavailable or returns invalid data
     */
    suspend fun fetchSchematicsWithOptions(options: QueryOptions): Pair<List<JsonObject>, JsonObject> {
        val queryParams = mutableMapOf<String, String>()

        // Search
        if (!options.search.isNullOrBlank()) {
            queryParams["search"] = urlEncode(options.search)
        }

        // Visibility filter
        options.visibility.apiValue?.let { queryParams["visibility"] = it }

        // Sorting
        queryParams["sort"] = options.sort.apiValue
        queryParams["order"] = options.order.apiValue

        // Pagination
        queryParams["page"] = options.page.toString()
        queryParams["per_page"] = options.perPage.toString()

        // Only show schematics that can be converted to .schem (WorldEdit format)
        queryParams["convertible_to"] = CONVERTIBLE_TO_FORMAT

        val httpUtil = plugin.httpUtil ?: throw Exception("API not connected - no token configured")
        val queryString = queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val response = httpUtil.sendGetRequest("$SCHEMATICS_ENDPOINT?$queryString")

        if (response == null) {
            throw Exception("Connection refused - API may be unavailable")
        }

        val jsonResponse = parseJsonSafe(response)
            ?: throw Exception("Invalid JSON response from API")

        val dataArray = jsonResponse.safeGetArray("data")
            .mapNotNull { it.asJsonObjectOrNull() }

        val meta = jsonResponse.safeGetObject("meta")
            ?: JsonObject()

        return Pair(dataArray, meta)
    }

    /**
     * Fetches schematics with full caching, offline mode, rate limiting, and query options.
     *
     * This variant accepts QueryOptions for full control over filtering and sorting.
     *
     * @param playerId Player UUID for rate limiting
     * @param options Query options for filtering and sorting
     * @param cacheKeyPrefix Prefix for cache key to differentiate UI variants
     * @param onResult Callback with the fetch result, called on the main thread
     */
    fun fetchSchematicsWithCacheAndOptions(
        playerId: UUID,
        options: QueryOptions,
        cacheKeyPrefix: String = "list",
        onResult: (FetchResult) -> Unit
    ) {
        val cache = plugin.schematicCache
        val offlineMode = plugin.offlineMode

        // Build cache key including all options
        val cacheKey = buildCacheKeyFromOptions(cacheKeyPrefix, options)

        // Check cache first - cache hits bypass rate limiting for fast UX
        val cachedResult = cache.getListings(cacheKey)
        if (cachedResult != null) {
            onResult(FetchResult.Success(
                schematics = cachedResult.schematics,
                meta = cachedResult.meta,
                fromCache = true
            ))
            return
        }

        // Check if we should skip API call (offline mode with backoff)
        if (offlineMode.shouldSkipApiCall()) {
            val staleResult = cache.getListingsStale(cacheKey)
            if (staleResult != null) {
                val (data, ageMs) = staleResult
                val ageMinutes = (ageMs / 60000).toInt()
                onResult(FetchResult.Success(
                    schematics = data.schematics,
                    meta = data.meta,
                    fromCache = true,
                    staleMinutes = ageMinutes
                ))
            } else {
                onResult(FetchResult.OfflineNoCache(offlineMode.getTimeUntilRetrySeconds()))
            }
            return
        }

        // Rate limit only applies to actual API calls
        val rateLimitResult = plugin.rateLimiter.tryAcquire(playerId)
        if (rateLimitResult == null) {
            onResult(FetchResult.RateLimited(plugin.rateLimiter.getWaitTimeSeconds(playerId)))
            return
        }

        // Run async fetch
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            offlineMode.recordAttempt()

            try {
                val (schematics, meta) = runBlocking { fetchSchematicsWithOptions(options) }

                // Cache the successful result
                cache.putListings(cacheKey, schematics, meta)
                offlineMode.recordSuccess()

                // Return on main thread
                plugin.server.scheduler.runTask(plugin, Runnable {
                    onResult(FetchResult.Success(
                        schematics = schematics,
                        meta = meta,
                        fromCache = false
                    ))
                })
            } catch (e: Exception) {
                val enteredOffline = offlineMode.recordFailure()
                val staleResult = cache.getListingsStale(cacheKey)

                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (staleResult != null) {
                        val (data, ageMs) = staleResult
                        val ageMinutes = (ageMs / 60000).toInt()
                        onResult(FetchResult.Success(
                            schematics = data.schematics,
                            meta = data.meta,
                            fromCache = true,
                            staleMinutes = ageMinutes
                        ))
                    } else {
                        onResult(FetchResult.Error(
                            message = e.message ?: "Unknown error",
                            enteredOffline = enteredOffline
                        ))
                    }
                })
            }
        })
    }

    /**
     * Builds a cache key for schematic listings.
     */
    fun buildCacheKey(prefix: String, search: String?, page: Int, perPage: Int): String {
        val normalizedSearch = normalizeSearch(search)
        return "$prefix:search=${normalizedSearch ?: ""}:page=$page:perPage=$perPage"
    }

    /**
     * Builds a cache key from QueryOptions.
     */
    fun buildCacheKeyFromOptions(prefix: String, options: QueryOptions): String {
        val normalizedSearch = normalizeSearch(options.search)
        return buildString {
            append("$prefix:")
            append("search=${normalizedSearch ?: ""}")
            append(":vis=${options.visibility.name}")
            append(":sort=${options.sort.name}")
            append(":order=${options.order.name}")
            append(":page=${options.page}")
            append(":perPage=${options.perPage}")
        }
    }

    /**
     * Normalizes search input by handling placeholder text.
     */
    private fun normalizeSearch(search: String?): String? {
        return when {
            search.isNullOrBlank() -> null
            search == "Enter search term" -> null
            else -> search
        }
    }

    /**
     * URL-encodes a string for use in query parameters.
     */
    private suspend fun urlEncode(value: String): String {
        return withContext(Dispatchers.IO) {
            URLEncoder.encode(value, "UTF-8")
        }
    }

    /**
     * Categorizes an error message for user-friendly display.
     */
    fun categorizeError(message: String): ErrorCategory {
        return when {
            message.contains("API not connected") -> ErrorCategory.NOT_CONNECTED
            message.contains("Connection refused") ||
            message.contains("timed out") ||
            message.contains("No route to host") -> ErrorCategory.API_UNAVAILABLE
            else -> ErrorCategory.OTHER
        }
    }

    enum class ErrorCategory {
        NOT_CONNECTED,
        API_UNAVAILABLE,
        OTHER
    }
}
