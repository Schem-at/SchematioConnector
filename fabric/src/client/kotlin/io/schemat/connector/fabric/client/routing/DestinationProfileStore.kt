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
    private val write: (String, String?) -> Unit,
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

    /** Stores [order] for [key]; null resets the profile. */
    fun save(key: String, order: List<Destination>?) {
        val encoded = order?.let { DestinationProfiles.encode(it) }
        write(PREFIX + key, encoded)
        // Decode what was written so the cache matches a fresh read exactly.
        cache[key] = Entry(DestinationProfiles.decode(encoded))
    }

    /** Drops every cached profile; the next lookup re-reads disk. */
    fun clear() = cache.clear()

    companion object {
        const val PREFIX = "download_destinations."
    }
}
