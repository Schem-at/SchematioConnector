package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.ui.ButtonElement
import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.UIElement
import org.bukkit.Material

/**
 * Page chrome - the non-intrusive control buttons in the corner of a page.
 *
 * Renders two buttons in the top-right corner:
 * - Close button (X) - closes/removes the page
 * - Options button (three dots) - opens a dropdown menu with more options
 *
 * @param page The page this chrome belongs to
 * @param ui The FloatingUI to render to
 */
class PageChrome(
    private val page: Page,
    private val ui: FloatingUI
) {
    companion object {
        const val CHROME_HEIGHT = 0.25f
        const val BUTTON_SIZE = 0.15f
        const val BUTTON_GAP = 0.04f
        const val CORNER_PADDING = 0.06f
        const val Z_OFFSET = -0.03  // In front of content
    }

    private var closeButton: ButtonElement? = null
    private var optionsButton: ButtonElement? = null
    private var dropdown: DropdownMenu? = null

    private val elements = mutableListOf<UIElement>()

    /**
     * Render the chrome buttons.
     */
    fun render(bounds: PageBounds) {
        // Position close button in top-right corner
        val closeX = bounds.right - CORNER_PADDING - BUTTON_SIZE / 2
        val closeY = bounds.y - CORNER_PADDING - BUTTON_SIZE / 2

        closeButton = ui.addButton(
            offsetRight = closeX.toDouble(),
            offsetUp = closeY.toDouble(),
            offsetForward = Z_OFFSET,
            label = "§c\u2715",  // Red X
            material = Material.RED_CONCRETE,
            hoverMaterial = Material.ORANGE_CONCRETE,
            size = BUTTON_SIZE
        ) {
            page.close()
        }
        elements.add(closeButton!!)

        // Position options button to the left of close button
        val optionsX = closeX - BUTTON_SIZE - BUTTON_GAP
        val optionsY = closeY

        optionsButton = ui.addButton(
            offsetRight = optionsX.toDouble(),
            offsetUp = optionsY.toDouble(),
            offsetForward = Z_OFFSET,
            label = "§f\u22EE",  // Vertical ellipsis (three dots)
            material = Material.GRAY_CONCRETE,
            hoverMaterial = Material.LIGHT_GRAY_CONCRETE,
            size = BUTTON_SIZE
        ) {
            toggleDropdown(optionsX, optionsY - BUTTON_SIZE / 2 - 0.05f)
        }
        elements.add(optionsButton!!)
    }

    /**
     * Toggle the options dropdown menu.
     */
    private fun toggleDropdown(anchorX: Float, anchorY: Float) {
        if (dropdown?.isShown() == true) {
            dropdown?.dismiss()
            dropdown = null
            return
        }

        val items = page.getDropdownOptions()
        if (items.isEmpty()) return

        dropdown = DropdownMenu(
            ui = ui,
            anchorX = anchorX,
            anchorY = anchorY,
            items = items,
            onDismiss = { dropdown = null }
        )
        dropdown?.show()
    }

    /**
     * Dismiss any open dropdown.
     */
    fun dismissDropdown() {
        dropdown?.dismiss()
        dropdown = null
    }

    /**
     * Clean up all chrome elements.
     */
    fun destroy() {
        dismissDropdown()

        elements.forEach {
            it.destroy()
            ui.removeElement(it)
        }
        elements.clear()

        closeButton = null
        optionsButton = null
    }
}
