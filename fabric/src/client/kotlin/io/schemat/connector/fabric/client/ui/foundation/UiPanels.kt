package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.fabric.client.ui.compat.*
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.gui.Font

/**
 * Legacy facade over [Theme] - kept so existing call sites keep compiling and
 * now render the unified design system. New code should use [Theme] directly.
 */
@Deprecated("Use Theme directly", ReplaceWith("Theme", "io.schemat.connector.fabric.client.ui.theme.Theme"))
object UiPanels {
    const val SCRIM = Theme.SCRIM
    const val PANEL = Theme.SURFACE
    const val PANEL_BORDER = Theme.BORDER

    /** Brand accent - `#db45f0`, used for active controls / highlights. */
    const val ACCENT = Theme.ACCENT
    /** Card background. */
    const val CARD = Theme.SURFACE
    /** Card border hairline. */
    const val CARD_BORDER = Theme.BORDER
    /** Muted label text. */
    const val MUTED = Theme.TEXT_MUTED
    /** Publish/primary CTA - emerald (pair with [Theme.SUCCESS_TEXT]). */
    const val EMERALD = Theme.SUCCESS
    /** Inset/recessed fill inside cards (input wells, placeholders). */
    const val WELL = Theme.SURFACE_ALT

    /** Full-screen opaque scrim - call first in render() to kill world bleed-through. */
    fun drawScrim(ctx: GuiGraphics, width: Int, height: Int) = Theme.scrim(ctx, width, height)

    /** Opaque bordered panel rect (accent-edged dialog surface). */
    fun drawPanel(ctx: GuiGraphics, x: Int, y: Int, w: Int, h: Int) = Theme.panel(ctx, x, y, w, h)

    /** Opaque card: dark fill with a 1px border ring. */
    fun drawCard(ctx: GuiGraphics, x: Int, y: Int, w: Int, h: Int, border: Int = Theme.BORDER) {
        Theme.stroke(ctx, x - 1, y - 1, w + 2, h + 2, border)
        ctx.fill(x, y, x + w, y + h, Theme.SURFACE)
    }

    /** Uppercase faint section label (the website's label idiom). */
    fun drawSectionLabel(ctx: GuiGraphics, font: Font, label: String, x: Int, y: Int) =
        Theme.sectionLabel(ctx, font, label, x, y)
}
