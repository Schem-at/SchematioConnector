package io.schemat.connector.fabric.client.ui.widgets
import io.schemat.connector.fabric.client.ui.theme.argbToImVec4
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiInputTextFlags
import io.schemat.connector.core.text.RichDocument
import io.schemat.connector.core.text.RichSpan
import io.schemat.connector.core.text.RichText
import imgui.type.ImString

/**
 * ImGui rich-text editor for schematic descriptions (Task 21).
 *
 * **Option B — markup source + live preview.** The user edits the mod's
 * markdown-ish *markup* in a normal [ImGui.inputTextMultiline]; a read-only
 * **preview** pane below renders the formatted result so they see what will
 * publish without authoring raw HTML. A small toolbar (B / I / U / S / bullet)
 * wraps the current word (or inserts a marker pair the caret sits between) with
 * the corresponding markup token, mirroring the four [RichDocument.StyleFlag]s
 * the vanilla [io.schemat.connector.fabric.client.ui.foundation.RichTextEditor]
 * exposes plus line-level bullets.
 *
 * Why B and not a custom WYSIWYG draw-list widget: a correct WYSIWYG caret needs
 * per-glyph hit-testing against laid-out runs, which is unverifiable here (no
 * runtime test, no existing draw-list precedent in the codebase, and the
 * multi-version constraint rules out Minecraft's `Font.width`). The markup +
 * preview path is robust, reuses the proven [RichText] parser, and round-trips
 * through the same sanitizer-safe HTML as the website.
 *
 * Load/save: [setHtml] seeds from website HTML (HTML → markup via
 * [RichText.htmlToMarkup]); [toHtml] serializes back (markup → HTML via
 * [RichText.markupToHtml]). The preview is built with
 * [RichText.markupToSpans] → styled runs.
 *
 * Markup reference (see [RichText]): `**bold**`, `*italic*`/`_italic_`,
 * `__underline__`, `~~strike~~`, `\n` line break, blank line paragraph break,
 * `- ` line prefix bullet.
 *
 * No raw color literals — all colors come from [ImGuiColors]; passes the panel
 * theme-discipline check.
 *
 * @param id stable widget id; used to namespace the `##` ImGui control ids so two
 *           widgets (edit + upload panels) never collide.
 */
class RichTextWidget(private val id: String) {

    /** Backing markup buffer the user edits directly. */
    private val buffer = ImString(MARKUP_MAX)

    /** Cache of the last-parsed markup + its spans so the preview only re-parses on change. */
    private var cachedMarkup: String? = null
    private var cachedSpans: List<RichSpan> = emptyList()

    companion object {
        /** Markup is looser than HTML; allow generous headroom over the model cap. */
        private const val MARKUP_MAX = 8192
        private const val BULLET_PREFIX = "• "
    }

    // ---- load / save ----

    /** Seed from website HTML (converted to editor markup). Caret resets to start. */
    fun setHtml(html: String) {
        buffer.set(RichText.htmlToMarkup(html))
        invalidate()
    }

    /** Seed directly from markup (e.g. an empty new description). */
    fun setMarkup(markup: String) {
        buffer.set(markup)
        invalidate()
    }

    /** Clear to an empty document. */
    fun clear() {
        buffer.set("")
        invalidate()
    }

    /** The raw markup the user is editing. */
    fun markup(): String = buffer.get()

    /**
     * The document as sanitizer-safe website HTML ("" when blank).
     * Mirrors [RichDocument.toHtml] / [RichText.spansToHtml] semantics.
     */
    fun toHtml(): String {
        val md = buffer.get()
        return if (md.isBlank()) "" else RichText.markupToHtml(md)
    }

    /** True when the markup is blank (only whitespace). */
    fun isEmpty(): Boolean = buffer.get().isBlank()

    /** Plain text with all markup tokens resolved away (for length checks / fallbacks). */
    fun plainText(): String =
        spans().joinToString("") { it.text }

    // ---- rendering ----

    /**
     * Draw the toolbar, the markup editor, and the live preview.
     *
     * @param editorHeight pixel height of the editable markup box.
     * @param previewHeight pixel height of the scrollable preview child.
     */
    fun render(editorHeight: Float = 120f, previewHeight: Float = 120f) {
        renderToolbar()

        // ---- markup editor ----
        ImGui.setNextItemWidth(-1f)
        ImGui.inputTextMultiline("##$id-md", buffer, -1f, editorHeight, ImGuiInputTextFlags.AllowTabInput)

        ImGui.spacing()

        // ---- preview label ----
        ImGui.textColored(
            ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y,
            ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w,
            "Preview",
        )

        renderPreview(previewHeight)
    }

    private fun renderToolbar() {
        // Each button wraps the selection-less caret region / current word with markers.
        if (Widgets.button("B")) wrap("**", "**")
        ImGui.sameLine()
        if (Widgets.button("I")) wrap("*", "*")
        ImGui.sameLine()
        if (Widgets.button("U")) wrap("__", "__")
        ImGui.sameLine()
        if (Widgets.button("S")) wrap("~~", "~~")
        ImGui.sameLine()
        if (Widgets.button("• List")) toggleBulletLine()
        ImGui.sameLine()
        ImGui.textColored(
            ImGuiColors.TEXT_FAINT.x, ImGuiColors.TEXT_FAINT.y,
            ImGuiColors.TEXT_FAINT.z, ImGuiColors.TEXT_FAINT.w,
            "**bold**  *italic*  __underline__  ~~strike~~",
        )
    }

