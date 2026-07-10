package io.schemat.connector.core.text

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [RichDocument]: the mutable editor-state model used by the ImGui
 * rich-text widget (Task 21). Pure Kotlin, no MC dependencies.
 *
 * Covers: insertText, deleteRange, toggleStyle/isStyleActive, plainText/length,
 * styleAt, toSpans/fromSpans, and round-trip serialisation after edits.
 */
class RichDocumentTest {

    // ---- helpers ----

    private fun doc(html: String = ""): RichDocument =
        if (html.isEmpty()) RichDocument() else RichDocument.fromHtml(html)

    private fun span(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        underline: Boolean = false,
        strikethrough: Boolean = false,
        bullet: Boolean = false,
        color: Int? = null,
        link: String? = null,
    ) = RichSpan(text, bold, italic, underline, strikethrough, bullet, color, link)

    // ---- construction / round-trip ----

    @Nested
    @DisplayName("construction")
    inner class Construction {

        @Test
        fun `empty document has length 0 and empty plainText`() {
            val d = doc()
            assertEquals(0, d.length)
            assertEquals("", d.plainText)
        }

        @Test
        fun `fromHtml round-trips back to same HTML`() {
            val html = "<p><strong>Hello</strong> world</p>"
            val d = doc(html)
            // toHtml() must produce HTML that parses to same spans
            val spans = RichText.htmlToSpans(d.toHtml())
            assertEquals(RichText.htmlToSpans(html), spans)
        }

        @Test
        fun `fromSpans then toSpans is identity`() {
            val spans = listOf(
                span("Hello ", bold = true),
                span("world"),
            )
            val d = RichDocument.fromSpans(spans)
            assertEquals(spans, d.toSpans())
        }

        @Test
        fun `length matches plainText character count`() {
            val d = doc("<p>Hello</p>")
            assertEquals(d.plainText.length, d.length)
        }
    }

    // ---- plainText / length ----

    @Nested
    @DisplayName("plainText and length")
    inner class PlainTextAndLength {

        @Test
        fun `plainText strips formatting and returns raw characters`() {
            val d = doc("<p><strong>bold</strong> and <em>italic</em></p>")
            assertEquals("bold and italic", d.plainText)
        }

        @Test
        fun `newlines are preserved in plainText`() {
            val d = doc("<p>line1<br>line2</p>")
            assertEquals("line1\nline2", d.plainText)
        }

        @Test
        fun `paragraph break is two newlines`() {
            val d = doc("<p>para1</p><p>para2</p>")
            assertTrue(d.plainText.contains("\n\n"), "expected paragraph break: '${d.plainText}'")
        }
    }

    // ---- styleAt ----

    @Nested
    @DisplayName("styleAt")
    inner class StyleAtTests {

        @Test
        fun `styleAt returns style of character at offset`() {
            val d = doc("<p><strong>bold</strong> plain</p>")
            // offset 0 = 'b' (bold)
            val s0 = d.styleAt(0)
            assertTrue(s0.bold)
            assertFalse(s0.italic)
            // offset 5 = ' ' (plain)
            val s5 = d.styleAt(5)
            assertFalse(s5.bold)
        }

        @Test
        fun `styleAt on newline returns default style`() {
            val d = doc("<p>line1<br>line2</p>")
            val nl = d.plainText.indexOf('\n')
            val s = d.styleAt(nl)
            assertFalse(s.bold)
            assertFalse(s.italic)
        }

        @Test
        fun `styleAt at length returns style of last char`() {
            val d = doc("<p><strong>hi</strong></p>")
            // Caret at end: style is that of the last char
            val s = d.styleAt(d.length)
            assertTrue(s.bold)
        }

        @Test
        fun `styleAt at length of empty document returns default style`() {
            val d = doc()
            assertEquals(0, d.length)
            val s = d.styleAt(d.length) // styleAt(0) on empty doc — must not throw
            assertFalse(s.bold)
            assertFalse(s.italic)
            assertFalse(s.underline)
            assertFalse(s.strikethrough)
            assertEquals(null, s.color)
            assertEquals(null, s.link)
        }

        @Test
        fun `styleAt past length when last char is newline returns default style`() {
            // Last real char is a newline; styleAt(length) must skip it and find the
            // last non-newline char (or default if none).
            val d = doc("<p>hi</p><p>x</p>")
            val s = d.styleAt(d.length)
            assertFalse(s.bold)
        }
    }

    // ---- insertText ----

