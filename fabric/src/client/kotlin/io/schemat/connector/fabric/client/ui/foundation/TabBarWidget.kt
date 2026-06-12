package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.Minecraft
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component

/**
 * Flat, design-system tab bar (no vanilla button texture). Tabs size to their
 * label (falling back to an even split when space is tight) and sit on a
 * 1px [Theme.BORDER] baseline; the active tab gets [Theme.TEXT_PRIMARY] text
 * plus a 2px [Theme.ACCENT] underline, inactive tabs are [Theme.TEXT_MUTED]
 * and brighten to [Theme.TEXT_SECONDARY] on hover.
 *
 * The host screen registers [widgets] via `addRenderableWidget` and calls
 * [render] each frame to draw the baseline + active underline on top.
 */
class TabBarWidget(
    private val x: Int,
    private val y: Int,
    private val width: Int,
    private val height: Int,
    labels: List<String>,
    private val selectedIndex: () -> Int,
    onSelect: (Int) -> Unit,
) {

    companion object {
        /** Horizontal label padding inside each tab. */
        private const val TAB_PAD = Theme.LG
        private const val UNDERLINE_H = 2
    }

    private val tabs: List<TabWidget>

    init {
        val font = Minecraft.getInstance().font
        val count = labels.size.coerceAtLeast(1)
        val intrinsic = labels.map { font.width(it) + TAB_PAD * 2 }
        val widths =
            if (intrinsic.sum() <= width) intrinsic
            else List(count) { width / count }
        var cx = x
        tabs = labels.mapIndexed { index, label ->
            val tab = TabWidget(index, cx, y, widths[index], height, Component.literal(label), onSelect)
            cx += widths[index]
            tab
        }
    }

    /** Widgets the host screen must register with `addRenderableWidget`. */
    fun widgets(): List<AbstractWidget> = tabs

    /** Bottom edge of the bar - content should start below this. */
    val bottom: Int get() = y + height

    fun render(context: GuiGraphics) {
        // Baseline divider under the whole bar, with the active tab's accent
        // underline drawn over it.
        context.fill(x, y + height - 1, x + width, y + height, Theme.BORDER)
        tabs.getOrNull(selectedIndex())?.let { sel ->
            context.fill(sel.x, y + height - UNDERLINE_H, sel.x + sel.width, y + height, Theme.ACCENT)
        }
    }

    private inner class TabWidget(
        private val index: Int,
        tabX: Int,
        tabY: Int,
        tabWidth: Int,
        tabHeight: Int,
        label: Component,
        private val onSelect: (Int) -> Unit,
    ) : AbstractWidget(tabX, tabY, tabWidth, tabHeight, label) {

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
            if (index != selectedIndex()) {
                playDownSound(Minecraft.getInstance().soundManager)
            }
            onSelect(index)
        }

        // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
        //? if >=26.1 {
        /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        *///?} else {
        override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        //?}
            val active = index == selectedIndex()
            if (!active && isHovered) {
                context.fill(x, y, x + width, y + height - 1, Theme.withAlpha(Theme.SURFACE_HOVER, 0x66))
            }
            val color = when {
                active -> Theme.TEXT_PRIMARY
                isHovered -> Theme.TEXT_SECONDARY
                else -> Theme.TEXT_MUTED
            }
            val font = Minecraft.getInstance().font
            val textX = x + (width - font.width(message)) / 2
            val textY = y + (height - font.lineHeight + 1) / 2
            context.drawString(font, message, textX, textY, color, false)
        }

        override fun updateWidgetNarration(builder: NarrationElementOutput) {
            defaultButtonNarrationText(builder)
        }
    }
}
