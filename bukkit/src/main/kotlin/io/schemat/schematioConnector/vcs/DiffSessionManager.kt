package io.schemat.schematioConnector.vcs

import io.schemat.connector.core.vcs.DiffRenderer
import io.schemat.connector.core.vcs.DiffSession
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the one-diff-session-per-player invariant (spec §3) and its lifecycle:
 * opening a new session disposes the old; disposal on done/cancel, quit, world change
 * and 10-minute idle (scheduler sweep).
 */
class DiffSessionManager(private val plugin: Plugin) : Listener {

    companion object {
        const val IDLE_TIMEOUT_MS: Long = 10 * 60 * 1000
        private const val IDLE_SWEEP_TICKS: Long = 20 * 30 // every 30s
    }

    /**
     * A player's live session: its state, its rendering backend, and an optional
     * flow attachment (the RESOLVE flow hangs its commit context here).
     */
    class Active(
        val session: DiffSession,
        val renderer: DiffRenderer,
        val attachment: Any? = null,
    )

    private val sessions = ConcurrentHashMap<UUID, Active>()
    private var idleTaskId = -1

    /** Call once from onEnable: registers listeners and starts the idle sweep. */
    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        idleTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, {
            sessions.forEach { (uuid, active) ->
                if (active.session.idleMs() >= IDLE_TIMEOUT_MS) {
                    plugin.server.getPlayer(uuid)?.sendMessage(
                        Component.text("Diff session closed after 10 minutes of inactivity.").color(NamedTextColor.GRAY),
                    )
                    closeByUuid(uuid)
                }
            }
        }, IDLE_SWEEP_TICKS, IDLE_SWEEP_TICKS)
    }

    /** Opens (rendering immediately) after disposing any previous session. */
    fun open(player: Player, session: DiffSession, renderer: DiffRenderer, attachment: Any? = null): Active {
        close(player)
        val active = Active(session, renderer, attachment)
        sessions[player.uniqueId] = active
        renderer.show(session)
        return active
    }

    fun current(player: Player): Active? = sessions[player.uniqueId]

    /** Clears the overlay and disposes the session. No-op without one. */
    fun close(player: Player) = closeByUuid(player.uniqueId)

    private fun closeByUuid(uuid: UUID) {
        sessions.remove(uuid)?.let { active ->
            try {
                active.renderer.clear()
            } finally {
                active.session.dispose()
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = close(event.player)

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (sessions.containsKey(event.player.uniqueId)) {
            event.player.sendMessage(Component.text("Diff session closed (world change).").color(NamedTextColor.GRAY))
            close(event.player)
        }
    }

    /** Call from onDisable: closes everything and cancels the sweep. */
    fun shutdown() {
        sessions.keys.toList().forEach { closeByUuid(it) }
        if (idleTaskId != -1) {
            plugin.server.scheduler.cancelTask(idleTaskId)
            idleTaskId = -1
        }
    }
}
