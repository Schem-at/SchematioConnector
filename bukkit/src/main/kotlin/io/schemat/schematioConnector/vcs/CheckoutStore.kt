package io.schemat.schematioConnector.vcs

import com.google.gson.JsonObject
import io.schemat.connector.core.api.PlayerStorage
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetString

/**
 * The versioned-schematic state a player last fetched: written whenever a versioned
 * schematic lands in their clipboard (download/paste), read as the commit-time
 * `expected_head_version_id` and - later - as stage C's 3-way merge base.
 *
 * [versionId]/[branchId] are null when the schematic's version info was not resolvable
 * at fetch time (endpoint missing, non-versioned schematic, offline).
 */
data class Checkout(
    val schematicId: String,
    val versionId: String?,
    val branchId: String?,
)

/**
 * Persists one [Checkout] per player through the platform [PlayerStorage]
 * (PersistentDataContainer on Bukkit, so it survives restarts and relogs).
 * JSON-encoded under [STORAGE_KEY]; malformed payloads read as null.
 */
class CheckoutStore(private val storage: PlayerStorage) {

    companion object {
        const val STORAGE_KEY = "vcs_checkout"
    }

    fun get(): Checkout? {
        val raw = storage.getString(STORAGE_KEY) ?: return null
        val json = parseJsonSafe(raw) ?: return null
        val schematicId = json.safeGetString("schematicId") ?: return null
        return Checkout(
            schematicId = schematicId,
            versionId = json.safeGetString("versionId"),
            branchId = json.safeGetString("branchId"),
        )
    }

    fun set(checkout: Checkout) {
        val json = JsonObject().apply {
            addProperty("schematicId", checkout.schematicId)
            checkout.versionId?.let { addProperty("versionId", it) }
            checkout.branchId?.let { addProperty("branchId", it) }
        }
        storage.setString(STORAGE_KEY, json.toString())
    }

    fun clear() {
        storage.remove(STORAGE_KEY)
    }
}