    @Nested
    @DisplayName("insertText")
    inner class InsertTextTests {

        @Test
        fun `insert plain text at start of empty document`() {
            val d = doc()
            d.insertText(0, "Hello", RichDocument.Style())
            assertEquals("Hello", d.plainText)
        }

        @Test
        fun `insert inherits given style`() {
            val d = doc()
            d.insertText(0, "hi", RichDocument.Style(bold = true))
            assertEquals(listOf(span("hi", bold = true)), d.toSpans())
        }

        @Test
        fun `insert in the middle splits span correctly`() {
            val d = doc("<p>Hello world</p>")
            // Insert "brave " at offset 6 (after "Hello ")
            d.insertText(6, "brave ", RichDocument.Style())
            assertEquals("Hello brave world", d.plainText)
        }

        @Test
        fun `insert bold text in plain span splits into three spans`() {
            val d = doc("<p>abcde</p>")
            d.insertText(2, "X", RichDocument.Style(bold = true))
            val spans = d.toSpans()
            // Should have: "ab" plain, "X" bold, "cde" plain
            assertEquals("ab", spans[0].text)
            assertFalse(spans[0].bold)
            assertEquals("X", spans[1].text)
            assertTrue(spans[1].bold)
            assertEquals("cde", spans[2].text)
            assertFalse(spans[2].bold)
        }

        @Test
        fun `insert preserves adjacent same-style span merging`() {
            val d = doc("<p><strong>ab</strong></p>")
            d.insertText(1, "X", RichDocument.Style(bold = true))
            // All bold: should merge to single span "aXb"
            assertEquals(listOf(span("aXb", bold = true)), d.toSpans())
        }

        @Test
        fun `insert newline at caret`() {
            val d = doc("<p>Hello world</p>")
            d.insertText(5, "\n", RichDocument.Style())
            assertEquals("Hello\n world", d.plainText)
        }

        @Test
        fun `insert respects MAX_LENGTH`() {
            val d = doc()
            d.insertText(0, "A".repeat(RichDocument.MAX_LENGTH), RichDocument.Style())
            assertEquals(RichDocument.MAX_LENGTH, d.length)
            // Further insert is silently ignored
            d.insertText(d.length, "X", RichDocument.Style())
            assertEquals(RichDocument.MAX_LENGTH, d.length)
        }
    }

    // ---- deleteRange ----

    @Nested
    @DisplayName("deleteRange")
    inner class DeleteRangeTests {

        @Test
        fun `delete within a span removes characters`() {
            val d = doc("<p>Hello world</p>")
            d.deleteRange(5, 11) // delete " world"
            assertEquals("Hello", d.plainText)
        }

        @Test
        fun `delete across span boundary merges remaining`() {
            val d = doc("<p><strong>bold</strong> plain</p>")
            // Delete from offset 2 (mid-bold) to offset 7 (mid-plain): "ld pl"
            d.deleteRange(2, 7)
            val text = d.plainText
            assertEquals("bo" + "ain", text)
        }

        @Test
        fun `deleteRange of entire content leaves empty document`() {
            val d = doc("<p>Hello</p>")
            d.deleteRange(0, d.length)
            assertEquals("", d.plainText)
            assertEquals(0, d.length)
        }

        @Test
        fun `deleteRange with start == end is a no-op`() {
            val d = doc("<p>Hello</p>")
            val before = d.plainText
            d.deleteRange(2, 2)
            assertEquals(before, d.plainText)
        }

        @Test
        fun `delete preserves styling of remaining chars`() {
            val d = doc("<p><strong>bold</strong> plain</p>")
            // plain text: "bold plain" — "bold"=0..3, " plain"=4..9
            // Delete from offset 4 to end removes " plain", leaving "bold"
            d.deleteRange(4, d.length)
            val spans = d.toSpans()
            assertEquals(1, spans.size)
            assertEquals("bold", spans[0].text)
            assertTrue(spans[0].bold)
        }

        @Test
        fun `deleteRange that brings two same-style spans adjacent merges them`() {
            // "AA" bold, "XX" plain, "BB" bold. Deleting the plain middle should leave
            // the two bold runs adjacent and toSpans must merge them into one span.
            val d = doc("<p><strong>AA</strong>XX<strong>BB</strong></p>")
            d.deleteRange(2, 4) // remove "XX"
            assertEquals("AABB", d.plainText)
            val spans = d.toSpans()
            assertEquals(1, spans.size)
            assertEquals("AABB", spans[0].text)
            assertTrue(spans[0].bold)
        }
    }

    // ---- isStyleActive / toggleStyle ----

