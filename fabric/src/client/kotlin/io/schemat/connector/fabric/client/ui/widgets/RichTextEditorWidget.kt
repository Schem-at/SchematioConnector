package io.schemat.connector.fabric.client.ui.widgets
import io.schemat.connector.fabric.client.ui.theme.argbToImVec4

import imgui.ImGui
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import io.schemat.connector.core.text.RichSpan
import io.schemat.connector.core.text.RichText
import dev.harrison.panellib.framework.ImGuiGl3Renderer
import io.schemat.connector.fabric.client.ui.framework.ImGuiManager
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.widgets.richtext.RichTextLayout
import io.schemat.connector.fabric.client.ui.widgets.richtext.RichTextToolbar
import io.schemat.connector.fabric.client.ui.widgets.richtext.VLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A **true inline WYSIWYG** rich-text editor drawn as a Dear ImGui draw-list widget.
 *
 * This replaces the old markup-source [RichTextWidget]: instead of editing markdown-ish
 * markup with a read-only preview, the user types directly into the formatted text. The
 * per-character model, wrapped-line layout, caret/selection and keyboard navigation are
 * ported from the old vanilla `RichTextEditor` (see git `c3424f2^`); only rendering and
 * input are reimplemented against imgui-java 1.89.0's restricted API.
 *
 * ### Rendering (imgui-java 1.89.0 — no `prim*`, no bold/italic font)
 * Only one TTF is loaded (Inter-Regular in [ImGuiManager.loadFonts]); there are no bold
 * or italic font files. So formatting is faked on the draw list:
 * - **Bold** = the run is drawn twice via `ImDrawList.addText`, the second pass offset
 *   `+`[BOLD_OFFSET]`px` in x to thicken the strokes.
 * - **Italic** = a faux shear: each glyph is drawn with `addImageQuad` from the font
 *   atlas, with the two *top* corners shifted right by `slant * (glyph height above
 *   baseline)` ([ITALIC_SLANT]). Bold+italic does the shear twice (offset pass).
 * - **Underline / strike** = a thin `addLine` under / through the run.
 * - **Color** = the run's text color (default = [Theme.TEXT_PRIMARY]).
 *
 * Selection is a translucent `addRectFilled`; the caret is a 1px `addRectFilled` that
 * blinks. The font atlas texture id comes from [ImGuiGl3Renderer.fontTextureId] (our
 * GL3 renderer owns it; `ImFontAtlas` has no getTexID in this binding).
 *
 * ### Input
 * imgui-java has no read API for typed characters, so [ImGuiManager.drainTypedChars]
 * (fed by `KeyboardMixin -> charCallback`) is drained each frame while focused. Edit/nav
 * keys use `ImGui.isKeyPressed(..., repeat=true)`; modifiers via the IO ctrl/shift/super
 * flags; clipboard via `ImGui.getClipboardText` / `setClipboardText`. Mouse hit-testing
 * uses an `invisibleButton` surface + `getMousePosX/Y` against the laid-out per-char xs.
 *
 * Load/save round-trips website HTML through the shared [RichText] parser:
 * [setHtml] (`htmlToSpans`) and [toHtml] (`spansToHtml`).
 *
 * Lives under `ui/imgui/` (not `ui/imgui/panels/`), so it is exempt from
 * `checkThemeDiscipline`; it still sources all colors from [Theme] for consistency.
 *
 * @param id stable id; namespaces the `##` ImGui control ids so the upload and edit
 *           panels' editors never collide.
 */
class RichTextEditorWidget(internal val id: String) {

    // ---- supported styles (mirrors RichSpan's editable fields) ----
    enum class StyleFlag { BOLD, ITALIC, UNDERLINE, STRIKE }

