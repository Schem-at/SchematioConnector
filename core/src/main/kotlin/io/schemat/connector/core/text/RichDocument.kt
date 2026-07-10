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
                            bullet = span.bullet && i == 0,
                        )
                    )
                }
            }
            return RichDocument(chars)
        }

        /** Build from sanitizer-allowed website HTML (via [RichText.htmlToSpans]). */
        fun fromHtml(html: String): RichDocument = fromSpans(RichText.htmlToSpans(html))
    }

    // ---- nested types ----

    /** The formatting flags the ImGui toolbar can toggle. */
    enum class StyleFlag { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }

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
        /** Set on the first character of a bullet line (line-level flag). */
        val bullet: Boolean = false,
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
        return Style(rc.bold, rc.italic, rc.underline, rc.strikethrough, rc.color, rc.link)
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
            chars.add(insertAt, RChar(c, style.bold, style.italic, style.underline, style.strikethrough, style.color, style.link))
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
        val sb = StringBuilder()

        fun flush() {
            val k = runKey ?: return
            if (sb.isNotEmpty()) {
                spans.add(
                    RichSpan(
                        sb.toString(),
                        k.bold, k.italic, k.underline, k.strikethrough,
                        runBullet, k.color, k.link,
                    )
                )
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
        Style(rc.bold, rc.italic, rc.underline, rc.strikethrough, rc.color, rc.link)

    private fun flagOf(rc: RChar, flag: StyleFlag): Boolean = when (flag) {
        StyleFlag.BOLD -> rc.bold
        StyleFlag.ITALIC -> rc.italic
        StyleFlag.UNDERLINE -> rc.underline
        StyleFlag.STRIKETHROUGH -> rc.strikethrough
    }

    private fun withFlag(rc: RChar, flag: StyleFlag, value: Boolean): RChar = when (flag) {
        StyleFlag.BOLD -> rc.copy(bold = value)
        StyleFlag.ITALIC -> rc.copy(italic = value)
        StyleFlag.UNDERLINE -> rc.copy(underline = value)
        StyleFlag.STRIKETHROUGH -> rc.copy(strikethrough = value)
    }
}
