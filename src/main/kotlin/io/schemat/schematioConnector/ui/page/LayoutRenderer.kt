package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.layout.Layout
import org.bukkit.Material

/**
 * Converts Layout coordinates to FloatingUI coordinates and provides
 * helper methods for rendering layout elements.
 *
 * Layout coordinate system:
 * - (0, 0) is top-left
 * - X increases to the right
 * - Y increases downward
 *
 * UI coordinate system:
 * - (0, 0) is center
 * - X increases to the right
 * - Y increases upward
 */
class LayoutRenderer(
    private val ui: FloatingUI,
    private val layout: Layout,
    private val pageBounds: PageBounds
) {
    /**
     * Convert layout coordinates to UI local coordinates.
     *
     * @param layoutX X position in layout space (from left edge)
     * @param layoutY Y position in layout space (from top edge)
     * @param width Width of the element
     * @param height Height of the element
     * @return Pair of (uiX, uiY) representing the center of the element in UI space
     */
    fun layoutToUI(layoutX: Float, layoutY: Float, width: Float, height: Float): Pair<Double, Double> {
        // Calculate center of element in layout space
        val layoutCenterX = layoutX + width / 2
        val layoutCenterY = layoutY + height / 2

        // Convert to UI space:
        // - Layout X=0 maps to pageBounds.x (left edge)
        // - Layout Y=0 maps to pageBounds.y (top edge)
        // - Layout Y increases downward, UI Y increases upward
        val uiX = pageBounds.x + layoutCenterX
        val uiY = pageBounds.y - layoutCenterY

        return Pair(uiX.toDouble(), uiY.toDouble())
    }

    /**
     * Get the UI coordinates for a layout element by ID.
     *
     * @param elementId The layout element ID
     * @return Triple of (uiX, uiY, LayoutResult) or null if element not found
     */
    fun getElementPosition(elementId: String): ElementPosition? {
        val result = layout.getResult(elementId) ?: return null
        val absPos = layout.getAbsolutePosition(elementId) ?: return null

        val (uiX, uiY) = layoutToUI(absPos.x, absPos.y, result.width, result.height)

        return ElementPosition(
            uiX = uiX,
            uiY = uiY,
            width = result.width,
            height = result.height
        )
    }

    /**
     * Render a layout element as a panel.
     *
     * @param elementId The layout element ID
     * @param material The material for the panel
     * @param zOffset Z offset (negative = toward player)
     * @return The created panel element, or null if element not found
     */
    fun renderPanel(
        elementId: String,
        material: Material,
        zOffset: Double = 0.0
    ): io.schemat.schematioConnector.ui.PanelElement? {
        val pos = getElementPosition(elementId) ?: return null

        return ui.addPanel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = zOffset,
            width = pos.width,
            height = pos.height,
            material = material
        )
    }

    /**
     * Render a label at a layout element's position (billboarded toward player).
     *
     * @param elementId The layout element ID
     * @param text The text to display
     * @param scale Text scale (default 0.35)
     * @param zOffset Z offset (negative = toward player)
     * @return The created label element, or null if element not found
     */
    fun renderLabel(
        elementId: String,
        text: String,
        scale: Float = 0.35f,
        zOffset: Double = -0.05
    ): io.schemat.schematioConnector.ui.LabelElement? {
        val pos = getElementPosition(elementId) ?: return null

        return ui.addLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = zOffset,
            text = text,
            scale = scale
        )
    }

    /**
     * Render an aligned label at a layout element's position (fixed to UI plane).
     * This text stays aligned with the page surface instead of facing the player.
     *
     * @param elementId The layout element ID
     * @param text The text to display
     * @param scale Text scale (default 0.4)
     * @param zOffset Z offset (negative = toward player)
     * @return The created aligned label element, or null if element not found
     */
    fun renderAlignedLabel(
        elementId: String,
        text: String,
        scale: Float = 0.4f,
        zOffset: Double = -0.03
    ): io.schemat.schematioConnector.ui.AlignedLabelElement? {
        val pos = getElementPosition(elementId) ?: return null

        return ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = zOffset,
            text = text,
            scale = scale
        )
    }

    /**
     * Render a button at a layout element's position.
     *
     * @param elementId The layout element ID
     * @param label Button label text
     * @param material Button material
     * @param hoverMaterial Material when hovered
     * @param zOffset Z offset (negative = toward player)
     * @param onClick Click handler
     * @return The created button element, or null if element not found
     */
    fun renderButton(
        elementId: String,
        label: String?,
        material: Material,
        hoverMaterial: Material,
        zOffset: Double = -0.02,
        onClick: () -> Unit
    ): io.schemat.schematioConnector.ui.ButtonElement? {
        val pos = getElementPosition(elementId) ?: return null

        // Use the smaller dimension as the button size
        val size = minOf(pos.width, pos.height)

        return ui.addButton(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = zOffset,
            label = label,
            material = material,
            hoverMaterial = hoverMaterial,
            size = size,
            onClick = onClick
        )
    }

    /**
     * Position data for a layout element in UI space.
     */
    data class ElementPosition(
        val uiX: Double,
        val uiY: Double,
        val width: Float,
        val height: Float
    )
}
