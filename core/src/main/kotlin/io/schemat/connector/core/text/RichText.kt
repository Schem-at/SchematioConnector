package io.schemat.connector.core.text

/**
 * Rich-text descriptions: parse the website's HTML into styled [RichSpan]s for
 * display, and convert between HTML and the mod's editor markup.
 *
 * **HTML** (what the website stores; `flux:editor` output, sanitized by
 * `App\Helpers\Sanitizer::cleanHtml`): `<b>/<strong>`, `<i>/<em>`, `<u>`,
 * `<s>/<strike>/<del>`, `<p>`, `<br>`, `<ul>/<li>`, plus
 * `<span style="color: ...">` and `<a href="...">` (viewing only - [markupToHtml]
 * still emits only the plain allowlist: `p`, `br`, `strong`, `em`, `u`, `s`, `ul`, `li`).
 *
 * **Markup** (what the mod's description editor edits; markdown-ish):
 * `**bold**`, `*italic*` (or `_italic_`), `__underline__`, `~~strike~~`,
 * `\n` = line break, blank line = paragraph break, a `- ` line prefix = bullet.
 * A backslash escapes a literal marker character (`\*`, `\_`, `\~`, `\-`, `\\`);
 * an opening marker with no closer on the same line stays literal text.
 *
 * All functions are tolerant of malformed input and never throw; runs of 3+
 * newlines collapse to a blank line. For basic formatting,
 * `htmlToMarkup(markupToHtml(m)) == m`.
 */
object RichText {

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    /** Any well-formed open/close tag; unterminated "<foo" stays literal text. */
    private val TAG = Regex("""</?[a-zA-Z][^>]*>""")
    private val TAG_NAME = Regex("""</?\s*([a-zA-Z][a-zA-Z0-9]*)""")

    private val PARAGRAPH_SPLIT = Regex("\n{2,}")
    private val EXCESS_NEWLINES = Regex("\n{3,}")