    @Nested
    @DisplayName("toggleStyle and isStyleActive")
    inner class ToggleStyleTests {

        @Test
        fun `isStyleActive is false for unstyled range`() {
            val d = doc("<p>plain</p>")
            assertFalse(d.isStyleActive(0, d.length, RichDocument.StyleFlag.BOLD))
        }

        @Test
        fun `isStyleActive is true when all chars in range are bold`() {
            val d = doc("<p><strong>hello</strong></p>")
            assertTrue(d.isStyleActive(0, d.length, RichDocument.StyleFlag.BOLD))
        }

        @Test
        fun `isStyleActive is false when only some chars are bold`() {
            val d = doc("<p><strong>bold</strong> plain</p>")
            // Range covers both bold and plain — not all bold
            assertFalse(d.isStyleActive(0, d.length, RichDocument.StyleFlag.BOLD))
        }

        @Test
        fun `toggleStyle turns on bold for plain selection`() {
            val d = doc("<p>hello</p>")
            d.toggleStyle(0, d.length, RichDocument.StyleFlag.BOLD)
            assertTrue(d.isStyleActive(0, d.length, RichDocument.StyleFlag.BOLD))
            assertEquals(listOf(span("hello", bold = true)), d.toSpans())
        }

        @Test
        fun `toggleStyle turns off bold when all chars are bold`() {
            val d = doc("<p><strong>hello</strong></p>")
            d.toggleStyle(0, d.length, RichDocument.StyleFlag.BOLD)
            assertFalse(d.isStyleActive(0, d.length, RichDocument.StyleFlag.BOLD))
            assertEquals(listOf(span("hello", bold = false)), d.toSpans())
        }

        @Test
        fun `toggleStyle on mixed selection turns all on`() {
            val d = doc("<p><strong>bold</strong> plain</p>")
            d.toggleStyle(0, d.length, RichDocument.StyleFlag.BOLD)
            // Because not all were bold, toggleStyle should set all to bold
            assertTrue(d.isStyleActive(0, d.length, RichDocument.StyleFlag.BOLD))
        }

        @Test
        fun `toggleStyle on partial range leaves other chars unchanged`() {
            val d = doc("<p>hello world</p>")
            d.toggleStyle(0, 5, RichDocument.StyleFlag.BOLD) // bold "hello"
            assertTrue(d.isStyleActive(0, 5, RichDocument.StyleFlag.BOLD))
            assertFalse(d.isStyleActive(6, d.length, RichDocument.StyleFlag.BOLD))
        }

        @Test
        fun `toggleStyle italic works independently of bold`() {
            val d = doc("<p>text</p>")
            d.toggleStyle(0, d.length, RichDocument.StyleFlag.ITALIC)
            assertTrue(d.isStyleActive(0, d.length, RichDocument.StyleFlag.ITALIC))
            assertFalse(d.isStyleActive(0, d.length, RichDocument.StyleFlag.BOLD))
        }

        @Test
        fun `toggleStyle skips newline characters`() {
            val d = doc("<p>line1<br>line2</p>")
            // Toggle bold over entire doc including the newline
            d.toggleStyle(0, d.length, RichDocument.StyleFlag.BOLD)
            // Newlines carry no formatting; content chars are bold
            val plain = d.plainText
            val nlIdx = plain.indexOf('\n')
            assertTrue(d.isStyleActive(0, nlIdx, RichDocument.StyleFlag.BOLD))
            assertTrue(d.isStyleActive(nlIdx + 1, d.length, RichDocument.StyleFlag.BOLD))
        }

        @Test
        fun `toggleStyle then toSpans merges adjacent same-style spans`() {
            val d = doc("<p><strong>fo</strong><strong>o</strong></p>")
            // All bold already; toggle off
            d.toggleStyle(0, d.length, RichDocument.StyleFlag.BOLD)
            val spans = d.toSpans()
            // Should merge to a single plain span
            assertEquals(1, spans.size)
            assertEquals("foo", spans[0].text)
        }

        @Test
        fun `zero-width toggleStyle is a no-op and zero-width isStyleActive is false`() {
            val d = doc("<p>hello</p>")
            val before = d.toHtml()
            d.toggleStyle(2, 2, RichDocument.StyleFlag.BOLD) // start == end
            assertEquals(before, d.toHtml())
            // An empty range has no relevant chars, so it is never "active".
            assertFalse(d.isStyleActive(2, 2, RichDocument.StyleFlag.BOLD))
        }
    }

    // ---- round-trip after edits ----

    @Nested
    @DisplayName("edit round-trips")
    inner class EditRoundTrips {

        @Test
        fun `insert then toHtml then fromHtml reproduces content`() {
            val d = doc()
            d.insertText(0, "Hello ", RichDocument.Style())
            d.insertText(6, "world", RichDocument.Style(bold = true))
            val html = d.toHtml()
            val d2 = doc(html)
            assertEquals(d.plainText, d2.plainText)
        }

        @Test
        fun `delete then serialize is stable`() {
            val html = "<p><strong>bold</strong> and plain</p>"
            val d = doc(html)
            d.deleteRange(0, 4) // delete "bold", leaving " and plain"
            val out = d.toHtml()
            // htmlToSpans trims leading whitespace, so re-parsed plain text is "and plain"
            assertEquals("and plain", RichDocument.fromHtml(out).plainText)
        }

        @Test
        fun `toggle then serialize round-trips`() {
            val d = doc("<p>hello world</p>")
            d.toggleStyle(6, 11, RichDocument.StyleFlag.BOLD) // bold "world"
            val html = d.toHtml()
            val d2 = doc(html)
            assertEquals(d.plainText, d2.plainText)
            assertTrue(d2.isStyleActive(6, 11, RichDocument.StyleFlag.BOLD))
            assertFalse(d2.isStyleActive(0, 5, RichDocument.StyleFlag.BOLD))
        }
    }
}
