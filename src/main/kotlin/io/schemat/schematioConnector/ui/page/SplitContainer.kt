package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.ui.FloatingUI

/**
 * A container that splits its area between two children.
 *
 * The split can be horizontal (left/right) or vertical (top/bottom).
 * A draggable divider allows adjusting the split ratio.
 *
 * @param id Unique identifier
 * @param ui The FloatingUI to render to
 * @param bounds The bounds of this container
 * @param direction Split direction (HORIZONTAL or VERTICAL)
 * @param first First child (left or top)
 * @param second Second child (right or bottom)
 * @param splitRatio Ratio for first child (0.0 to 1.0, default 0.5)
 */
class SplitContainer(
    id: String,
    ui: FloatingUI,
    bounds: PageBounds,
    val direction: SplitDirection,
    private var first: PageOrContainer,
    private var second: PageOrContainer,
    private var splitRatio: Float = 0.5f
) : PageContainer(id, ui, bounds) {

    companion object {
        const val DIVIDER_THICKNESS = 0.08f
        const val MIN_RATIO = 0.15f
        const val MAX_RATIO = 0.85f
    }

    /** The divider element */
    private var divider: DividerElement? = null

    init {
        // Set parent references for nested containers
        updateChildParents()
    }

    /**
     * Get the current split ratio.
     */
    fun getSplitRatio(): Float = splitRatio

    /**
     * Set the split ratio (clamped to valid range).
     */
    fun setSplitRatio(ratio: Float) {
        splitRatio = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
        // Re-render with new ratio
        render()
    }

    override fun getPages(): List<Page> {
        return first.getPages() + second.getPages()
    }

    override fun render() {
        // Destroy old divider
        divider?.destroy()

        // Calculate child bounds
        val (firstBounds, secondBounds) = calculateChildBounds()

        // Resize and render children (resize doesn't call render to avoid recursion)
        resizeChild(first, firstBounds)
        first.render()

        resizeChild(second, secondBounds)
        second.render()

        // Render divider
        renderDivider()
    }

    override fun resize(newBounds: PageBounds) {
        bounds = newBounds
        // Don't call render() here - just update bounds
        // The caller is responsible for calling render() after resize if needed
    }

    /**
     * Resize a child without triggering its render.
     * This avoids infinite recursion when nested.
     */
    private fun resizeChild(child: PageOrContainer, newBounds: PageBounds) {
        when (child) {
            is PageOrContainer.SinglePage -> child.page.bounds = newBounds
            is PageOrContainer.Nested -> child.container.bounds = newBounds
        }
    }

    override fun destroy() {
        divider?.destroy()
        divider = null

        first.destroy()
        second.destroy()
    }

    override fun removePage(page: Page): PageOrContainer? {
        // Check if page is in first child
        when (val f = first) {
            is PageOrContainer.SinglePage -> {
                if (f.page == page) {
                    // Remove first, return second
                    f.page.detachFromContainer()
                    return second
                }
            }
            is PageOrContainer.Nested -> {
                if (f.container.containsPage(page)) {
                    val remaining = f.container.removePage(page)
                    if (remaining == null) {
                        // First is now empty, return second
                        return second
                    } else {
                        first = remaining
                        render()
                        return null  // Container still exists
                    }
                }
            }
        }

        // Check if page is in second child
        when (val s = second) {
            is PageOrContainer.SinglePage -> {
                if (s.page == page) {
                    // Remove second, return first
                    s.page.detachFromContainer()
                    return first
                }
            }
            is PageOrContainer.Nested -> {
                if (s.container.containsPage(page)) {
                    val remaining = s.container.removePage(page)
                    if (remaining == null) {
                        // Second is now empty, return first
                        return first
                    } else {
                        second = remaining
                        render()
                        return null  // Container still exists
                    }
                }
            }
        }

        return null  // Page not found
    }

    /**
     * Calculate bounds for first and second children based on split ratio.
     */
    private fun calculateChildBounds(): Pair<PageBounds, PageBounds> {
        return when (direction) {
            SplitDirection.HORIZONTAL -> bounds.splitHorizontal(splitRatio, DIVIDER_THICKNESS)
            SplitDirection.VERTICAL -> bounds.splitVertical(splitRatio, DIVIDER_THICKNESS)
        }
    }

    /**
     * Render the draggable divider.
     */
    private fun renderDivider() {
        val (firstBounds, _) = calculateChildBounds()

        val dividerBounds = when (direction) {
            SplitDirection.HORIZONTAL -> PageBounds(
                x = firstBounds.right,
                y = bounds.y,
                width = DIVIDER_THICKNESS,
                height = bounds.height
            )
            SplitDirection.VERTICAL -> PageBounds(
                x = bounds.x,
                y = firstBounds.bottom,
                width = bounds.width,
                height = DIVIDER_THICKNESS
            )
        }

        divider = DividerElement(
            ui = ui,
            container = this,
            direction = direction,
            bounds = dividerBounds
        )
        divider?.spawn()
    }

    /**
     * Update parent references for children.
     */
    private fun updateChildParents() {
        when (val f = first) {
            is PageOrContainer.SinglePage -> f.page.container = this
            is PageOrContainer.Nested -> f.container.parent = this
        }
        when (val s = second) {
            is PageOrContainer.SinglePage -> s.page.container = this
            is PageOrContainer.Nested -> s.container.parent = this
        }
    }

    /**
     * Replace first child without destroying the old one.
     * The caller should handle destroying the old child if needed.
     * Note: Does NOT call render() - caller is responsible for rendering.
     */
    fun setFirst(child: PageOrContainer) {
        first = child
        updateChildParents()
    }

    /**
     * Replace second child without destroying the old one.
     * The caller should handle destroying the old child if needed.
     * Note: Does NOT call render() - caller is responsible for rendering.
     */
    fun setSecond(child: PageOrContainer) {
        second = child
        updateChildParents()
    }

    /**
     * Get the divider element (for update loop).
     */
    fun getDivider(): DividerElement? = divider

    /**
     * Get the first child.
     */
    fun getFirst(): PageOrContainer = first

    /**
     * Get the second child.
     */
    fun getSecond(): PageOrContainer = second

    /**
     * Check if a page is in the first position.
     */
    fun isInFirstPosition(page: Page): Boolean {
        return when (val f = first) {
            is PageOrContainer.SinglePage -> f.page == page
            is PageOrContainer.Nested -> f.container.containsPage(page)
        }
    }
}
