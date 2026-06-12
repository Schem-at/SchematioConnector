package io.schemat.connector.fabric.client.ui.theme

import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component

/**
 * SchematioConnector design system - single source of truth for the client UI.
 *
 * Direction: refined website-match (schemat.io) - dark flat surfaces, thin 1px
 * borders, generous spacing, uppercase faint section labels, fuchsia accent
 * used sparingly, and NO text drop-shadow (flatter, more modern).
 *
 * Minecraft can't do rounded corners or blur, so "modern" is emulated with the
 * above. All drawing helpers are pure [GuiGraphics] calls - no widgets.
 *
 * Conventions for screens:
 *  - Backgrounds: [scrim] first, then [panel] for dialogs / [card] for content.
 *  - Component: use [label]/[value]/[muted]/[hint]/[sectionLabel] - never raw
 *    drawTextWithShadow for body copy.
 *  - Buttons: `FlatButton.primary/secondary/ghost/success/danger`.
 */
object Theme {

    // =========================================================================
    // Palette (ARGB)
    // =========================================================================

    /** Brand fuchsia - `#db45f0`. Active controls, highlights, focus. */
    const val ACCENT = 0xFFDB45F0.toInt()
    /** Accent hover state - lightened fuchsia. */
    const val ACCENT_HOVER = 0xFFE978FA.toInt()
    /** Dimmed accent - accent-tinted borders and wells. */
    const val ACCENT_DIM = 0xFF7A2E88.toInt()

    /** Page background (deepest layer). */
    const val BG = 0xFF0A0A0C.toInt()
    /** Card / panel surface. */
    const val SURFACE = 0xFF15151B.toInt()
    /** Alternate surface - wells, headers, input backgrounds. */
    const val SURFACE_ALT = 0xFF1C1C24.toInt()
    /** Hovered surface (rows, list items). */
    const val SURFACE_HOVER = 0xFF24242E.toInt()

    /** Standard 1px border. */
    const val BORDER = 0xFF2A2A33.toInt()
    /** Subtle border - dividers, quiet outlines. */
    const val BORDER_SUBTLE = 0xFF1E1E26.toInt()
    /** Accent-tinted border - elevated dialogs, focused inputs. */
    const val BORDER_ACCENT = 0xFF7A2E88.toInt()

    /** Primary text - headings, values. */
    const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    /** Secondary text - body copy, labels. */
    const val TEXT_SECONDARY = 0xFFB4B4C0.toInt()
    /** Muted text - captions, metadata. */
    const val TEXT_MUTED = 0xFF8A8A96.toInt()
    /** Faint text - section labels, disabled, placeholders. */
    const val TEXT_FAINT = 0xFF5E5E68.toInt()

    /** Success / publish emerald (pair with [SUCCESS_TEXT]). */
    const val SUCCESS = 0xFF34D399.toInt()
    /** Dark text for on-emerald labels. */
    const val SUCCESS_TEXT = 0xFF06281C.toInt()
    /** Errors / destructive. */
    const val DANGER = 0xFFF87171.toInt()
    /** Warnings / stale data. */
    const val WARNING = 0xFFFBBF24.toInt()
    /** Informational. */
    const val INFO = 0xFF7EA8FF.toInt()

    /** Near-opaque full-screen backdrop (kills world bleed-through). */
    const val SCRIM = 0xF00A0A0C.toInt()

    // =========================================================================
    // Scale
    // =========================================================================

    const val XS = 4
    const val SM = 6
    const val MD = 8
    const val LG = 12
    const val XL = 16
    const val XXL = 24

    /** Standard button height. */
    const val BTN_H = 20
    /** Standard text-input height. */
    const val INPUT_H = 20
    /** Standard list-row height. */
    const val ROW_H = 22
    /** Height consumed by [header] (title + underline + gap, no subtitle). */
    const val HEADER_H = 34
    /** Chip / badge pill height. */
    const val CHIP_H = 12

    // =========================================================================
    // Color utilities
    // =========================================================================

