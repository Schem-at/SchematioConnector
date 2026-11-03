package io.schemat.connector.fabric.client.ui.widgets.richtext

import imgui.ImFont
import imgui.ImGui
import io.schemat.connector.core.text.RichDocument.ListKind
import io.schemat.connector.fabric.client.ui.theme.Fonts
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.Companion.BOLD_OFFSET
import io.schemat.connector.core.text.RichGlyphs
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.Companion.LINE_GAP
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.Companion.QUOTE_INDENT
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.RChar
import kotlin.math.abs

/**
 * One visual (wrapped) row: document range [start, end), per-boundary x offsets
 * ([xs] has size end-start+1; `xs[i]` is the x of the boundary *before* char
 * start+i), [indent] for the list/quote hang, [prefix] ("• " or "3. ") drawn on the
 * first row of a list item, [y]/[height] relative to the content top, and the [font]
 * and [size] the row is drawn in (headings use a larger face).
 */
internal class VLine(
    val start: Int,
    val end: Int,
    val indent: Float,
    val prefix: String?,
    val xs: FloatArray,
    val y: Float,
    val height: Float,
    val font: ImFont,
    val size: Float,
    val quote: Boolean,
)

/**
 * Wrapped-line layout engine for [RichTextEditorWidget]: builds the [VLine] cache from
 * the widget's char document, and answers font-metric / hit-test queries against it.
 * Operates on the widget's state via [w].
 */
internal class RichTextLayout(private val w: RichTextEditorWidget) {

    /** Width the current [RichTextEditorWidget.lines] cache was built for. */
    private var layoutWidth = -1f

    fun ensureLayout(width: Float) {
        if (!w.layoutDirty && width == layoutWidth) return
        rebuildLayout(width)
    }

    fun font(): ImFont = ImGui.getFont()
    // ImGui.getFontSize() returns int in this binding; the editor measures in float px.
    fun fontSize(): Float = ImGui.getFontSize().toFloat()

    /** Total height of the laid-out content, excluding padding. */
    fun contentHeight(): Float = w.lines.lastOrNull()?.let { it.y + it.height } ?: 0f

    /** The face and pixel size for a line: h2 = H2, h3 = SemiBold, body = the current font. */
    private fun faceFor(heading: Int): Pair<ImFont, Float> {
        val body = font()
        // Global font scale, so headings scale with the body text.
        val ratio = fontSize() / body.fontSize
        val face = when (heading) {
            2 -> Fonts.H2
            3 -> Fonts.SEMIBOLD
            else -> null
        } ?: return body to fontSize()
        // Whole pixels: ImDrawList.addText takes an int size.
        return face to Math.round(face.fontSize * ratio).toFloat()
    }

    /**
     * Advance width of one char in [font] at [size]. Bold does not widen it: the faux-bold
     * pass redraws the whole run [BOLD_OFFSET] px to the right, so adding the offset per char
     * opened a gap after every bold run ("Bold ,").
     */
    private fun charWidth(rc: RChar, font: ImFont, size: Float): Float {
        if (rc.ch == '\n') return 0f
        val glyph = font.findGlyph(rc.ch.code) ?: return 0f
        return glyph.advanceX * size / font.fontSize
    }

    fun textWidth(text: String, font: ImFont, size: Float): Float {
        var w = 0f
        for (c in text) {
            val g = font.findGlyph(c.code) ?: continue
            w += g.advanceX * size / font.fontSize
        }
        return w
    }

    private fun rebuildLayout(width: Float) {
        val lines = mutableListOf<VLine>()
        val innerW = (width - w.pad * 2).coerceAtLeast(16f)
        var y = 0f
        var number = 0
        var ls = 0
        while (ls <= w.chars.size) {
            var le = ls
            while (le < w.chars.size && w.chars[le].ch != '\n') le++
            val format = if (ls < le) w.chars[ls].line else null
            val (face, size) = faceFor(format?.heading ?: 0)
            number = if (format?.list == ListKind.ORDERED) number + 1 else 0
            val prefix = when (format?.list) {
                ListKind.BULLET -> RichGlyphs.bulletPrefix { FontGlyphs.has(face, it) }
                ListKind.ORDERED -> "$number. "
                else -> null
            }
            val quote = format?.quote == true && prefix == null
            val indent = (if (quote) QUOTE_INDENT else 0f) + (prefix?.let { textWidth(it, face, size) } ?: 0f)
            val rowH = size + LINE_GAP
            val avail = (innerW - indent).coerceAtLeast(8f)
            val widths = FloatArray(le - ls) { charWidth(w.chars[ls + it], face, size) }
            var i = ls
            var first = true
            while (true) {
                var wAcc = 0f
                var j = i
                var breakAt = -1
                while (j < le) {
                    val cw = widths[j - ls]
                    if (wAcc + cw > avail && j > i) break
                    wAcc += cw
                    if (w.chars[j].ch == ' ') breakAt = j + 1
                    j++
                }
                val segEnd = if (j >= le) le else if (breakAt > i) breakAt else j
                val xs = FloatArray(segEnd - i + 1)
                var acc = 0f
                for (k in i until segEnd) {
                    acc += widths[k - ls]
                    xs[k - i + 1] = acc
                }
                lines.add(VLine(i, segEnd, indent, prefix.takeIf { first }, xs, y, rowH, face, size, quote))
                y += rowH
                first = false
                if (segEnd >= le) break
                i = segEnd
            }
            ls = le + 1
        }
        w.lines = lines
        w.layoutDirty = false
        layoutWidth = width
    }

    fun lineIndexAt(pos: Int): Int {
        for ((idx, l) in w.lines.withIndex()) {
            if (pos < l.end) return idx
            if (pos == l.end && (l.end == w.chars.size || w.chars[l.end].ch == '\n')) return idx
        }
        return (w.lines.size - 1).coerceAtLeast(0)
    }

    /** Nearest character boundary to a screen mouse position (clamped into the document). */
    fun hitTest(mouseX: Float, mouseY: Float, originX: Float, originY: Float): Int {
        if (w.lines.isEmpty()) return 0
        val relY = mouseY - (originY + w.pad)
        val vl = w.lines.firstOrNull { relY < it.y + it.height } ?: w.lines.last()
        val target = mouseX - (originX + w.pad) - vl.indent
        var best = 0
        for (k in vl.xs.indices) {
            if (abs(vl.xs[k] - target) < abs(vl.xs[best] - target)) best = k
        }
        return vl.start + best
    }

    /** The char under a screen position, or null when the position is past the row's text. */
    fun charAt(mouseX: Float, mouseY: Float, originX: Float, originY: Float): Int? {
        val relY = mouseY - (originY + w.pad)
        val vl = w.lines.firstOrNull { relY >= it.y && relY < it.y + it.height } ?: return null
        val x = mouseX - (originX + w.pad) - vl.indent
        for (k in 0 until vl.xs.size - 1) {
            if (x >= vl.xs[k] && x < vl.xs[k + 1]) return vl.start + k
        }
        return null
    }
}