    companion object {
        private const val MAX_LENGTH = 5000
        internal const val BULLET_PREFIX = "• "
        /** Faux-bold thickening: second addText pass offset, in px. */
        internal const val BOLD_OFFSET = 0.6f
        /** Faux-italic shear factor (x-shift per px of glyph height above baseline). */
        private const val ITALIC_SLANT = 0.21f
        internal const val PAD = 6f
        /** Extra leading between visual rows (px), on top of the font line height. */
        internal const val LINE_GAP = 2f
        private const val CARET_BLINK_MS = 500L

        /** Toolbar color swatches (null = theme default). Ported from RichDescriptionEditor. */
        internal val SWATCHES: List<Int?> = listOf(
            null,
            Theme.ACCENT,
            0xFFE03E2D.toInt(),
            0xFF2DC26B.toInt(),
            0xFF3598DB.toInt(),
        )
    }

    // ---- document model (ported) ----

    /** One styled character; `'\n'` ends a logical line. */
    internal data class RChar(
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
    internal data class TypingStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val color: Int? = null,
        val link: String? = null,
    )

    internal val chars = ArrayList<RChar>()
    private var cursor = 0

    /** Selection anchor (non-moving end); -1 = none. Selection = [selAnchor, cursor]. */
    private var selAnchor = -1

    /** Toolbar-toggled style for the next typed char; cleared when the caret moves. */
    private var pendingOverride: TypingStyle? = null

    private var blinkBase = System.currentTimeMillis()

    /** True while the caret is in this widget (set when its surface is clicked/active). */
    private var focused = false

    /**
     * Set during [RichTextToolbar.render] when one of this widget's own toolbar controls
     * (style toggles, bullet, color swatches) was clicked/activated this frame. Used by
     * [handleInput] so that interacting with the toolbar does NOT drop editor focus
     * (clicking a toolbar button is not "clicking elsewhere"). Without this, clicking the
     * B/I/U/S buttons released focus and discarded the pending style + keyboard capture.
     */
    internal var toolbarInteracted = false

    /** Drag state: caret follows the mouse while the surface is active. */
    private var dragging = false

    /** Normalized HTML the widget was last seeded with, for [isChanged]. */
    private var initialHtml = ""

    // ---- layout ----

    /** Wrapped-line cache, rebuilt by [RichTextLayout] when [layoutDirty] or the width changes. */
    internal var lines: List<VLine> = emptyList()
    internal var layoutDirty = true

    /** Layout engine (wrapping, metrics, hit-testing) extracted to [RichTextLayout]. */
    internal val layout = RichTextLayout(this)

    /** Toolbar row (B/I/U/S, bullet, swatches) extracted to [RichTextToolbar]. */
    internal val toolbar = RichTextToolbar(this)

    // ============================ public API ============================

