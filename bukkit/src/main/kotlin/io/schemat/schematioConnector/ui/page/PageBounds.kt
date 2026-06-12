package io.schemat.schematioConnector.ui.page

/**
 * Represents the position and size of a Page in UI local coordinates.
 *
 * Coordinate system:
 * - x: horizontal position (positive = right)
 * - y: vertical position (positive = up)
 * - (0, 0) is the center of the UI
 *
 * @param x Left edge x-coordinate
 * @param y Top edge y-coordinate
 * @param width Width of the page
 * @param height Height of the page
 */
data class PageBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    /** Center x-coordinate */
    val centerX: Float get() = x + width / 2

    /** Center y-coordinate (y is top edge, so center is y - height/2) */
    val centerY: Float get() = y - height / 2

    /** Right edge x-coordinate */
    val right: Float get() = x + width

    /** Bottom edge y-coordinate */
    val bottom: Float get() = y - height

    /**
     * Create bounds centered at origin with given dimensions
     */
    companion object {
        fun centered(width: Float, height: Float): PageBounds {
            return PageBounds(
                x = -width / 2,
                y = height / 2,
                width = width,
                height = height
            )
        }
    }

    /**
     * Split this bounds horizontally (left/right)
     * @param ratio The fraction of width for the left side (0.0 to 1.0)
     * @param gap Gap between the two halves
     * @return Pair of (left bounds, right bounds)
     */
    fun splitHorizontal(ratio: Float, gap: Float = 0f): Pair<PageBounds, PageBounds> {
        val leftWidth = (width - gap) * ratio
        val rightWidth = (width - gap) * (1 - ratio)

        val left = PageBounds(x, y, leftWidth, height)
        val right = PageBounds(x + leftWidth + gap, y, rightWidth, height)

        return Pair(left, right)
    }

    /**
     * Split this bounds vertically (top/bottom)
     * @param ratio The fraction of height for the top side (0.0 to 1.0)
     * @param gap Gap between the two halves
     * @return Pair of (top bounds, bottom bounds)
     */
    fun splitVertical(ratio: Float, gap: Float = 0f): Pair<PageBounds, PageBounds> {
        val topHeight = (height - gap) * ratio
        val bottomHeight = (height - gap) * (1 - ratio)

        val top = PageBounds(x, y, width, topHeight)
        val bottom = PageBounds(x, y - topHeight - gap, width, bottomHeight)

        return Pair(top, bottom)
    }

    /**
     * Inset bounds by padding on all sides
     */
    fun inset(padding: Float): PageBounds {
        return PageBounds(
            x = x + padding,
            y = y - padding,
            width = (width - padding * 2).coerceAtLeast(0f),
            height = (height - padding * 2).coerceAtLeast(0f)
        )
    }

    /**
     * Inset bounds by different padding values
     */
    fun inset(left: Float, top: Float, right: Float, bottom: Float): PageBounds {
        return PageBounds(
            x = x + left,
            y = y - top,
            width = (width - left - right).coerceAtLeast(0f),
            height = (height - top - bottom).coerceAtLeast(0f)
        )
    }
}