    /** Replace a color's alpha channel (alpha 0..255). */
    fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    /** Linear blend of [a] toward [b] by [t] (0..1), per channel incl. alpha. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            return (ca + ((cb - ca) * tt)).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Lighten toward white by [t] (0..1), preserving alpha. */
    fun lighten(color: Int, t: Float): Int =
        mix(color, (color and 0xFF000000.toInt()) or 0x00FFFFFF, t)

    /** Darken toward black by [t] (0..1), preserving alpha. */
    fun darken(color: Int, t: Float): Int =
        mix(color, color and 0xFF000000.toInt(), t)

    // =========================================================================
    // Surfaces
    // =========================================================================

    /** Full-screen near-opaque page scrim - call first in render(). */
    fun scrim(ctx: GuiGraphics, width: Int, height: Int) {
        ctx.fill(0, 0, width, height, SCRIM)
    }

    /** 1px rectangle outline (stroke), drawn inside the given bounds. */
    fun stroke(ctx: GuiGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {
        ctx.fill(x, y, x + w, y + 1, color)             // top
        ctx.fill(x, y + h - 1, x + w, y + h, color)     // bottom
        ctx.fill(x, y + 1, x + 1, y + h - 1, color)     // left
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color) // right
    }

    /**
     * Content card: flat [fill] with an optional 1px [BORDER] ring drawn just
     * outside the bounds (matches the legacy UiPanels.drawCard footprint).
     */
    fun card(ctx: GuiGraphics, x: Int, y: Int, w: Int, h: Int, border: Boolean = true, fill: Int = SURFACE) {
        if (border) stroke(ctx, x - 1, y - 1, w + 2, h + 2, BORDER)
        ctx.fill(x, y, x + w, y + h, fill)
    }

    /** Elevated dialog panel: [SURFACE] fill + accent-tinted 1px edge. */
    fun panel(ctx: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        stroke(ctx, x - 1, y - 1, w + 2, h + 2, BORDER_ACCENT)
        ctx.fill(x, y, x + w, y + h, SURFACE)
    }

    /** 1px horizontal divider in [BORDER_SUBTLE]. */
    fun divider(ctx: GuiGraphics, x: Int, y: Int, w: Int) {
        ctx.fill(x, y, x + w, y + 1, BORDER_SUBTLE)
    }

    // =========================================================================
    // Component (all shadow-free for the flat look)
    // =========================================================================

    /** Body label - [TEXT_SECONDARY] by default. */
    fun label(ctx: GuiGraphics, tr: Font, text: String, x: Int, y: Int, color: Int = TEXT_SECONDARY) {
        ctx.drawString(tr, text, x, y, color, false)
    }

    /** Emphasized value / heading text - [TEXT_PRIMARY]. */
    fun value(ctx: GuiGraphics, tr: Font, text: String, x: Int, y: Int, color: Int = TEXT_PRIMARY) {
        ctx.drawString(tr, text, x, y, color, false)
    }

    /** Muted caption / metadata text - [TEXT_MUTED]. */
    fun muted(ctx: GuiGraphics, tr: Font, text: String, x: Int, y: Int, color: Int = TEXT_MUTED) {
        ctx.drawString(tr, text, x, y, color, false)
    }

    /** Faint hint / placeholder text - [TEXT_FAINT]. */
    fun hint(ctx: GuiGraphics, tr: Font, text: String, x: Int, y: Int, color: Int = TEXT_FAINT) {
        ctx.drawString(tr, text, x, y, color, false)
    }

    /** UPPERCASE faint section label - the website's label idiom. */
    fun sectionLabel(ctx: GuiGraphics, tr: Font, text: String, x: Int, y: Int) {
        ctx.drawString(tr, text.uppercase(), x, y, TEXT_FAINT, false)
    }

