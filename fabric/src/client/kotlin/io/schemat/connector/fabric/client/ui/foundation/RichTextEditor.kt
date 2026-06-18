package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.core.text.RichSpan
import io.schemat.connector.core.text.RichText
import io.schemat.connector.fabric.client.ui.theme.Theme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import org.lwjgl.glfw.GLFW

/**
 * Inline WYSIWYG rich-text editor: one surface where the user types and *sees*
 * bold/italic/underline/strikethrough/color exactly as it will publish - no raw
 * markup, no separate preview.
 *
 * The document is a flat list of styled characters ([RChar]); `'\n'` is a hard
 * line break (two in a row = paragraph break, matching the core span model).
 * Characters are laid out into word-wrapped visual lines, each rendered as
 * styled [Component] (links underlined in [Theme.INFO], bullets with a hanging "• "
 * indent). A blinking accent caret marks [cursor]; a translucent accent
 * highlight marks the selection. Vertical scrolling keeps the caret visible.
 *
 * Editing: typing inserts with the style at the caret (or the pending style the
 * toolbar toggled); arrows/Home/End/Up/Down move the caret (shift extends the
 * selection); Ctrl+A/C/X/V select all / copy / cut / paste (clipboard is plain
 * text); click places the caret, drag selects, double-click selects a word.
 *
 * Load/save via [setHtml]/[getHtml] (core `RichText.htmlToSpans`/`spansToHtml`,
 * sanitizer-allowlisted HTML). Bullets and links render and round-trip but have
 * no in-editor authoring UI.
 */