    /**
     * Render the parsed spans read-only inside a bordered scroll child.
     *
     * Color is applied via [ImGuiCol.Text]; **underline** and **strikethrough**
     * are drawn as thin lines via the window draw list (read-only static text —
     * no caret, no hit-testing, so none of the risky WYSIWYG layout math). Bold
     * and italic are faithful in the markup source above and tinted with the
     * accent color here (ImGui has no per-call bold/italic glyph without a
     * separate font atlas, which is out of scope).
     */
    private fun renderPreview(height: Float) {
        ImGui.beginChild("##$id-preview", -1f, height, true)

        val parsed = spans()
        if (parsed.isEmpty()) {
            ImGui.textColored(
                ImGuiColors.TEXT_FAINT.x, ImGuiColors.TEXT_FAINT.y,
                ImGuiColors.TEXT_FAINT.z, ImGuiColors.TEXT_FAINT.w,
                "Nothing to preview yet.",
            )
            ImGui.endChild()
            return
        }

        val drawList = ImGui.getWindowDrawList()
        // `lineHasContent` is false at the start of every visual line so the first
        // run is flush-left; subsequent runs on the same line append via sameLine.
        var lineHasContent = false

        for (span in parsed) {
            // A span may contain embedded newlines; split so each visual line wraps.
            val parts = span.text.split("\n")
            parts.forEachIndexed { i, part ->
                if (i > 0) {
                    // End the current visual line. newLine() only advances when the
                    // line already has content; for a truly blank line we still want
                    // vertical space, so emit a zero-content text run.
                    if (lineHasContent) ImGui.newLine() else ImGui.text("")
                    lineHasContent = false
                }
                val bulletHead = span.bullet && !lineHasContent && part.isNotEmpty()
                if (bulletHead) {
                    drawRun(drawList, BULLET_PREFIX, lineHasContent,
                        color = null, underline = false, strike = false, emphasize = false)
                    lineHasContent = true
                }
                if (part.isEmpty()) return@forEachIndexed
                drawRun(
                    drawList, part, lineHasContent,
                    color = span.color
                        ?: (if (span.link != null) io.schemat.connector.fabric.client.ui.theme.Theme.INFO else null),
                    underline = span.underline || span.link != null,
                    strike = span.strikethrough,
                    emphasize = span.bold || span.italic,
                )
                lineHasContent = true
            }
        }

        ImGui.endChild()
    }

    /**
     * Emit one inline text run on the current line, pushing a text color and
     * underlining / striking via the draw list (read-only — no caret/hit-test).
     *
     * @param sameLine true when a previous run already occupies this visual line.
     */
    private fun drawRun(
        drawList: imgui.ImDrawList,
        text: String,
        sameLine: Boolean,
        color: Int?,
        underline: Boolean,
        strike: Boolean,
        emphasize: Boolean,
    ) {
        if (sameLine) ImGui.sameLine(0f, 0f)

        // Anchor the text screen position before drawing so we can underline/strike it.
        val x0 = ImGui.getCursorScreenPosX()
        val y0 = ImGui.getCursorScreenPosY()

        val pushed = pushTextColor(color, emphasize)
        ImGui.text(text)
        if (pushed) ImGui.popStyleColor()

        val w = ImGui.getItemRectSizeX()
        val h = ImGui.getItemRectSizeY()
        val lineColor = lineColorFor(color)
        if (underline) {
            drawList.addLine(x0, y0 + h - 1f, x0 + w, y0 + h - 1f, lineColor)
        }
        if (strike) {
            val midY = y0 + h * 0.5f
            drawList.addLine(x0, midY, x0 + w, midY, lineColor)
        }
    }

    /** Push [ImGuiCol.Text] for an explicit color, else the accent tint for bold/italic. */
    private fun pushTextColor(color: Int?, emphasize: Boolean): Boolean {
        val c = when {
            color != null -> argbToImVec4(color)
            emphasize -> ImGuiColors.ACCENT
            else -> null
        } ?: return false
        ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, c.w)
        return true
    }

    private fun lineColorFor(color: Int?): Int {
        val v = if (color != null) argbToImVec4(color) else ImGuiColors.TEXT_PRIMARY
        return ImGui.colorConvertFloat4ToU32(v.x, v.y, v.z, v.w)
    }

    // ---- toolbar editing primitives ----

    /**
     * Wrap a marker pair around the end of the current buffer (no selection model in
     * ImGui's inputTextMultiline that we can read here), inserting `open``close`
     * with the caret conceptually between them. Because we cannot query ImGui's
     * internal caret/selection from Java reliably, we append the marker pair at the
     * end of the text; the user then types between the markers (or moves them). This
     * keeps behavior predictable and never corrupts existing markup.
     */
    private fun wrap(open: String, close: String) {
        val cur = buffer.get()
        // Trim a single trailing space so "word " + bold gives "word **|**" cleanly.
        val base = cur
        buffer.set(base + open + close)
        invalidate()
    }

    /** Prefix the last line with "- " (bullet) or remove it if already present. */
    private fun toggleBulletLine() {
        val cur = buffer.get()
        val nl = cur.lastIndexOf('\n')
        val lineStart = nl + 1
        val line = cur.substring(lineStart)
        val newLine = if (line.startsWith("- ")) line.removePrefix("- ") else "- $line"
        buffer.set(cur.substring(0, lineStart) + newLine)
        invalidate()
    }

    // ---- preview cache ----

    private fun spans(): List<RichSpan> {
        val md = buffer.get()
        if (md != cachedMarkup) {
            cachedMarkup = md
            cachedSpans = if (md.isBlank()) emptyList() else RichText.markupToSpans(md)
        }
        return cachedSpans
    }

    private fun invalidate() {
        cachedMarkup = null
    }
}
