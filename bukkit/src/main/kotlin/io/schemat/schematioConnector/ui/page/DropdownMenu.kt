package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.PanelElement
import io.schemat.schematioConnector.ui.UIElement
import io.schemat.schematioConnector.ui.layout.CrossAxisAlignment
import io.schemat.schematioConnector.ui.layout.Layout
import io.schemat.schematioConnector.ui.layout.Padding
import org.bukkit.Material

/**
 * A single item in a dropdown menu.
 *
 * @param id Unique identifier for this item
 * @param label Display text
 * @param icon Optional icon prefix (e.g., emoji or symbol)
 * @param enabled Whether the item can be clicked
 * @param onClick Action when clicked
 */
data class DropdownItem(
    val id: String,
    val label: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * A dropdown menu that appears below an anchor point.
 *
 * Uses the layout engine for consistent sizing and positioning.
 * The menu displays a list of items that can be clicked.
 * Clicking an item dismisses the menu.
 *
 * @param ui The FloatingUI to render to
 * @param anchorX X position of the anchor point (right edge of dropdown)
 * @param anchorY Y position of the anchor point (top edge of dropdown)
 * @param items List of items to display
 * @param onDismiss Called when the menu is dismissed
 */
class DropdownMenu(
    private val ui: FloatingUI,
    private val anchorX: Float,
    private val anchorY: Float,
    private val items: List<DropdownItem>,
    private val onDismiss: () -> Unit
) {
    companion object {
        const val ITEM_HEIGHT = 0.22f
        const val ITEM_WIDTH = 1.2f
        const val PADDING = 0.04f
        const val ITEM_GAP = 0.02f
        const val Z_OFFSET = -0.15  // In front of other elements
    }

    private var backgroundPanel: PanelElement? = null
    private val elements = mutableListOf<UIElement>()
    private var isShown = false
    private var layout: Layout? = null

    /**
     * Show the dropdown menu.
     */
    fun show() {
        if (isShown || items.isEmpty()) return
        isShown = true

        // Build the layout
        val menuWidth = ITEM_WIDTH + PADDING * 2
        val menuHeight = items.size * ITEM_HEIGHT + (items.size - 1) * ITEM_GAP + PADDING * 2

        layout = Layout(width = menuWidth, height = menuHeight).apply {
            column(
                "root",
                padding = Padding.all(PADDING),
                gap = ITEM_GAP,
                crossAxisAlignment = CrossAxisAlignment.Stretch
            ) {
                items.forEachIndexed { index, item ->
                    leaf("item_$index", height = ITEM_HEIGHT)
                }
            }
            compute()
        }

        // Calculate menu position (right edge at anchor, top at anchor)
        val menuBounds = PageBounds(
            x = anchorX - menuWidth,
            y = anchorY,
            width = menuWidth,
            height = menuHeight
        )

        // Create renderer for this layout
        val renderer = LayoutRenderer(ui, layout!!, menuBounds)

        // Background panel
        backgroundPanel = ui.addPanel(
            offsetRight = menuBounds.centerX.toDouble(),
            offsetUp = menuBounds.centerY.toDouble(),
            offsetForward = Z_OFFSET,
            width = menuWidth,
            height = menuHeight,
            material = Material.BLACK_CONCRETE
        )

        // Render each item using layout positions
        items.forEachIndexed { index, item ->
            val elementId = "item_$index"
            val pos = renderer.getElementPosition(elementId) ?: return@forEachIndexed

            val displayText = if (item.icon != null) {
                "${item.icon} ${item.label}"
            } else {
                item.label
            }

            val textColor = when {
                !item.enabled -> "§8"  // Dark gray for disabled
                else -> "§f"           // White for enabled
            }

            // Interactive panel for hover effect and click handling (full width)
            val panel = ui.addInteractivePanel(
                offsetRight = pos.uiX,
                offsetUp = pos.uiY,
                offsetForward = Z_OFFSET - 0.01,
                width = pos.width,
                height = pos.height,
                material = Material.BLACK_CONCRETE,
                hoverMaterial = if (item.enabled) Material.GRAY_CONCRETE else null
            ) {
                if (item.enabled) {
                    dismiss()
                    item.onClick()
                }
            }
            elements.add(panel)

            // Aligned label for the text (in front of the panel)
            val label = ui.addAlignedLabel(
                offsetRight = pos.uiX,
                offsetUp = pos.uiY,
                offsetForward = Z_OFFSET - 0.02,
                text = "$textColor$displayText",
                scale = 0.25f
            )
            elements.add(label)
        }
    }

    /**
     * Dismiss (hide) the dropdown menu.
     */
    fun dismiss() {
        if (!isShown) return
        isShown = false

        destroy()
        onDismiss()
    }

    /**
     * Clean up all UI elements.
     */
    fun destroy() {
        backgroundPanel?.let {
            it.destroy()
            ui.removeElement(it)
        }
        backgroundPanel = null

        elements.forEach {
            it.destroy()
            ui.removeElement(it)
        }
        elements.clear()

        layout = null
    }

    /**
     * Check if the menu is currently shown.
     */
    fun isShown(): Boolean = isShown
}
