package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.core.text.RichSpan
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.compat.*
import java.net.URI
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

/**
 * Render core [RichSpan]s as styled Minecraft text: formatting flags map onto
 * [Style] (bold/italic/underline/strikethrough), [RichSpan.color] overrides the
 * theme text color, and link spans render underlined in [Theme.INFO] with a
 * [ClickEvent.OpenUrl] + hover attached, so they are clickable wherever the
 * result is used as chat/widget [Component] ([drawWrapped] still shows link color +
 * underline, just without click hit-testing). `\n` inside span text breaks
 * lines and bullet spans get a "• " prefix with a hanging indent, so wrapped
 * bullet lines align under their own text instead of under the bullet glyph.
 * Drawing is shadow-free in the [Theme] palette, matching the rest of the
 * design system.
 */
object RichTextRender {

    private const val BULLET_PREFIX = "• "

    /** A logical (unwrapped) line: its styled text, and whether it is a bullet item. */
    private data class LogicalLine(val text: MutableComponent, val bullet: Boolean)

    /** The spans as one [Component], newlines kept literal (for narration / simple uses). */
    fun toText(spans: List<RichSpan>): Component {
        val root = Component.empty()
        toLines(spans).forEachIndexed { index, line ->
            if (index > 0) root.append("\n")
            root.append(line)
        }
        return root
    }

    /** The spans split into logical (unwrapped) lines, bullets prefixed, styles applied. */
    fun toLines(spans: List<RichSpan>): List<MutableComponent> =
        logicalLines(spans).map { line ->
            if (line.bullet) Component.empty().append(BULLET_PREFIX).append(line.text) else line.text
        }

    /** Logical lines with the bullet kept as a flag (not baked into the text). */
    private fun logicalLines(spans: List<RichSpan>): List<LogicalLine> {
        val lines = mutableListOf<LogicalLine>()
        var current = Component.empty()
        var bullet = false
        var empty = true
        for (span in spans) {
            val style = styleOf(span)
            span.text.split("\n").forEachIndexed { index, part ->
                if (index > 0) {
                    lines.add(LogicalLine(current, bullet))
                    current = Component.empty()
                    bullet = false
                    empty = true
                }
                if (span.bullet && index == 0 && empty) {
                    bullet = true
                    empty = false
                }
                if (part.isNotEmpty()) {
                    current.append(Component.literal(part).setStyle(style))
                    empty = false
                }
            }
        }
        if (!empty || lines.isNotEmpty()) lines.add(LogicalLine(current, bullet))
        return lines
    }

    /**
     * Word-wrap the spans into [maxWidth] and draw them at ([x], [y]); empty logical
     * lines (paragraph gaps) consume one [lineHeight]. Bullet lines draw a "• " glyph
     * and hang-indent their wrapped continuation rows. Lines past [maxY] are skipped.
     * Returns the height used, so callers can size/flow the description area.
     */
    fun drawWrapped(
        ctx: GuiGraphics,
        font: Font,
        spans: List<RichSpan>,
        x: Int,
        y: Int,
        maxWidth: Int,
        lineHeight: Int = 10,
        color: Int = Theme.TEXT_SECONDARY,
        maxY: Int = Int.MAX_VALUE,
    ): Int {
        var dy = y
        val width = maxWidth.coerceAtLeast(8)
        val bulletIndent = font.width(BULLET_PREFIX)
        outer@ for (line in logicalLines(spans)) {
            if (line.text.string.isEmpty() && !line.bullet) {
                dy += lineHeight
                continue
            }
            val indent = if (line.bullet) bulletIndent else 0
            val wrapped = font.split(line.text, (width - indent).coerceAtLeast(8))
            if (wrapped.isEmpty()) {
                // A bare bullet ("- " with no text yet) still shows its glyph.
                if (dy > maxY) break@outer
                if (line.bullet) ctx.drawString(font, BULLET_PREFIX, x, dy, color, false)
                dy += lineHeight
                continue
            }
            wrapped.forEachIndexed { row, rendered ->
                if (dy > maxY) return@forEachIndexed
                if (line.bullet && row == 0) {
                    ctx.drawString(font, BULLET_PREFIX, x, dy, color, false)
                }
                ctx.drawString(font, rendered, x + indent, dy, color, false)
                dy += lineHeight
            }
            if (dy > maxY) break@outer
        }
        return dy - y
    }

    /**
     * The height [drawWrapped] would use for [spans] at [maxWidth] (no clipping),
     * so callers can scroll/anchor the preview before drawing.
     */
    fun measureHeight(
        font: Font,
        spans: List<RichSpan>,
        maxWidth: Int,
        lineHeight: Int = 10,
    ): Int {
        var h = 0
        val width = maxWidth.coerceAtLeast(8)
        val bulletIndent = font.width(BULLET_PREFIX)
        for (line in logicalLines(spans)) {
            if (line.text.string.isEmpty() && !line.bullet) {
                h += lineHeight
                continue
            }
            val indent = if (line.bullet) bulletIndent else 0
            val rows = font.split(line.text, (width - indent).coerceAtLeast(8))
                .size.coerceAtLeast(1)
            h += rows * lineHeight
        }
        return h
    }

    private fun styleOf(span: RichSpan): Style {
        var style = Style.EMPTY
        if (span.bold) style = style.withBold(true)
        if (span.italic) style = style.withItalic(true)
        if (span.underline) style = style.withUnderlined(true)
        if (span.strikethrough) style = style.withStrikethrough(true)
        // HTML-set color overrides the theme default the caller draws with.
        span.color?.let { style = style.withColor(TextColor.fromRgb(it and 0xFFFFFF)) }
        span.link?.let { url ->
            // Links read as links: underlined, info accent unless the HTML set a color.
            style = style.withUnderlined(true)
            if (span.color == null) style = style.withColor(TextColor.fromRgb(Theme.INFO and 0xFFFFFF))
            runCatching { URI(url) }.getOrNull()?.let { uri ->
                style = style
                    .withClickEvent(ClickEvent.OpenUrl(uri))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal(url)))
            }
        }
        return style
    }
}
