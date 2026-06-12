package io.schemat.connector.core.text

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [RichText] parses website HTML descriptions into styled [RichSpan]s and converts
 * between HTML and the description editor's markdown-ish markup.
 */
class RichTextTest {

    private fun span(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        underline: Boolean = false,
        strikethrough: Boolean = false,
        bullet: Boolean = false,
    ) = RichSpan(text, bold, italic, underline, strikethrough, bullet)

    @Nested
    @DisplayName("htmlToSpans")
    inner class HtmlToSpans {

        @Test
        fun `plain text becomes a single unstyled span`() {
            assertEquals(listOf(span("Just a description.")), RichText.htmlToSpans("Just a description."))
        }

        @Test
        fun `empty input yields no spans`() {
            assertEquals(emptyList(), RichText.htmlToSpans(""))
            assertEquals(emptyList(), RichText.htmlToSpans("   \n  "))
        }

        @Test
        fun `strong and b set bold`() {
            assertEquals(
                listOf(span("A "), span("bold", bold = true), span(" castle")),
                RichText.htmlToSpans("A <strong>bold</strong> castle"),
            )
            assertEquals(
                listOf(span("bold", bold = true)),
                RichText.htmlToSpans("<b>bold</b>"),
            )
        }

        @Test
        fun `em and i set italic`() {
            assertEquals(
                listOf(span("slanted", italic = true)),
                RichText.htmlToSpans("<em>slanted</em>"),
            )
            assertEquals(
                listOf(span("slanted", italic = true)),
                RichText.htmlToSpans("<i>slanted</i>"),
            )
        }

        @Test
        fun `u sets underline`() {
            assertEquals(
                listOf(span("under", underline = true)),
                RichText.htmlToSpans("<u>under</u>"),
            )
        }

        @Test
        fun `s strike and del set strikethrough`() {
            for (tag in listOf("s", "strike", "del")) {
                assertEquals(
                    listOf(span("gone", strikethrough = true)),
                    RichText.htmlToSpans("<$tag>gone</$tag>"),
                    "tag <$tag>",
                )
            }
        }

        @Test
        fun `nested tags combine flags`() {
            assertEquals(
                listOf(
                    span("a", bold = true),
                    span("b", bold = true, italic = true),
                    span("c", bold = true, italic = true, underline = true),
                ),
                RichText.htmlToSpans("<strong>a<em>b<u>c</u></em></strong>"),
            )
        }

        @Test
        fun `br and paragraph closes become newlines`() {
            assertEquals(
                listOf(span("one\ntwo\n\nthree")),
                RichText.htmlToSpans("<p>one<br>two</p><p>three</p>"),
            )
        }

        @Test
        fun `runs of breaks collapse to one blank line`() {
            assertEquals(
                listOf(span("a\n\nb")),
                RichText.htmlToSpans("a<br><br><br><br>b"),
            )
            assertEquals(
                listOf(span("a\n\nb")),
                RichText.htmlToSpans("<p>a</p><p></p><p></p><p>b</p>"),
            )
        }

        @Test
        fun `list items become bullet spans on their own lines`() {
            assertEquals(
                listOf(
                    span("Features:\n"),
                    span("fast\n", bullet = true),
                    span("cheap", bullet = true),
                ),
                RichText.htmlToSpans("<p>Features:</p><ul><li>fast</li><li>cheap</li></ul>"),
            )
        }

        @Test
        fun `formatting inside a list item keeps the bullet on the first span`() {
            assertEquals(
                listOf(span("very", bold = true, bullet = true), span(" fast")),
                RichText.htmlToSpans("<ul><li><strong>very</strong> fast</li></ul>"),
            )
        }

        @Test
        fun `unknown tags are stripped but their text kept`() {
            assertEquals(
                listOf(span("See the wiki for more")),
                RichText.htmlToSpans("""See <code>the wiki</code> for <blockquote class="x">more</blockquote>"""),
            )
            // <a>/<span> without href/color contribute no styling and the text merges.
            assertEquals(
                listOf(span("See the wiki for more")),
                RichText.htmlToSpans("""See <a>the wiki</a> for <span class="x">more</span>"""),
            )
        }

        @Test
        fun `entities are decoded, named and numeric`() {
            assertEquals(
                listOf(span("Tom & Jerry <3 \"quotes\" A")),
                RichText.htmlToSpans("Tom &amp; Jerry &lt;3 &quot;quotes&quot; &#65;"),
            )
            assertEquals(
                listOf(span("A", bold = true)),
                RichText.htmlToSpans("<b>&#x41;</b>"),
            )
        }

        @Test
        fun `escaped markup survives as literal text`() {
            assertEquals(
                listOf(span("<b>not bold</b>")),
                RichText.htmlToSpans("&lt;b&gt;not bold&lt;/b&gt;"),
            )
        }

        @Test
        fun `malformed html never throws`() {
            // Unterminated tag stays literal; stray closers are ignored.
            assertEquals(listOf(span("a <b unfinished")), RichText.htmlToSpans("a <b unfinished"))
            assertEquals(listOf(span("text")), RichText.htmlToSpans("</strong>text</em>"))
            assertEquals(listOf(span("a < b")), RichText.htmlToSpans("a < b"))
            assertEquals(
                listOf(span("never closed", bold = true)),
                RichText.htmlToSpans("<b>never closed"),
            )
        }

        @Test
        fun `attributes on formatting tags are tolerated`() {
            assertEquals(
                listOf(span("x", bold = true)),
                RichText.htmlToSpans("""<strong style="color:red">x</strong>"""),
            )
        }

        @Test
        fun `inter-tag whitespace stays a single inline space`() {
            assertEquals(
                listOf(span("a", bold = true), span(" "), span("b", italic = true)),
                RichText.htmlToSpans("<b>a</b> <i>b</i>"),
            )
        }

        @Test
        fun `span style hex color is parsed (real website example)`() {
            assertEquals(
                listOf(
                    span("By "),
                    RichSpan("Auzifriend", color = 0xFFE03E2D.toInt()),
                ),
                RichText.htmlToSpans("""<p>By <span style="color: #e03e2d;">Auzifriend</span></p>"""),
            )
        }

        @Test
        fun `span style rgb color is parsed, short hex expands, colorless span is plain`() {
            assertEquals(
                listOf(RichSpan("x", color = 0xFFE03E2D.toInt())),
                RichText.htmlToSpans("""<span style="color: rgb(224, 62, 45)">x</span>"""),
            )
            assertEquals(
                listOf(RichSpan("x", color = 0xFFFF0000.toInt())),
                RichText.htmlToSpans("""<span style="color:#f00">x</span>"""),
            )
            // No color declaration (or unparseable one) → no color, text kept.
            assertEquals(listOf(span("x")), RichText.htmlToSpans("""<span style="font-size: 12px">x</span>"""))
            assertEquals(listOf(span("x")), RichText.htmlToSpans("""<span style="background-color: #fff">x</span>"""))
        }

        @Test
        fun `nested span color propagates and combines with formatting`() {
            assertEquals(
                listOf(RichSpan("hot", bold = true, color = 0xFFE03E2D.toInt())),
                RichText.htmlToSpans("""<span style="color:#e03e2d"><b>hot</b></span>"""),
            )
        }

        @Test
        fun `anchor href becomes a link span`() {
            assertEquals(
                listOf(
                    span("see "),
                    RichSpan("the site", link = "https://example.com/a?b=1"),
                ),
                RichText.htmlToSpans("""<p>see <a href="https://example.com/a?b=1">the site</a></p>"""),
            )
        }

        @Test
        fun `bare urls in text autolink (real website example)`() {
            val url = "https://github.com/ZpippoZ/minecraft-vertical-decoder-generator"
            assertEquals(
                listOf(
                    span("Generated by "),
                    RichSpan(url, link = url),
                ),
                RichText.htmlToSpans("<p>Generated by $url</p>"),
            )
            // Trailing prose punctuation is not part of the link.
            assertEquals(
                listOf(
                    span("see "),
                    RichSpan("https://example.com", link = "https://example.com"),
                    span("."),
                ),
                RichText.htmlToSpans("see https://example.com."),
            )
        }

        @Test
        fun `text inside an anchor never double-links`() {
            assertEquals(
                listOf(RichSpan("https://example.com/page", link = "https://other.example")),
                RichText.htmlToSpans("""<a href="https://other.example">https://example.com/page</a>"""),
            )
        }
    }

