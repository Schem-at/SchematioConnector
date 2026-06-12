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
 * Slider component - draggable value selector.
 *
 * Renders as a horizontal track with a draggable handle. Click the handle
 * to start dragging, move to adjust value, click again to confirm.
 *
 * @property ui The FloatingUI this element belongs to
 * @property localOffset Position in UI local coordinates
 * @property width Width of the slider track
 * @property minValue Minimum selectable value
 * @property maxValue Maximum selectable value
 * @property value Current value
 * @property step Value increment (1 for integers)
 * @property showValue Whether to display current value text
 * @property label Optional text label displayed to the left
 * @property onValueChange Callback invoked when value changes
 */
class SliderElement(
    ui: FloatingUI,
    localOffset: Vector,
    val width: Float = 2.0f,
    var minValue: Float = 0f,
    var maxValue: Float = 100f,
    var value: Float = 50f,
    val step: Float = 1f,
    val showValue: Boolean = true,
    val label: String? = null,
    val onValueChange: (Float) -> Unit = {}
) : UIElement(
    ui = ui,
    localOffset = localOffset,
    isInteractive = true,
    hitboxSize = 0.0,  // Not used - using rectangular hitbox
    hitboxWidth = width.toDouble(),
    hitboxHeight = 0.3  // Generous height for easy clicking
) {

    private var trackPanel: org.bukkit.entity.BlockDisplay? = null
    private var handlePanel: org.bukkit.entity.BlockDisplay? = null
    private var labelDisplay: TextDisplay? = null
    private var valueDisplay: TextDisplay? = null
    private var isDragging = false

    private val trackHeight = 0.1f
    private val handleSize = 0.15f

    init {
        this.onClick = {
            isDragging = !isDragging
            updateAppearance()

            if (isDragging) {
                ui.plugin.server.broadcast(
                    Component.text("§e[Slider] Move mouse to adjust value (click again to release)")
                )
            }
        }
    }

    override fun spawn(location: Location) {
        val world = location.world ?: return

        // Create track (background bar) - use UI-aligned matrix
        trackPanel = world.spawn(location, org.bukkit.entity.BlockDisplay::class.java) { display ->
            display.block = Material.GRAY_CONCRETE.createBlockData()
            display.brightness = Display.Brightness(15, 15)

            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = -width / 2,
                    localOffsetY = -trackHeight / 2,
                    localOffsetZ = 0f,
                    scaleX = width,
                    scaleY = trackHeight,
                    scaleZ = 0.02f
                )
            )
        }

        // Create handle (draggable button) - spawns at correct position, uses UI-aligned matrix
        val handleX = getHandleXPosition()
        handlePanel = world.spawn(
            location.clone().add(ui.right.clone().multiply(handleX)),
            org.bukkit.entity.BlockDisplay::class.java
        ) { display ->
            display.block = Material.LIGHT_BLUE_CONCRETE.createBlockData()
            display.brightness = Display.Brightness(15, 15)

            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = -handleSize / 2,
                    localOffsetY = -handleSize / 2,
                    localOffsetZ = -0.01f,
                    scaleX = handleSize,
                    scaleY = handleSize,
                    scaleZ = handleSize
                )
            )
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
                        localOffsetX = -width / 2 - 0.3f,  // Position to the left of the slider
                        localOffsetY = 0f,
                        localOffsetZ = 0.02f,  // Positive Z = toward player
                        scaleX = 0.35f,
                        scaleY = 0.35f
                    )
                )
            }
        }

        // Create value display if enabled - use UI-aligned matrix
        if (showValue) {
            valueDisplay = world.spawn(location, TextDisplay::class.java) { display ->
                updateValueDisplay(display)
                display.billboard = Display.Billboard.FIXED
                display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
                display.isSeeThrough = true
                display.brightness = Display.Brightness(15, 15)
                display.alignment = TextDisplay.TextAlignment.CENTER

                display.setTransformationMatrix(
                    ui.buildUIElementMatrix(
                        localOffsetX = width / 2 + 0.2f,  // Position to the right of the slider
                        localOffsetY = 0f,
                        localOffsetZ = 0.02f,  // Positive Z = toward player
                        scaleX = 0.35f,
                        scaleY = 0.35f
                    )
                )
            }
        }

        updateAppearance()
    }

    private fun getHandleXPosition(): Double {
        val normalizedValue = (value - minValue) / (maxValue - minValue)
        return (normalizedValue * width - width / 2).toDouble()
    }

    private fun updateValueDisplay(display: TextDisplay) {
        val formattedValue = if (step >= 1f) {
            value.roundToInt().toString()
        } else {
            String.format("%.2f", value)
        }
        display.text(Component.text("§e$formattedValue"))
    }

    override fun destroy() {
        trackPanel?.remove()
        handlePanel?.remove()
        labelDisplay?.remove()
        valueDisplay?.remove()
        trackPanel = null
        handlePanel = null
        labelDisplay = null
        valueDisplay = null
    }

    override fun onHoverChanged() {
        updateAppearance()
    }

    private fun updateAppearance() {
        handlePanel?.let { display ->
            val material = when {
                isDragging -> Material.ORANGE_CONCRETE
                isHovered -> Material.CYAN_CONCRETE
                else -> Material.LIGHT_BLUE_CONCRETE
            }
            display.block = material.createBlockData()
        }
    }

    /**
     * Update the slider based on mouse position
     */
    fun update() {
        if (!isDragging) return

        // Get mouse position on UI plane
        val mousePos = ui.getMousePositionOnPlane() ?: return
        val (mouseX, _) = mousePos

        // Convert mouse X to value
        val localX = mouseX - localOffset.x
        val normalizedX = ((localX + width / 2) / width).coerceIn(0.0, 1.0)
        val newValue = (minValue + normalizedX * (maxValue - minValue)).toFloat()

        // Apply step
        val steppedValue = if (step > 0) {
            (newValue / step).roundToInt() * step
        } else {
            newValue
        }.coerceIn(minValue, maxValue)

        if (steppedValue != value) {
            value = steppedValue
            updateHandlePosition()
            valueDisplay?.let { updateValueDisplay(it) }
            onValueChange(value)
        }
    }

    private fun updateHandlePosition() {
        val handleX = getHandleXPosition()
        val worldPos = ui.calculatePosition(
            localOffset.x + handleX,
            localOffset.y,
            localOffset.z
        )
        handlePanel?.teleport(worldPos)
    }

    /**
     * Update the value programmatically
     */
    fun updateSliderValue(newValue: Float) {
        value = newValue.coerceIn(minValue, maxValue)
        updateHandlePosition()
        valueDisplay?.let { updateValueDisplay(it) }
        onValueChange(value)
    }

    fun isDragging() = isDragging
}
