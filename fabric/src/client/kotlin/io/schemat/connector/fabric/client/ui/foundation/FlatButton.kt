package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.Minecraft
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component

/**
 * Flat-color button matching the design system (no vanilla sprite): a solid
 * fill + 1px border + centered label, drawn without text shadow.
 *
 * Preferred API - [Variant] + the companion factories:
 *   `FlatButton.primary(x, y, w, Component.literal("Save")) { ... }`
 * Variants carry hover/disabled styling; height defaults to [Theme.BTN_H].
 *
 * Legacy API - the supplier-based constructor (bg/fg/border lambdas) is kept
 * for reactive restyling (e.g. segmented controls whose active segment turns
 * accent without re-creating widgets).
 *
 * Built on [AbstractWidget] because 1.21.11's PressableWidget made
 * `renderWidget` final (it always draws the vanilla button sprite).
 */
class FlatButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    label: Component,
    private val bg: () -> Int,
    private val fg: () -> Int = { Theme.TEXT_PRIMARY },
    private val border: () -> Int = { Theme.BORDER },
    private val textShadow: Boolean = false,
    private val onPress: () -> Unit,
) : AbstractWidget(x, y, width, height, label) {

    /**
     * Design-system button styles. Each defines base/hover colors for fill,
     * text, and border; disabled is uniform (faint text on [Theme.SURFACE]).
     */
    enum class Variant(
        val bg: Int,
        val bgHover: Int,
        val fg: Int,
        val fgHover: Int,
        val border: Int,
        val borderHover: Int,
    ) {
        /** Accent fill, white text - the screen's main action. */
        PRIMARY(Theme.ACCENT, Theme.ACCENT_HOVER, Theme.TEXT_PRIMARY, Theme.TEXT_PRIMARY, Theme.ACCENT_DIM, Theme.ACCENT_HOVER),

        /** Emerald fill, dark text - publish / confirm CTAs. */
        SUCCESS(Theme.SUCCESS, Theme.lighten(Theme.SUCCESS, 0.25f), Theme.SUCCESS_TEXT, Theme.SUCCESS_TEXT, Theme.darken(Theme.SUCCESS, 0.45f), Theme.SUCCESS),

        /** Quiet surface fill - secondary actions. */
        SECONDARY(Theme.SURFACE_ALT, Theme.SURFACE_HOVER, Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, Theme.BORDER, Theme.BORDER),

        /** Transparent with border only - tertiary / inline actions. */
        GHOST(0x00000000, Theme.withAlpha(Theme.SURFACE_HOVER, 0x66), Theme.TEXT_MUTED, Theme.TEXT_PRIMARY, Theme.BORDER_SUBTLE, Theme.BORDER),

        /** Danger-tinted - destructive actions. */
        DANGER(0xFF36161E.toInt(), 0xFF4A1E29.toInt(), Theme.DANGER, Theme.lighten(Theme.DANGER, 0.3f), 0xFF5C2430.toInt(), Theme.DANGER),
    }

    private var variant: Variant? = null

    /** Variant-styled button. Prefer the companion factories for brevity. */
    constructor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        label: Component,
        variant: Variant,
        onPress: () -> Unit,
    ) : this(x, y, width, height, label, bg = { 0 }, onPress = onPress) {
        this.variant = variant
    }

    companion object {
        fun primary(x: Int, y: Int, width: Int, label: Component, height: Int = Theme.BTN_H, onPress: () -> Unit) =
            FlatButton(x, y, width, height, label, Variant.PRIMARY, onPress)

        fun success(x: Int, y: Int, width: Int, label: Component, height: Int = Theme.BTN_H, onPress: () -> Unit) =
            FlatButton(x, y, width, height, label, Variant.SUCCESS, onPress)

        fun secondary(x: Int, y: Int, width: Int, label: Component, height: Int = Theme.BTN_H, onPress: () -> Unit) =
            FlatButton(x, y, width, height, label, Variant.SECONDARY, onPress)

        fun ghost(x: Int, y: Int, width: Int, label: Component, height: Int = Theme.BTN_H, onPress: () -> Unit) =
            FlatButton(x, y, width, height, label, Variant.GHOST, onPress)

        fun danger(x: Int, y: Int, width: Int, label: Component, height: Int = Theme.BTN_H, onPress: () -> Unit) =
            FlatButton(x, y, width, height, label, Variant.DANGER, onPress)
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
        playDownSound(Minecraft.getInstance().soundManager)
        onPress()
    }

    // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
    //? if >=26.1 {
    /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    *///?} else {
    override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    //?}
        val v = variant
        if (v != null) renderVariant(context, v) else renderLegacy(context)
    }

    private fun renderVariant(context: GuiGraphics, v: Variant) {
        val hovered = active && isHovered
        val bgColor = if (!active) Theme.SURFACE else if (hovered) v.bgHover else v.bg
        val fgColor = if (!active) Theme.TEXT_FAINT else if (hovered) v.fgHover else v.fg
        val borderColor = if (!active) Theme.BORDER_SUBTLE else if (hovered) v.borderHover else v.border
        if ((bgColor ushr 24) != 0) {
            context.fill(x, y, x + width, y + height, bgColor)
        }
        Theme.stroke(context, x, y, width, height, borderColor)
        drawCenteredLabel(context, fgColor, shadow = false)
    }

    private fun renderLegacy(context: GuiGraphics) {
        context.fill(x, y, x + width, y + height, bg())
        Theme.stroke(context, x - 1, y - 1, width + 2, height + 2, border())
        if (!active) {
            context.fill(x, y, x + width, y + height, 0x99_101014.toInt())
        } else if (isHovered) {
            context.fill(x, y, x + width, y + height, 0x22_FFFFFF)
        }
        val color = if (active) fg() else Theme.TEXT_MUTED
        drawCenteredLabel(context, color, textShadow)
    }

    private fun drawCenteredLabel(context: GuiGraphics, color: Int, shadow: Boolean) {
        val font = Minecraft.getInstance().font
        val textX = x + (width - font.width(message)) / 2
        val textY = y + (height - font.lineHeight + 1) / 2
        context.drawString(font, message, textX, textY, color, shadow)
    }

    override fun updateWidgetNarration(builder: NarrationElementOutput) {
        defaultButtonNarrationText(builder)
    }
}
