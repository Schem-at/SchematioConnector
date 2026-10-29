package io.schemat.connector.fabric.worldedit

import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.util.Timer

/**
 * Works around a WorldEdit shutdown hang on dedicated Fabric servers.
 *
 * WorldEdit's `FutureProgressListener` (7.4.6, still on master) holds a
 * `private static final Timer timer = new Timer()`: a NON-daemon thread, started by the
 * first async command (`//schem load`, `//schem save`, `//schem list`, ...) and never
 * cancelled. Vanilla's dedicated server does not call System.exit after stopping, so the
 * JVM waits on that thread forever: the log ends at "Thread RCON Listener stopped" and the
 * panel has to kill the process.
 *
 * Only called on a dedicated server, whose JVM is about to exit. On an integrated server
 * the client keeps running and WorldEdit would fail every later async command on the
 * cancelled timer.
 */
object WorldEditShutdown {

    private const val PROGRESS_LISTENER_CLASS = "com.sk89q.worldedit.command.util.FutureProgressListener"
    private val LOGGER = LoggerFactory.getLogger("schematioconnector-worldedit")

    fun releaseProgressTimer() {
        if (!FabricWorldEditUtil.isAvailable()) return
        try {
            if (cancelStaticTimer(Class.forName(PROGRESS_LISTENER_CLASS))) {
                LOGGER.info("Cancelled WorldEdit's progress timer so the server can exit")
            }
        } catch (t: Throwable) {
            LOGGER.debug("Could not cancel WorldEdit's progress timer", t)
        }
    }

    /** Cancels the first static [Timer] declared on [owner]; false when it declares none. */
    internal fun cancelStaticTimer(owner: Class<*>): Boolean {
        val field = owner.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && Timer::class.java.isAssignableFrom(it.type)
        } ?: return false
        field.isAccessible = true
        val timer = field.get(null) as? Timer ?: return false
        timer.cancel()
        return true
    }
}
