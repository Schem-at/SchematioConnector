package io.schemat.connector.fabric.client.ui.widgets.richtext

import imgui.ImFont
import imgui.ImGui
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.Companion.BOLD_OFFSET
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.Companion.BULLET_PREFIX
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.Companion.LINE_GAP
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.Companion.PAD
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.RChar
import kotlin.math.abs

/**
 * One visual (wrapped) row: document range [start, end), per-boundary x offsets
 * ([xs] has size end-start+1; `xs[i]` is the x of the boundary *before* char
 * start+i), [indent] for bullet hang, [bulletHead] to draw the glyph.
 */
internal class VLine(
    val start: Int,
    val end: Int,
    val indent: Float,
    val bulletHead: Boolean,
    val xs: FloatArray,
)

/**
 * Wrapped-line layout engine for [RichTextEditorWidget]: builds the [VLine] cache from
 * the widget's char document, and answers font-metric / hit-test queries against it.
 * Extracted verbatim from the widget; operates on the widget's state via [w].
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
    fun lineHeight(): Float = fontSize() + LINE_GAP

    /** Advance width of one char at the current font size (faux-bold adds [BOLD_OFFSET]). */
    private fun charWidth(rc: RChar): Float {
        if (rc.ch == '\n') return 0f
        val f = font()
        val scale = fontSize() / f.fontSize
        val glyph = f.findGlyph(rc.ch.code) ?: return 0f
        var w = glyph.advanceX * scale
        if (rc.bold) w += BOLD_OFFSET
        return w
    }

    private fun bulletWidth(): Float {
        val f = font()
        val scale = fontSize() / f.fontSize
        var w = 0f
        for (c in BULLET_PREFIX) {
            val g = f.findGlyph(c.code) ?: continue
            w += g.advanceX * scale
        }
        return w
    }

    private fun rebuildLayout(width: Float) {
        val lines = mutableListOf<VLine>()
        val innerW = (width - PAD * 2).coerceAtLeast(16f)
        val bulletIndent = bulletWidth()
        var ls = 0
        while (ls <= w.chars.size) {
            var le = ls
            while (le < w.chars.size && w.chars[le].ch != '\n') le++
            val bullet = ls < le && w.chars[ls].bullet
            val indent = if (bullet) bulletIndent else 0f
            val avail = (innerW - indent).coerceAtLeast(8f)
            val widths = FloatArray(le - ls) { charWidth(w.chars[ls + it]) }
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
                lines.add(VLine(i, segEnd, indent, bullet && first, xs))
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
        val row = ((mouseY - (originY + PAD)) / lineHeight()).toInt().coerceIn(0, w.lines.size - 1)
        val vl = w.lines[row]
        val target = mouseX - (originX + PAD) - vl.indent
        var best = 0
        for (k in vl.xs.indices) {
            if (abs(vl.xs[k] - target) < abs(vl.xs[best] - target)) best = k
        }
        return vl.start + best
    }
}
