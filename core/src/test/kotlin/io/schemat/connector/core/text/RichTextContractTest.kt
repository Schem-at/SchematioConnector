package io.schemat.connector.core.text

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

/**
 * The shared description contract (bug #3): descriptions are HTML limited to `p`, `br`,
 * `strong`, `em`, `u`, `s`, `ul`, `ol`, `li`, `h2`, `h3`, `a[href http(s)]`, `code`,
 * `blockquote` and `span style="color:#rrggbb"` with one of the 16 Minecraft chat colours.
 * The website and the mod must both emit only this and render all of it.
 */
class RichTextContractTest {

    private val allowedTags = setOf("p", "br", "strong", "em", "u", "s", "ul", "ol", "li", "h2", "h3", "a", "code", "blockquote", "span")
    private val tag = Regex("""<\s*/?\s*([a-zA-Z][a-zA-Z0-9]*)([^>]*)>""")

    /** Every tag is allowlisted; spans carry only a palette colour; links only http(s). */
    private fun assertWithinContract(html: String) {
        for (m in tag.findAll(html)) {
            val name = m.groupValues[1].lowercase()
            val attrs = m.groupValues[2].trim()
            assertTrue(name in allowedTags, "tag <$name> outside the allowlist in $html")
            if (m.value.startsWith("</")) continue
            when (name) {
                "span" -> {
                    val c = Regex("""^style="color:(#[0-9a-f]{6})"$""").find(attrs)
                    assertTrue(c != null, "span attrs '$attrs' are not exactly style=\"color:#rrggbb\" in $html")
                    assertTrue(RichPalette.contains(RichPalette.parseHex(c.groupValues[1])!!), "non-palette colour in $html")
                }
                "a" -> assertTrue(Regex("""^href="https?://[^"]+"$""").matches(attrs), "a attrs '$attrs' in $html")
                else -> assertEquals("", attrs, "<$name> must carry no attributes in $html")
            }
        }
    }

    private fun roundTrip(html: String) = RichText.spansToHtml(RichText.htmlToSpans(html))

    @Test
    fun `the palette is exactly the 16 Minecraft chat colours`() {
        assertEquals(
            listOf(
                "#000000", "#0000aa", "#00aa00", "#00aaaa", "#aa0000", "#aa00aa", "#ffaa00", "#aaaaaa",
                "#555555", "#5555ff", "#55ff55", "#55ffff", "#ff5555", "#ff55ff", "#ffff55", "#ffffff",
            ),
            RichPalette.COLORS.map { RichPalette.hex(it.argb) },
        )
    }