    /** Seed from website HTML (parsed into styled chars). Caret resets to start. */
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
        pendingOverride = null
        layoutDirty = true
        initialHtml = currentHtml()
    }

    /** Alias for [setHtml] (task-requested name). */
    fun setFromHtml(html: String) = setHtml(html)

    /** Clear to an empty document. */
    fun clear() {
        chars.clear()
        cursor = 0
        selAnchor = -1
        pendingOverride = null
        layoutDirty = true
        initialHtml = ""
    }

    /** The document as sanitizer-safe website HTML ("" when only whitespace). */
    fun toHtml(): String = currentHtml()

    /** True when the document has only whitespace. */
    fun isEmpty(): Boolean = chars.none { !it.ch.isWhitespace() }

    /** True when the current HTML differs from what was last seeded. */
    fun isChanged(): Boolean = currentHtml() != initialHtml

    /** Plain text with all formatting resolved away (length checks / fallbacks). */
    fun plainText(): String = buildString { for (rc in chars) append(rc.ch) }

    /**
     * Draw the toolbar, then the editable WYSIWYG surface.
     *
     * @param editorHeight pixel height of the editable surface.
     * @param previewHeight ignored (kept for call-site compatibility with the old
     *        markup+preview [RichTextWidget]; this editor is itself the preview).
     */
    fun render(editorHeight: Float = 120f, @Suppress("UNUSED_PARAMETER") previewHeight: Float = 120f) {
        toolbar.render()
        renderSurface(editorHeight)
    }

    // ============================ surface (draw + input) ============================

    private fun renderSurface(height: Float) {
        val drawList = ImGui.getWindowDrawList()
        val originX = ImGui.getCursorScreenPosX()
        val originY = ImGui.getCursorScreenPosY()

        var width = ImGui.getContentRegionAvailX()
        if (width < 32f) width = 32f

        // Background + border via the surface's own draw-list rects.
        drawList.addRectFilled(originX, originY, originX + width, originY + height, abgr(Theme.SURFACE_ALT))
        drawList.addRect(originX, originY, originX + width, originY + height, abgr(Theme.BORDER_SUBTLE))

        // The invisible button is the input/hit surface (drag, hover, focus, activation).
        ImGui.invisibleButton("##$id-rte", width, height)
        val hovered = ImGui.isItemHovered()
        val active = ImGui.isItemActive()

        layout.ensureLayout(width)

        handleInput(originX, originY, width, height, hovered, active)
        // Layout may have changed during input (typed chars / deletes); rebuild before draw.
        layout.ensureLayout(width)

        drawContent(drawList, originX, originY, width, height)
    }

    // ---- selection helpers ----

    private fun hasSelection() = selAnchor >= 0 && selAnchor != cursor
    private fun selMin() = min(selAnchor, cursor)
    private fun selMax() = max(selAnchor, cursor)
    private fun resetBlink() { blinkBase = System.currentTimeMillis() }

    // ---- editing primitives ----

    private fun edited() {
        cursor = cursor.coerceIn(0, chars.size)
        if (selAnchor > chars.size) selAnchor = chars.size
        if (selAnchor == cursor) selAnchor = -1
        layoutDirty = true
        resetBlink()
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

    private fun typingStyle(): TypingStyle = pendingOverride ?: derivedStyle()

    private fun styleKeyOf(rc: RChar) =
        TypingStyle(rc.bold, rc.italic, rc.underline, rc.strikethrough, rc.color, rc.link)

    private fun derivedStyle(): TypingStyle {
        if (hasSelection()) {
            val first = chars.getOrNull(selMin())?.takeIf { it.ch != '\n' } ?: return TypingStyle()
            return styleKeyOf(first).copy(link = null)
        }
        val before = chars.getOrNull(cursor - 1)?.takeIf { it.ch != '\n' }
        val after = chars.getOrNull(cursor)?.takeIf { it.ch != '\n' }
        val basis = before ?: after ?: return TypingStyle()
        val link = if (before?.link != null && before.link == after?.link) before.link else null
        return styleKeyOf(basis).copy(link = link)
    }

    private fun logicalLineStart(pos: Int): Int {
        var i = pos
        while (i > 0 && chars[i - 1].ch != '\n') i--
        return i
    }

    private fun bulletForInsert(c: Char): Boolean {
        if (c == '\n') return false
        val ls = logicalLineStart(cursor)
        return cursor == ls && chars.getOrNull(ls)?.let { it.ch != '\n' && it.bullet } == true
    }

    private fun insertChar(c: Char) {
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
        val clamped = target.coerceIn(0, chars.size)
        if (extend) {
            if (selAnchor < 0) selAnchor = cursor
        } else {
            selAnchor = -1
        }
        cursor = clamped
        if (selAnchor == cursor) selAnchor = -1
        // A toolbar-armed pending style (e.g. a just-picked color, or B/I/U/S toggled
        // with no selection) deliberately SURVIVES caret placement, so the natural flow
        // "pick a colour → click to position → type" keeps the colour. The pending style
        // is superseded by the next toolbar toggle and reset on setHtml/clear; typed
        // chars consume it as their style but leave it armed for continued typing.
        resetBlink()
    }

    private fun moveVertical(direction: Int, extend: Boolean) {
        if (lines.isEmpty()) return
        val li = layout.lineIndexAt(cursor)
        val target = li + direction
        if (target !in lines.indices) {
            placeCursor(if (direction < 0) 0 else chars.size, extend)
            return
        }
        val vl = lines[li]
        val xRel = vl.indent + vl.xs[(cursor - vl.start).coerceIn(0, vl.xs.size - 1)]
        val tl = lines[target]
        val tx = xRel - tl.indent
        var best = 0
        for (k in tl.xs.indices) {
            if (abs(tl.xs[k] - tx) < abs(tl.xs[best] - tx)) best = k
        }
        if (best == tl.xs.size - 1 && tl.end < chars.size && chars[tl.end].ch != '\n' && best > 0) best--
        placeCursor(tl.start + best, extend)
    }

    private fun copySelection() {
        if (!hasSelection()) return
        val text = buildString { for (i in selMin() until selMax()) append(chars[i].ch) }
        ImGui.setClipboardText(text)
    }

    private fun paste() {
        val raw = ImGui.getClipboardText() ?: return
        val clip = raw.replace("\r\n", "\n").replace('\r', '\n')
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

    // ---- toolbar style API (ported) ----

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

    internal fun toggleStyle(flag: StyleFlag) {
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

    internal fun isStyleActive(flag: StyleFlag): Boolean {
        if (hasSelection()) {
            val sel = (selMin() until selMax()).filter { chars[it].ch != '\n' }
            return sel.isNotEmpty() && sel.all { flagOf(styleKeyOf(chars[it]), flag) }
        }
        return flagOf(typingStyle(), flag)
    }

    internal fun setColor(color: Int?) {
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

    internal fun activeColor(): Int? =
        if (hasSelection()) chars.getOrNull(selMin())?.color else typingStyle().color

    internal fun toggleBulletLine() {
        val ls = logicalLineStart(cursor)
        if (ls >= chars.size || chars[ls].ch == '\n') return
        val makeBullet = !chars[ls].bullet
        chars[ls] = chars[ls].copy(bullet = makeBullet)
        edited()
    }

    // ---- document -> HTML ----

    private fun currentHtml(): String = if (isEmpty()) "" else RichText.spansToHtml(getSpans())

    private fun getSpans(): List<RichSpan> {
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

    // ============================ input ============================

    private fun handleInput(
        originX: Float,
        originY: Float,
        width: Float,
        height: Float,
        hovered: Boolean,
        active: Boolean,
    ) {
        val io = ImGui.getIO()
        val mx = ImGui.getMousePosX()
        val my = ImGui.getMousePosY()

        // ---- focus + mouse ----
        val wasFocused = focused
        if (active) {
            focused = true
            if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                selectWordAt(layout.hitTest(mx, my, originX, originY))
                dragging = false
            } else if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                val pos = layout.hitTest(mx, my, originX, originY)
                placeCursor(pos, io.keyShift)
                dragging = true
            } else if (dragging && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                placeCursor(layout.hitTest(mx, my, originX, originY), true)
            }
        } else if (ImGui.isMouseClicked(ImGuiMouseButton.Left) && !hovered && !toolbarInteracted) {
            // Clicked elsewhere (NOT our own toolbar): drop focus so typed chars stop
            // routing here. Excluding [toolbarInteracted] keeps focus + the pending style
            // alive when the user clicks B/I/U/S or a color swatch (Bug 2).
            focused = false
            dragging = false
        }
        if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) dragging = false

        // ---- keyboard ownership ----
        // The editor is a custom draw-list widget (invisibleButton), NOT an ImGui
        // InputText, so it never raises io.WantTextInput. When ANY real ImGui InputText is
        // active (the schematic name field, the collaborator name field, tag search…),
        // io.WantTextInput is true: that field owns the keyboard, so we must yield. If we
        // didn't, we would BOTH (a) keep draining the shared typedChars queue — cloning the
        // characters the user types into the name/title field straight into the description
        // (Bug 1), and (b) fight the InputText for input (Bug 3, fields "won't take text").
        if (io.wantTextInput) {
            focused = false
        }

        // ROBUST OWNERSHIP GATE (fixes the collaborator field that renders AFTER this editor).
        //
        // io.wantTextInput alone is insufficient for any InputText rendered *after* this
        // widget in the same frame. Frame order inside ##details-form is:
        //     name InputText (before) → editor.handleInput → … → collaborator InputText (after)
        // io.WantTextInput / io.WantCaptureKeyboard reflect the widget that was active at the
        // END of the previous frame. When the user clicks the collaborator field:
        //   - The editor's invisibleButton is rendered first and is NOT hit, so `active`
        //     (isItemActive for our button) is false and the click-outside branch above drops
        //     focus on the click frame.
        //   - But on the very next frame the collaborator InputText is the active item. Reading
        //     io.wantTextInput here still lags by a frame (the collaborator field is processed
        //     LATER in the frame than this code), so the editor could re-grab focus, re-assert
        //     setNextFrameWantCaptureKeyboard(true), and drain typedChars — starving the
        //     collaborator field of every character (the observed bug).
        //
        // ImGui.isAnyItemActive() does NOT lag: once the collaborator InputText is active it
        // stays the active item every subsequent frame, and isAnyItemActive() reports true at
        // this point in the frame. If ANY item other than our own surface is active, that item
        // owns interaction — the editor must fully yield: don't hold focus, don't assert
        // capture, don't drain typedChars. This lets ImGui (which natively raises
        // WantCaptureKeyboard/WantTextInput for the active InputText) route chars to it.
        //
        // `active` is isItemActive() for our invisibleButton (passed in), so this never yields
        // while the user is genuinely clicking/dragging inside the editor surface itself.
        if (ImGui.isAnyItemActive() && !active) {
            focused = false
        }

        if (!focused) return

        // Force ImGui keyboard capture for the NEXT frame. The Keyboard mixin gates char/key
        // forwarding on io.WantCaptureKeyboard, which is evaluated during GLFW callbacks at
        // the START of the next frame (before this widget renders). Because our invisibleButton
        // only raises WantCaptureKeyboard while the mouse is physically held on it, typed chars
        // stopped reaching us the instant the button was released — breaking typing/spacing as
        // soon as the user let go or clicked a toolbar button (Bug 2). Asserting capture every
        // focused frame keeps the gate open while the caret lives here. We deliberately do NOT
        // raise WantTextInput, so the ownership check above can still detect real InputTexts.
        ImGui.setNextFrameWantCaptureKeyboard(true)

        // On the frame we (re)gain focus, discard any chars that another field queued while
        // we were unfocused, so clicking back into the editor never dumps a burst of the
        // name/title field's keystrokes into the description.
        if (!wasFocused) {
            ImGuiManager.drainTypedChars()
            return
        }

        // ---- typed characters (drained from ImGuiManager) ----
        for (cp in ImGuiManager.drainTypedChars()) {
            if (cp == '\r'.code || cp == '\n'.code) continue // Enter handled as a key
            if (cp < 0x20) continue
            // Code points outside the BMP would need a surrogate pair; the editor model
            // is char-based, so only the BMP is supported (matches the website content).
            if (cp <= 0xFFFF) insertChar(cp.toChar())
        }

        // ---- clipboard / select-all (ctrl or super on mac) ----
        val cmd = io.keyCtrl || io.keySuper
        if (cmd && ImGui.isKeyPressed(ImGuiKey.A, false)) {
            selAnchor = 0
            cursor = chars.size
            resetBlink()
        }
        if (cmd && ImGui.isKeyPressed(ImGuiKey.C, false)) copySelection()
        if (cmd && ImGui.isKeyPressed(ImGuiKey.X, false)) { copySelection(); deleteSelection() }
        if (cmd && ImGui.isKeyPressed(ImGuiKey.V, false)) paste()

        // ---- edit / navigation keys (repeat=true for held keys) ----
        val shift = io.keyShift
        if (ImGui.isKeyPressed(ImGuiKey.Enter, true) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, true)) insertChar('\n')
        if (ImGui.isKeyPressed(ImGuiKey.Backspace, true)) backspace()
        if (ImGui.isKeyPressed(ImGuiKey.Delete, true)) forwardDelete()
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow, true)) {
            if (hasSelection() && !shift) placeCursor(selMin(), false) else placeCursor(cursor - 1, shift)
        }
        if (ImGui.isKeyPressed(ImGuiKey.RightArrow, true)) {
            if (hasSelection() && !shift) placeCursor(selMax(), false) else placeCursor(cursor + 1, shift)
        }
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true)) moveVertical(-1, shift)
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true)) moveVertical(1, shift)
        if (ImGui.isKeyPressed(ImGuiKey.Home, true)) placeCursor(lines.getOrNull(layout.lineIndexAt(cursor))?.start ?: 0, shift)
        if (ImGui.isKeyPressed(ImGuiKey.End, true)) placeCursor(lines.getOrNull(layout.lineIndexAt(cursor))?.end ?: chars.size, shift)
    }

    // ============================ draw ============================

    private fun drawContent(
        drawList: imgui.ImDrawList,
        originX: Float,
        originY: Float,
        width: Float,
        height: Float,
    ) {
        val baseX = originX + PAD
        val baseY = originY + PAD
        val lineH = layout.lineHeight()
        val fontH = layout.fontSize()

        // Clip to the inner surface.
        drawList.pushClipRect(originX + 1f, originY + 1f, originX + width - 1f, originY + height - 1f, true)

        if (chars.isEmpty() && !focused) {
            drawText(drawList, "Description...", baseX, baseY, abgr(Theme.TEXT_FAINT), bold = false, italic = false)
        }

        val selecting = hasSelection()
        val a = if (selecting) selMin() else 0
        val b = if (selecting) selMax() else 0

        lines.forEachIndexed { li, vl ->
            val ry = baseY + li * lineH

            // selection highlight
            if (selecting && a <= vl.end && b >= vl.start) {
                val sa = a.coerceAtLeast(vl.start)
                val sb = b.coerceAtMost(vl.end)
                if (sa <= sb) {
                    val x1 = baseX + vl.indent + vl.xs[sa - vl.start]
                    var x2 = baseX + vl.indent + vl.xs[sb - vl.start]
                    if (b > vl.end) x2 += 3f // show a selected line break
                    if (x2 > x1) {
                        drawList.addRectFilled(x1, ry - 1f, x2, ry + fontH + 1f, abgr(Theme.withAlpha(Theme.ACCENT, 0x44)))
                    }
                }
            }

            if (vl.bulletHead) {
                drawText(drawList, BULLET_PREFIX, baseX, ry, abgr(Theme.TEXT_SECONDARY), bold = false, italic = false)
            }
            drawLineRuns(drawList, vl, baseX + vl.indent, ry, fontH)
        }

        // caret
        if (focused && lines.isNotEmpty() && caretBlinkOn()) {
            val li = layout.lineIndexAt(cursor)
            val vl = lines[li]
            val cx = baseX + vl.indent + vl.xs[(cursor - vl.start).coerceIn(0, vl.xs.size - 1)]
            val cy = baseY + li * lineH
            drawList.addRectFilled(cx, cy - 1f, cx + 1f, cy + fontH + 1f, abgr(Theme.ACCENT))
        }

        drawList.popClipRect()
    }

    /** Draw one visual row's characters, grouped into same-style runs. */
    private fun drawLineRuns(drawList: imgui.ImDrawList, vl: VLine, startX: Float, y: Float, fontH: Float) {
        if (vl.start >= vl.end) return
        var i = vl.start
        while (i < vl.end) {
            val key = styleKeyOf(chars[i])
            var j = i
            val sb = StringBuilder()
            while (j < vl.end && styleKeyOf(chars[j]) == key) {
                sb.append(chars[j].ch)
                j++
            }
            val runStartX = startX + vl.xs[i - vl.start]
            val runEndX = startX + vl.xs[j - vl.start]
            val color = runColor(key)
            drawText(drawList, sb.toString(), runStartX, y, color, key.bold, key.italic)

            val lineColor = color
            if (key.underline || key.link != null) {
                drawList.addLine(runStartX, y + fontH - 1f, runEndX, y + fontH - 1f, lineColor, 1f)
            }
            if (key.strikethrough) {
                val midY = y + fontH * 0.5f
                drawList.addLine(runStartX, midY, runEndX, midY, lineColor, 1f)
            }
            i = j
        }
    }

    private fun runColor(k: TypingStyle): Int {
        val argb = k.color ?: (if (k.link != null) Theme.INFO else Theme.TEXT_PRIMARY)
        return abgr(argb)
    }

    /**
     * Draw [text] at ([x],[y]) in [colAbgr]. [bold] thickens via a second offset pass;
     * [italic] renders glyph-by-glyph with a faux shear via [addItalicRun].
     */
    private fun drawText(drawList: imgui.ImDrawList, text: String, x: Float, y: Float, colAbgr: Int, bold: Boolean, italic: Boolean) {
        if (text.isEmpty()) return
        if (italic) {
            addItalicRun(drawList, text, x, y, colAbgr)
            if (bold) addItalicRun(drawList, text, x + BOLD_OFFSET, y, colAbgr)
        } else {
            val f = layout.font()
            // addText(ImFont, int fontSize, float x, float y, int colAbgr, String): fontSize is int.
            val size = ImGui.getFontSize()
            drawList.addText(f, size, x, y, colAbgr, text)
            if (bold) drawList.addText(f, size, x + BOLD_OFFSET, y, colAbgr, text)
        }
    }

    /**
     * Render a run glyph-by-glyph with a faux italic shear: each glyph quad's top edge
     * is shifted right relative to its bottom edge by [ITALIC_SLANT] times the glyph's
     * height above the baseline, using the font atlas texture + the glyph's UVs.
     */
    private fun addItalicRun(drawList: imgui.ImDrawList, text: String, x: Float, y: Float, colAbgr: Int) {
        val f = layout.font()
        val scale = layout.fontSize() / f.fontSize
        val texId = ImGuiGl3Renderer.fontTextureId.toLong()
        drawList.pushTextureID(texId)
        var penX = x
        for (c in text) {
            val g = f.findGlyph(c.code)
            if (g == null) { continue }
            // Glyph box in screen space (y0 is the top offset from the baseline-anchored draw y).
            val gx0 = penX + g.x0 * scale
            val gx1 = penX + g.x1 * scale
            val gy0 = y + g.y0 * scale
            val gy1 = y + g.y1 * scale
            // Shear: shift the TOP corners right. Top is "above" the line; more shift higher up.
            val topShift = ITALIC_SLANT * (gy1 - gy0)
            // corners: top-left, top-right, bottom-right, bottom-left
            drawList.addImageQuad(
                texId,
                gx0 + topShift, gy0, // TL
                gx1 + topShift, gy0, // TR
                gx1, gy1,            // BR
                gx0, gy1,            // BL
                g.u0, g.v0,
                g.u1, g.v0,
                g.u1, g.v1,
                g.u0, g.v1,
                colAbgr,
            )
            penX += g.advanceX * scale
        }
        drawList.popTextureID()
    }

    private fun caretBlinkOn(): Boolean = ((System.currentTimeMillis() - blinkBase) / CARET_BLINK_MS) % 2L == 0L

    // ---- color packing ----

    /** Pack an ARGB int (Theme convention) into the ABGR int Dear ImGui draw APIs expect. */
    private fun abgr(argb: Int): Int {
        val v = argbToImVec4(argb)
        return ImGui.colorConvertFloat4ToU32(v.x, v.y, v.z, v.w)
    }
}
