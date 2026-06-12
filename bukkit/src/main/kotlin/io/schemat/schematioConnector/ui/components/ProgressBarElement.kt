package io.schemat.schematioConnector.ui.components

import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.UIElement
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Vector
import kotlin.math.roundToInt

/**
 * Progress bar component - visual indicator of completion percentage.
 */
class ProgressBarElement(
    ui: FloatingUI,
    localOffset: Vector,
    val width: Float = 2.0f,
    val height: Float = 0.15f,
    var progress: Float = 0f, // 0.0 to 1.0
    val showPercentage: Boolean = true,
    val label: String? = null,
    val barColor: Material = Material.LIME_CONCRETE,
    val backgroundColor: Material = Material.GRAY_CONCRETE
) : UIElement(ui, localOffset, isInteractive = false) {

    private var backgroundPanel: org.bukkit.entity.BlockDisplay? = null
    private var progressPanel: org.bukkit.entity.BlockDisplay? = null
    private var labelDisplay: TextDisplay? = null
    private var percentageDisplay: TextDisplay? = null

    override fun spawn(location: Location) {
        val world = location.world ?: return

        // Create background bar - use UI-aligned matrix
        // Note: positive Z = toward player (forward direction is negated in buildUIElementMatrix)
        backgroundPanel = world.spawn(location, org.bukkit.entity.BlockDisplay::class.java) { display ->
            display.block = backgroundColor.createBlockData()
            display.brightness = Display.Brightness(15, 15)

            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = -width / 2,
                    localOffsetY = -height / 2,
                    localOffsetZ = 0f,  // Background at base Z
                    scaleX = width,
                    scaleY = height,
                    scaleZ = 0.02f
                )
            )
        }

        // Create progress bar (foreground) - use UI-aligned matrix
        progressPanel = world.spawn(location, org.bukkit.entity.BlockDisplay::class.java) { display ->
            display.block = barColor.createBlockData()
            display.brightness = Display.Brightness(15, 15)
            updateProgressTransform(display)
        }

        // Create label if provided - use UI-aligned matrix
        if (label != null) {
            labelDisplay = world.spawn(location, TextDisplay::class.java) { display ->
                display.text(Component.text("§f$label"))
                display.billboard = Display.Billboard.FIXED
                display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
                display.isSeeThrough = true
                display.brightness = Display.Brightness(15, 15)
                display.alignment = TextDisplay.TextAlignment.CENTER

                display.setTransformationMatrix(
                    ui.buildUIElementMatrix(
                        localOffsetX = -width / 2 - 0.3f,  // Position to the left of the bar
                        localOffsetY = 0f,
                        localOffsetZ = 0.02f,  // Positive Z = toward player
                        scaleX = 0.35f,
                        scaleY = 0.35f
                    )
                )
            }
        }

        // Create percentage display if enabled - use UI-aligned matrix
        if (showPercentage) {
            percentageDisplay = world.spawn(location, TextDisplay::class.java) { display ->
                updatePercentageDisplay(display)
                display.billboard = Display.Billboard.FIXED
                display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
                display.isSeeThrough = true
                display.brightness = Display.Brightness(15, 15)
                display.alignment = TextDisplay.TextAlignment.CENTER

                display.setTransformationMatrix(
                    ui.buildUIElementMatrix(
                        localOffsetX = 0f,
                        localOffsetY = 0f,
                        localOffsetZ = 0.05f,  // Positive Z = in front of bars (toward player)
                        scaleX = 0.35f,
                        scaleY = 0.35f
                    )
                )
            }
        }
    }

    private fun updateProgressTransform(display: org.bukkit.entity.BlockDisplay) {
        val progressWidth = width * progress.coerceIn(0f, 1f)

        display.setTransformationMatrix(
            ui.buildUIElementMatrix(
                localOffsetX = -width / 2,  // Always start from left edge
                localOffsetY = -height / 2,
                localOffsetZ = 0.03f,  // Positive Z = in front of background (toward player)
                scaleX = progressWidth.coerceAtLeast(0.001f), // Avoid zero scale
                scaleY = height,
                scaleZ = 0.01f  // Thinner depth
            )
        )
    }

    private fun updatePercentageDisplay(display: TextDisplay) {
        val percentage = (progress * 100).roundToInt()
        val color = when {
            progress < 0.33f -> "§c"
            progress < 0.66f -> "§e"
            else -> "§a"
        }
        display.text(Component.text("$color$percentage%"))
    }

    override fun destroy() {
        backgroundPanel?.remove()
        progressPanel?.remove()
        labelDisplay?.remove()
        percentageDisplay?.remove()
        backgroundPanel = null
        progressPanel = null
        labelDisplay = null
        percentageDisplay = null
    }

    override fun onHoverChanged() {
        // Progress bars don't react to hover
    }

    /**
     * Update the progress value (0.0 to 1.0)
     */
    fun updateProgress(newProgress: Float) {
        progress = newProgress.coerceIn(0f, 1f)
        progressPanel?.let { updateProgressTransform(it) }
        percentageDisplay?.let { updatePercentageDisplay(it) }
    }

    /**
     * Increment the progress by a delta value
     */
    fun incrementProgress(delta: Float) {
        updateProgress(progress + delta)
    }
}
