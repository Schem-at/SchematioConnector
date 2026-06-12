package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

/**
 * Design-system single-line text input: a [Theme.SURFACE_ALT] well with a 1px
 * border (accent while focused) and a chrome-less vanilla [EditBox]
 * inset inside it. The constructor takes the OUTER well bounds; the editable
 * area is inset by [Theme.SM] horizontally and vertically centered.
 *
 * The well is drawn by the widget itself (in [renderWidget]) so hosts don't
 * need a renderBackground hook.
 */
class ThemedTextField(
    font: Font,
    x: Int,
    y: Int,
    width: Int,
    height: Int = Theme.INPUT_H,
    label: Component,
    placeholder: String? = null,
) : EditBox(
    font,
    x + Theme.SM,
    y + (height - 8) / 2,
    (width - Theme.SM * 2).coerceAtLeast(20),
    12,
    label,
) {

    private val wellX = x
    private val wellY = y
    private val wellW = width
    private val wellH = height

    init {
        setBordered(false)
        // MUST be full ARGB: since the 1.21.6 GUI-render rewrite,
        // GuiGraphics.drawText starts with `if (ColorHelper.getAlpha(color) == 0) return;`
        // so an alpha-stripped color (e.g. `TEXT_PRIMARY and 0xFFFFFF`) silently
        // drops every glyph - typed text becomes invisible.
        setTextColor(Theme.TEXT_PRIMARY)
        setTextColorUneditable(Theme.TEXT_MUTED)
        placeholder?.let { text ->
            // Style colors are 24-bit RGB (alpha comes from the opaque draw color).
            setHint(Component.literal(text).withStyle { it.withColor(Theme.TEXT_FAINT and 0xFFFFFF) })
        }
    }

    // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
    //? if >=26.1 {
    /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    *///?} else {
    override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    //?}
        context.fill(wellX, wellY, wellX + wellW, wellY + wellH, Theme.SURFACE_ALT)
        Theme.stroke(context, wellX, wellY, wellW, wellH, if (isFocused) Theme.BORDER_ACCENT else Theme.BORDER)
        //? if >=26.1 {
        /*super.extractWidgetRenderState(context, mouseX, mouseY, delta)
        *///?} else {
        super.renderWidget(context, mouseX, mouseY, delta)
        //?}
    }
}
