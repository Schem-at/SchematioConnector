package io.schemat.connector.fabric.adapter

import io.schemat.connector.core.api.PlatformAdapter
import io.schemat.connector.core.api.TaskHandle
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Fabric implementation of PlatformAdapter.
 * Uses Minecraft server's tick system and executor for scheduling.
 */
class FabricPlatformAdapter(
    private val server: MinecraftServer,
    override val logger: Logger
) : PlatformAdapter {

    // Executor for async tasks
    private val asyncExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(4) { runnable ->
        Thread(runnable, "Schematio-Async").apply {
            isDaemon = true
        }
    }

    override fun runAsync(task: Runnable) {
        asyncExecutor.submit(task)
    }

    override fun runOnMainThread(task: Runnable) {
        if (server.isOnThread) {
            task.run()
        } else {
            server.execute(task)
        }
    }

    override fun scheduleRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle {
        // Convert ticks to milliseconds (1 tick = 50ms)
        val delayMs = delayTicks * 50
        val periodMs = periodTicks * 50

        val future = asyncExecutor.scheduleAtFixedRate({
            runOnMainThread(task)
        }, delayMs, periodMs, TimeUnit.MILLISECONDS)

        return FabricTaskHandle(future)
    }

    override fun scheduleDelayed(delayTicks: Long, task: Runnable): TaskHandle {
        // Convert ticks to milliseconds (1 tick = 50ms)
        val delayMs = delayTicks * 50

        val future = asyncExecutor.schedule({
            runOnMainThread(task)
        }, delayMs, TimeUnit.MILLISECONDS)

        return FabricTaskHandle(future)
    }

    override fun isMainThread(): Boolean = server.isOnThread

    override val platformName: String = "Fabric"

    override val platformVersion: String
        get() = server.version

    /**
     * Shutdown the async executor. Call this when the server stops.
     */
    fun shutdown() {
        asyncExecutor.shutdown()
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            asyncExecutor.shutdownNow()
        }
    }
}

/**
 * Fabric implementation of TaskHandle using ScheduledFuture.
 */
class FabricTaskHandle(private val future: ScheduledFuture<*>) : TaskHandle {
    override fun cancel() {
        future.cancel(false)
    }

    override val isCancelled: Boolean
        get() = future.isCancelled
}
