package io.schemat.connector.core.text

/**
 * Mutable editor-state document for the ImGui rich-text widget (Task 21).
 *
 * Internally stores the document as a flat [MutableList] of [RChar] (one entry per
 * character, including `'\n'` line-break characters), mirroring the vanilla
 * [RichTextEditor]'s approach so the two widgets share identical semantics.
 *
 * All edit operations mutate the document in place.  Load/save via [fromHtml]/[toHtml]
 * (round-trips through [RichText.htmlToSpans] / [RichText.spansToHtml]).
 *
 * Pure Kotlin — no Minecraft or Fabric dependencies.
 */
class RichDocument private constructor(private val chars: MutableList<RChar>) {

    // ---- companion / factories ----

    companion object {
        /** Maximum number of characters accepted (mirrors the vanilla editor cap). */
        const val MAX_LENGTH = 4096

        /** Empty document. */
        operator fun invoke(): RichDocument = RichDocument(mutableListOf())

        /** Build from an existing span list (e.g. from [RichText.htmlToSpans]). */
        fun fromSpans(spans: List<RichSpan>): RichDocument {
            val chars = mutableListOf<RChar>()
            for (span in spans) {
                span.text.forEachIndexed { i, c ->
                    chars.add(
                        RChar(
                            ch = c,
                            bold = span.bold,
                            italic = span.italic,
                            underline = span.underline,
                            strikethrough = span.strikethrough,
                            color = span.color,
                            link = span.link,
                            code = span.code,
                            line = lineFormatOf(span, listHead = i == 0, lineStart = chars.lastOrNull().let { it == null || it.ch == '\n' }),
                        )
                    )
                }
            }
            return RichDocument(chars)
        }

        /**
         * The line format a char carries when it heads a line: the list kind on the first
         * char of a bullet span, heading/quote on any char that starts a line.
         */
        private fun lineFormatOf(span: RichSpan, listHead: Boolean, lineStart: Boolean): LineFormat {
            val list = when {
                !(span.bullet && listHead) -> ListKind.NONE
                span.ordered -> ListKind.ORDERED
                else -> ListKind.BULLET
            }
            return if (lineStart) LineFormat(list, span.heading, span.quote) else LineFormat(list)
        }

        /** Build from sanitizer-allowed website HTML (via [RichText.htmlToSpans]). */
        fun fromHtml(html: String): RichDocument = fromSpans(RichText.htmlToSpans(html))
    }

    // ---- nested types ----

    /** The formatting flags the ImGui toolbar can toggle. */
    enum class StyleFlag { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, CODE }

    /** What list, if any, a line belongs to. */
    enum class ListKind { NONE, BULLET, ORDERED }

    /**
     * Line-level (block) formatting, carried by the first character of a line:
     * list membership, heading level (0, 2 or 3) and blockquote.
     */
    data class LineFormat(val list: ListKind = ListKind.NONE, val heading: Int = 0, val quote: Boolean = false)

