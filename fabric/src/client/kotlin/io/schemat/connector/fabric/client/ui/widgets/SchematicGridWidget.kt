package io.schemat.connector.fabric.client.ui.widgets

import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.fabric.client.ui.PreviewImageManager
import io.schemat.connector.fabric.client.ui.foundation.PreviewDraw
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component

/**
 * Scrollable thumbnail grid of schematics: column count derives from a minimum cell
 * width (cells stretch a little, then the grid centers in the leftover space). Each
 * cell is a design-system card - 16:9 preview (cover-cropped) or placeholder on top,
 * a padded two-line text strip (name, first author) below, with a hover highlight.
 * Clicking a cell fires [onClickItem]; scrolling near the bottom fires [onNeedMore]
 * while [hasMore] - the host owns pagination/busy state and calls [setItems] with
 * the accumulated list.
 */
class SchematicGridWidget(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val previews: PreviewImageManager,
    private val onClickItem: (SchematicSummary) -> Unit,
    private val onNeedMore: () -> Unit,
) : AbstractWidget(x, y, width, height, Component.literal("Schematics")) {

    companion object {
        /** Gutter between cells, both axes. */
        private const val CELL_GAP = Theme.MD
        private const val MIN_CELL_WIDTH = 140
        /** Cells stretch up to this before the grid centers with leftover space instead. */
        private const val MAX_CELL_WIDTH = 180
        /** Two text lines (name + author) with [Theme.SM] breathing room above/below. */
        private const val TEXT_BLOCK_HEIGHT = Theme.SM + 9 + 2 + 9 + Theme.SM
        private const val SCROLL_STEP = 24.0
        /** Request the next page when within one row of the bottom. */
        private const val PREFETCH_MARGIN = 1.0
    }

    private var items: List<SchematicSummary> = emptyList()

    /** Whether more pages exist server-side; set by the host alongside [setItems]. */
    var hasMore: Boolean = false

    private var scrollOffset = 0.0

    private val columns: Int
        get() = ((width + CELL_GAP) / (MIN_CELL_WIDTH + CELL_GAP)).coerceAtLeast(1)

    private val cellWidth: Int
        get() = ((width - CELL_GAP * (columns - 1)) / columns).coerceAtMost(MAX_CELL_WIDTH)

    /** Left edge of the (horizontally centered) grid content. */
    private val originX: Int
        get() {
            val contentWidth = columns * cellWidth + (columns - 1) * CELL_GAP
            return x + ((width - contentWidth) / 2).coerceAtLeast(0)
        }

    /** 16:9 thumbnail spanning the card width, inset 1px for the card border. */
    private val thumbWidth: Int get() = cellWidth - 2
    private val thumbHeight: Int get() = thumbWidth * 9 / 16
    private val cellHeight: Int get() = 1 + thumbHeight + TEXT_BLOCK_HEIGHT

    private val contentHeight: Int
        get() {
            if (items.isEmpty()) return 0
            val rows = (items.size + columns - 1) / columns
            return rows * cellHeight + (rows - 1) * CELL_GAP
        }

    private val maxScroll: Double get() = (contentHeight - height).coerceAtLeast(0).toDouble()

    /** Replace the displayed items; [preserveScroll] keeps the position (page appends). */
    fun setItems(items: List<SchematicSummary>, preserveScroll: Boolean = false) {
        this.items = items
        scrollOffset = if (preserveScroll) scrollOffset.coerceIn(0.0, maxScroll) else 0.0
    }

    fun resetScroll() {
        scrollOffset = 0.0
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        scrollOffset = (scrollOffset - verticalAmount * SCROLL_STEP).coerceIn(0.0, maxScroll)
        maybeRequestMore()
        return true
    }

    // 1.21.9 changed input handlers to take event objects; on 1.21.8 the
    // primitive override delegates to the event-shaped method (compat shim).
    //? if <1.21.9 {
    /*override fun onClick(clickX: Double, clickY: Double) {
        onClick(MouseButtonEvent(clickX, clickY), false)
    }
    *///?}
    //? if >=1.21.9
    override
    fun onClick(click: MouseButtonEvent, doubled: Boolean) {
        itemAt(click.x(), click.y())?.let(onClickItem)
    }

    private fun itemAt(mouseX: Double, mouseY: Double): SchematicSummary? {
        val localX = mouseX - originX
        val localY = mouseY - y + scrollOffset
        if (localX < 0 || localY < 0) return null
        val col = (localX / (cellWidth + CELL_GAP)).toInt()
        val row = (localY / (cellHeight + CELL_GAP)).toInt()
        if (col >= columns) return null
        // Reject clicks landing in the gap between cells
        if (localX - col * (cellWidth + CELL_GAP) > cellWidth) return null
        if (localY - row * (cellHeight + CELL_GAP) > cellHeight) return null
        return items.getOrNull(row * columns + col)
    }

    private fun maybeRequestMore() {
        if (hasMore && scrollOffset >= maxScroll - cellHeight * PREFETCH_MARGIN) {
            onNeedMore()
        }
    }

    // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
    //? if >=26.1 {
    /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    *///?} else {
    override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    //?}
        // When the loaded content does not even fill the viewport, keep paging in
        // (onNeedMore is busy-guarded by the host, so this is a cheap no-op while loading).
        if (hasMore && contentHeight <= height) onNeedMore()

        if (items.isEmpty()) return

        val font = Minecraft.getInstance().font
        val hoveredItem = if (isMouseOver(mouseX.toDouble(), mouseY.toDouble())) {
            itemAt(mouseX.toDouble(), mouseY.toDouble())
        } else null

        context.enableScissor(x, y, x + width, y + height)

        items.forEachIndexed { index, item ->
            val col = index % columns
            val row = index / columns
            val cellX = originX + col * (cellWidth + CELL_GAP)
            val cellY = y + row * (cellHeight + CELL_GAP) - scrollOffset.toInt()

            // Cull cells fully outside the viewport
            if (cellY + cellHeight < y || cellY > y + height) return@forEachIndexed

            // Card: flat surface + 1px border, both brightening subtly on hover
            val hovered = item === hoveredItem
            context.fill(
                cellX, cellY, cellX + cellWidth, cellY + cellHeight,
                if (hovered) Theme.SURFACE_HOVER else Theme.SURFACE
            )
            Theme.stroke(
                context, cellX, cellY, cellWidth, cellHeight,
                if (hovered) Theme.BORDER_ACCENT else Theme.BORDER
            )

            // 16:9 thumbnail across the card top (the preview covers it aspect-correct, crop-centered)
            val thumbX = cellX + 1
            val thumbY = cellY + 1
            val entry = previews.getEntry(item.id, item.previewImageUrl)
            when {
                entry != null -> PreviewDraw.drawCover(
                    context, entry.id, entry.width, entry.height,
                    thumbX, thumbY, thumbWidth, thumbHeight
                )
                else -> {
                    context.fill(thumbX, thumbY, thumbX + thumbWidth, thumbY + thumbHeight, Theme.SURFACE_ALT)
                    val label = when {
                        previews.isLoading(item.id) -> "..."
                        item.previewImageUrl.isNullOrBlank() -> "No preview"
                        else -> "Unavailable"
                    }
                    val trimmed = font.plainSubstrByWidth(label, thumbWidth - Theme.MD)
                    context.drawString(
                        font, trimmed,
                        thumbX + (thumbWidth - font.width(trimmed)) / 2,
                        thumbY + (thumbHeight - font.lineHeight) / 2,
                        Theme.TEXT_FAINT, false
                    )
                }
            }

            // Two-line text strip below the thumbnail, padded off the card edges
            val textX = cellX + Theme.MD
            val maxTextWidth = cellWidth - Theme.MD * 2
            Theme.value(
                context, font,
                font.plainSubstrByWidth(item.name, maxTextWidth),
                textX, thumbY + thumbHeight + Theme.SM
            )
            item.authors.firstOrNull()?.lastSeenName?.takeIf { it.isNotBlank() }?.let { author ->
                Theme.muted(
                    context, font,
                    font.plainSubstrByWidth("by $author", maxTextWidth),
                    textX, thumbY + thumbHeight + Theme.SM + 11
                )
            }
        }

        context.disableScissor()

        Theme.scrollbar(context, x + width - 3, y, height, contentHeight, height, scrollOffset.toInt())
    }

    override fun updateWidgetNarration(builder: NarrationElementOutput) {
        defaultButtonNarrationText(builder)
    }
}
