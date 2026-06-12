package io.schemat.connector.core.api

import java.nio.file.Path

/**
 * Platform-agnostic configuration management.
 * Abstracts loading/saving configuration from YAML or other formats.
 */
interface ConfigAdapter {
    /**
     * Get the configuration directory path.
     * This is where config files and data are stored.
     */
    val configDirectory: Path

    /**
     * Reload configuration from disk.
     * Should be called after external changes to config files.
     */
    fun reload()

    /**
     * Save default configuration if it doesn't exist.
     * Typically copies a bundled config.yml to the config directory.
     */
    fun saveDefaultConfig()

    /**
     * Get a string value from configuration.
     * @param key The config key (supports dot notation for nested values)
     * @param default Default value if key doesn't exist
     * @return The config value or default
     */
    fun getString(key: String, default: String? = null): String?

    /**
     * Get an integer value from configuration.
     * @param key The config key
     * @param default Default value if key doesn't exist
     * @return The config value or default
     */
    fun getInt(key: String, default: Int = 0): Int

    /**
     * Get a boolean value from configuration.
     * @param key The config key
     * @param default Default value if key doesn't exist
     * @return The config value or default
     */
    fun getBoolean(key: String, default: Boolean = false): Boolean

    /**
     * Get a long value from configuration.
     * @param key The config key
     * @param default Default value if key doesn't exist
     * @return The config value or default
     */
    fun getLong(key: String, default: Long = 0L): Long

    /**
     * Get a double value from configuration.
     * @param key The config key
     * @param default Default value if key doesn't exist
     * @return The config value or default
     */
    fun getDouble(key: String, default: Double = 0.0): Double

    /**
     * Get a list of strings from configuration.
     * @param key The config key
     * @return The list of strings, or empty list if not present
     */
    fun getStringList(key: String): List<String>

    /**
     * Set a configuration value.
     * Changes are not persisted until save() is called.
     * @param key The config key
     * @param value The value to set
     */
    fun set(key: String, value: Any?)

    /**
     * Save current configuration to disk.
     */
    fun save()

    /**
     * Check if a configuration key exists.
     * @param key The config key to check
     * @return true if the key exists
     */
    fun contains(key: String): Boolean
}
