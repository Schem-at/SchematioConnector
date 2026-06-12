package io.schemat.connector.bukkit.adapter

import io.schemat.connector.core.api.PlatformAdapter
import io.schemat.connector.core.api.TaskHandle
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Logger

/**
 * Bukkit/Paper implementation of PlatformAdapter.
 * Wraps Bukkit's scheduler and provides platform-specific functionality.
 */
class BukkitPlatformAdapter(private val plugin: JavaPlugin) : PlatformAdapter {

    override val logger: Logger = plugin.logger

    override fun runAsync(task: Runnable) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, task)
    }

    override fun runOnMainThread(task: Runnable) {
        if (Bukkit.isPrimaryThread()) {
            task.run()
        } else {
            plugin.server.scheduler.runTask(plugin, task)
        }
    }

    override fun scheduleRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle {
        val bukkitTask = plugin.server.scheduler.runTaskTimer(plugin, task, delayTicks, periodTicks)
        return BukkitTaskHandle(bukkitTask)
    }

    override fun scheduleDelayed(delayTicks: Long, task: Runnable): TaskHandle {
        val bukkitTask = plugin.server.scheduler.runTaskLater(plugin, task, delayTicks)
        return BukkitTaskHandle(bukkitTask)
    }

    override fun isMainThread(): Boolean = Bukkit.isPrimaryThread()

    override val platformName: String = "Bukkit"

    override val platformVersion: String
        get() = Bukkit.getVersion()
}

/**
 * Bukkit implementation of TaskHandle.
 */
class BukkitTaskHandle(private val task: BukkitTask) : TaskHandle {
    override fun cancel() {
        task.cancel()
    }

    override val isCancelled: Boolean
        get() = task.isCancelled
}
