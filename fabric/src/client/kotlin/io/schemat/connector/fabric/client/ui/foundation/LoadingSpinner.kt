package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.fabric.client.ui.compat.*
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.gui.Font

/**
 * Design-system loading indicator: a muted label followed by three small
 * square dots, the active one lit in the accent color, cycling left-to-right.
 * Drawn centered at the given position.
 */
object LoadingSpinner {

    private const val FRAME_MS = 220L
    private const val DOT = 3
    private const val DOT_GAP = 3
    private const val LABEL_GAP = 6

    fun render(
        context: GuiGraphics,
        font: Font,
        centerX: Int,
        y: Int,
        label: String = "Loading",
        color: Int = Theme.ACCENT,
    ) {
        val frame = ((System.currentTimeMillis() / FRAME_MS) % 3).toInt()
        val labelWidth = font.width(label)
        val dotsWidth = DOT * 3 + DOT_GAP * 2
        val total = labelWidth + LABEL_GAP + dotsWidth
        val startX = centerX - total / 2

        context.drawString(font, label, startX, y, Theme.TEXT_MUTED, false)

        val dotY = y + (font.lineHeight - DOT) / 2
        var dx = startX + labelWidth + LABEL_GAP
        for (i in 0 until 3) {
            val dotColor = if (i == frame) color else Theme.TEXT_FAINT
            context.fill(dx, dotY, dx + DOT, dotY + DOT, dotColor)
            dx += DOT + DOT_GAP
        }
    }
}
