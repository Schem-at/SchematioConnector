package io.schemat.connector.bukkit.adapter

import io.schemat.connector.core.api.ConfigAdapter
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path

/**
 * Bukkit/Paper implementation of ConfigAdapter.
 * Wraps Bukkit's YAML configuration system.
 */
class BukkitConfigAdapter(private val plugin: JavaPlugin) : ConfigAdapter {

    override val configDirectory: Path = plugin.dataFolder.toPath()

    override fun reload() {
        plugin.reloadConfig()
    }

    override fun saveDefaultConfig() {
        plugin.saveDefaultConfig()
    }

    override fun getString(key: String, default: String?): String? {
        return plugin.config.getString(key, default)
    }

    override fun getInt(key: String, default: Int): Int {
        return plugin.config.getInt(key, default)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean {
        return plugin.config.getBoolean(key, default)
    }

    override fun getLong(key: String, default: Long): Long {
        return plugin.config.getLong(key, default)
    }

    override fun getDouble(key: String, default: Double): Double {
        return plugin.config.getDouble(key, default)
    }

    override fun getStringList(key: String): List<String> {
        return plugin.config.getStringList(key)
    }

    override fun set(key: String, value: Any?) {
        plugin.config.set(key, value)
    }

    override fun save() {
        plugin.saveConfig()
    }

    override fun contains(key: String): Boolean {
        return plugin.config.contains(key)
    }
}
