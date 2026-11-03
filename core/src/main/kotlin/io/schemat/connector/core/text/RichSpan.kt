package io.schemat.connector.core.text

/**
 * One styled run of a rich-text description.
 *
 * A description is a flat `List<RichSpan>`; [text] may contain `\n` (one for a line
 * break, two for a paragraph break - never more). [bullet] marks a span that begins a
 * list item: such spans always start at the beginning of a visual line (the producing
 * parsers emit the separating newlines in a preceding span), so a renderer prefixes
 * the line with "• ".
 *
 * No Minecraft dependencies - fabric converts spans to styled `Text` in
 * `RichTextRender`.
 */
data class RichSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val bullet: Boolean = false,
    /** ARGB text color, always a [RichPalette] colour (`<span style="color:#aa0000">`); null = theme default. */
    val color: Int? = null,
    /** Target URL when this span is a link (`<a href>` or an autolinked bare URL). */
    val link: String? = null,
    /** On a [bullet] span: the item belongs to a numbered (`<ol>`) list. */
    val ordered: Boolean = false,
    /** Heading level of the line this span sits on: 0 = body text, 2 = `<h2>`, 3 = `<h3>`. */
    val heading: Int = 0,
    /** The span sits inside a `<blockquote>`. */
    val quote: Boolean = false,
    /** Inline `<code>`. */
    val code: Boolean = false,
) {
    /** True when both spans carry the same formatting (bullet and ordered excluded). */
    internal fun sameFormatting(other: RichSpan): Boolean =
        bold == other.bold && italic == other.italic &&
            underline == other.underline && strikethrough == other.strikethrough &&
            color == other.color && link == other.link &&
            heading == other.heading && quote == other.quote && code == other.code
}