    @Nested
    @DisplayName("markupToSpans")
    inner class MarkupToSpans {

        @Test
        fun `plain markup is a single span`() {
            assertEquals(listOf(span("hello world")), RichText.markupToSpans("hello world"))
        }

        @Test
        fun `markers map to flags`() {
            assertEquals(
                listOf(
                    span("b", bold = true),
                    span(" "),
                    span("i", italic = true),
                    span(" "),
                    span("u", underline = true),
                    span(" "),
                    span("s", strikethrough = true),
                ),
                RichText.markupToSpans("**b** *i* __u__ ~~s~~"),
            )
        }

        @Test
        fun `underscore works for italic`() {
            assertEquals(listOf(span("i", italic = true)), RichText.markupToSpans("_i_"))
        }

        @Test
        fun `nested markers combine`() {
            assertEquals(
                listOf(span("both", bold = true, italic = true)),
                RichText.markupToSpans("***both***"),
            )
        }

        @Test
        fun `bullet lines set the bullet flag`() {
            assertEquals(
                listOf(span("one\n", bullet = true), span("two", bullet = true)),
                RichText.markupToSpans("- one\n- two"),
            )
        }

        @Test
        fun `blank line is a paragraph break, capped at one`() {
            assertEquals(listOf(span("a\n\nb")), RichText.markupToSpans("a\n\n\n\nb"))
        }

        @Test
        fun `unclosed marker stays literal`() {
            assertEquals(listOf(span("2*3 = 6")), RichText.markupToSpans("2*3 = 6"))
            assertEquals(listOf(span("**still literal")), RichText.markupToSpans("**still literal"))
        }

        @Test
        fun `backslash escapes a marker`() {
            assertEquals(listOf(span("*literal*")), RichText.markupToSpans("""\*literal\*"""))
        }

        @Test
        fun `empty markup yields no spans`() {
            assertEquals(emptyList(), RichText.markupToSpans(""))
            assertEquals(emptyList(), RichText.markupToSpans("  \n "))
        }
    }