    private val STYLE_ATTR = Regex("""style\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val HREF_ATTR = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    /** The `color:` declaration of a style attribute (not `background-color:` etc.). */
    private val STYLE_COLOR = Regex("""(?:^|;)\s*color\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
    private val HEX_COLOR = Regex("""^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$""")
    private val RGB_COLOR = Regex("""^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})""", RegexOption.IGNORE_CASE)

    /** A bare URL in plain text, autolinked when not already inside an `<a>`. */
    private val BARE_URL = Regex("""https?://[^\s<>"']+""")

    /** Trailing punctuation that is prose, not part of an autolinked URL. */
    private const val URL_TRAILERS = ".,;:!?)]"

    // ---- HTML -> spans ----

    /**
     * Parse website HTML into styled spans: inline tags set formatting flags (nested
     * tags combine), `<br>`/`</p>`/`</div>`/`</li>` produce line breaks, `<li>` starts
     * a bullet span, `<span style="color: ...">` sets [RichSpan.color] (hex or
     * `rgb(...)`), `<a href>` sets [RichSpan.link] (bare http/https URLs in plain text
     * autolink too); color/link propagate to nested children. Unknown tags are stripped
     * (their text kept) and entities decoded. Never throws.
     */
    fun htmlToSpans(html: String): List<RichSpan> {
        val source = COMMENT.replace(html.replace("\r\n", "\n"), "")
        val spans = mutableListOf<RichSpan>()
        var bold = 0
        var italic = 0
        var underline = 0
        var strike = 0
        var pending = 0
        var bulletPending = false
        var started = false
        // One entry per open <span>/<a> (null when the tag carries no color/href),
        // so each closing tag pops its own entry and nesting propagates naturally.
        val colors = mutableListOf<Int?>()
        val links = mutableListOf<String?>()

        fun appendSpan(raw: String, linkOverride: String? = null) {
            val text = if (started) raw else raw.trimStart()
            if (text.isEmpty()) return
            if (started && pending > 0) spans.add(RichSpan("\n".repeat(pending)))
            pending = 0
            spans.add(
                RichSpan(
                    text,
                    bold = bold > 0,
                    italic = italic > 0,
                    underline = underline > 0,
                    strikethrough = strike > 0,
                    bullet = bulletPending,
                    color = colors.lastOrNull { it != null },
                    link = linkOverride ?: links.lastOrNull { it != null },
                )
            )
            bulletPending = false
            started = true
        }

        /** Inline text outside an `<a>`: autolink any bare http/https URLs. */
        fun appendAutolinked(part: String) {
            if (links.any { it != null }) {
                appendSpan(part)
                return
            }
            var pos = 0
            for (match in BARE_URL.findAll(part)) {
                val url = match.value.trimEnd { it in URL_TRAILERS }
                if (url.length <= "https://".length) continue
                if (match.range.first > pos) appendSpan(part.substring(pos, match.range.first))
                appendSpan(url, linkOverride = url)
                pos = match.range.first + url.length
            }
            if (pos < part.length) appendSpan(part.substring(pos))
        }

        fun emitChunk(raw: String) {
            val decoded = HtmlText.decodeEntities(raw)
            decoded.split("\n").forEachIndexed { index, part ->
                if (index > 0) pending = (pending + 1).coerceAtMost(2)
                when {
                    part.isEmpty() -> {}
                    // Whitespace between tags: meaningful only inline (no break pending).
                    part.isBlank() -> if (started && pending == 0) appendSpan(" ")
                    else -> appendAutolinked(part)
                }
            }
        }

        var index = 0
        for (match in TAG.findAll(source)) {
            if (match.range.first > index) emitChunk(source.substring(index, match.range.first))
            index = match.range.last + 1
            val closing = match.value.length > 1 && match.value[1] == '/'
            when (TAG_NAME.find(match.value)?.groupValues?.get(1)?.lowercase()) {
                "b", "strong" -> if (closing) bold = (bold - 1).coerceAtLeast(0) else bold++
                "i", "em" -> if (closing) italic = (italic - 1).coerceAtLeast(0) else italic++
                "u" -> if (closing) underline = (underline - 1).coerceAtLeast(0) else underline++
                "s", "strike", "del" -> if (closing) strike = (strike - 1).coerceAtLeast(0) else strike++
                "span" ->
                    if (closing) {
                        if (colors.isNotEmpty()) colors.removeAt(colors.lastIndex)
                    } else {
                        colors.add(parseStyleColor(match.value))
                    }
                "a" ->
                    if (closing) {
                        if (links.isNotEmpty()) links.removeAt(links.lastIndex)
                    } else {
                        links.add(parseHref(match.value))
                    }
                "br" -> pending = (pending + 1).coerceAtMost(2)
                "p", "div" -> if (closing) pending = 2
                "ul", "ol" -> if (closing) pending = 2
                "li" ->
                    if (closing) {
                        pending = pending.coerceAtLeast(1)
                    } else {
                        // A bullet always starts exactly one line below whatever precedes
                        // it (even right after a </p>), matching the markup "intro\n- item".
                        if (started) pending = 1
                        bulletPending = true
                    }
                else -> {} // Unknown tag: stripped, its text kept.
            }
        }
        if (index < source.length) emitChunk(source.substring(index))

        // Trailing whitespace never renders - trim it off the end.
        while (spans.isNotEmpty()) {
            val last = spans.last()
            val trimmed = last.text.trimEnd()
            if (trimmed.isEmpty()) {
                spans.removeAt(spans.lastIndex)
            } else {
                if (trimmed != last.text) spans[spans.lastIndex] = last.copy(text = trimmed)
                break
            }
        }
        return merge(spans)
    }

    // ---- markup -> spans (live preview) ----

    /** Parse editor markup into styled spans (same span model as [htmlToSpans]). */
    fun markupToSpans(markup: String): List<RichSpan> {
        val normalized = markup.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        val spans = mutableListOf<RichSpan>()
        var pending = 0
        var started = false
        for (line in normalized.split("\n")) {
            if (started) pending = (pending + 1).coerceAtMost(2)
            if (line.isBlank()) continue
            val bullet = line.startsWith("- ")
            val lineSpans = parseInline(if (bullet) line.substring(2) else line)
            if (lineSpans.isEmpty()) continue
            if (started && pending > 0) spans.add(RichSpan("\n".repeat(pending)))
            pending = 0
            spans.add(lineSpans.first().copy(bullet = bullet))
            spans.addAll(lineSpans.drop(1))
            started = true
        }
        return merge(spans)
    }

    // ---- markup -> HTML (saving) ----

    /**
     * Convert editor markup to website HTML for saving. Emits only sanitizer-allowed
     * tags: `<p>`/`<br>` structure, `<strong>/<em>/<u>/<s>` inline, `<ul><li>` for
     * runs of `- ` lines; user text is entity-escaped. Blank input yields "".
     */
    fun markupToHtml(markup: String): String {
        val normalized = markup.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return ""
        val sb = StringBuilder()
        for (block in PARAGRAPH_SPLIT.split(normalized)) {
            if (block.isBlank()) continue
            val lines = block.split("\n")
            var i = 0
            while (i < lines.size) {
                if (lines[i].startsWith("- ")) {
                    sb.append("<ul>")
                    while (i < lines.size && lines[i].startsWith("- ")) {
                        sb.append("<li>").append(inlineHtml(lines[i].substring(2))).append("</li>")
                        i++
                    }
                    sb.append("</ul>")
                } else {
                    val paragraph = mutableListOf<String>()
                    while (i < lines.size && !lines[i].startsWith("- ")) {
                        paragraph.add(inlineHtml(lines[i]))
                        i++
                    }
                    sb.append("<p>").append(paragraph.joinToString("<br>")).append("</p>")
                }
            }
        }
        return sb.toString()
    }

    // ---- HTML -> markup (loading into the editor) ----

    /**
     * Convert website HTML to editor markup, the inverse of [markupToHtml]: formatting
     * becomes markers, bullets become `- ` lines, paragraph breaks blank lines; literal
     * marker characters in the text are backslash-escaped so they survive a round-trip.
     */
    fun htmlToMarkup(html: String): String {
        val spans = htmlToSpans(html)
        if (spans.isEmpty()) return ""

        class Line(var bullet: Boolean = false, val segments: MutableList<RichSpan> = mutableListOf())

        val lines = mutableListOf(Line())
        for (span in spans) {
            span.text.split("\n").forEachIndexed { index, part ->
                if (index > 0) lines.add(Line())
                val line = lines.last()
                if (span.bullet && index == 0 && line.segments.isEmpty()) line.bullet = true
                if (part.isNotEmpty()) line.segments.add(span.copy(text = part, bullet = false))
            }
        }
        return lines.joinToString("\n") { line ->
            val body = line.segments.joinToString("") { wrapMarkers(it) }
            when {
                line.bullet -> "- $body"
                body.startsWith("- ") -> "\\$body" // literal "- " must not round-trip into a bullet
                else -> body
            }
        }.trim()
    }

    // ---- spans -> HTML (saving from the inline editor) ----

    /**
     * Serialize styled spans back to sanitizer-allowed website HTML, the inverse of
     * [htmlToSpans] for the supported features: paragraphs become `<p>` (single
     * newlines inside one become `<br>`), bullet runs become `<ul><li>`, formatting
     * becomes `<strong>/<em>/<u>/<s>`, [RichSpan.color] becomes
     * `<span style="color: #rrggbb;">` and [RichSpan.link] becomes `<a href>`; text is
     * entity-escaped. `htmlToSpans(spansToHtml(spans))` reproduces the spans for
     * content produced by [htmlToSpans] (one caveat: text directly after a bullet run
     * starts a new paragraph, so a single newline there widens to a paragraph break).
     */
    fun spansToHtml(spans: List<RichSpan>): String {
        class Line(var bullet: Boolean = false, val segments: MutableList<RichSpan> = mutableListOf()) {
            val isEmpty get() = !bullet && segments.isEmpty()
        }

        val lines = mutableListOf(Line())
        for (span in spans) {
            span.text.split("\n").forEachIndexed { index, part ->
                if (index > 0) lines.add(Line())
                val line = lines.last()
                if (span.bullet && index == 0 && line.segments.isEmpty()) line.bullet = true
                if (part.isNotEmpty()) line.segments.add(span.copy(text = part, bullet = false))
            }
        }
        while (lines.isNotEmpty() && lines.first().isEmpty) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isEmpty) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) return ""

