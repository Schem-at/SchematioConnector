package io.schemat.connector.core.text

/**
 * Conversion between the website's HTML schematic descriptions and plain text.
 *
 * Descriptions are authored on the website with a rich-text editor (`flux:editor`),
 * stored as HTML and rendered inside a `prose` container; the API returns them raw
 * (`SchematicResource`). The mod displays them as plain text via [toPlain] and saves
 * edits via [toHtml].
 *
 * [toHtml] deliberately emits only `<p>` and `<br>` with all user text entity-escaped:
 * both tags are in the site sanitizer's allowlist (`App\Helpers\Sanitizer::cleanHtml`)
 * and match what the website editor produces for paragraph/line breaks, so a saved
 * edit renders on the web exactly like an editor-authored description.
 *
 * No Minecraft dependencies. Both functions are regex-based and never throw, even on
 * malformed HTML; [toPlain] is a no-op on plain text (modulo whitespace trimming).
 */
object HtmlText {

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val BR = Regex("""<br\s*/?\s*>""", RegexOption.IGNORE_CASE)

    // Block-level closes become paragraph breaks; the collapse pass caps runs at one blank line.
    private val BLOCK_CLOSE = Regex("""</\s*(?:p|div)\s*>""", RegexOption.IGNORE_CASE)
    private val LI_CLOSE = Regex("""</\s*li\s*>""", RegexOption.IGNORE_CASE)
    private val LI_OPEN = Regex("""<li(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE)

    // Any remaining well-formed open/close tag; unterminated "<foo" is left as literal text.
    private val ANY_TAG = Regex("""</?[a-zA-Z][^>]*>""")

    private val ENTITY = Regex("""&(#[xX]?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);""")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
    )

    private val TRAILING_WHITESPACE = Regex("""[ \t]+\n""")
    private val EXCESS_NEWLINES = Regex("\n{3,}")
    private val PARAGRAPH_SPLIT = Regex("\n{2,}")

    /**
     * Convert a website HTML description to readable plain text.
     *
     * `<br>` becomes a newline, `</p>`/`</div>` a paragraph break, `<li>` a bulleted
     * line; every other tag is stripped, entities (named + numeric) are decoded, runs
     * of 3+ newlines collapse to a blank line and the result is trimmed. Safe on plain
     * text (returned unchanged) and on malformed HTML (never throws).
     */
    fun toPlain(html: String): String {
        var text = html.replace("\r\n", "\n")
        text = COMMENT.replace(text, "")
        text = BR.replace(text, "\n")
        text = BLOCK_CLOSE.replace(text, "\n\n")
        text = LI_CLOSE.replace(text, "\n")
        text = LI_OPEN.replace(text, "• ")
        text = ANY_TAG.replace(text, "")
        // Decode after stripping so escaped markup ("&lt;b&gt;") survives as literal text.
        text = decodeEntities(text)
        text = TRAILING_WHITESPACE.replace(text, "\n")
        text = EXCESS_NEWLINES.replace(text, "\n\n")
        return text.trim()
    }

    /**
     * Inverse of [toPlain] for saving plain-text edits back to the website: escape
     * `& < > " '`, wrap blank-line-separated chunks in `<p>…</p>` and turn remaining
     * single newlines into `<br>`. Blank input yields an empty string.
     *
     * For typical multi-paragraph text, `toPlain(toHtml(x)) == x`.
     */
    fun toHtml(plain: String): String {
        val normalized = plain.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return ""
        val escaped = escapeHtml(normalized)
        return PARAGRAPH_SPLIT.split(escaped)
            .filter { it.isNotBlank() }
            .joinToString("") { paragraph -> "<p>${paragraph.trim().replace("\n", "<br>")}</p>" }
    }

    /** Entity-escape user text for embedding in HTML (shared with [RichText]). */
    internal fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /** Single-pass entity decode; unknown or invalid entities are left untouched (shared with [RichText]). */
    internal fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.toSafeString() ?: match.value
            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.toSafeString() ?: match.value
            else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
        }
    }

    /** A code point as a string, or null when it is not a valid Unicode scalar value. */
    private fun Int.toSafeString(): String? = try {
        if (Character.isValidCodePoint(this) && this > 0) String(Character.toChars(this)) else null
    } catch (e: Exception) {
        null
    }
}
