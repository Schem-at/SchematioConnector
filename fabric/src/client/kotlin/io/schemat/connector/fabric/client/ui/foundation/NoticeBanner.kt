package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.fabric.client.ui.compat.*
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component

/**
 * A one-line notice banner styled to the design system: a tinted filled bar
 * with a 2px left accent stripe and a 1px tinted border. Kinds map to the
 * Theme status colors (error → danger, stale → warning, info → info) and may
 * carry an optional ghost Retry button.
 *
 * Usage: call [layout] from the host screen's `init()` and register the returned
 * button via `addRenderableWidget`; call [render] each frame; call [show]/[clear] to
 * change state (state survives screen re-init - layout only repositions).
 */
class NoticeBanner {

    enum class Kind(val tint: Int) {
        ERROR(Theme.DANGER),
        STALE(Theme.WARNING),
        INFO(Theme.INFO),
    }

    companion object {
        const val HEIGHT = 16
        private const val RETRY_WIDTH = 50
        private const val STRIPE_W = 2
    }

    private var kind: Kind = Kind.INFO
    private var message: String? = null
    private var onRetry: (() -> Unit)? = null

    private var x = 0
    private var y = 0
    private var width = 0
    private var retryButton: AbstractWidget? = null

    val isVisible: Boolean get() = message != null

    /**
     * Position the banner and (re)create the Retry button. The caller must register
     * the returned widget with `addRenderableWidget`.
     */
    fun layout(x: Int, y: Int, width: Int): AbstractWidget {
        this.x = x
        this.y = y
        this.width = width
        val button = FlatButton.ghost(
            x + width - RETRY_WIDTH - 2, y + 2, RETRY_WIDTH,
            Component.literal("Retry"), height = HEIGHT - 4,
        ) { onRetry?.invoke() }
        button.visible = message != null && onRetry != null
        retryButton = button
        return button
    }

    fun show(kind: Kind, message: String, onRetry: (() -> Unit)? = null) {
        this.kind = kind
        this.message = message
        this.onRetry = onRetry
        retryButton?.visible = onRetry != null
    }

    fun clear() {
        message = null
        onRetry = null
        retryButton?.visible = false
    }

    fun render(context: GuiGraphics, font: Font) {
        val msg = message ?: return
        val tint = kind.tint
        context.fill(x, y, x + width, y + HEIGHT, Theme.withAlpha(tint, 0x22))
        Theme.stroke(context, x, y, width, HEIGHT, Theme.withAlpha(tint, 0x55))
        context.fill(x, y, x + STRIPE_W, y + HEIGHT, tint)
        val textWidth = if (onRetry != null) width - RETRY_WIDTH - 12 else width - 10
        val trimmed = font.plainSubstrByWidth(msg, textWidth)
        context.drawString(font, trimmed, x + STRIPE_W + Theme.XS, y + (HEIGHT - font.lineHeight + 1) / 2, tint, false)
    }
}