        val sb = StringBuilder()
        var i = 0
        while (i < lines.size) {
            when {
                lines[i].isEmpty -> i++ // Paragraph gap - the surrounding blocks imply it.
                lines[i].bullet -> {
                    sb.append("<ul>")
                    while (i < lines.size && lines[i].bullet) {
                        sb.append("<li>").append(spanRunHtml(lines[i].segments)).append("</li>")
                        i++
                    }
                    sb.append("</ul>")
                }
                else -> {
                    val rows = mutableListOf<String>()
                    while (i < lines.size && !lines[i].bullet && !lines[i].isEmpty) {
                        rows.add(spanRunHtml(lines[i].segments))
                        i++
                    }
                    sb.append("<p>").append(rows.joinToString("<br>")).append("</p>")
                }
            }
        }
        return sb.toString()
    }

    /** One line's segments as inline HTML, innermost formatting closest to the text. */
    private fun spanRunHtml(segments: List<RichSpan>): String =
        segments.joinToString("") { span ->
            buildString {
                span.link?.let { append("<a href=\"").append(HtmlText.escapeHtml(it)).append("\">") }
                span.color?.let { append("<span style=\"color: #%06x;\">".format(it and 0xFFFFFF)) }
                if (span.bold) append("<strong>")
                if (span.italic) append("<em>")
                if (span.underline) append("<u>")
                if (span.strikethrough) append("<s>")
                append(HtmlText.escapeHtml(span.text))
                if (span.strikethrough) append("</s>")
                if (span.underline) append("</u>")
                if (span.italic) append("</em>")
                if (span.bold) append("</strong>")
                if (span.color != null) append("</span>")
                if (span.link != null) append("</a>")
            }
        }

    // ---- html attribute parsing ----

    /** ARGB color from an opening tag's `style="...; color: ...;"`, null when absent/unparseable. */
    private fun parseStyleColor(tag: String): Int? {
        val style = STYLE_ATTR.find(tag)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            ?: return null
        val value = STYLE_COLOR.find(style)?.groupValues?.get(1)?.trim() ?: return null
        return parseCssColor(value)
    }

    /** `#RGB`/`#RRGGBB`/`rgb(r,g,b)`/`rgba(r,g,b,a)` as opaque ARGB, null otherwise. */
    internal fun parseCssColor(value: String): Int? {
        val trimmed = value.trim()
        HEX_COLOR.find(trimmed)?.groupValues?.get(1)?.let { hex ->
            val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
            return full.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
        }
        RGB_COLOR.find(trimmed)?.let { match ->
            val (r, g, b) = match.destructured
            val red = r.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            val green = g.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            val blue = b.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        return null
    }

    /** The `href` of an opening `<a>` tag (entities decoded), null when absent/blank. */
    private fun parseHref(tag: String): String? {
        val raw = HREF_ATTR.find(tag)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            ?: return null
        return HtmlText.decodeEntities(raw).trim().ifEmpty { null }
    }

    // ---- shared internals ----

    /** Inline markers, two-char tokens first so `**` wins over `*`. */
    private val MARKERS = listOf(
        "**" to Flag.BOLD,
        "__" to Flag.UNDERLINE,
        "~~" to Flag.STRIKE,
        "*" to Flag.ITALIC,
        "_" to Flag.ITALIC,
    )

    private enum class Flag { BOLD, ITALIC, UNDERLINE, STRIKE }

    private const val ESCAPABLE = "*_~-\\"

    /**
     * Scan one line of markup into spans. Markers toggle flags; an opening marker with
     * no matching closer later in the line is kept as literal text; `\x` escapes.
     */
    private fun parseInline(line: String): List<RichSpan> {
        val out = mutableListOf<RichSpan>()
        val sb = StringBuilder()
        var bold = false
        var italic = false
        var underline = false
        var strike = false

        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(RichSpan(sb.toString(), bold, italic, underline, strike))
                sb.clear()
            }
        }

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length && line[i + 1] in ESCAPABLE) {
                sb.append(line[i + 1])
                i += 2
                continue
            }
            val marker = MARKERS.firstOrNull { line.startsWith(it.first, i) }
            if (marker == null) {
                sb.append(c)
                i++
                continue
            }
            val (token, flag) = marker
            val active = when (flag) {
                Flag.BOLD -> bold
                Flag.ITALIC -> italic
                Flag.UNDERLINE -> underline
                Flag.STRIKE -> strike
            }
            if (!active && line.indexOf(token, i + token.length) == -1) {
                sb.append(token) // No closer ahead - literal.
                i += token.length
                continue
            }
            flush()
            when (flag) {
                Flag.BOLD -> bold = !bold
                Flag.ITALIC -> italic = !italic
                Flag.UNDERLINE -> underline = !underline
                Flag.STRIKE -> strike = !strike
            }
            i += token.length
        }
        flush()
        return merge(out)
    }

    /** One markup line rendered as inline HTML. */
    private fun inlineHtml(line: String): String =
        parseInline(line).joinToString("") { span ->
            buildString {
                if (span.bold) append("<strong>")
                if (span.italic) append("<em>")
                if (span.underline) append("<u>")
                if (span.strikethrough) append("<s>")
                append(HtmlText.escapeHtml(span.text))
                if (span.strikethrough) append("</s>")
                if (span.underline) append("</u>")
                if (span.italic) append("</em>")
                if (span.bold) append("</strong>")
            }
        }

    /** One span rendered as markup, marker-escaped. */
    private fun wrapMarkers(span: RichSpan): String {
        val prefix = buildString {
            if (span.bold) append("**")
            if (span.italic) append("*")
            if (span.underline) append("__")
            if (span.strikethrough) append("~~")
        }
        val suffix = buildString {
            if (span.strikethrough) append("~~")
            if (span.underline) append("__")
            if (span.italic) append("*")
            if (span.bold) append("**")
        }
        return prefix + escapeMarkup(span.text) + suffix
    }

    private fun escapeMarkup(text: String): String = text
        .replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("~", "\\~")

    /** Merge adjacent spans with identical formatting (a bullet span never merges back). */
    private fun merge(spans: List<RichSpan>): List<RichSpan> {
        val out = mutableListOf<RichSpan>()
        for (span in spans) {
            val last = out.lastOrNull()
            if (last != null && !span.bullet && last.sameFormatting(span)) {
                out[out.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                out.add(span)
            }
        }
        // Defensive: never let more than a blank line through, wherever it came from.
        return out.map { span ->
            if (span.text.contains("\n\n\n")) span.copy(text = EXCESS_NEWLINES.replace(span.text, "\n\n")) else span
        }
    }
}
