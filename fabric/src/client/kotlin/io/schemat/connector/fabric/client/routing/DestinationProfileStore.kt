package io.schemat.connector.fabric.client.routing

import io.schemat.connector.core.routing.Destination
import io.schemat.connector.core.routing.DestinationProfiles

/**
 * In-memory cache of decoded destination profiles over config.properties. The detail and
 * settings panels ask for the order every frame, so each key is read from disk once (lazily)
 * and [save] writes through and updates the cache. Absent or invalid profiles are cached too.
 * Not thread-safe: client thread only, like its callers.
 */
class DestinationProfileStore(
    private val read: (String) -> String?,
    /** Persists a value (null removes it); returns false on failure. */
    private val write: (String, String?) -> Boolean,
) {
    /** A decoded profile; [order] is null when the key is absent or invalid on disk. */
    private class Entry(val order: List<Destination>?)

    private val cache = HashMap<String, Entry>()

    private fun profile(key: String): List<Destination>? =
        cache.getOrPut(key) { Entry(DestinationProfiles.decode(read(PREFIX + key))) }.order

    /** The stored profile, else the stored default, else [Destination.DEFAULT_ORDER]. */
    fun order(key: String): List<Destination> =
        profile(key) ?: profile(DestinationProfiles.DEFAULT_KEY) ?: Destination.DEFAULT_ORDER

    fun hasOwnProfile(key: String): Boolean = profile(key) != null

    /**
     * Stores [order] for [key]; null resets the profile. Returns false when the write failed
     * (the writer logs why). The cache then forgets everything: a failed write may have left
     * config.properties in any state, so the next lookups re-read what is really on disk.
     */
    fun save(key: String, order: List<Destination>?): Boolean {
        val encoded = order?.let { DestinationProfiles.encode(it) }
        if (!write(PREFIX + key, encoded)) {
            cache.clear()
            return false
        }
        // Decode what was written so the cache matches a fresh read exactly.
        cache[key] = Entry(DestinationProfiles.decode(encoded))
        return true
    }

    /** Drops every cached profile; the next lookup re-reads disk. */
    fun clear() = cache.clear()

    companion object {
        const val PREFIX = "download_destinations."
    }
}
