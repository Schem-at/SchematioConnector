package io.schemat.connector.bukkit.adapter

import io.schemat.connector.core.api.PlayerStorage
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * Bukkit/Paper implementation of PlayerStorage using PersistentDataContainer.
 * Data is stored on the player entity and persists across sessions.
 */
class BukkitPlayerStorage(
    private val player: Player,
    private val plugin: Plugin
) : PlayerStorage {

    private fun key(name: String): NamespacedKey = NamespacedKey(plugin, name)

    override fun getString(key: String): String? {
        return player.persistentDataContainer.get(key(key), PersistentDataType.STRING)
    }

    override fun setString(key: String, value: String) {
        player.persistentDataContainer.set(key(key), PersistentDataType.STRING, value)
    }

    override fun getInt(key: String): Int? {
        return player.persistentDataContainer.get(key(key), PersistentDataType.INTEGER)
    }

    override fun setInt(key: String, value: Int) {
        player.persistentDataContainer.set(key(key), PersistentDataType.INTEGER, value)
    }

    override fun getBoolean(key: String): Boolean? {
        return player.persistentDataContainer.get(key(key), PersistentDataType.BYTE)?.let { it != 0.toByte() }
    }

    override fun setBoolean(key: String, value: Boolean) {
        player.persistentDataContainer.set(key(key), PersistentDataType.BYTE, if (value) 1.toByte() else 0.toByte())
    }

    override fun getLong(key: String): Long? {
        return player.persistentDataContainer.get(key(key), PersistentDataType.LONG)
    }

    override fun setLong(key: String, value: Long) {
        player.persistentDataContainer.set(key(key), PersistentDataType.LONG, value)
    }

    override fun remove(key: String) {
        player.persistentDataContainer.remove(key(key))
    }

    override fun has(key: String): Boolean {
        return player.persistentDataContainer.has(key(key))
    }
}
