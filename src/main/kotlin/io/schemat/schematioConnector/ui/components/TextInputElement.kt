package io.schemat.schematioConnector.ui.components

import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.UIElement
import io.schemat.schematioConnector.utils.SignInputUtil
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Vector

/**
 * Text input component - displays editable text with a background panel.
 * Uses a fake sign via ProtocolLib for input when clicked.
 */
class TextInputElement(
    ui: FloatingUI,
    localOffset: Vector,
    val width: Float = 2.0f,
    val height: Float = 0.3f,
    var placeholder: String = "Click to edit...",
    var value: String = "",
    val maxLength: Int = 50,
    val onValueChange: (String) -> Unit = {}
) : UIElement(
    ui = ui,
    localOffset = localOffset,
    isInteractive = true,
    hitboxSize = 0.0,  // Not used - using rectangular hitbox
    hitboxWidth = width.toDouble(),
    hitboxHeight = height.toDouble()
) {

    private var backgroundPanel: org.bukkit.entity.BlockDisplay? = null
    private var textDisplay: TextDisplay? = null

    var backgroundColor: Color = Color.fromARGB(200, 60, 60, 60)
    var hoverColor: Color = Color.fromARGB(220, 80, 80, 80)
    var textColor: String = "§f"
    var placeholderColor: String = "§7"

    init {
        this.onClick = {
            openSignInput()
        }
    }

    private fun openSignInput() {
        // Split current value across lines if needed (15 chars per line on signs)
        val line1 = value.take(15)
        val line2 = if (value.length > 15) value.drop(15).take(15) else ""
        val line3 = if (value.length > 30) value.drop(30).take(15) else ""

        SignInputUtil.openSignInput(
            player = ui.player,
            plugin = ui.plugin,
            initialLines = listOf(line1, line2, line3, ""),
            callback = { lines ->
                // Combine first 3 lines into value
                val combined = "${lines[0]}${lines[1]}${lines[2]}".trim().take(maxLength)
                updateValue(combined)
            }
        )
    }

    override fun spawn(location: Location) {
        val world = location.world ?: return

        // Create background panel - use UI-aligned matrix
        backgroundPanel = world.spawn(location, org.bukkit.entity.BlockDisplay::class.java) { display ->
            display.block = Material.GRAY_CONCRETE.createBlockData()
            display.brightness = Display.Brightness(15, 15)

            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = -width / 2,
                    localOffsetY = -height / 2,
                    localOffsetZ = 0f,
                    scaleX = width,
                    scaleY = height,
                    scaleZ = 0.02f
                )
            )
        }

        // Create text display - use UI-aligned matrix
        textDisplay = world.spawn(location, TextDisplay::class.java) { display ->
            display.billboard = Display.Billboard.FIXED
            display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
            display.isSeeThrough = true
            display.brightness = Display.Brightness(15, 15)
            display.alignment = TextDisplay.TextAlignment.CENTER  // Use center alignment for predictable positioning

            updateTextDisplay(display)

            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = 0f,  // Center horizontally
                    localOffsetY = 0f,  // Center vertically
                    localOffsetZ = 0.03f,  // Positive Z = in front of background (toward player)
                    scaleX = 0.4f,
                    scaleY = 0.4f
                )
            )
        }

        updateAppearance()
    }

    private fun updateTextDisplay(display: TextDisplay) {
        val displayText = if (value.isEmpty()) {
            "$placeholderColor$placeholder"
        } else {
            "$textColor$value"
        }
        display.text(Component.text(displayText))
    }

    override fun destroy() {
        backgroundPanel?.remove()
        textDisplay?.remove()
        backgroundPanel = null
        textDisplay = null
    }

    override fun onHoverChanged() {
        updateAppearance()
    }

    private fun updateAppearance() {
        backgroundPanel?.let { display ->
            // Change material based on state
            val material = if (isHovered) {
                Material.LIGHT_GRAY_CONCRETE
            } else {
                Material.GRAY_CONCRETE
            }
            display.block = material.createBlockData()
        }

        textDisplay?.let { display ->
            updateTextDisplay(display)
        }
    }

    /**
     * Update the text value programmatically
     */
    fun updateValue(newValue: String) {
        value = newValue.take(maxLength)
        textDisplay?.let { updateTextDisplay(it) }
        onValueChange(value)
    }
}