    @Nested
    @DisplayName("markupToHtml")
    inner class MarkupToHtml {

        @Test
        fun `plain text wraps in a paragraph`() {
            assertEquals("<p>hello</p>", RichText.markupToHtml("hello"))
        }

        @Test
        fun `inline markers become allowlisted tags`() {
            assertEquals(
                "<p><strong>b</strong> <em>i</em> <u>u</u> <s>s</s></p>",
                RichText.markupToHtml("**b** *i* __u__ ~~s~~"),
            )
        }

        @Test
        fun `line break becomes br, blank line a new paragraph`() {
            assertEquals("<p>a<br>b</p><p>c</p>", RichText.markupToHtml("a\nb\n\nc"))
        }

        @Test
        fun `bullet lines become ul li`() {
            assertEquals(
                "<p>Features:</p><ul><li>fast</li><li><strong>very</strong> cheap</li></ul>",
                RichText.markupToHtml("Features:\n- fast\n- **very** cheap"),
            )
        }

        @Test
        fun `user text is entity-escaped`() {
            assertEquals(
                "<p>a &lt;b&gt; &amp; &#39;c&#39;</p>",
                RichText.markupToHtml("a <b> & 'c'"),
            )
        }

        @Test
        fun `nested markers nest tags`() {
            assertEquals("<p><strong><em>x</em></strong></p>", RichText.markupToHtml("***x***"))
        }

        @Test
        fun `blank input yields empty string`() {
            assertEquals("", RichText.markupToHtml(""))
            assertEquals("", RichText.markupToHtml("  \n\n "))
        }

        @Test
        fun `only allowlisted tags are ever emitted`() {
            val html = RichText.markupToHtml("**a**\n- *b*\n- __c__\n\n~~d~~")
            val tags = Regex("</?([a-z]+)").findAll(html).map { it.groupValues[1] }.toSet()
            assertTrue(tags.all { it in setOf("p", "br", "strong", "em", "u", "s", "ul", "li") }, html)
        }
    }

