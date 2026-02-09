package io.schemat.connector.core.api

/**
 * Platform-agnostic player persistent data storage.
 * On Bukkit: Uses PersistentDataContainer
 * On Fabric: Uses player NBT or file-based storage
 *
 * Data persists across server restarts and player sessions.
 */
interface PlayerStorage {
    /**
     * Get a string value from storage.
     * @param key The storage key
     * @return The stored value, or null if not present
     */
    fun getString(key: String): String?

    /**
     * Set a string value in storage.
     * @param key The storage key
     * @param value The value to store
     */
    fun setString(key: String, value: String)

    /**
     * Get an integer value from storage.
     * @param key The storage key
     * @return The stored value, or null if not present
     */
    fun getInt(key: String): Int?

    /**
     * Set an integer value in storage.
     * @param key The storage key
     * @param value The value to store
     */
    fun setInt(key: String, value: Int)

    /**
     * Get a boolean value from storage.
     * @param key The storage key
     * @return The stored value, or null if not present
     */
    fun getBoolean(key: String): Boolean?

    /**
     * Set a boolean value in storage.
     * @param key The storage key
     * @param value The value to store
     */
    fun setBoolean(key: String, value: Boolean)

    /**
     * Get a long value from storage.
     * @param key The storage key
     * @return The stored value, or null if not present
     */
    fun getLong(key: String): Long?

    /**
     * Set a long value in storage.
     * @param key The storage key
     * @param value The value to store
     */
    fun setLong(key: String, value: Long)

    /**
     * Remove a value from storage.
     * @param key The storage key to remove
     */
    fun remove(key: String)

    /**
     * Check if a key exists in storage.
     * @param key The storage key to check
     * @return true if the key exists
     */
    fun has(key: String): Boolean
}
