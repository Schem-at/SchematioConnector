package io.schemat.connector.fabric.client.ui.widgets

import io.schemat.connector.fabric.client.ui.compat.*
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.gui.Font

/**
 * Shared layout + rendering for tag "chips": small colored pills showing a tag name,
 * optionally with a trailing close ("x") affordance. Pure helpers - interactive hosts
 * (the tag selector's chip strip) hit-test clicks against the returned [Placed] rects;
 * read-only hosts (edit / upload tag summaries) just render.
 */
object TagChips {

    const val CHIP_HEIGHT = Theme.CHIP_H
    private const val H_GAP = 4
    private const val V_GAP = 3
    private const val TEXT_PAD = 4
    private const val CLOSE_WIDTH = 9

    /** [id] is null for purely informational chips (no removal semantics). */
    data class Chip(val id: String?, val label: String, val color: String?)

    /** A laid-out chip; [closeX] is -1 when the chip has no close affordance. */
    data class Placed(val chip: Chip, val x: Int, val y: Int, val width: Int, val closeX: Int)

    /**
     * Wrap [chips] left-to-right into the given area. Returns the chips that fit plus
     * the count of those that did not (rendered as a trailing "+N").
     */
    fun layout(
        chips: List<Chip>,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        font: Font,
        withClose: Boolean,
    ): Pair<List<Placed>, Int> {
        val placed = mutableListOf<Placed>()
        var cx = x + 2
        var cy = y + 2
        chips.forEachIndexed { index, chip ->
            val labelWidth = font.width(chip.label).coerceAtMost((width - 24).coerceAtLeast(20))
            val closeWidth = if (withClose) CLOSE_WIDTH else 0
            val chipWidth = TEXT_PAD + labelWidth + TEXT_PAD + closeWidth
            if (cx + chipWidth > x + width - 2 && cx > x + 2) {
                cx = x + 2
                cy += CHIP_HEIGHT + V_GAP
            }
            if (cy + CHIP_HEIGHT > y + height) return placed to (chips.size - index)
            placed.add(Placed(chip, cx, cy, chipWidth, if (withClose) cx + chipWidth - closeWidth else -1))
            cx += chipWidth + H_GAP
        }
        return placed to 0
    }

    fun render(context: GuiGraphics, font: Font, placed: List<Placed>, overflow: Int) {
        placed.forEach { p ->
            val tint = parseColor(p.chip.color) ?: Theme.ACCENT
            val labelMax = p.width - TEXT_PAD * 2 - (if (p.closeX >= 0) CLOSE_WIDTH else 0)
            Theme.chip(
                context, font,
                font.plainSubstrByWidth(p.chip.label, labelMax.coerceAtLeast(8)),
                tint, p.x, p.y, width = p.width,
            )
            if (p.closeX >= 0) {
                context.drawString(font, "x", p.closeX + 1, p.y + 2, Theme.TEXT_MUTED, false)
            }
        }
        if (overflow > 0) {
            val anchor = placed.lastOrNull() ?: return
            context.drawString(
                font, "+$overflow",
                anchor.x + anchor.width + H_GAP, anchor.y + 2, Theme.TEXT_FAINT, false
            )
        }
    }

    /** Parse `#RRGGBB` into an opaque ARGB int, or null. */
    fun parseColor(hex: String?): Int? {
        val cleaned = hex?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
        return cleaned.toIntOrNull(16)?.let { 0xFF_000000.toInt() or it }
    }
}