    @Nested
    @DisplayName("htmlToMarkup")
    inner class HtmlToMarkup {

        @Test
        fun `formatting becomes markers`() {
            assertEquals(
                "**b** *i* __u__ ~~s~~",
                RichText.htmlToMarkup("<p><strong>b</strong> <em>i</em> <u>u</u> <s>s</s></p>"),
            )
        }

        @Test
        fun `paragraphs become blank lines, br a newline`() {
            assertEquals("a\nb\n\nc", RichText.htmlToMarkup("<p>a<br>b</p><p>c</p>"))
        }

        @Test
        fun `list items become dash lines`() {
            assertEquals("- one\n- two", RichText.htmlToMarkup("<ul><li>one</li><li>two</li></ul>"))
        }

        @Test
        fun `literal marker characters are escaped`() {
            assertEquals("""2\*3 and 4\_5""", RichText.htmlToMarkup("<p>2*3 and 4_5</p>"))
        }

        @Test
        fun `blank input yields empty string`() {
            assertEquals("", RichText.htmlToMarkup(""))
        }
    }

    @Nested
    @DisplayName("spansToHtml")
    inner class SpansToHtml {

        @Test
        fun `plain text becomes a paragraph`() {
            assertEquals("<p>hello</p>", RichText.spansToHtml(listOf(span("hello"))))
        }

        @Test
        fun `empty spans yield empty string`() {
            assertEquals("", RichText.spansToHtml(emptyList()))
        }

        @Test
        fun `formatting flags become inline tags`() {
            assertEquals(
                "<p><strong>b</strong> <em>i</em> <u>u</u> <s>s</s></p>",
                RichText.spansToHtml(
                    listOf(
                        span("b", bold = true), span(" "),
                        span("i", italic = true), span(" "),
                        span("u", underline = true), span(" "),
                        span("s", strikethrough = true),
                    )
                ),
            )
        }

        @Test
        fun `combined flags nest innermost-first`() {
            assertEquals(
                "<p><strong><em>x</em></strong></p>",
                RichText.spansToHtml(listOf(span("x", bold = true, italic = true))),
            )
        }

        @Test
        fun `single newline becomes br, double a new paragraph`() {
            assertEquals(
                "<p>a<br>b</p><p>c</p>",
                RichText.spansToHtml(listOf(span("a\nb\n\nc"))),
            )
        }

        @Test
        fun `bullet runs become ul-li`() {
            assertEquals(
                "<p>intro</p><ul><li>one</li><li>two</li></ul>",
                RichText.spansToHtml(
                    listOf(
                        span("intro"), span("\n"),
                        span("one", bullet = true), span("\n"),
                        span("two", bullet = true),
                    )
                ),
            )
        }

        @Test
        fun `color becomes a styled span tag`() {
            assertEquals(
                "<p><span style=\"color: #e03e2d;\">red</span></p>",
                RichText.spansToHtml(listOf(RichSpan("red", color = 0xFFE03E2D.toInt()))),
            )
        }

        @Test
        fun `link becomes an anchor tag`() {
            assertEquals(
                "<p><a href=\"https://schemat.io\">site</a></p>",
                RichText.spansToHtml(listOf(RichSpan("site", link = "https://schemat.io"))),
            )
        }

        @Test
        fun `text is entity-escaped`() {
            assertEquals(
                "<p>a &amp; &lt;b&gt; &quot;c&quot;</p>",
                RichText.spansToHtml(listOf(span("a & <b> \"c\""))),
            )
        }

        @Test
        fun `leading and trailing blank lines are dropped`() {
            assertEquals("<p>x</p>", RichText.spansToHtml(listOf(span("\n\nx\n\n"))))
        }
    }

