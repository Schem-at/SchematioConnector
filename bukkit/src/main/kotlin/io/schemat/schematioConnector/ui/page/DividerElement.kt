package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.PanelElement
import io.schemat.schematioConnector.ui.UIElement
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.Vector

/**
 * Draggable divider for split containers.
 *
 * The divider appears between two split panes and can be dragged
 * to adjust the split ratio.
 *
 * @param ui The FloatingUI to render to
 * @param container The parent SplitContainer
 * @param direction Direction of the split
 * @param bounds Bounds of the divider bar
 */
class DividerElement(
    ui: FloatingUI,
    private val container: SplitContainer,
    private val direction: SplitDirection,
    private val bounds: PageBounds
) : UIElement(
    ui = ui,
    localOffset = Vector(bounds.centerX.toDouble(), bounds.centerY.toDouble(), 0.0),
    isInteractive = true,
    hitboxSize = 0.0,
    hitboxWidth = bounds.width.toDouble(),
    hitboxHeight = bounds.height.toDouble()
) {
    companion object {
        const val Z_OFFSET = -0.05  // Slightly in front of content
    }

    /** Whether currently dragging */
    var isDragging = false
        private set

    /** The visual panel */
    private var panel: PanelElement? = null

    /** Materials for different states */
    private val normalMaterial = Material.GRAY_CONCRETE
    private val hoverMaterial = Material.LIGHT_GRAY_CONCRETE
    private val draggingMaterial = Material.YELLOW_CONCRETE

    init {
        this.onClick = {
            isDragging = !isDragging
            updateAppearance()
        }
    }

    /**
     * Spawn the divider visual.
     */
    fun spawn() {
        panel = ui.addPanel(
            offsetRight = bounds.centerX.toDouble(),
            offsetUp = bounds.centerY.toDouble(),
            offsetForward = Z_OFFSET,
            width = bounds.width,
            height = bounds.height,
            material = normalMaterial
        )
    }

    override fun spawn(location: Location) {
        spawn()
    }

    override fun destroy() {
        panel?.let {
            it.destroy()
            ui.removeElement(it)
        }
        panel = null
    }

    override fun onHoverChanged() {
        updateAppearance()
    }

    /**
     * Update the divider position based on mouse position.
     * Called each tick while dragging.
     */
    fun update() {
        if (!isDragging) return

        val mousePos = ui.getMousePositionOnPlane() ?: return
        val (mouseX, mouseY) = mousePos

        // Calculate new ratio based on mouse position relative to container bounds
        val containerBounds = container.bounds
        val newRatio = when (direction) {
            SplitDirection.HORIZONTAL -> {
                // Mouse X position determines ratio
                val relativeX = mouseX - containerBounds.x
                (relativeX / containerBounds.width).toFloat()
            }
            SplitDirection.VERTICAL -> {
                // Mouse Y position determines ratio (invert because Y increases upward)
                val relativeY = containerBounds.y - mouseY
                (relativeY / containerBounds.height).toFloat()
            }
        }

        // Update container with new ratio (clamping handled in container)
        container.setSplitRatio(newRatio)
    }

    /**
     * Update the visual appearance based on state.
     */
    private fun updateAppearance() {
        panel?.let { p ->
            val material = when {
                isDragging -> draggingMaterial
                isHovered -> hoverMaterial
                else -> normalMaterial
            }
            // Need to recreate panel with new material since PanelElement doesn't have material update
            destroy()
            panel = ui.addPanel(
                offsetRight = bounds.centerX.toDouble(),
                offsetUp = bounds.centerY.toDouble(),
                offsetForward = Z_OFFSET,
                width = bounds.width,
                height = bounds.height,
                material = material
            )
        }
    }

    /**
     * Stop dragging.
     */
    fun stopDragging() {
        isDragging = false
        updateAppearance()
    }
}