class RichTextEditor(
    private val font: Font,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val placeholder: String = "Description...",
) : AbstractWidget(x, y, width, height, Component.literal("Description")) {

    /** Called after every document mutation (typing, deletes, style changes, paste). */
    var onChanged: () -> Unit = {}

    enum class StyleFlag { BOLD, ITALIC, UNDERLINE, STRIKE }

    companion object {
        private const val PAD = Theme.XS
        private const val LINE_H = 11
        private const val MAX_LENGTH = 5000
        private const val BULLET_PREFIX = "• "
    }

    // ---- document model ----

    /** One styled character of the document; `'\n'` ends a logical line. */
    private data class RChar(
        val ch: Char,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val color: Int? = null,
        val link: String? = null,
        /** Set on the first character of a bullet line (line-level flag). */
        val bullet: Boolean = false,
    )

    /** The style a newly typed character receives (bullet handled separately). */
    private data class TypingStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val color: Int? = null,
        val link: String? = null,
    )

    private val chars = ArrayList<RChar>()
    private var cursor = 0

    /** Selection anchor (the non-moving end); -1 = no anchor. Selection = anchor..cursor. */
    private var selAnchor = -1

    /** Where the mouse was pressed, so a *drag* selects from the click point. */
    private var pressPos = 0

    /** Toolbar-toggled style for the next typed char; cleared when the caret moves. */
    private var pendingOverride: TypingStyle? = null

    private var scrollY = 0
    private var blinkBase = System.currentTimeMillis()

    // ---- layout (rebuilt on edit / on demand) ----

    /**
     * One visual (wrapped) row: document range [start, end) plus caret x offsets -
     * `xs[i]` is the x of the boundary before char `start + i` (size end-start+1).
     * [indent] hangs bullet continuation rows; [bulletHead] draws the glyph.
     */
    private class VLine(
        val start: Int,
        val end: Int,
        val indent: Int,
        val bulletHead: Boolean,
        val xs: IntArray,
    )

    private var layout: List<VLine> = emptyList()
    private var layoutDirty = true

    private fun styleKeyOf(rc: RChar) =
        TypingStyle(rc.bold, rc.italic, rc.underline, rc.strikethrough, rc.color, rc.link)

    private fun renderStyle(k: TypingStyle): Style {
        var s = Style.EMPTY
        if (k.bold) s = s.withBold(true)
        if (k.italic) s = s.withItalic(true)
        if (k.underline) s = s.withUnderlined(true)
        if (k.strikethrough) s = s.withStrikethrough(true)
        k.color?.let { s = s.withColor(TextColor.fromRgb(it and 0xFFFFFF)) }
        if (k.link != null) {
            s = s.withUnderlined(true)
            if (k.color == null) s = s.withColor(TextColor.fromRgb(Theme.INFO and 0xFFFFFF))
        }
        return s
    }

    private fun charWidth(rc: RChar): Int =
        font.width(Component.literal(rc.ch.toString()).setStyle(renderStyle(styleKeyOf(rc))))

    private fun rebuildLayout() {
        val lines = mutableListOf<VLine>()
        val innerW = (width - PAD * 2).coerceAtLeast(16)
        val bulletIndent = font.width(BULLET_PREFIX)
        var ls = 0
        while (ls <= chars.size) {
            var le = ls
            while (le < chars.size && chars[le].ch != '\n') le++
            val bullet = ls < le && chars[ls].bullet
            val indent = if (bullet) bulletIndent else 0
            val avail = (innerW - indent).coerceAtLeast(8)
            val widths = IntArray(le - ls) { charWidth(chars[ls + it]) }
            var i = ls
            var first = true
            while (true) {
                var w = 0
                var j = i
                var breakAt = -1
                while (j < le) {
                    val cw = widths[j - ls]
                    if (w + cw > avail && j > i) break
                    w += cw
                    if (chars[j].ch == ' ') breakAt = j + 1
                    j++
                }
                val segEnd = if (j >= le) le else if (breakAt > i) breakAt else j
                val xs = IntArray(segEnd - i + 1)
                var acc = 0
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
        layout = lines
        layoutDirty = false
        clampScroll()
    }

    private fun ensureLayout() {
        if (layoutDirty) rebuildLayout()
    }

    /** The visual row the caret position [pos] displays on. */
    private fun lineIndexAt(pos: Int): Int {
        ensureLayout()
        for ((idx, l) in layout.withIndex()) {
            if (pos < l.end) return idx
            // pos == end is this row only at a logical line end (else it is the
            // next wrapped row's start).
            if (pos == l.end && (l.end == chars.size || chars[l.end].ch == '\n')) return idx
        }
        return (layout.size - 1).coerceAtLeast(0)
    }

    /** Nearest character boundary to a mouse position (clamped into the document). */
    private fun hitTest(mouseX: Double, mouseY: Double): Int {
        ensureLayout()
        if (layout.isEmpty()) return 0
        val row = ((mouseY - (y + PAD) + scrollY) / LINE_H).toInt().coerceIn(0, layout.size - 1)
        val vl = layout[row]
        val target = (mouseX - (x + PAD) - vl.indent).toInt()
        var best = 0
        for (i in vl.xs.indices) {
            if (abs(vl.xs[i] - target) < abs(vl.xs[best] - target)) best = i
        }
        return vl.start + best
    }

    // ---- scrolling ----

    private fun clampScroll() {
        val innerH = (height - PAD * 2).coerceAtLeast(LINE_H)
        val contentH = layout.size * LINE_H
        scrollY = scrollY.coerceIn(0, (contentH - innerH).coerceAtLeast(0))
    }

    private fun ensureCaretVisible() {
        ensureLayout()
        val innerH = (height - PAD * 2).coerceAtLeast(LINE_H)
        val top = lineIndexAt(cursor) * LINE_H
        if (top < scrollY) scrollY = top
        else if (top + LINE_H > scrollY + innerH) scrollY = top + LINE_H - innerH
        clampScroll()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false
        ensureLayout()
        scrollY -= (verticalAmount * LINE_H * 2).toInt()
        clampScroll()
        return true
    }

    // ---- selection helpers ----

    private fun hasSelection() = selAnchor >= 0 && selAnchor != cursor
    private fun selMin() = min(selAnchor, cursor)
    private fun selMax() = max(selAnchor, cursor)

    private fun resetBlink() {
        blinkBase = System.currentTimeMillis()
    }

    // ---- editing primitives ----

    private fun edited() {
        // A mutation can shrink the document past a stale caret/anchor (e.g. the
        // anchor a click left at the old document end). Re-clamp both and drop a
        // collapsed selection so the bounds can never overrun `chars` on the next
        // render — isStyleActive/toggleStyle/render all index chars[selMin until selMax].
        cursor = cursor.coerceIn(0, chars.size)
        if (selAnchor > chars.size) selAnchor = chars.size
        if (selAnchor == cursor) selAnchor = -1
        layoutDirty = true
        resetBlink()
        ensureCaretVisible()
        onChanged()
    }

    private fun deleteSelection(): Boolean {
        if (!hasSelection()) return false
        val a = selMin()
        val b = selMax()
        repeat(b - a) { chars.removeAt(a) }
        cursor = a
        selAnchor = -1
        edited()
        return true
    }

    /** Style for the next typed character: toolbar override, else the caret's context. */
    private fun typingStyle(): TypingStyle = pendingOverride ?: derivedStyle()

    private fun derivedStyle(): TypingStyle {
        if (hasSelection()) {
            val first = chars.getOrNull(selMin())?.takeIf { it.ch != '\n' } ?: return TypingStyle()
            return styleKeyOf(first).copy(link = null)
        }
        val before = chars.getOrNull(cursor - 1)?.takeIf { it.ch != '\n' }
        val after = chars.getOrNull(cursor)?.takeIf { it.ch != '\n' }
        val basis = before ?: after ?: return TypingStyle()
        // Only keep extending a link when typing strictly inside it.
        val link = if (before?.link != null && before.link == after?.link) before.link else null
        return styleKeyOf(basis).copy(link = link)
    }

    private fun logicalLineStart(pos: Int): Int {
        var i = pos
        while (i > 0 && chars[i - 1].ch != '\n') i--
        return i
    }

    /** Inserting at a bullet line's head keeps the line a bullet. */
    private fun bulletForInsert(c: Char): Boolean {
        if (c == '\n') return false
        val ls = logicalLineStart(cursor)
        return cursor == ls && chars.getOrNull(ls)?.let { it.ch != '\n' && it.bullet } == true
    }

    private fun insert(c: Char) {
        if (hasSelection()) deleteSelection()
        if (chars.size >= MAX_LENGTH) return
        val st = typingStyle()
        chars.add(cursor, RChar(c, st.bold, st.italic, st.underline, st.strikethrough, st.color, st.link, bulletForInsert(c)))
        cursor++
        edited()
    }

    private fun backspace() {
        if (deleteSelection()) return
        if (cursor > 0) {
            chars.removeAt(cursor - 1)
            cursor--
            edited()
        }
    }

    private fun forwardDelete() {
        if (deleteSelection()) return
        if (cursor < chars.size) {
            chars.removeAt(cursor)
            edited()
        }
    }

    private fun placeCursor(target: Int, extend: Boolean) {
        if (extend) {
            if (selAnchor < 0) selAnchor = cursor
        } else {
            selAnchor = -1
        }
        cursor = target.coerceIn(0, chars.size)
        if (selAnchor == cursor) selAnchor = -1 // a collapsed selection is no selection
        pendingOverride = null
        resetBlink()
        ensureCaretVisible()
    }

    private fun moveVertical(direction: Int, extend: Boolean) {
        ensureLayout()
        if (layout.isEmpty()) return
        val li = lineIndexAt(cursor)
        val target = li + direction
        if (target !in layout.indices) {
            placeCursor(if (direction < 0) 0 else chars.size, extend)
            return
        }
        val vl = layout[li]
        val xRel = vl.indent + vl.xs[(cursor - vl.start).coerceIn(0, vl.xs.size - 1)]
        val tl = layout[target]
        val tx = xRel - tl.indent
        var best = 0
        for (i in tl.xs.indices) {
            if (abs(tl.xs[i] - tx) < abs(tl.xs[best] - tx)) best = i
        }
        // Avoid landing on a wrap boundary (it would display one row further).
        if (best == tl.xs.size - 1 && tl.end < chars.size && chars[tl.end].ch != '\n' && best > 0) best--
        placeCursor(tl.start + best, extend)
    }

    private fun copySelection() {
        if (!hasSelection()) return
        val text = buildString { for (i in selMin() until selMax()) append(chars[i].ch) }
        Minecraft.getInstance().keyboardHandler.setClipboard(text)
    }

    private fun paste() {
        val clip = Minecraft.getInstance().keyboardHandler.clipboard
            .replace("\r\n", "\n").replace('\r', '\n')
        if (clip.isEmpty()) return
        if (hasSelection()) deleteSelection()
        val st = typingStyle()
        var inserted = false
        for (c in clip) {
            if (chars.size >= MAX_LENGTH) break
            if (c != '\n' && c.code < 0x20) continue
            chars.add(cursor, RChar(c, st.bold, st.italic, st.underline, st.strikethrough, st.color, st.link, false))
            cursor++
            inserted = true
        }
        if (inserted) edited()
    }

    private fun selectWordAt(pos: Int) {
        fun isWord(c: Char) = !c.isWhitespace()
        var a = pos.coerceIn(0, chars.size)
        var b = a
        while (a > 0 && chars[a - 1].ch != '\n' && isWord(chars[a - 1].ch)) a--
        while (b < chars.size && chars[b].ch != '\n' && isWord(chars[b].ch)) b++
        selAnchor = a
        cursor = b
        resetBlink()
    }

    // ---- toolbar API ----

    private fun flagOf(k: TypingStyle, flag: StyleFlag): Boolean = when (flag) {
        StyleFlag.BOLD -> k.bold
        StyleFlag.ITALIC -> k.italic
        StyleFlag.UNDERLINE -> k.underline
        StyleFlag.STRIKE -> k.strikethrough
    }

    private fun withFlag(rc: RChar, flag: StyleFlag, value: Boolean): RChar = when (flag) {
        StyleFlag.BOLD -> rc.copy(bold = value)
        StyleFlag.ITALIC -> rc.copy(italic = value)
        StyleFlag.UNDERLINE -> rc.copy(underline = value)
        StyleFlag.STRIKE -> rc.copy(strikethrough = value)
    }

    private fun withFlag(k: TypingStyle, flag: StyleFlag, value: Boolean): TypingStyle = when (flag) {
        StyleFlag.BOLD -> k.copy(bold = value)
        StyleFlag.ITALIC -> k.copy(italic = value)
        StyleFlag.UNDERLINE -> k.copy(underline = value)
        StyleFlag.STRIKE -> k.copy(strikethrough = value)
    }

    /**
     * Toggle [flag] on the selection (set when any selected char lacks it, cleared
     * when all have it) or, with no selection, on the pending typing style.
     */
    fun toggleStyle(flag: StyleFlag) {
        if (hasSelection()) {
            val a = selMin()
            val b = selMax()
            val all = (a until b).all { chars[it].ch == '\n' || flagOf(styleKeyOf(chars[it]), flag) }
            for (i in a until b) {
                if (chars[i].ch != '\n') chars[i] = withFlag(chars[i], flag, !all)
            }
            edited()
        } else {
            pendingOverride = withFlag(typingStyle(), flag, !flagOf(typingStyle(), flag))
            resetBlink()
        }
    }

    /** True when the whole selection (or the pending typing style) carries [flag]. */
    fun isStyleActive(flag: StyleFlag): Boolean {
        if (hasSelection()) {
            val sel = (selMin() until selMax()).filter { chars[it].ch != '\n' }
            return sel.isNotEmpty() && sel.all { flagOf(styleKeyOf(chars[it]), flag) }
        }
        return flagOf(typingStyle(), flag)
    }

    /** Apply [color] (null = theme default) to the selection or the pending style. */
    fun setColor(color: Int?) {
        if (hasSelection()) {
            for (i in selMin() until selMax()) {
                if (chars[i].ch != '\n') chars[i] = chars[i].copy(color = color)
            }
            edited()
        } else {
            pendingOverride = typingStyle().copy(color = color)
            resetBlink()
        }
    }

    /** The color the selection start (or the pending typing style) carries. */
    fun activeColor(): Int? =
        if (hasSelection()) chars.getOrNull(selMin())?.color else typingStyle().color

    // ---- document <-> HTML ----

    /** Load website HTML, replacing the document; caret moves to the start. */
    fun setHtml(html: String) {
        chars.clear()
        for (span in RichText.htmlToSpans(html)) {
            span.text.forEachIndexed { i, c ->
                chars.add(
                    RChar(
                        c, span.bold, span.italic, span.underline, span.strikethrough,
                        span.color, span.link, bullet = span.bullet && i == 0,
                    )
                )
            }
        }
        cursor = 0
        selAnchor = -1
        scrollY = 0
        pendingOverride = null
        layoutDirty = true
    }

    fun isEmpty(): Boolean = chars.none { !it.ch.isWhitespace() }

    /** The document as sanitizer-safe website HTML ("" when only whitespace). */
    fun getHtml(): String = if (isEmpty()) "" else RichText.spansToHtml(getSpans())

    /** The document as core spans (adjacent same-style chars merged), for previews. */
    fun getSpans(): List<RichSpan> {
        val spans = mutableListOf<RichSpan>()
        if (chars.isEmpty()) return spans
        val plain = TypingStyle()
        var runKey: TypingStyle? = null
        var runBullet = false
        val sb = StringBuilder()
        fun flush() {
            val k = runKey ?: return
            if (sb.isNotEmpty()) {
                spans.add(RichSpan(sb.toString(), k.bold, k.italic, k.underline, k.strikethrough, runBullet, k.color, k.link))
                sb.clear()
            }
            runBullet = false
        }
        chars.forEachIndexed { i, rc ->
            val lineStart = i == 0 || chars[i - 1].ch == '\n'
            // Newlines carry no formatting in the span model.
            val key = if (rc.ch == '\n') plain else styleKeyOf(rc)
            val bulletStart = lineStart && rc.ch != '\n' && rc.bullet
            if (key != runKey || bulletStart) {
                flush()
                runKey = key
                if (bulletStart) runBullet = true
            }
            sb.append(rc.ch)
        }
        flush()
        return spans
    }

    // ---- input ----

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
        ensureLayout()
        val pos = hitTest(click.x(), click.y())
        if (doubled) {
            selectWordAt(pos)
            return
        }
        if (click.hasShiftDown()) {
            if (selAnchor < 0) selAnchor = cursor
        } else {
            // A plain click places the caret with NO selection. Leaving a stale
            // anchor here (== cursor) is invisible until the next edit moves the
            // caret off it, resurrecting a phantom selection whose bound can then
            // overrun `chars` (end-of-text backspace → render crash). A drag
            // re-establishes the anchor from pressPos.
            selAnchor = -1
        }
        cursor = pos
        pressPos = pos
        pendingOverride = null
        resetBlink()
    }

    // 1.21.9 changed input handlers to take event objects; on 1.21.8 the
    // primitive override delegates to the event-shaped method (compat shim).
    //? if <1.21.9 {
    /*override fun onDrag(clickX: Double, clickY: Double, deltaX: Double, deltaY: Double) {
        onDrag(MouseButtonEvent(clickX, clickY), deltaX, deltaY)
    }
    *///?}
    //? if >=1.21.9
    override
    fun onDrag(click: MouseButtonEvent, deltaX: Double, deltaY: Double) {
        ensureLayout()
        // First drag movement after a plain click opens a selection from the
        // press point (the click cleared selAnchor).
        if (selAnchor < 0) selAnchor = pressPos
        cursor = hitTest(click.x(), click.y())
        resetBlink()
        ensureCaretVisible()
    }

    // 1.21.9 changed input handlers to take event objects; on 1.21.8 the
    // primitive override delegates to the event-shaped method (compat shim).
    //? if <1.21.9 {
    /*override fun charTyped(chr: Char, modifiers: Int): Boolean {
        return charTyped(CharacterEvent(chr.code, modifiers))
    }
    *///?}
    //? if >=1.21.9
    override
    fun charTyped(input: CharacterEvent): Boolean {
        if (!isFocused || !active || !visible) return false
        if (!input.isAllowedChatCharacter()) return false
        for (c in input.codepointAsString()) {
            if (c.code >= 0x20) insert(c)
        }
        return true
    }

    // 1.21.9 changed input handlers to take event objects; on 1.21.8 the
    // primitive override delegates to the event-shaped method (compat shim).
    //? if <1.21.9 {
    /*override fun keyPressed(key: Int, scanCode: Int, modifiers: Int): Boolean {
        return keyPressed(KeyEvent(key, scanCode, modifiers))
    }
    *///?}
    //? if >=1.21.9
    override
    fun keyPressed(input: KeyEvent): Boolean {
        if (!isFocused || !active || !visible) return false
        when {
            input.isSelectAll() -> {
                selAnchor = 0
                cursor = chars.size
                resetBlink()
            }
            input.isCopy() -> copySelection()
            input.isCut() -> {
                copySelection()
                deleteSelection()
            }
            input.isPaste() -> paste()
            input.isConfirmation() -> insert('\n')
            else -> when (input.key()) {
                GLFW.GLFW_KEY_BACKSPACE -> backspace()
                GLFW.GLFW_KEY_DELETE -> forwardDelete()
                GLFW.GLFW_KEY_LEFT ->
                    if (hasSelection() && !input.hasShiftDown()) placeCursor(selMin(), false)
                    else placeCursor(cursor - 1, input.hasShiftDown())
                GLFW.GLFW_KEY_RIGHT ->
                    if (hasSelection() && !input.hasShiftDown()) placeCursor(selMax(), false)
                    else placeCursor(cursor + 1, input.hasShiftDown())
                GLFW.GLFW_KEY_UP -> moveVertical(-1, input.hasShiftDown())
                GLFW.GLFW_KEY_DOWN -> moveVertical(1, input.hasShiftDown())
                GLFW.GLFW_KEY_HOME -> placeCursor(layout.getOrNull(lineIndexAt(cursor))?.start ?: 0, input.hasShiftDown())
                GLFW.GLFW_KEY_END -> placeCursor(layout.getOrNull(lineIndexAt(cursor))?.end ?: chars.size, input.hasShiftDown())
                else -> return false
            }
        }
        return true
    }

    // ---- rendering ----

    // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
    //? if >=26.1 {
    /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    *///?} else {
    override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    //?}
        ensureLayout()
        context.fill(x, y, x + width, y + height, Theme.SURFACE_ALT)
        context.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1)

        val baseX = x + PAD
        val baseY = y + PAD - scrollY

        if (chars.isEmpty() && !isFocused) {
            context.drawString(
                font,
                font.plainSubstrByWidth(placeholder, width - PAD * 2),
                baseX, y + PAD, Theme.TEXT_FAINT, false,
            )
        }

        val selecting = hasSelection()
        val a = if (selecting) selMin() else 0
        val b = if (selecting) selMax() else 0
        val fontH = font.lineHeight

        layout.forEachIndexed { li, vl ->
            val ry = baseY + li * LINE_H
            if (ry + LINE_H < y || ry > y + height) return@forEachIndexed

            if (selecting && a <= vl.end && b >= vl.start) {
                val sa = a.coerceAtLeast(vl.start)
                val sb = b.coerceAtMost(vl.end)
                if (sa <= sb) {
                    val x1 = baseX + vl.indent + vl.xs[sa - vl.start]
                    var x2 = baseX + vl.indent + vl.xs[sb - vl.start]
                    if (b > vl.end) x2 += 3 // show the selected line break
                    if (x2 > x1) {
                        context.fill(x1, ry - 1, x2, ry + fontH + 1, Theme.withAlpha(Theme.ACCENT, 0x44))
                    }
                }
            }

            if (vl.bulletHead) {
                context.drawString(font, BULLET_PREFIX, baseX, ry, Theme.TEXT_SECONDARY, false)
            }
            lineText(vl)?.let { text ->
                context.drawString(font, text, baseX + vl.indent, ry, Theme.TEXT_PRIMARY, false)
            }
        }

        if (isFocused && layout.isNotEmpty() && caretBlinkOn()) {
            val li = lineIndexAt(cursor)
            val vl = layout[li]
            val cx = baseX + vl.indent + vl.xs[(cursor - vl.start).coerceIn(0, vl.xs.size - 1)]
            val cy = baseY + li * LINE_H
            context.fill(cx, cy - 1, cx + 1, cy + fontH + 1, Theme.ACCENT)
        }

        context.disableScissor()
    }

    private fun caretBlinkOn(): Boolean = ((System.currentTimeMillis() - blinkBase) / 500L) % 2L == 0L

    /** The row's characters as one styled [Component] (consecutive same-style runs merged). */
    private fun lineText(vl: VLine): MutableComponent? {
        if (vl.start >= vl.end) return null
        val root = Component.empty()
        var runKey = styleKeyOf(chars[vl.start])
        val sb = StringBuilder()
        for (i in vl.start until vl.end) {
            val key = styleKeyOf(chars[i])
            if (key != runKey) {
                root.append(Component.literal(sb.toString()).setStyle(renderStyle(runKey)))
                sb.clear()
                runKey = key
            }
            sb.append(chars[i].ch)
        }
        root.append(Component.literal(sb.toString()).setStyle(renderStyle(runKey)))
        return root
    }

    override fun updateWidgetNarration(builder: NarrationElementOutput) {
        defaultButtonNarrationText(builder)
    }
}
