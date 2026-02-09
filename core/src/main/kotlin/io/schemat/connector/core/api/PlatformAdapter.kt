package io.schemat.connector.core.api

import java.util.logging.Logger

/**
 * Abstraction for platform-specific scheduling and lifecycle management.
 * Implemented by each platform (Bukkit, Fabric) to provide async execution,
 * task scheduling, and logging facilities.
 */
interface PlatformAdapter {
    /**
     * Platform-specific logger instance.
     */
    val logger: Logger

    /**
     * Execute a task asynchronously (off the main/server thread).
     * @param task The task to execute
     */
    fun runAsync(task: Runnable)

    /**
     * Execute a task on the main/server thread.
     * @param task The task to execute
     */
    fun runOnMainThread(task: Runnable)

    /**
     * Schedule a repeating task.
     * @param delayTicks Initial delay in server ticks (20 ticks = 1 second)
     * @param periodTicks Period between executions in server ticks
     * @param task The task to execute
     * @return Handle to cancel the task
     */
    fun scheduleRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle

    /**
     * Schedule a delayed task.
     * @param delayTicks Delay in server ticks (20 ticks = 1 second)
     * @param task The task to execute
     * @return Handle to cancel the task
     */
    fun scheduleDelayed(delayTicks: Long, task: Runnable): TaskHandle

    /**
     * Check if the current thread is the main/server thread.
     * @return true if on main thread
     */
    fun isMainThread(): Boolean

    /**
     * Get the platform name (e.g., "Bukkit", "Fabric").
     */
    val platformName: String

    /**
     * Get the platform version string.
     */
    val platformVersion: String
}

/**
 * Handle for cancelling scheduled tasks.
 */
interface TaskHandle {
    /**
     * Cancel this task.
     */
    fun cancel()

    /**
     * Check if this task has been cancelled.
     */
    val isCancelled: Boolean
}
