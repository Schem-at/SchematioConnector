package io.schemat.connector.fabric.client.ui.layout

/**
 * Immutable axis-aligned rectangle for laying out screen content.
 *
 * Layouts are built top-down by splitting a root rect into smaller rects, so a
 * child can never draw outside the space its parent gave it — overlap is
 * structurally prevented.
 *
 * Each split returns the consumed slice plus the remainder, with an optional
 * gap that's deducted from the remainder.
 */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {

    val right: Int get() = x + w
    val bottom: Int get() = y + h
    val centerX: Int get() = x + w / 2
    val centerY: Int get() = y + h / 2

    fun inset(padding: Int): Rect = inset(padding, padding, padding, padding)

    fun inset(horizontal: Int, vertical: Int): Rect =
        inset(horizontal, vertical, horizontal, vertical)

    fun inset(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect(x + left, y + top, (w - left - right).coerceAtLeast(0), (h - top - bottom).coerceAtLeast(0))

    /** Take `size` pixels from the top; return (consumed, remainder-with-gap-removed). */
    fun splitTop(size: Int, gap: Int = 0): Pair<Rect, Rect> {
        val s = size.coerceAtMost(h)
        val top = Rect(x, y, w, s)
        val rest = Rect(x, y + s + gap, w, (h - s - gap).coerceAtLeast(0))
        return top to rest
    }

    fun splitBottom(size: Int, gap: Int = 0): Pair<Rect, Rect> {
        val s = size.coerceAtMost(h)
        val bottom = Rect(x, y + h - s, w, s)
        val rest = Rect(x, y, w, (h - s - gap).coerceAtLeast(0))
        return bottom to rest
    }

    fun splitLeft(size: Int, gap: Int = 0): Pair<Rect, Rect> {
        val s = size.coerceAtMost(w)
        val left = Rect(x, y, s, h)
        val rest = Rect(x + s + gap, y, (w - s - gap).coerceAtLeast(0), h)
        return left to rest
    }

    fun splitRight(size: Int, gap: Int = 0): Pair<Rect, Rect> {
        val s = size.coerceAtMost(w)
        val right = Rect(x + w - s, y, s, h)
        val rest = Rect(x, y, (w - s - gap).coerceAtLeast(0), h)
        return right to rest
    }

    /** Centered sub-rect of the given size, clamped to this rect. */
    fun centered(width: Int, height: Int): Rect {
        val cw = width.coerceAtMost(w)
        val ch = height.coerceAtMost(h)
        return Rect(x + (w - cw) / 2, y + (h - ch) / 2, cw, ch)
    }

    /** Sub-rect with the same top-left but the given size, clamped to this rect. */
    fun sized(width: Int, height: Int): Rect =
        Rect(x, y, width.coerceAtMost(w), height.coerceAtMost(h))
}
