package io.schemat.schematioConnector.utils

import com.google.gson.JsonObject
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ui.FloatingUI
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.*

/**
 * Manages schematic preview UIs using the FloatingUI library and MapEngine.
 */
class SchematicPreviewUI(private val plugin: SchematioConnector) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize() {
        plugin.logger.info("SchematicPreviewUI initialized")
    }

    /**
     * Show a preview of a schematic with interactive buttons
     */
    fun showPreview(player: Player, schematic: JsonObject) {
        // Close any existing preview
        FloatingUI.closeForPlayer(player)
        player.closeInventory()

        val name = schematic.get("name")?.asString ?: "Unknown"
        val shortId = schematic.get("short_id")?.asString ?: ""
        val previewUrl = schematic.get("preview_image_url")?.asString
        val downloads = schematic.get("downloads")?.asInt ?: 0
        val author = schematic.get("author")?.asString ?: "Unknown"

        // Calculate UI position - 4 blocks in front of player
        val eyeLoc = player.eyeLocation.clone()
        val direction = eyeLoc.direction.normalize()
        val center = eyeLoc.add(direction.multiply(4.0))

        // Create the floating UI
        val ui = FloatingUI.create(plugin, player, center)

        // Add background panel
        ui.addPanel(
            offsetRight = 0.0,
            offsetUp = 0.0,
            offsetForward = 0.15,
            width = 3.0f,
            height = 2.5f,
            material = Material.BLACK_CONCRETE
        )

        // Add title
        ui.addLabel(
            offsetRight = 0.0,
            offsetUp = 0.9,
            offsetForward = 0.0,
            text = "§6§l$name",
            scale = 1.2f,
            backgroundColor = Color.fromARGB(0, 0, 0, 0)
        )

        // Add author info
        ui.addLabel(
            offsetRight = 0.0,
            offsetUp = 0.6,
            offsetForward = 0.0,
            text = "§7by §f$author",
            scale = 0.7f,
            backgroundColor = Color.fromARGB(0, 0, 0, 0)
        )

        // Add download count
        ui.addLabel(
            offsetRight = 0.0,
            offsetUp = 0.35,
            offsetForward = 0.0,
            text = "§7Downloads: §e$downloads",
            scale = 0.6f,
            backgroundColor = Color.fromARGB(0, 0, 0, 0)
        )

        // Add buttons
        ui.addButton(
            offsetRight = -0.6,
            offsetUp = -0.7,
            offsetForward = 0.0,
            label = "§a§lDOWNLOAD",
            material = Material.LIME_CONCRETE,
            hoverMaterial = Material.LIME_GLAZED_TERRACOTTA,
            size = 0.35f
        ) {
            ui.destroy()
            player.performCommand("schematio download $shortId")
        }

        ui.addButton(
            offsetRight = 0.6,
            offsetUp = -0.7,
            offsetForward = 0.0,
            label = "§c§lCLOSE",
            material = Material.RED_CONCRETE,
            hoverMaterial = Material.RED_GLAZED_TERRACOTTA,
            size = 0.35f
        ) {
            ui.destroy()
            player.sendMessage(Component.text("Preview closed.").color(NamedTextColor.GRAY))
        }

        // Show map preview if MapEngine is available and we have a preview URL
        if (previewUrl != null && plugin.mapEngineHandler != null) {
            showMapPreview(player, previewUrl, center)
        }

        // Send instructions
        player.sendMessage(Component.empty())
        player.sendMessage(
            Component.text("  ✨ Preview: ").color(NamedTextColor.GREEN)
                .append(Component.text(name).color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        )
        player.sendMessage(
            Component.text("  §7▸ §eRight-click §7buttons to interact")
        )
        player.sendMessage(
            Component.text("  §7▸ §cWalk away §7to dismiss")
        )
        player.sendMessage(Component.empty())
    }

    private fun showMapPreview(player: Player, previewUrl: String, center: Location) {
        scope.launch {
            try {
                val image = plugin.mapEngineHandler?.fetchImage(previewUrl)
                if (image == null) {
                    plugin.logger.warning("Failed to fetch preview image for ${player.name}")
                    return@launch
                }

                // Schedule map display creation on main thread
                plugin.server.scheduler.runTask(plugin, Runnable {
                    try {
                        val mapCenter = center.clone().add(0.0, 0.1, 0.0)
                        plugin.mapEngineHandler?.showPreview(
                            player = player,
                            image = image,
                            center = mapCenter,
                            widthMaps = 2,
                            heightMaps = 1
                        )
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to display map preview: ${e.message}")
                    }
                })
            } catch (e: Exception) {
                plugin.logger.warning("Error loading preview image: ${e.message}")
            }
        }
    }

    /**
     * Close preview for a player
     */
    fun closePreview(player: Player) {
        FloatingUI.closeForPlayer(player)
        plugin.mapEngineHandler?.closePreview(player)
    }

    /**
     * Shutdown and cleanup
     */
    fun shutdown() {
        FloatingUI.closeAll()
        scope.cancel()
    }
}