    /** Canonical contract HTML survives a parse and re-serialize unchanged. */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "<p>a <strong>b</strong> <em>c</em> <u>d</u> <s>e</s> <code>f</code></p>",
            "<ul><li>one</li><li>two</li></ul>",
            "<ol><li>one</li><li>two</li></ol>",
            "<h2>Title</h2><p>body</p>",
            "<h3>Sub</h3><p>body</p>",
            "<blockquote><p>quoted</p></blockquote><p>after</p>",
            "<blockquote><p>one</p><p>two</p></blockquote>",
            "<p><span style=\"color:#aa0000\">red</span> and <span style=\"color:#55ffff\">aqua</span></p>",
            "<p><a href=\"https://schemat.io\">site</a></p>",
            "<h2><span style=\"color:#ffaa00\">Gold</span> title</h2><ol><li><strong>bold</strong> step</li></ol><blockquote><p><em>said</em></p></blockquote>",
        ],
    )
    fun `canonical contract html round-trips`(html: String) {
        assertEquals(html, roundTrip(html))
        assertWithinContract(roundTrip(html))
    }

    @Test
    fun `ordered list items are ordered bullets, unordered are not`() {
        val spans = RichText.htmlToSpans("<ul><li>a</li></ul><ol><li>b</li><li>c</li></ol>")
        assertEquals(
            listOf("a" to false, "b" to true, "c" to true),
            spans.filter { it.bullet }.map { it.text.trim() to it.ordered },
        )
    }

    @Test
    fun `headings, quotes and code parse into their own flags`() {
        val spans = RichText.htmlToSpans("<h2>A</h2><h3>B</h3><blockquote><p>C</p></blockquote><p><code>D</code></p>")
        fun of(t: String) = spans.first { it.text.trim() == t }
        assertEquals(2, of("A").heading)
        assertEquals(3, of("B").heading)
        assertTrue(of("C").quote)
        assertTrue(of("D").code)
        assertEquals(0, of("D").heading)
    }

    @Test
    fun `aliases normalise to the canonical tags`() {
        assertEquals("<h2>T</h2>", roundTrip("<h1>T</h1>"))
        assertEquals("<p><strong>b</strong><em>i</em><s>d</s> <s>k</s></p>", roundTrip("<p><b>b</b><i>i</i><del>d</del> <strike>k</strike></p>"))
    }

    @Test
    fun `non-palette colours are dropped, palette colours are kept in any notation`() {
        assertEquals("<p>x</p>", roundTrip("<p><span style=\"color: #e03e2d;\">x</span></p>"))
        assertEquals("<p><span style=\"color:#aa0000\">x</span></p>", roundTrip("<p><span style=\"color: #AA0000;\">x</span></p>"))
        assertEquals("<p><span style=\"color:#aa0000\">x</span></p>", roundTrip("<p><span style=\"color: rgb(170, 0, 0)\">x</span></p>"))
        assertEquals(null, RichText.htmlToSpans("<span style=\"color:#e03e2d\">x</span>").single().color)
    }

    @Test
    fun `only http and https links survive`() {
        assertEquals("<p>x</p>", roundTrip("<p><a href=\"javascript:alert(1)\">x</a></p>"))
        assertEquals("<p>x</p>", roundTrip("<p><a href=\"data:text/html,hi\">x</a></p>"))
        assertEquals("<p>x</p>", roundTrip("<p><a href=\"/relative\">x</a></p>"))
        assertEquals(null, RichText.htmlToSpans("<a href=\"javascript:alert(1)\">x</a>").single().link)
        assertTrue(RichText.isAllowedHref("HTTPS://schemat.io/a"))
        assertTrue(!RichText.isAllowedHref("https://"))
    }

    @Test
    fun `spansToHtml never emits a colour or link outside the contract`() {
        val html = RichText.spansToHtml(
            listOf(
                RichSpan("a", color = 0xFFE03E2D.toInt()),
                RichSpan(" b", link = "javascript:alert(1)"),
                RichSpan(" c", color = 0xFF5555FF.toInt(), link = "https://ok.example"),
            ),
        )
        assertEquals("<p>a b<a href=\"https://ok.example\"><span style=\"color:#5555ff\"> c</span></a></p>", html)
    }

    /** Messy website HTML (old flux:editor output, pasted content) re-serializes inside the contract. */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "<div class=\"x\"><p style=\"text-align:center\">centered</p></div>",
            "<h4>deep</h4><h5>deeper</h5><h6>deepest</h6>",
            "<p><span style=\"color: #e03e2d; font-size: 20px\">big</span> <span style=\"background-color:#fff\">bg</span></p>",
            "<table><tr><td>cell</td></tr></table><img src=\"x.png\">",
            "<p><a href=\"https://a.example\" target=\"_blank\" onclick=\"x()\">link</a> <a>bare</a></p>",
            "<pre><code>block</code></pre>",
            "<ul><li>a<ul><li>nested</li></ul></li><li>b</li></ul>",
            "<blockquote>bare quote</blockquote>",
            "<script>alert(1)</script><style>p{}</style>",
            "plain text with https://schemat.io/s/1 link",
        ],
    )
    fun `arbitrary html re-serializes within the contract`(html: String) {
        assertWithinContract(roundTrip(html))
    }

    @Test
    fun `h4 to h6 render as plain paragraphs`() {
        assertEquals("<p>deep</p><p>deeper</p>", roundTrip("<h4>deep</h4><h6>deeper</h6>"))
    }

    // ---- editor document ----

    @Test
    fun `RichDocument round-trips the full contract`() {
        val html = "<h2><span style=\"color:#ffaa00\">Gold</span> title</h2><ol><li><strong>bold</strong> step</li></ol>" +
            "<blockquote><p><em>said</em></p></blockquote><p><code>x</code> <a href=\"https://schemat.io\">site</a></p>"
        assertEquals(html, RichDocument.fromHtml(html).toHtml())
    }

    @Test
    fun `RichDocument toggles code over a range`() {
        val d = RichDocument.fromHtml("<p>run this</p>")
        d.toggleStyle(4, 8, RichDocument.StyleFlag.CODE)
        assertEquals("<p>run <code>this</code></p>", d.toHtml())
    }

    @Test
    fun `RichDocument sets line formats - heading, ordered list, quote`() {
        val d = RichDocument.fromHtml("<p>Title<br>step<br>said</p>")
        d.setLineFormat(0, RichDocument.LineFormat(heading = 2))
        d.setLineFormat(6, RichDocument.LineFormat(list = RichDocument.ListKind.ORDERED))
        d.setLineFormat(11, RichDocument.LineFormat(quote = true))
        assertEquals("<h2>Title</h2><ol><li>step</li></ol><blockquote><p>said</p></blockquote>", d.toHtml())
        assertEquals(RichDocument.LineFormat(list = RichDocument.ListKind.ORDERED), d.lineFormatAt(8))
    }

    @Test
    fun `RichDocument link and colour setters enforce the contract`() {
        val d = RichDocument.fromHtml("<p>abc</p>")
        d.setColor(0, 1, 0xFFE03E2D.toInt()) // not in the palette: ignored
        d.setColor(1, 2, 0xFF55FF55.toInt())
        d.setLink(2, 3, "javascript:alert(1)") // rejected
        assertEquals("<p>a<span style=\"color:#55ff55\">b</span>c</p>", d.toHtml())
        d.setLink(2, 3, "https://schemat.io")
        assertEquals("<p>a<span style=\"color:#55ff55\">b</span><a href=\"https://schemat.io\">c</a></p>", d.toHtml())
    }
}
