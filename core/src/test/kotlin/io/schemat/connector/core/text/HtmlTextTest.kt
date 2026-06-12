package io.schemat.connector.core.text

import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [HtmlText] converts between the website's rich-text-editor HTML descriptions
 * (stored raw, rendered in a `prose` container) and plain text shown in the mod.
 */
class HtmlTextTest {

    @Nested
    @DisplayName("toPlain")
    inner class ToPlain {

        @Test
        fun `plain text is returned unchanged`() {
            assertEquals("Just a plain description.", HtmlText.toPlain("Just a plain description."))
            assertEquals("Line one\nLine two", HtmlText.toPlain("Line one\nLine two"))
        }

        @Test
        fun `empty string stays empty`() {
            assertEquals("", HtmlText.toPlain(""))
        }

        @Test
        fun `inline formatting tags are stripped`() {
            assertEquals(
                "A bold and italic castle",
                HtmlText.toPlain("A <strong>bold</strong> and <em>italic</em> castle"),
            )
        }

        @Test
        fun `tags with attributes are stripped`() {
            assertEquals(
                "See the wiki",
                HtmlText.toPlain("""See <a href="https://example.com" target="_blank">the wiki</a>"""),
            )
        }

        @Test
        fun `br variants become newlines`() {
            assertEquals("a\nb\nc\nd", HtmlText.toPlain("a<br>b<br/>c<br />d"))
            assertEquals("a\nb", HtmlText.toPlain("a<BR>b"))
        }

        @Test
        fun `paragraphs become blank-line separated blocks`() {
            assertEquals("First", HtmlText.toPlain("<p>First</p>"))
            assertEquals("First\n\nSecond", HtmlText.toPlain("<p>First</p><p>Second</p>"))
            assertEquals("First\n\nSecond", HtmlText.toPlain("""<p class="intro">First</p><div>Second</div>"""))
        }

        @Test
        fun `list items become bulleted lines`() {
            assertEquals(
                "• One\n• Two",
                HtmlText.toPlain("<ul><li>One</li><li>Two</li></ul>"),
            )
        }

        @Test
        fun `nested structure flattens to readable text`() {
            val html = "<div><p>Features:</p><ul><li><strong>Fast</strong></li><li>Cheap</li></ul></div>"
            assertEquals("Features:\n\n• Fast\n• Cheap", HtmlText.toPlain(html))
        }

        @Test
        fun `named entities are decoded`() {
            assertEquals(
                "Stone & Wood <3 \"quoted\" 'single' here",
                HtmlText.toPlain("Stone &amp; Wood &lt;3 &quot;quoted&quot; &#39;single&#39;&nbsp;here"),
            )
        }

        @Test
        fun `numeric entities decimal and hex are decoded`() {
            assertEquals("A'B", HtmlText.toPlain("A&#39;B"))
            assertEquals("A'B", HtmlText.toPlain("A&#x27;B"))
            assertEquals("é", HtmlText.toPlain("&#233;"))
            assertEquals("é", HtmlText.toPlain("&#xE9;"))
        }

        @Test
        fun `unknown or invalid entities are left untouched`() {
            assertEquals("&bogus; &#xZZ; & bare", HtmlText.toPlain("&bogus; &#xZZ; &amp; bare"))
        }

        @Test
        fun `escaped markup survives as literal text, not as tags`() {
            // &lt;b&gt; is literal text on the website, so it must stay literal here.
            assertEquals("<b>not bold</b>", HtmlText.toPlain("&lt;b&gt;not bold&lt;/b&gt;"))
        }

        @Test
        fun `runs of three or more newlines collapse to one blank line`() {
            assertEquals("a\n\nb", HtmlText.toPlain("<p>a</p>\n\n<p>b</p>"))
            assertEquals("a\n\nb", HtmlText.toPlain("a<br><br><br><br>b"))
        }

        @Test
        fun `malformed html never throws`() {
            assertEquals("unterminated <tag here", HtmlText.toPlain("unterminated <tag here"))
            assertEquals("stray > bracket", HtmlText.toPlain("stray > bracket"))
            assertEquals("text", HtmlText.toPlain("<p><b>text</p></b>"))
            assertEquals("", HtmlText.toPlain("<p></p><div></div>"))
            // No well-formed tag inside - left as literal text rather than guessed at.
            assertEquals("<<<>>>x", HtmlText.toPlain("<<<>>>x"))
        }

        @Test
        fun `comments are removed`() {
            assertEquals("visible", HtmlText.toPlain("<!-- hidden -->visible"))
        }

        @Test
        fun `result is trimmed`() {
            assertEquals("hello", HtmlText.toPlain("  <p>hello</p>  "))
        }
    }

    @Nested
    @DisplayName("toHtml")
    inner class ToHtml {

        @Test
        fun `single paragraph is wrapped in p`() {
            assertEquals("<p>Hello world</p>", HtmlText.toHtml("Hello world"))
        }

        @Test
        fun `double newlines split paragraphs`() {
            assertEquals("<p>First</p><p>Second</p>", HtmlText.toHtml("First\n\nSecond"))
            assertEquals("<p>First</p><p>Second</p>", HtmlText.toHtml("First\n\n\n\nSecond"))
        }

        @Test
        fun `single newlines become br within a paragraph`() {
            assertEquals("<p>Line one<br>Line two</p>", HtmlText.toHtml("Line one\nLine two"))
        }

        @Test
        fun `special characters are escaped`() {
            assertEquals(
                "<p>Stone &amp; Wood &lt;3 &quot;quoted&quot; &#39;single&#39;</p>",
                HtmlText.toHtml("Stone & Wood <3 \"quoted\" 'single'"),
            )
        }

        @Test
        fun `html typed by the user is escaped, not interpreted`() {
            assertEquals(
                "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>",
                HtmlText.toHtml("<script>alert(1)</script>"),
            )
        }

        @Test
        fun `blank input yields empty string`() {
            assertEquals("", HtmlText.toHtml(""))
            assertEquals("", HtmlText.toHtml("   \n\n  "))
        }

        @Test
        fun `windows line endings are handled`() {
            assertEquals("<p>a<br>b</p><p>c</p>", HtmlText.toHtml("a\r\nb\r\n\r\nc"))
        }
    }

    @Nested
    @DisplayName("round trip")
    inner class RoundTrip {

        @Test
        fun `toPlain of toHtml is identity for typical multi-paragraph text`() {
            val original = "A cozy cottage & garden.\nBuilt for <survival> use.\n\n" +
                "Second paragraph with \"quotes\" and 'apostrophes'.\n\nThird."
            assertEquals(original, HtmlText.toPlain(HtmlText.toHtml(original)))
        }

        @Test
        fun `toPlain of toHtml is identity for a single line`() {
            assertEquals("Just one line", HtmlText.toPlain(HtmlText.toHtml("Just one line")))
        }

        @Test
        fun `editor-style description converts to readable plain text`() {
            // Shape produced by the website's flux:editor for a typical description.
            val html = "<p>My <strong>castle</strong> build.</p>" +
                "<p>Features:</p><ul><li>Drawbridge</li><li>Moat &amp; towers</li></ul>"
            assertEquals(
                "My castle build.\n\nFeatures:\n\n• Drawbridge\n• Moat & towers",
                HtmlText.toPlain(html),
            )
        }
    }
}
