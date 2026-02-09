@file:Suppress("DEPRECATION") // AsyncPlayerChatEvent is deprecated but Paper's replacement isn't stable yet

package io.schemat.schematioConnector.utils

import io.schemat.schematioConnector.SchematioConnector
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility for capturing player text input.
 * Uses chat input with a simple prompt system.
 * (ProtocolLib sign-based input could be added as an optional enhancement)
 */
object SignInputUtil {

    data class InputSession(
        val player: Player,
        val callback: (List<String>) -> Unit,
        val prompt: String = "Enter text:"
    )

    private val activeSessions = ConcurrentHashMap<UUID, InputSession>()
    private var listenerRegistered = false

    /**
     * Open a text input prompt for the player using chat.
     * @param player The player to prompt
     * @param plugin The plugin instance
     * @param initialLines Initial text (displayed as hint)
     * @param callback Called with the input when player types in chat
     */
    fun openSignInput(
        player: Player,
        plugin: SchematioConnector,
        initialLines: List<String> = listOf("", "", "", ""),
        callback: (List<String>) -> Unit
    ) {
        ensureListenerRegistered(plugin)

        // Store session
        val currentValue = initialLines.filter { it.isNotBlank() }.joinToString("")
        val session = InputSession(player, callback, "Enter text:")
        activeSessions[player.uniqueId] = session

        // Show prompt to player
        player.sendMessage(Component.empty())
        player.sendMessage(Component.text("═══════════════════════════════════").color(NamedTextColor.GOLD))
        player.sendMessage(Component.text("  Type your text in chat").color(NamedTextColor.YELLOW))
        if (currentValue.isNotBlank()) {
            player.sendMessage(Component.text("  Current: ").color(NamedTextColor.GRAY)
                .append(Component.text(currentValue).color(NamedTextColor.WHITE)))
        }
        player.sendMessage(Component.text("  Type 'cancel' to cancel").color(NamedTextColor.GRAY))
        player.sendMessage(Component.text("═══════════════════════════════════").color(NamedTextColor.GOLD))
    }

    private fun ensureListenerRegistered(plugin: SchematioConnector) {
        if (listenerRegistered) return
        listenerRegistered = true

        plugin.server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun onChat(event: AsyncPlayerChatEvent) {
                val session = activeSessions.remove(event.player.uniqueId) ?: return

                // Cancel the chat message so it doesn't show
                event.isCancelled = true

                val input = event.message.trim()

                // Handle cancel
                if (input.equals("cancel", ignoreCase = true)) {
                    event.player.sendMessage(Component.text("Input cancelled.").color(NamedTextColor.GRAY))
                    return
                }

                // Split input into "lines" (for compatibility with sign-style callback)
                val lines = listOf(
                    input.take(15),
                    if (input.length > 15) input.drop(15).take(15) else "",
                    if (input.length > 30) input.drop(30).take(15) else "",
                    ""
                )

                // Call callback on main thread
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    session.callback(lines)
                    event.player.sendMessage(Component.text("Text set: ").color(NamedTextColor.GREEN)
                        .append(Component.text(input).color(NamedTextColor.WHITE)))
                })
            }
        }, plugin)

        plugin.logger.info("[SignInputUtil] Chat input listener registered")
    }

    /**
     * Cancel any active input session for a player
     */
    fun cancelSession(player: Player) {
        activeSessions.remove(player.uniqueId)
    }

    /**
     * Check if a player has an active input session
     */
    fun hasActiveSession(player: Player): Boolean {
        return activeSessions.containsKey(player.uniqueId)
    }
}