    /**
     * The style applied to newly inserted characters.  Mirrors the vanilla
     * [RichTextEditor.TypingStyle] so ImGui can reuse the same shape.
     */
    data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val color: Int? = null,
        val link: String? = null,
        val code: Boolean = false,
    )

    // ---- internal character model ----

    /** One styled character; `'\n'` is a hard line-break (carries no formatting). */
    private data class RChar(
        val ch: Char,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val color: Int? = null,
        val link: String? = null,
        val code: Boolean = false,
        /** Line-level format; meaningful only on the first character of a line. */
        val line: LineFormat = LineFormat(),
    )

    // ---- read-only properties ----

    /** Number of characters in the document (including `'\n'`). */
    val length: Int get() = chars.size

    /** All characters concatenated without any formatting. */
    val plainText: String get() = chars.map { it.ch }.joinToString("")

    // ---- styleAt ----

    /**
     * Style at [offset].  If [offset] is at or past the end, returns the style of
     * the last non-newline character, or a default [Style] for an empty document.
     * If [offset] falls on a `'\n'`, returns the default [Style] (newlines carry no
     * formatting in the span model).
     */
    fun styleAt(offset: Int): Style {
        val rc = when {
            chars.isEmpty() -> return Style()
            offset >= chars.size -> chars.lastOrNull { it.ch != '\n' } ?: return Style()
            else -> chars[offset]
        }
        if (rc.ch == '\n') return Style()
        return styleKeyOf(rc)
    }

    // ---- insertText ----

    /**
     * Insert [text] at character [offset] with the given [style].  Characters beyond
     * [MAX_LENGTH] are silently dropped.  `'\n'` in [text] is accepted; control
     * characters below 0x20 other than `'\n'` are skipped.
     *
     * Note on bullets: inserted characters are **never** marked as a bullet head, even
     * when inserting at the very first character of a bullet line (offset 0 of a `<li>`).
     * `bullet` is a line-level flag carried only by the original first character of a
     * list item (see [fromSpans], where it is set on `i == 0`).  Inserting before that
     * character therefore produces a non-bullet run at the line head, so the line loses
     * its bullet on the next [toSpans]/[toHtml] until the inserted text is removed.  The
     * ImGui widget authors bullets at the line level (markup `- ` prefix), not through
     * [insertText], so this does not affect it; callers needing bullet-preserving
     * insertion at a line head must re-apply the line's bullet themselves.
     */
    fun insertText(offset: Int, text: String, style: Style) {
        val pos = offset.coerceIn(0, chars.size)
        var insertAt = pos
        for (c in text) {
            if (chars.size >= MAX_LENGTH) break
            if (c != '\n' && c.code < 0x20) continue
            chars.add(insertAt, RChar(c, style.bold, style.italic, style.underline, style.strikethrough, style.color, style.link, style.code))
            insertAt++
        }
    }

    // ---- deleteRange ----

    /**
     * Delete characters in [[start], [end]) (end exclusive).  Clamped to valid
     * bounds; [start] == [end] is a no-op.
     */
    fun deleteRange(start: Int, end: Int) {
        val a = start.coerceIn(0, chars.size)
        val b = end.coerceIn(a, chars.size)
        if (a == b) return
        repeat(b - a) { chars.removeAt(a) }
    }

    // ---- isStyleActive / toggleStyle ----

    /**
     * Returns true when every non-newline character in [[start], [end]) has [flag]
     * set.  Returns false for an empty or newlines-only range.
     */
    fun isStyleActive(start: Int, end: Int, flag: StyleFlag): Boolean {
        val a = start.coerceIn(0, chars.size)
        val b = end.coerceIn(a, chars.size)
        val relevant = (a until b).filter { chars[it].ch != '\n' }
        return relevant.isNotEmpty() && relevant.all { flagOf(chars[it], flag) }
    }

    /**
     * Toggle [flag] over [[start], [end]).  If all non-newline chars in the range
     * already have [flag] set, clears it; otherwise sets it on all of them.
     * Newline characters are skipped.
     */
    fun toggleStyle(start: Int, end: Int, flag: StyleFlag) {
        val a = start.coerceIn(0, chars.size)
        val b = end.coerceIn(a, chars.size)
        val all = isStyleActive(a, b, flag)
        for (i in a until b) {
            if (chars[i].ch != '\n') chars[i] = withFlag(chars[i], flag, !all)
        }
    }

    // ---- colour / link ----

    /**
     * Set the text colour of the non-newline chars in [[start], [end]); null clears it.
     * A colour outside [RichPalette] is ignored (the contract allows only the palette).
     */
    fun setColor(start: Int, end: Int, color: Int?) {
        if (color != null && !RichPalette.contains(color)) return
        val argb = color?.let { RichPalette.entryOf(it)!!.argb }
        forRange(start, end) { it.copy(color = argb) }
    }

    /**
     * Make the non-newline chars in [[start], [end]) a link to [url]; null removes the
     * link. A URL that fails [RichText.isAllowedHref] is ignored.
     */
    fun setLink(start: Int, end: Int, url: String?) {
        if (url != null && !RichText.isAllowedHref(url)) return
        val target = url?.trim()
        forRange(start, end) { it.copy(link = target) }
    }

    private fun forRange(start: Int, end: Int, change: (RChar) -> RChar) {
        val a = start.coerceIn(0, chars.size)
        val b = end.coerceIn(a, chars.size)
        for (i in a until b) {
            if (chars[i].ch != '\n') chars[i] = change(chars[i])
        }
    }

    // ---- line formats ----

    /** Index of the first char of the line containing [offset]. */
    private fun lineStart(offset: Int): Int {
        var i = offset.coerceIn(0, chars.size)
        while (i > 0 && chars[i - 1].ch != '\n') i--
        return i
    }

    /** The [LineFormat] of the line containing [offset] (default for an empty line). */
    fun lineFormatAt(offset: Int): LineFormat {
        val ls = lineStart(offset)
        return chars.getOrNull(ls)?.takeIf { it.ch != '\n' }?.line ?: LineFormat()
    }

    /**
     * Set the [LineFormat] of the line containing [offset]. An empty line has no char to
     * carry it, so it is left unchanged. Heading levels other than 2 and 3 clear the heading.
     */
    fun setLineFormat(offset: Int, format: LineFormat) {
        val ls = lineStart(offset)
        if (ls >= chars.size || chars[ls].ch == '\n') return
        val heading = if (format.heading == 2 || format.heading == 3) format.heading else 0
        chars[ls] = chars[ls].copy(line = format.copy(heading = heading))
    }

    // ---- serialisation ----

    /**
     * Convert to a merged [List<RichSpan>] (adjacent same-style chars merged),
     * suitable for rendering or saving.  Mirrors [RichTextEditor.getSpans].
     */
    fun toSpans(): List<RichSpan> {
        if (chars.isEmpty()) return emptyList()
        val spans = mutableListOf<RichSpan>()
        val plain = Style()
        var runKey: Style? = null
        var runBullet = false
        var runLine = LineFormat()
        var line = LineFormat()
        val sb = StringBuilder()

        fun flush() {
            val k = runKey ?: return
            if (sb.isNotEmpty()) {
                spans.add(
                    RichSpan(
                        sb.toString(),
                        k.bold, k.italic, k.underline, k.strikethrough,
                        runBullet, k.color, k.link,
                        ordered = runBullet && runLine.list == ListKind.ORDERED,
                        heading = runLine.heading,
                        quote = runLine.quote,
                        code = k.code,
                    )
                )
                sb.clear()
            }
            runBullet = false
        }

        chars.forEachIndexed { i, rc ->
            val lineStart = i == 0 || chars[i - 1].ch == '\n'
            if (lineStart) line = if (rc.ch == '\n') LineFormat() else rc.line
            val key = if (rc.ch == '\n') plain else styleKeyOf(rc)
            // Newlines carry no block format, so a heading/quote never leaks into the gap.
            val blockKey = if (rc.ch == '\n') LineFormat() else line.copy(list = ListKind.NONE)
            val bulletStart = lineStart && rc.ch != '\n' && rc.line.list != ListKind.NONE
            if (key != runKey || blockKey != runLine.copy(list = ListKind.NONE) || bulletStart) {
                flush()
                runKey = key
                runLine = if (rc.ch == '\n') LineFormat() else line
                if (bulletStart) runBullet = true
            }
            sb.append(rc.ch)
        }
        flush()
        return spans
    }

    /**
     * Serialize to sanitizer-allowed website HTML via [RichText.spansToHtml].
     * Returns "" when the document is empty or contains only whitespace.
     */
    fun toHtml(): String {
        val spans = toSpans()
        return if (spans.isEmpty()) "" else RichText.spansToHtml(spans)
    }

    // ---- private helpers ----

    private fun styleKeyOf(rc: RChar): Style =
        Style(rc.bold, rc.italic, rc.underline, rc.strikethrough, rc.color, rc.link, rc.code)

    private fun flagOf(rc: RChar, flag: StyleFlag): Boolean = when (flag) {
        StyleFlag.BOLD -> rc.bold
        StyleFlag.ITALIC -> rc.italic
        StyleFlag.UNDERLINE -> rc.underline
        StyleFlag.STRIKETHROUGH -> rc.strikethrough
        StyleFlag.CODE -> rc.code
    }

    private fun withFlag(rc: RChar, flag: StyleFlag, value: Boolean): RChar = when (flag) {
        StyleFlag.BOLD -> rc.copy(bold = value)
        StyleFlag.ITALIC -> rc.copy(italic = value)
        StyleFlag.UNDERLINE -> rc.copy(underline = value)
        StyleFlag.STRIKETHROUGH -> rc.copy(strikethrough = value)
        StyleFlag.CODE -> rc.copy(code = value)
    }
}
