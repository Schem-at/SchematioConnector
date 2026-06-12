package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.fabric.client.ui.compat.*
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.gui.screens.Screen
import net.minecraft.util.FormattedCharSequence
import net.minecraft.network.chat.Component

/**
 * Design-system confirmation dialog rendered over a dimmed background: a
 * centered [Theme.panel] with a bold title, wrapped body copy, and a
 * Confirm + Cancel button row. Confirm returns to [parent] and then invokes
 * [onConfirm]; Cancel / ESC just returns to [parent].
 *
 * Pass [danger] = true for destructive confirms (delete / kick / revoke /
 * leave) - the confirm button renders as [FlatButton.danger] instead of
 * primary.
 */
class ConfirmDialogScreen(
    private val parent: Screen,
    title: String,
    private val message: String,
    private val confirmLabel: String = "Yes",
    private val danger: Boolean = false,
    private val onConfirm: () -> Unit,
) : Screen(Component.literal(title)) {

    companion object {
        private const val PANEL_WIDTH = 260
        private const val BUTTON_WIDTH = 96
        private const val LINE_H = 11
    }

    // Computed in init(), shared with render().
    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0
    private var messageLines: List<FormattedCharSequence> = emptyList()

    override fun init() {
        super.init()
        panelW = PANEL_WIDTH.coerceAtMost(width - Theme.XL * 2)
        messageLines = font.split(Component.literal(message), panelW - Theme.XL * 2)

        panelH = Theme.XL + font.lineHeight + Theme.LG +
            messageLines.size * LINE_H + Theme.XL + Theme.BTN_H + Theme.XL
        panelX = (width - panelW) / 2
        panelY = ((height - panelH) / 2).coerceAtLeast(Theme.XL)

        val buttonY = panelY + panelH - Theme.XL - Theme.BTN_H
        val cancelX = panelX + panelW / 2 - BUTTON_WIDTH - Theme.XS
        val confirmX = panelX + panelW / 2 + Theme.XS

        addRenderableWidget(FlatButton.ghost(cancelX, buttonY, BUTTON_WIDTH, Component.literal("Cancel")) { onClose() })
        val confirmAction: () -> Unit = {
            minecraft!!.setScreen(parent)
            onConfirm()
        }
        addRenderableWidget(
            if (danger) {
                FlatButton.danger(confirmX, buttonY, BUTTON_WIDTH, Component.literal(confirmLabel), onPress = confirmAction)
            } else {
                FlatButton.primary(confirmX, buttonY, BUTTON_WIDTH, Component.literal(confirmLabel), onPress = confirmAction)
            }
        )
    }

    // 26.x renamed the Screen render hooks: render -> extractRenderState,
    // renderBackground -> extractBackground.
    //? if >=26.1 {
    /*override fun extractBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
    *///?} else {
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.renderBackground(context, mouseX, mouseY, delta)
    //?}
        Theme.panel(context, panelX, panelY, panelW, panelH)
    }

    //? if >=26.1 {
    /*override fun extractRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
    *///?} else {
    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    //?}

        val centerX = width / 2
        val boldTitle = Component.literal(title.string).withStyle { it.withBold(true) }
        context.drawString(
            font, boldTitle,
            centerX - font.width(boldTitle) / 2, panelY + Theme.XL,
            if (danger) Theme.DANGER else Theme.TEXT_PRIMARY, false,
        )

        var lineY = panelY + Theme.XL + font.lineHeight + Theme.LG
        for (line in messageLines) {
            context.drawString(
                font, line,
                centerX - font.width(line) / 2, lineY,
                Theme.TEXT_SECONDARY, false,
            )
            lineY += LINE_H
        }
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }
}