    @Nested
    @DisplayName("round-trips")
    inner class RoundTrips {

        /** html -> spans -> html -> spans is stable for the supported features. */
        private fun assertSpansStable(html: String) {
            val spans = RichText.htmlToSpans(html)
            val reserialized = RichText.spansToHtml(spans)
            assertEquals(spans, RichText.htmlToSpans(reserialized), "spans -> html -> spans for: $reserialized")
            // And the normalized HTML is a fixed point.
            assertEquals(reserialized, RichText.spansToHtml(RichText.htmlToSpans(reserialized)))
        }

        @Test
        fun `colored author credit round-trips through spansToHtml`() {
            assertSpansStable("""<p>By <span style="color:#e03e2d;">Auzifriend</span></p>""")
        }

        @Test
        fun `autolinked bare url round-trips through spansToHtml`() {
            assertSpansStable("<p>Source and updates at https://github.com/auzi/castle-schematics for free</p>")
        }

        @Test
        fun `formatting, bullets and paragraphs round-trip through spansToHtml`() {
            assertSpansStable(
                "<p>A <strong>large</strong> castle.<br>Built in <em>survival</em>.</p>" +
                    "<ul><li>4 towers</li><li><u>moat</u> included</li></ul><p>Enjoy &amp; share!</p>"
            )
        }

        @Test
        fun `colored bold link round-trips through spansToHtml`() {
            assertSpansStable(
                """<p><a href="https://schemat.io/s/1"><strong><span style="color: #2dc26b;">Download</span></strong></a> it <s>now</s></p>"""
            )
        }

        @Test
        fun `canonical spans html is reproduced exactly`() {
            val html = "<p>By <span style=\"color: #e03e2d;\">Auzifriend</span></p>" +
                "<ul><li><strong>fast</strong></li></ul>"
            assertEquals(html, RichText.spansToHtml(RichText.htmlToSpans(html)))
        }

        private fun assertMarkupRoundTrip(markup: String) {
            assertEquals(markup, RichText.htmlToMarkup(RichText.markupToHtml(markup)), "markup -> html -> markup")
        }

        @Test
        fun `plain text round-trips unchanged`() {
            assertMarkupRoundTrip("just plain text")
        }

        @Test
        fun `basic formatting round-trips`() {
            assertMarkupRoundTrip("**bold** and *italic* and __under__ and ~~strike~~")
        }

        @Test
        fun `paragraphs and line breaks round-trip`() {
            assertMarkupRoundTrip("first line\nsecond line\n\nsecond paragraph")
        }

        @Test
        fun `bullets round-trip`() {
            assertMarkupRoundTrip("intro\n- item one\n- **bold** item")
        }

        @Test
        fun `combined styles round-trip`() {
            assertMarkupRoundTrip("***bold italic*** plain **__bold under__**")
        }

        @Test
        fun `escaped markers round-trip`() {
            assertMarkupRoundTrip("""2\*3 = 6""")
        }

        @Test
        fun `canonical html round-trips through markup`() {
            val html = "<p><strong>b</strong> and <em>i</em></p><ul><li>x</li></ul>"
            assertEquals(html, RichText.markupToHtml(RichText.htmlToMarkup(html)))
        }

        @Test
        fun `website-style description parses and converts consistently`() {
            val html = "<p>A <strong>large</strong> castle.<br>Built in <em>survival</em>.</p>" +
                "<ul><li>4 towers</li><li><u>moat</u> included</li></ul><p>Enjoy &amp; share!</p>"
            val markup = RichText.htmlToMarkup(html)
            assertEquals(
                "A **large** castle.\nBuilt in *survival*.\n- 4 towers\n- __moat__ included\n\nEnjoy & share!",
                markup,
            )
            // Spans from the html and from the equivalent markup agree.
            assertEquals(RichText.htmlToSpans(html), RichText.markupToSpans(markup))
        }
    }
}
