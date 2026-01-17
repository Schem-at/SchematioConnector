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

/**
 * Checkbox/Toggle component - clickable boolean state indicator.
 *
 * Renders as a square box that displays a checkmark when checked.
 * Clicking toggles the checked state and invokes the callback.
 *
 * @property ui The FloatingUI this element belongs to
 * @property localOffset Position in UI local coordinates
 * @property checked Initial checked state
 * @property label Optional text label displayed to the right
 * @property onToggle Callback invoked when checked state changes
 */
class CheckboxElement(
    ui: FloatingUI,
    localOffset: Vector,
    var checked: Boolean = false,
    val label: String? = null,
    val onToggle: (Boolean) -> Unit = {}
) : UIElement(ui, localOffset, isInteractive = true, hitboxSize = 0.3) {

    private var boxDisplay: org.bukkit.entity.BlockDisplay? = null
    private var checkmarkDisplay: org.bukkit.entity.BlockDisplay? = null
    private var labelDisplay: TextDisplay? = null

    private val boxSize = 0.2f

    init {
        this.onClick = {
            checked = !checked
            updateAppearance()
            onToggle(checked)
        }
    }

    override fun spawn(location: Location) {
        val world = location.world ?: return

        // Create checkbox box - use UI-aligned matrix
        boxDisplay = world.spawn(location, org.bukkit.entity.BlockDisplay::class.java) { display ->
            display.block = Material.LIGHT_GRAY_CONCRETE.createBlockData()
            display.brightness = Display.Brightness(15, 15)

            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = -boxSize / 2,
                    localOffsetY = -boxSize / 2,
                    localOffsetZ = 0f,
                    scaleX = boxSize,
                    scaleY = boxSize,
                    scaleZ = 0.02f
                )
            )
        }

        // Create checkmark (initially hidden) - use UI-aligned matrix
        checkmarkDisplay = world.spawn(location, org.bukkit.entity.BlockDisplay::class.java) { display ->
            display.block = Material.LIME_CONCRETE.createBlockData()
            display.brightness = Display.Brightness(15, 15)

            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = -boxSize / 2 * 0.6f,
                    localOffsetY = -boxSize / 2 * 0.6f,
                    localOffsetZ = 0.02f,  // Positive Z = in front of box (toward player)
                    scaleX = boxSize * 0.6f,
                    scaleY = boxSize * 0.6f,
                    scaleZ = 0.04f
                )
            )

            display.viewRange = if (checked) 1.0f else 0.0f
        }

        // Create label if provided
        if (label != null) {
            labelDisplay = world.spawn(location, TextDisplay::class.java) { display ->
                display.text(Component.text("§f$label"))
                display.billboard = Display.Billboard.FIXED
                display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
                display.isSeeThrough = true
                display.brightness = Display.Brightness(15, 15)
                display.alignment = TextDisplay.TextAlignment.CENTER

                // Use UI-aligned transformation matrix - position to the right of checkbox
                display.setTransformationMatrix(
                    ui.buildUIElementMatrix(
                        localOffsetX = boxSize / 2 + 0.4f,  // Further right to avoid overlap
                        localOffsetY = 0f,
                        localOffsetZ = 0.02f,  // Positive Z = toward player
                        scaleX = 0.4f,
                        scaleY = 0.4f
                    )
                )
            }
        }

        updateAppearance()
    }

    override fun destroy() {
        boxDisplay?.remove()
        checkmarkDisplay?.remove()
        labelDisplay?.remove()
        boxDisplay = null
        checkmarkDisplay = null
        labelDisplay = null
    }

    override fun onHoverChanged() {
        updateAppearance()
    }

    private fun updateAppearance() {
        boxDisplay?.let { display ->
            val material = if (isHovered) {
                Material.WHITE_CONCRETE
            } else {
                Material.LIGHT_GRAY_CONCRETE
            }
            display.block = material.createBlockData()
        }

        checkmarkDisplay?.let { display ->
            display.viewRange = if (checked) 1.0f else 0.0f
        }
    }

    /**
     * Update the checked state programmatically
     */
    fun updateChecked(newChecked: Boolean) {
        checked = newChecked
        updateAppearance()
        onToggle(checked)
    }
}
