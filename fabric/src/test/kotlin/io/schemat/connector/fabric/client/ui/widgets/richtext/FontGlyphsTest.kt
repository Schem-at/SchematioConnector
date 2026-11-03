package io.schemat.connector.fabric.client.ui.widgets.richtext

import imgui.ImFontGlyph
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FontGlyphsTest {

    @Test
    fun `a glyph lookup that found nothing is not a glyph`() {
        // imgui-java's findGlyphNoFallback never returns null: a missing glyph comes back as a
        // wrapper around a null pointer, so a null check let U+2022 through and it drew as "?".
        assertFalse(FontGlyphs.isFound(ImFontGlyph(0L)))
        assertTrue(FontGlyphs.isFound(ImFontGlyph(0x1000L)))
    }
}