    /**
     * Screen header: bold title, optional muted subtitle, then a divider whose
     * leading segment is an accent underline. Returns the Y where content
     * should start (already includes a [LG] gap below the divider).
     */
    fun header(
        ctx: GuiGraphics,
        tr: Font,
        title: String,
        subtitle: String? = null,
        x: Int,
        y: Int,
        w: Int,
    ): Int {
        val bold = Component.literal(title).withStyle { it.withBold(true) }
        ctx.drawString(tr, bold, x, y, TEXT_PRIMARY, false)
        var cy = y + tr.lineHeight + 2
        if (subtitle != null) {
            ctx.drawString(tr, subtitle, x, cy, TEXT_MUTED, false)
            cy += tr.lineHeight + 2
        }
        cy += XS
        val accentW = tr.width(bold).coerceAtMost(w)
        ctx.fill(x, cy, x + w, cy + 1, BORDER_SUBTLE)
        ctx.fill(x, cy, x + accentW, cy + 1, ACCENT)
        return cy + 1 + LG
    }

    /** Centered muted message for empty lists / zero states. */
    fun emptyState(ctx: GuiGraphics, tr: Font, text: String, x: Int, y: Int, w: Int, h: Int) {
        val trimmed = tr.plainSubstrByWidth(text, (w - MD * 2).coerceAtLeast(8))
        val tx = x + (w - tr.width(trimmed)) / 2
        val ty = y + (h - tr.lineHeight) / 2
        ctx.drawString(tr, trimmed, tx, ty, TEXT_MUTED, false)
    }

    // =========================================================================
    // Pills
    // =========================================================================

    /**
     * Tag chip pill: translucent tinted fill + tinted 1px outline + readable
     * tinted text. Height is [CHIP_H]. Returns the drawn width.
     *
     * Pass [width] >= 0 to force a width (layout-driven hosts like TagChips);
     * otherwise the chip sizes itself to the text.
     */
    fun chip(ctx: GuiGraphics, tr: Font, text: String, accentColor: Int, x: Int, y: Int, width: Int = -1): Int {
        val w = if (width >= 0) width else XS + tr.width(text) + XS
        ctx.fill(x, y, x + w, y + CHIP_H, withAlpha(accentColor, 0x30))
        stroke(ctx, x, y, w, CHIP_H, withAlpha(accentColor, 0x66))
        val fg = mix(accentColor or 0xFF000000.toInt(), TEXT_PRIMARY, 0.55f)
        val labelMax = (w - XS * 2).coerceAtLeast(8)
        ctx.drawString(tr, tr.plainSubstrByWidth(text, labelMax), x + XS, y + 2, fg, false)
        return w
    }

    /** Role / status badge: stronger tinted pill with the color as text. Returns width. */
    fun badge(ctx: GuiGraphics, tr: Font, text: String, color: Int, x: Int, y: Int): Int {
        val w = XS + tr.width(text) + XS
        ctx.fill(x, y, x + w, y + CHIP_H, withAlpha(color, 0x26))
        stroke(ctx, x, y, w, CHIP_H, withAlpha(color, 0x80))
        ctx.drawString(tr, text, x + XS, y + 2, color or 0xFF000000.toInt(), false)
        return w
    }

    // =========================================================================
    // Scrollbar
    // =========================================================================

    /**
     * Slim modern scrollbar (3px wide at [x]). No-op when content fits.
     *
     * @param h       track height (usually the viewport height)
     * @param contentH total content height
     * @param viewH    visible viewport height
     * @param scroll   current scroll offset in content pixels
     */
    fun scrollbar(ctx: GuiGraphics, x: Int, y: Int, h: Int, contentH: Int, viewH: Int, scroll: Int) {
        if (contentH <= viewH || h <= 0) return
        ctx.fill(x, y, x + 3, y + h, BORDER_SUBTLE)
        val thumbH = ((viewH.toFloat() / contentH) * h).toInt().coerceIn(LG, h)
        val maxScroll = (contentH - viewH).coerceAtLeast(1)
        val thumbY = y + (((scroll.coerceIn(0, maxScroll).toFloat() / maxScroll) * (h - thumbH)).toInt())
        ctx.fill(x, thumbY, x + 3, thumbY + thumbH, TEXT_FAINT)
    }
}
